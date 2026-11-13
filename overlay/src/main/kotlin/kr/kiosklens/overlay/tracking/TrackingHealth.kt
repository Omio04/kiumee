package kr.kiosklens.overlay.tracking

/**
 * 노란 테두리 위치를 믿을 수 있는지 판단한다 (흔들림 따라가기).
 *
 * - 믿을 만한 움직임(신뢰도 [minConfidence] 이상)이면 테두리를 그만큼 옮겨도 된다.
 * - 테두리가 떠 있는 동안 **믿을 수 없는 움직임이 한 번이라도 오면 "위치를 놓침"**: 그사이 화면이 얼마나 움직였는지
 *   모르니 테두리 위치를 믿을 수 없다. 새 위치(OCR 결과)가 [onAnchor]로 들어올 때까지 놓친 상태로 둔다
 *   (그동안 오는 움직임으로는 옮기지 않는다 — 빠진 움직임을 되찾을 수 없으므로).
 * - 가만히 있는데 흐리기만 한 프레임은 [CameraMotionTracker]가 "움직임 없음"으로 믿을 만하게 보내므로 놓치지 않는다.
 * - 테두리가 없을 때 온 움직임은 따지지 않는다.
 *
 * 2026-11-13 팀 시험: 예전 규칙("믿을 만한 움직임이 1초 넘게 없으면 흐리게")은 크게 움직여 놓친 뒤 다시 가만히 있으면
 * 믿을 만한 움직임이 돌아와 **엉뚱한 자리에 또렷하게** 계속 남았다. 그래서 한 번 놓치면 새 위치가 올 때까지 놓친 채로 둔다.
 *
 * 스레드 안전하지 않으므로 부르는 쪽이 잠근다.
 */
class TrackingHealth(private val minConfidence: Float = 0.5f) {
    private var lost = false
    private var reported = false

    /** 지금 위치를 놓친 상태인지. */
    val isLost: Boolean get() = lost

    /**
     * 움직임 하나를 받는다. 이 움직임만큼 테두리를 옮겨도 되면 true.
     * [showing] = 지금 테두리가 떠 있는지 (떠 있을 때만 놓침을 따진다).
     */
    fun onMotion(confidence: Float, showing: Boolean): Boolean {
        if (lost) return false
        if (confidence >= minConfidence) return true
        if (showing) lost = true
        return false
    }

    /** 새 위치가 왔다 (OCR로 다시 찾음). 다시 믿을 수 있다. */
    fun onAnchor() {
        lost = false
        reported = false
    }

    /** 놓친 상태로 바뀐 뒤 처음 부르면 true (재인식 요청을 한 번만 보내려고). */
    fun takeLostEvent(): Boolean {
        if (!lost || reported) return false
        reported = true
        return true
    }
}
