package kr.kiosklens.domain.session

import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideRequest
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.model.HistoryItem
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.SttError
import kr.kiosklens.domain.policy.GuideTextComposer
import kr.kiosklens.domain.policy.HighlightArea
import kr.kiosklens.domain.policy.TimeoutPolicy
import kr.kiosklens.domain.session.SessionEffect.CancelPending
import kr.kiosklens.domain.session.SessionEffect.ClearHighlight
import kr.kiosklens.domain.session.SessionEffect.Highlight
import kr.kiosklens.domain.session.SessionEffect.RequestGuide
import kr.kiosklens.domain.session.SessionEffect.Speak
import kr.kiosklens.domain.session.SessionEffect.StartListening
import kr.kiosklens.domain.session.SessionEffect.StartTimer
import kr.kiosklens.domain.session.SessionEffect.StopListening
import kr.kiosklens.domain.session.SessionEffect.StopSpeaking

/**
 * 세션 상태머신 (설계서 6장 · 16.5).
 *
 * (지금 상태, 일어난 일) → (다음 상태, 할 일 목록)을 정하는 **순수 함수**다.
 * 말하기 · 서버 호출 · 하이라이트를 직접 하지 않고 [SessionEffect] 목록만 돌려준다.
 * 같은 입력이면 항상 같은 결과라서 기기 없이 테스트로 모든 흐름을 확인할 수 있다.
 *
 * 공통 규칙
 * - 종료 버튼(StopPressed)은 어떤 단계에서든 IDLE.
 * - 번호표(ticket)가 지금 상태와 다른 결과 이벤트는 지난 일이라 무시한다.
 * - TTS를 다 읽은 뒤에만 STT를 시작한다 (에코 방지): ASKING → SpeechFinished → LISTENING.
 */
