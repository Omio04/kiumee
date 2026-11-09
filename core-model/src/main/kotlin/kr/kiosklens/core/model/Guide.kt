package kr.kiosklens.core.model

enum class ScrollDir { UP, DOWN, LEFT, RIGHT }

/** 서버(LLM)가 정한 다음 행동 (설계서 4.2). JSON에서는 type 필드로 구분한다. */
sealed interface GuideAction {
    /** guideText에는 위치 표현을 넣지 않는다. 위치 문구는 클라이언트가 만든다 (7.4). */
    data class Tap(val targetId: String, val guideText: String) : GuideAction
    data class Scroll(val direction: ScrollDir, val guideText: String) : GuideAction
    /** 화면 로딩 대기 */
    data class Wait(val guideText: String) : GuideAction
    /** 모호할 때 되묻기 */
    data class AskUser(val question: String) : GuideAction
    data class Done(val guideText: String) : GuideAction
    data class Unknown(val reason: String) : GuideAction
}

data class HistoryItem(
    val screenId: String,
    /** GuideAction 종류 이름 (TAP, SCROLL, ...) */
    val action: String,
    val targetText: String?,
)

data class GuideRequest(
    val sessionId: String,
    /** 새 발화가 있을 때만 */
    val utterance: String?,
    val snapshot: ScreenSnapshot,
    /** 최근 최대 [MAX_HISTORY]개 */
    val history: List<HistoryItem>,
) {
    companion object {
        const val MAX_HISTORY = 5
    }
}

data class GuideResponse(
    val goal: String,
    val action: GuideAction,
)
