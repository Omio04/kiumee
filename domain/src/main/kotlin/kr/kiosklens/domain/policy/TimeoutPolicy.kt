package kr.kiosklens.domain.policy

/** 시간 규칙 (설계서 6장). 수치 튜닝은 여기 한 곳에서. */
data class TimeoutPolicy(
    /** LISTENING: 이 시간 안에 말이 끝나지 않으면 IDLE (안내 음성) */
    val listenSilenceMs: Long = 8_000,
    /** SCANNING: 이 시간 동안 화면을 못 찾으면 LOST */
    val scanningMs: Long = 10_000,
    /** WAIT_CHANGE: 이 시간 동안 화면이 안 바뀌면 안내를 다시 읽는다 */
    val waitChangeRepeatMs: Long = 15_000,
    /**
     * PLANNING(서버에 묻는 중): 이 시간이 지나도 답이 없으면 "잠시만요, 찾고 있어요"를 읽는다.
     * 무음으로 기다리면 어르신은 고장이라고 느낀다 — 같은 시간이라도 짧은 안내가 있으면 덜 길게 느껴진다 (2026-11-13 자료조사).
     * 서버가 이 안에 답하면(빠른 길 · 규칙 · 사전은 수십 ms) 읽지 않는다.
     */
    val thinkingMs: Long = 1_500,
    /** 서버 요청이 실패했을 때 "잠시만요"를 읽고 이만큼 뒤 다시 묻는다 (무료 LLM은 가끔 늦거나 혼잡하다) */
    val retryAfterFailureMs: Long = 3_000,
    /** 서버 요청이 연달아 이만큼 실패하면 세션을 끝낸다 */
    val maxGuideFailures: Int = 3,
)