class SessionStateMachine(
    private val timeouts: TimeoutPolicy = TimeoutPolicy(),
    private val texts: GuideTextComposer = GuideTextComposer(),
) {
    fun reduce(state: SessionState, event: SessionEvent): Transition {
        if (event is SessionEvent.Ticketed && event.ticket != state.ticket) return stay(state)
        return when (event) {
            is SessionEvent.StopPressed -> stop(state)
            is SessionEvent.StartPressed -> onStart(state, event.sessionId)
            is SessionEvent.SnapshotReceived -> onSnapshot(state, event.snapshot)
            is SessionEvent.ScreenChanged -> onScreenChanged(state, event.screenId)
            is SessionEvent.SpeechRecognized -> onSpeech(state, event.text)
            is SessionEvent.SpeechFailed -> onSpeechFailed(state, event.error)
            is SessionEvent.SpeechFinished -> onSpeechFinished(state)
            is SessionEvent.GuideReceived -> onGuide(state, event.response)
            is SessionEvent.GuideFailed -> onGuideFailed(state)
            is SessionEvent.TimedOut -> onTimeout(state)
        }
    }

    // ---------------- 버튼 ----------------

    private fun onStart(state: SessionState, sessionId: String): Transition {
        if (state.phase != Phase.IDLE) return stay(state)          // 진행 중 "말하기"는 2일차에 정함
        val t = state.ticket + 1
        val next = SessionState(phase = Phase.LISTENING, ticket = t, sessionId = sessionId, snapshot = state.snapshot)
        return Transition(next, listOf(StopSpeaking, ClearHighlight, StartListening(t), StartTimer(timeouts.listenSilenceMs, t)))
    }

    private fun stop(state: SessionState): Transition {
        val next = SessionState(phase = Phase.IDLE, ticket = state.ticket + 1, snapshot = state.snapshot)
        return Transition(next, listOf(StopListening, StopSpeaking, ClearHighlight, CancelPending))
    }

    // ---------------- 화면 ----------------

    private fun onSnapshot(state: SessionState, s: ScreenSnapshot): Transition {
        val updated = state.copy(snapshot = s)
        return when (state.phase) {
            Phase.SCANNING -> requestOrScan(updated, listOf(CancelPending))                 // 화면을 찾음 → 서버에 묻기
            Phase.LOST -> requestOrScan(updated, listOf(StopSpeaking, CancelPending))       // 다시 보임 → 바로 묻기
            Phase.PLANNING ->                                                               // 묻는 사이 화면이 바뀜 → 다시 묻기
                if (s.screenId != state.requestedSnapshot?.screenId) requestOrScan(updated, listOf(CancelPending)) else stay(updated)
            Phase.GUIDING, Phase.WAIT_CHANGE -> {
                val g = state.guide ?: return stay(updated)
                if (s.screenId != g.screenId) screenChanged(updated) else follow(updated, g, state.snapshot, s)
            }
            else -> stay(updated)
        }
    }

    private fun onScreenChanged(state: SessionState, screenId: String): Transition {
        // 지금 들고 있는 스냅샷이 옛 화면이면 버린다 (새 화면 스냅샷이 먼저 왔으면 그대로 둠)
        val updated = state.copy(snapshot = state.snapshot?.takeIf { it.screenId == screenId })
        return when (state.phase) {
            Phase.PLANNING ->
                if (screenId != state.requestedSnapshot?.screenId) requestOrScan(updated, listOf(CancelPending)) else stay(updated)
            Phase.GUIDING, Phase.WAIT_CHANGE ->
                if (state.guide != null && screenId != state.guide.screenId) screenChanged(updated) else stay(updated)
            else -> stay(updated)
        }
    }

    /** 안내하던 화면이 바뀌었다 = 어르신이 눌렀다. 안내를 멈추고 새 화면으로 다시 묻는다. */
    private fun screenChanged(state: SessionState): Transition =
        requestOrScan(state.copy(guide = null), listOf(StopSpeaking, ClearHighlight, CancelPending))

    /**
     * 같은 화면의 새 스냅샷: 하이라이트가 버튼을 따라가게 위치를 갱신한다 (위치가 바뀌었을 때만).
     * 목표가 안 읽히면(손가락에 가려짐 등) 주변 요소가 움직인 만큼 옮기고, 그것도 모르면 숨긴다 —
     * 마지막 화면 좌표에 그대로 두면 휴대폰이 움직였을 때 옆 버튼을 가리킨다 (2026-11-11 시험: 아메리카노 안내에 에스프레소 테두리).
     */
    private fun follow(state: SessionState, g: Guidance, prev: ScreenSnapshot?, s: ScreenSnapshot): Transition {
        val id = g.targetId ?: return stay(state)
        val element = s.findElement(id)
        val rect = if (element != null) HighlightArea.of(element, s.elements) else HighlightArea.shift(g.rect, prev, s, id)
        if (rect == null) {
            return if (g.rect == null) stay(state) else Transition(state.copy(guide = g.copy(rect = null)), listOf(ClearHighlight))
        }
        if (rect == g.rect) return stay(state)
        return Transition(state.copy(guide = g.copy(rect = rect)), listOf(Highlight(HighlightTarget(id, rect))))
    }

    // ---------------- 음성 ----------------

    private fun onSpeech(state: SessionState, text: String): Transition {
        if (state.phase != Phase.LISTENING) return stay(state)
        val utterance = text.trim()
        if (utterance.isEmpty()) return toIdleSaying(state, texts.silence())
        return requestOrScan(state.copy(pendingUtterance = utterance), listOf(CancelPending))
    }

    private fun onSpeechFailed(state: SessionState, error: SttError): Transition {
        if (state.phase != Phase.LISTENING) return stay(state)
        return if (error == SttError.NO_SPEECH) toIdleSaying(state, texts.silence()) else toError(state, texts.speechError(error))
    }

    private fun onSpeechFinished(state: SessionState): Transition {
        val t = state.ticket + 1
        return when (state.phase) {
            Phase.GUIDING -> Transition(state.copy(phase = Phase.WAIT_CHANGE, ticket = t), listOf(StartTimer(timeouts.waitChangeRepeatMs, t)))
            Phase.ASKING -> Transition(state.copy(phase = Phase.LISTENING, ticket = t),        // 다 읽은 뒤에야 듣기 시작 (에코 방지)
                listOf(StartListening(t), StartTimer(timeouts.listenSilenceMs, t)))
            Phase.LOST -> Transition(state.copy(phase = Phase.SCANNING, ticket = t), listOf(StartTimer(timeouts.scanningMs, t)))
            Phase.DONE, Phase.ERROR -> Transition(SessionState(phase = Phase.IDLE, ticket = t, snapshot = state.snapshot), listOf(ClearHighlight))
            else -> stay(state)
        }
    }

    // ---------------- 서버 ----------------

    private fun onGuide(state: SessionState, response: GuideResponse): Transition {
        if (state.phase != Phase.PLANNING) return stay(state)
        val asked = state.requestedSnapshot ?: return stay(state)
        val action = response.action
        val base = state.copy(
            goal = response.goal,
            pendingUtterance = null,
            history = (state.history + HistoryItem.of(asked.screenId, action)).takeLast(GuideRequest.MAX_HISTORY),
        )
        return when (action) {
            is GuideAction.Tap -> {
                val element = asked.findElement(action.targetId)
                    ?: return speakAndWait(base, asked.screenId, texts.notFound(), retry = true)   // 계약 위반 방어
                // 응답을 기다리는 사이 같은 화면의 새 스냅샷이 왔으면 최신 위치를 쓴다. 메뉴 칸이면 칸 전체를 감싼다
                val latest = base.snapshot?.takeIf { it.screenId == asked.screenId }
                val now = latest?.findElement(element.id)
                val rect = if (latest != null && now != null) HighlightArea.of(now, latest.elements) else HighlightArea.of(element, asked.elements)
                val spoken = texts.tap(rect, action.guideText)
                val t = base.ticket + 1
                Transition(
                    base.copy(phase = Phase.GUIDING, ticket = t, guide = Guidance(asked.screenId, element.id, rect, spoken)),
                    listOf(Highlight(HighlightTarget(element.id, rect)), Speak(spoken, t)),
                )
            }
            is GuideAction.Scroll -> speakAndWait(base, asked.screenId, texts.scroll(action.direction, action.guideText))
            is GuideAction.Wait -> speakAndWait(base, asked.screenId, action.guideText)
            is GuideAction.AskUser -> speakThen(base, Phase.ASKING, action.question)
            is GuideAction.Done -> speakThen(base, Phase.DONE, action.guideText)
            is GuideAction.Unknown -> speakAndWait(base, asked.screenId, action.reason, retry = true)
        }
    }

    private fun onGuideFailed(state: SessionState): Transition =
        if (state.phase == Phase.PLANNING) toError(state, texts.networkError()) else stay(state)

    // ---------------- 시간 초과 ----------------

    private fun onTimeout(state: SessionState): Transition {
        val t = state.ticket + 1
        return when (state.phase) {
            Phase.LISTENING -> toIdleSaying(state, texts.silence())                             // 무음 8초
            Phase.SCANNING -> Transition(state.copy(phase = Phase.LOST, ticket = t), listOf(Speak(texts.lost(), t)))   // 10초
            Phase.WAIT_CHANGE -> {                                                               // 15초
                val g = state.guide ?: return stay(state)
                if (g.retryOnTimeout) requestOrScan(state.copy(guide = null), listOf(ClearHighlight))   // UNKNOWN 뒤: 최신 화면으로 다시 묻기
                else Transition(state.copy(phase = Phase.GUIDING, ticket = t), listOf(Speak(g.spokenText, t)))   // 다시 읽기
            }
            else -> stay(state)
        }
    }

    // ---------------- 공통 도우미 ----------------

    /** 화면이 있으면 서버에 묻고(PLANNING), 없으면 화면을 찾는다(SCANNING). */
    private fun requestOrScan(state: SessionState, before: List<SessionEffect>): Transition {
        val t = state.ticket + 1
        val snapshot = state.snapshot
            ?: return Transition(state.copy(phase = Phase.SCANNING, ticket = t, requestedSnapshot = null),
                before + StartTimer(timeouts.scanningMs, t))
        val request = GuideRequest(
            sessionId = checkNotNull(state.sessionId) { "세션 밖에서는 서버에 묻지 않는다" },
            utterance = state.pendingUtterance,
            goal = state.goal,
            snapshot = snapshot,
            history = state.history.takeLast(GuideRequest.MAX_HISTORY),
        )
        return Transition(state.copy(phase = Phase.PLANNING, ticket = t, requestedSnapshot = snapshot), before + RequestGuide(request, t))
    }

    /** 문장을 읽고 화면 전환을 기다린다 (SCROLL · WAIT · UNKNOWN). 하이라이트는 없다. */
    private fun speakAndWait(state: SessionState, screenId: String, text: String, retry: Boolean = false): Transition {
        val t = state.ticket + 1
        return Transition(
            state.copy(phase = Phase.GUIDING, ticket = t, guide = Guidance(screenId, spokenText = text, retryOnTimeout = retry)),
            listOf(ClearHighlight, Speak(text, t)),
        )
    }

    /** 문장을 읽고, 다 읽으면 onSpeechFinished가 다음으로 넘긴다 (ASKING · DONE). */
    private fun speakThen(state: SessionState, phase: Phase, text: String): Transition {
        val t = state.ticket + 1
        return Transition(state.copy(phase = phase, ticket = t, guide = null), listOf(ClearHighlight, Speak(text, t)))
    }

    private fun toError(state: SessionState, text: String): Transition {
        val t = state.ticket + 1
        return Transition(state.copy(phase = Phase.ERROR, ticket = t, guide = null),
            listOf(StopListening, CancelPending, ClearHighlight, Speak(text, t)))
    }

    /** 세션을 끝내고 IDLE로 가면서 한 문장 읽는다 (읽기가 끝나도 더 할 일은 없다). */
    private fun toIdleSaying(state: SessionState, text: String): Transition {
        val t = state.ticket + 1
        return Transition(SessionState(phase = Phase.IDLE, ticket = t, snapshot = state.snapshot),
            listOf(StopListening, CancelPending, ClearHighlight, Speak(text, t)))
    }

    private fun stay(state: SessionState) = Transition(state)
}
