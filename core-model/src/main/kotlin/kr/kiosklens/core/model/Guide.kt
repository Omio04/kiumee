package kr.kiosklens.core.model

/*
 * 서버와 주고받는 안내 계약 (계약 v1, docs/contract.md).
 * JSON 필드 이름은 이 파일의 Kotlin 이름과 같다 (camelCase).
 */

enum class ScrollDir { UP, DOWN, LEFT, RIGHT }

/** [GuideAction]의 종류. JSON의 "type" 값과 같다. */
enum class ActionType { TAP, SCROLL, WAIT, ASK_USER, DONE, UNKNOWN }

/**
 * 서버가 정한 다음 행동.
 * JSON에서는 "type" 필드로 구분한다. 예: {"type": "TAP", "targetId": "e4", "guideText": "아메리카노"}
 *
 * 문장 필드(guideText · question · reason)에는 위치 표현이 없다. "화면 오른쪽 아래" 같은 위치 문구는 앱이 만든다.
 */
sealed interface GuideAction {
    /** [targetId] 버튼을 누르게 안내. targetId는 요청 스냅샷에 반드시 있다. guideText는 화면의 실제 버튼 글자. */
    data class Tap(val targetId: String, val guideText: String) : GuideAction

    /** 화면을 밀어서 찾게 안내. 계약에는 있지만 서버는 아직 보내지 않는다. 앱은 처리할 수 있어야 한다. */
    data class Scroll(val direction: ScrollDir, val guideText: String) : GuideAction

    /** 화면 로딩 대기. 계약에는 있지만 서버는 아직 보내지 않는다. 앱은 처리할 수 있어야 한다. */
    data class Wait(val guideText: String) : GuideAction

    /** 무엇을 원하는지 되묻기. question은 그대로 읽어 줄 문장 (60자 이내). */
    data class AskUser(val question: String) : GuideAction

    /** 안내 끝. guideText는 그대로 읽어 줄 마무리 문장 (60자 이내). */
    data class Done(val guideText: String) : GuideAction

    /** 맞는 버튼을 못 찾음. reason은 어르신께 그대로 읽어도 되는 쉬운 문장 (60자 이내). */
    data class Unknown(val reason: String) : GuideAction
}

val GuideAction.type: ActionType
    get() = when (this) {
        is GuideAction.Tap -> ActionType.TAP
        is GuideAction.Scroll -> ActionType.SCROLL
        is GuideAction.Wait -> ActionType.WAIT
        is GuideAction.AskUser -> ActionType.ASK_USER
        is GuideAction.Done -> ActionType.DONE
        is GuideAction.Unknown -> ActionType.UNKNOWN
    }

/** 앱이 실행한 안내 한 건. 서버(LLM)가 흐름을 이어 가는 데 쓴다. */
data class HistoryItem(
    /** 그 안내를 받은 화면 */
    val screenId: String,
    val action: ActionType,
    /** Tap · Scroll · Wait · Done: guideText / AskUser: question / Unknown: null */
    val targetText: String?,
) {
    companion object {
        /** 기록 규칙을 한 곳에 둔다. 앱은 직접 만들지 말고 이 함수를 쓴다. */
        fun of(screenId: String, action: GuideAction): HistoryItem = HistoryItem(
            screenId = screenId,
            action = action.type,
            targetText = when (action) {
                is GuideAction.Tap -> action.guideText
                is GuideAction.Scroll -> action.guideText
                is GuideAction.Wait -> action.guideText
                is GuideAction.AskUser -> action.question
                is GuideAction.Done -> action.guideText
                is GuideAction.Unknown -> null
            },
        )
    }
}

/** POST /v1/guide/next 요청. 이미지는 절대 넣지 않는다 (설계서 2.2). */
data class GuideRequest(
    /** 세션 하나 = "말하기"부터 DONE/종료까지. 새 세션마다 새로 만든다. */
    val sessionId: String,
    /** 이번에 새로 들은 말. 없으면 null (화면만 바뀌어 다시 묻는 경우). */
    val utterance: String?,
    /**
     * 직전 응답의 [GuideResponse.goal]을 그대로 돌려준다. 세션의 첫 요청이면 null.
     * 서버는 상태를 저장하지 않으므로, 화면이 바뀐 뒤에도 목표를 기억하려면 앱이 들고 있어야 한다.
     */
    val goal: String?,
    val snapshot: ScreenSnapshot,
    /** 최근 [MAX_HISTORY]개, 오래된 것부터 */
    val history: List<HistoryItem>,
) {
    companion object {
        const val MAX_HISTORY = 5
        const val MAX_UTTERANCE_LENGTH = 200
        const val MAX_GOAL_LENGTH = 200
    }
}

data class GuideResponse(
    /** 지금까지 어르신이 말한 목표. 다음 요청의 [GuideRequest.goal]에 그대로 넣는다. 비어 있을 수 있다. */
    val goal: String,
    val action: GuideAction,
    /**
     * 도움 모드: 버튼을 가리킬 때 안내 문장 앞에 읽어 줄 이유 · 해결 방법 한 문장 ("메뉴를 먼저 골라야 넘어가요."). 없을 수 있다.
     * 선택 필드라 모르는 쪽은 무시하면 된다 (계약 v1과 호환).
     */
    val say: String? = null,
    /**
     * TAP인데 그 칸이 글자 목록(snapshot)에 없고 AI가 사진에서 직접 찾은 경우의 위치(0~1, 분석 화면 기준). 이때 targetId는 "photo".
     * 선택 필드라 모르는 쪽은 무시한다 (계약 v1과 호환).
     */
    val box: NormRect? = null,
)
