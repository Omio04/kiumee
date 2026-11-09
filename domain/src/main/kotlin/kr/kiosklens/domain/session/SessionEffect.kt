package kr.kiosklens.domain.session

import kr.kiosklens.core.model.GuideRequest
import kr.kiosklens.core.model.HighlightTarget

/**
 * 상태머신이 내리는 "할 일". 상태머신은 실행하지 않고 목록만 만든다.
 * SessionRunner(2일차)가 목록 순서대로 포트를 불러 실행한다.
 *
 * ticket이 붙은 일은 끝나면 같은 ticket을 단 결과 이벤트를 돌려보내야 한다.
 */
sealed interface SessionEffect {
    /** SpeechInput.start(). 결과: SpeechRecognized / SpeechFailed(ticket) */
    data class StartListening(val ticket: Int) : SessionEffect

    /** SpeechInput.stop() */
    data object StopListening : SessionEffect

    /** SpeechOutput.speak(text, interrupt = true). 끝까지 읽으면 SpeechFinished(ticket) */
    data class Speak(val text: String, val ticket: Int) : SessionEffect

    /** SpeechOutput.stop() */
    data object StopSpeaking : SessionEffect

    /** GuideAgent.next(request). 결과: GuideReceived / GuideFailed(ticket) */
    data class RequestGuide(val request: GuideRequest, val ticket: Int) : SessionEffect

    /** HighlightSurface.setTarget(target) */
    data class Highlight(val target: HighlightTarget) : SessionEffect

    /** HighlightSurface.setTarget(null) */
    data object ClearHighlight : SessionEffect

    /** delayMs 뒤 TimedOut(ticket). 새 타이머는 이전 타이머를 대신한다 (타이머는 항상 하나) */
    data class StartTimer(val delayMs: Long, val ticket: Int) : SessionEffect

    /** 진행 중인 타이머와 서버 요청을 취소한다 (결과가 와도 ticket이 달라 무시되지만, 자원 정리용) */
    data object CancelPending : SessionEffect
}

/** reduce 한 번의 결과: 다음 상태 + 할 일 목록 (순서대로 실행) */
data class Transition(
    val next: SessionState,
    val effects: List<SessionEffect> = emptyList(),
)
