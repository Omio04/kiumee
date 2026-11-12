package kr.kiosklens.overlay.tracking

/**
 * 흔들림 추적이 [lostAfterMs](1초) 넘게 실패하면 "추적 실패"로 본다 (4일차 계획: 추적 실패 1초 이상 → 반투명 + 재인식 요청).
 *
 * 실패 = 믿을 만한 움직임(신뢰도 [minConfidence] 이상)이 1초 넘게 없음. 예: 너무 빨리 흔들어 흐림 · 손으로 가림 ·
 * 다른 곳을 비춤. 이때 테두리 위치를 믿을 수 없으니 흐리게 보인다.
 *
 * - 움직임 정보가 아예 들어오지 않으면(아직 연결 안 함 · 카메라 멈춤) 실패로 보지 않는다 → 연결 전 동작은 그대로
 * - 믿을 만한 움직임이 다시 오거나 새 위치(OCR 결과)가 오면 바로 정상
 * - [takeLostEvent]는 실패로 바뀐 순간 한 번만 true (재인식 요청을 한 번만 보내려고)
 *
 * 시간은 밖에서 넣는다. 스레드 안전하지 않으므로 부르는 쪽이 잠근다.
 */
class TrackingHealth(
    private val lostAfterMs: Long = 1_000L,
    private val feedTimeoutMs: Long = 500L,
    private val minConfidence: Float = 0.5f,
) {
    private var lastFeed: Long? = null
    private var lastGood: Long? = null
    private var reported = false

    fun onMotion(confidence: Float, nowMs: Long) {
        lastFeed = nowMs
        if (lastGood == null || confidence >= minConfidence) markGood(nowMs)
    }

    /** 새 위치가 왔다 (OCR로 다시 찾음) → 지금 위치는 믿을 수 있다. */
    fun onTargetUpdated(nowMs: Long) = markGood(nowMs)

    fun isLost(nowMs: Long): Boolean {
        val feed = lastFeed ?: return false
        val good = lastGood ?: return false
        if (nowMs - feed > feedTimeoutMs) return false                  // 움직임 정보가 끊김 → 판단하지 않음
        return nowMs - good >= lostAfterMs
    }

    /** 실패로 바뀐 순간이면 true를 한 번 돌려준다. */
    fun takeLostEvent(nowMs: Long): Boolean {
        if (!isLost(nowMs) || reported) return false
        reported = true
        return true
    }

    private fun markGood(nowMs: Long) {
        lastGood = nowMs
        reported = false
    }
}
