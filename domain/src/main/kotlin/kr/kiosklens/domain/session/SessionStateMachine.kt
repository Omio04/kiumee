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

    /**
     * "말하기" 버튼.
     * - 대기 · 마무리 안내(DONE) · 오류 안내 중: 새 세션을 시작한다.
     * - 안내가 진행 중(묻는 중 · 화면 찾는 중 · 안내 · 기다림 · 되물음 · 화면 놓침): 같은 세션에 말을 보탠다.
     *   "따뜻한 걸로요", "라떼도 하나 주세요"처럼 중간에 바꾸거나 더할 말이 있다 (2026-11-11 시험: 처음 한 번만 말할 수 있었다).
     *   읽던 안내를 끊고 듣는다. 목표 · 이력은 그대로 두고, 들은 말은 다음 요청의 utterance가 되어 서버가 목표에 붙인다.
     * - 이미 듣는 중이면 무시한다.
     */
    private fun onStart(state: SessionState, sessionId: String): Transition = when (state.phase) {
        Phase.IDLE, Phase.DONE, Phase.ERROR -> newSession(state, sessionId)
        Phase.LISTENING -> stay(state)
        else -> listenMore(state)
    }

    private fun newSession(state: SessionState, sessionId: String): Transition {
        val t = state.ticket + 1
        val next = SessionState(phase = Phase.LISTENING, ticket = t, sessionId = sessionId, snapshot = state.snapshot)
        return Transition(next, listOf(StopSpeaking, ClearHighlight, StartListening(t), StartTimer(timeouts.listenSilenceMs, t)))
    }

    private fun listenMore(state: SessionState): Transition {
        val t = state.ticket + 1
        return Transition(
            state.copy(phase = Phase.LISTENING, ticket = t, guide = null, listeningToAdd = true),
            listOf(StopSpeaking, CancelPending, ClearHighlight, StartListening(t), StartTimer(timeouts.listenSilenceMs, t)),
        )
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
        if (utterance.isEmpty()) return heardNothing(state)
        // 서버가 아직 답하지 않은 말이 있으면(묻는 사이 또 말함) 이어 붙인다 — 앞의 말을 잃지 않게
        val said = listOfNotNull(state.pendingUtterance, utterance).joinToString(", ").takeLast(GuideRequest.MAX_UTTERANCE_LENGTH)
        return requestOrScan(state.copy(pendingUtterance = said, listeningToAdd = false), listOf(CancelPending))
    }

    private fun onSpeechFailed(state: SessionState, error: SttError): Transition {
        if (state.phase != Phase.LISTENING) return stay(state)
        if (state.listeningToAdd) return resume(state)                     // 보태려던 말만 실패: 하던 안내는 계속
        if (error != SttError.PERMISSION && inProgress(state)) return askAgainLater(state)
        return if (error == SttError.NO_SPEECH) toIdleSaying(state, texts.silence()) else toError(state, texts.speechError(error))
    }

    /** 아무 말도 못 들었다. 보태려던 말이면 하던 안내로, 질문에 대한 대답이면 잠시 뒤 다시 묻고, 처음 말하기면 세션을 끝낸다. */
    private fun heardNothing(state: SessionState): Transition = when {
        state.listeningToAdd -> resume(state)
        inProgress(state) -> askAgainLater(state)
        else -> toIdleSaying(state, texts.silence())
    }

    /** 목표나 이력이 있다 = 주문을 진행하던 중. 이때 음성 하나 실패로 세션을 끝내면 처음부터 다시 말해야 한다 */
    private fun inProgress(state: SessionState) = state.goal != null || state.history.isNotEmpty()

    /**
     * 진행 중인 세션에서 질문에 대한 대답을 못 들었다 (2026-11-12 시험: 인식 오류 11번 하나로 세션이 끝났다).
     * 세션을 끝내지 않고 "못 들었어요"를 읽고, 기다리다 최신 화면으로 다시 묻는다 → 같은 질문이 다시 나온다.
     * 그 사이 "말하기"를 눌러 바로 대답할 수도 있다.
     */
    private fun askAgainLater(state: SessionState): Transition {
        val screenId = state.snapshot?.screenId ?: state.requestedSnapshot?.screenId ?: return toIdleSaying(state, texts.silence())
        val t = state.ticket + 1
        val g = Guidance(screenId, spokenText = texts.notHeard(), retryOnTimeout = true)
        return Transition(state.copy(phase = Phase.GUIDING, ticket = t, guide = g), listOf(StopListening, CancelPending, Speak(g.spokenText, t)))
    }

    /** 하던 안내로 돌아간다: 최신 화면으로 서버에 다시 묻는다 (목표 · 이력은 그대로라 같은 단계가 다시 나온다). */
    private fun resume(state: SessionState): Transition =
        requestOrScan(state.copy(listeningToAdd = false), listOf(StopListening, CancelPending))

    private fun onSpeechFinished(state: SessionState): Transition {
        val t = state.ticket + 1
        return when (state.phase) {
            Phase.GUIDING -> Transition(state.copy(phase = Phase.WAIT_CHANGE, ticket = t),
                listOf(StartTimer(state.guide?.waitMs ?: timeouts.waitChangeRepeatMs, t)))
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
            guideFailures = 0,
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

    /**
     * 서버 요청 실패 (앱의 재시도까지 끝난 뒤). 무료 LLM은 가끔 늦거나 혼잡해서 한 번 실패로 세션을 끝내면
     * 처음부터 다시 말해야 한다 (2026-11-12). "잠시만요"를 읽고 잠깐 뒤 같은 내용(들은 말 · 목표 · 이력)으로 다시 묻는다.
     * 연달아 [TimeoutPolicy.maxGuideFailures]번 실패하면 그때 오류 안내 뒤 끝낸다.
     */
    private fun onGuideFailed(state: SessionState): Transition {
        if (state.phase != Phase.PLANNING) return stay(state)
        val failures = state.guideFailures + 1
        val asked = state.requestedSnapshot
        if (failures >= timeouts.maxGuideFailures || asked == null) return toError(state, texts.networkError())
        val t = state.ticket + 1
        val g = Guidance(asked.screenId, spokenText = texts.retrying(), retryOnTimeout = true, waitMs = timeouts.retryAfterFailureMs)
        return Transition(
            state.copy(phase = Phase.GUIDING, ticket = t, guide = g, guideFailures = failures),
            listOf(Speak(g.spokenText, t)),                                   // 하이라이트는 그대로 둔다 (하던 안내가 있으면 계속 보이게)
        )
    }

    // ---------------- 시간 초과 ----------------

    private fun onTimeout(state: SessionState): Transition {
        val t = state.ticket + 1
        return when (state.phase) {
            // 서버 답이 늦다: 상태 · 번호표는 그대로 두고(그래야 곧 올 답이 유효하다) 기다린다는 말만 한다
            Phase.PLANNING -> Transition(state, listOf(Speak(texts.thinking(), state.ticket)))
            Phase.LISTENING -> heardNothing(state)                                              // 무음 8초
            Phase.SCANNING -> Transition(state.copy(phase = Phase.LOST, ticket = t), listOf(Speak(texts.lost(), t)))   // 10초
            Phase.WAIT_CHANGE -> {                                                               // 15초
                val g = state.guide ?: return stay(state)
                if (g.retryOnTimeout || missedChange(state, g)) requestOrScan(state.copy(guide = null), listOf(ClearHighlight))   // 최신 화면으로 다시 묻기
                else Transition(state.copy(phase = Phase.GUIDING, ticket = t), listOf(Speak(g.spokenText, t)))   // 다시 읽기
            }
            else -> stay(state)
        }
    }

    /**
     * 화면 번호는 그대로인데 안내한 버튼이 안 보이고 처음 보는 글자가 [MISSED_CHANGE_NEW_TEXTS]개 이상 보이면
     * 화면 전환을 놓친 것으로 본다 (메뉴 위에 옵션 창이 떠서 뒤 글자가 그대로 읽히는 경우 등).
     * 2026-11-11 시험: 아메리카노를 눌렀는데도 4분 동안 15초마다 같은 안내만 되풀이했다.
     * 손가락이 버튼을 가린 것뿐이면 처음 보는 글자가 없어서 다시 묻지 않는다.
     */
    private fun missedChange(state: SessionState, g: Guidance): Boolean {
        val id = g.targetId ?: return false
        val now = state.snapshot?.takeIf { it.screenId == g.screenId } ?: return false
        val asked = state.requestedSnapshot ?: return false
        if (now.findElement(id) != null) return false
        val seen = asked.elements.mapTo(HashSet()) { it.text }
        return now.elements.count { it.text !in seen } >= MISSED_CHANGE_NEW_TEXTS
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
        return Transition(state.copy(phase = Phase.PLANNING, ticket = t, requestedSnapshot = snapshot), before + StartTimer(timeouts.thinkingMs, t) + RequestGuide(request, t))
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

    private companion object {
        /** 화면 전환을 놓쳤다고 볼 "처음 보는 글자" 수 (옵션 창: 제목 · 옵션 · 취소 · 담기 …) */
        const val MISSED_CHANGE_NEW_TEXTS = 3
    }
}
