package kr.kiosklens.domain.session

import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.SttError

/**
 * 상태머신에 들어오는 "일어난 일".
 * SessionRunner가 포트 · UI에서 받아 넣는다.
 */
sealed interface SessionEvent {
    /** 큰 "말하기" 버튼. sessionId는 SessionRunner가 만든다 (상태머신은 순수 함수라 직접 못 만듦) */
    data class StartPressed(val sessionId: String) : SessionEvent

    /** 종료 · "처음부터" 버튼. 어떤 단계에서든 IDLE로 */
    data object StopPressed : SessionEvent

    /** SnapshotSource.snapshots에서 새 화면이 왔다 */
    data class SnapshotReceived(val snapshot: ScreenSnapshot) : SessionEvent

    /** SnapshotSource.screenChanges에서 새 screenId가 왔다 */
    data class ScreenChanged(val screenId: String) : SessionEvent

    // ---- 아래는 상태머신이 시킨 일의 결과. 시킬 때 받은 번호표(ticket)를 그대로 달고 온다 ----

    /** 번호표가 붙은 결과 이벤트. 번호가 지금 상태의 번호와 다르면 무시된다 */
    sealed interface Ticketed : SessionEvent {
        val ticket: Int
    }

    /** SpeechInput이 Final을 냈다 */
    data class SpeechRecognized(override val ticket: Int, val text: String) : Ticketed

    /** SpeechInput이 Error를 냈다 */
    data class SpeechFailed(override val ticket: Int, val error: SttError) : Ticketed

    /** SpeechOutput.speak()가 끝까지 읽고 돌아왔다 (취소되면 보내지 않는다) */
    data class SpeechFinished(override val ticket: Int) : Ticketed

    /** GuideAgent.next()가 성공했다 */
    data class GuideReceived(override val ticket: Int, val response: GuideResponse) : Ticketed

    /** GuideAgent.next()가 실패했다 (재시도까지 끝난 뒤) */
    data class GuideFailed(override val ticket: Int) : Ticketed

    /** StartTimer로 건 시간이 지났다 */
    data class TimedOut(override val ticket: Int) : Ticketed
}
