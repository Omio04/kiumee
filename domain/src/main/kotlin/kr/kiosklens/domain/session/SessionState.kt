package kr.kiosklens.domain.session

import kr.kiosklens.core.model.HistoryItem
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot

/** 세션 단계 10개 (설계서 6장). */
enum class Phase {
    /** 시작 대기. "말하기" 버튼을 기다린다 */
    IDLE,

    /** 음성 인식 중 */
    LISTENING,

    /** 서버에 다음 행동을 묻는 중 */
    PLANNING,

    /** 키오스크 화면을 찾는 중 (스냅샷을 기다림) */
    SCANNING,

    /** 하이라이트를 켜고 안내 문장을 읽는 중 */
    GUIDING,

    /** 어르신이 누르고 화면이 바뀌기를 기다리는 중 */
    WAIT_CHANGE,

    /** 되묻는 질문을 읽는 중. 다 읽으면 LISTENING */
    ASKING,

    /** 마무리 안내를 읽는 중. 다 읽으면 IDLE */
    DONE,

    /** "화면이 잘 보이게 비춰 주세요"를 읽는 중. 다 읽으면 SCANNING */
    LOST,

    /** 오류 안내를 읽는 중. 다 읽으면 IDLE */
    ERROR,
}

/**
 * 세션 상태. [SessionStateMachine.reduce]만 새 상태를 만든다 (불변 객체).
 *
 * ticket: 비동기 결과(음성 인식 · 음성 안내 · 서버 응답 · 타이머)를 구분하는 번호표.
 * 기다리는 일을 새로 시작할 때마다 1씩 늘린다. 이벤트의 번호가 지금 번호와 다르면 지난 일이라 무시한다.
 */
data class SessionState(
    val phase: Phase = Phase.IDLE,
    val ticket: Int = 0,
    /** 세션 하나 = "말하기"부터 DONE/종료까지. IDLE이 아니면 항상 있다 */
    val sessionId: String? = null,
    /** 서버가 돌려준 목표. 다음 요청에 그대로 넣는다 (계약 v1, 3.3) */
    val goal: String? = null,
    /** 실행한 안내 기록. 최근 GuideRequest.MAX_HISTORY개만 */
    val history: List<HistoryItem> = emptyList(),
    /** 아직 서버 응답을 받지 못한 발화. 응답을 받으면 null */
    val pendingUtterance: String? = null,
    /** 가장 최근에 받은 화면. 세션과 상관없이 계속 갱신된다 */
    val snapshot: ScreenSnapshot? = null,
    /** 서버에 보낸 화면. 응답의 targetId는 이 화면 기준이다 */
    val requestedSnapshot: ScreenSnapshot? = null,
    /** 지금 안내 중인 내용 (GUIDING · WAIT_CHANGE) */
    val guide: Guidance? = null,
    /** 안내 중에 "말하기"를 다시 눌러 듣는 중. 못 들으면 세션을 끝내지 않고 하던 안내로 돌아간다 */
    val listeningToAdd: Boolean = false,
    /** 서버 요청이 연달아 실패한 횟수. 답을 받으면 0 */
    val guideFailures: Int = 0,
    /** 도움 모드: 말이 없어서 조용히 다시 듣기 시작한 횟수. 말을 들으면 0 (너무 길어지면 쉰다) */
    val silentRounds: Int = 0,
)

/** 지금 안내 중인 내용. 화면 전환 판단 · 하이라이트 따라가기 · 다시 읽기에 쓴다. */
data class Guidance(
    /** 안내를 받은 화면. 다른 screenId가 보이면 어르신이 누른 것으로 본다 */
    val screenId: String,
    /** 하이라이트 중인 요소 (TAP일 때만) */
    val targetId: String? = null,
    /** 하이라이트에 마지막으로 보낸 위치 */
    val rect: NormRect? = null,
    /** 15초 동안 화면이 안 바뀌면 다시 읽을 문장 */
    val spokenText: String,
    /** true면 15초 뒤 다시 읽는 대신 서버에 다시 묻는다 (UNKNOWN 응답 뒤) */
    val retryOnTimeout: Boolean = false,
    /** 다 읽은 뒤 기다릴 시간. null이면 15초 (서버 실패 뒤에는 짧게) */
    val waitMs: Long? = null,
)
