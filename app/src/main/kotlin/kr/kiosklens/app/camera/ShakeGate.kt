package kr.kiosklens.app.camera

import kotlin.math.hypot
import kr.kiosklens.core.model.FrameMotion

/**
 * 흔들리는 프레임을 글자 인식(OCR)에 넘기지 않는다 (2026-11-13: 휴대폰이 떨리면 인식이 잘 안 됨).
 *
 * 흔들리며 찍힌 프레임은 글자가 번져서 OCR이 엉뚱한 글자를 읽는다. 그러면 버튼을 못 찾거나, 글자가 달라져서
 * "화면이 바뀌었다"고 잘못 판단한다. 직전 프레임과의 움직임([FrameMotion])이 크면 그 프레임은 건너뛰고,
 * 손이 안정된 프레임을 기다린다.
 *
 * 다만 계속 흔들리면 아무것도 인식하지 못하므로, [MAX_SKIP_MS]가 지나면 흔들려도 한 장은 통과시킨다.
 * (노란 테두리가 버튼을 따라가는 것은 이 문과 상관없이 모든 프레임의 움직임으로 계속된다.)
 * 시간은 밖에서 넣어 주므로 JVM 테스트로 확인한다.
 */
class ShakeGate(
    /** 한 프레임 사이 화면이 이만큼(정규화 좌표, 0.006 = 가로의 0.6% ≈ 1280px에서 8px) 넘게 움직이면 흔들림으로 본다 */
    private val maxMotion: Float = MAX_MOTION,
    private val maxSkipMs: Long = MAX_SKIP_MS,
) {
    private var lastPassMs: Long? = null
    private var firstAttemptMs: Long? = null
    var skipped: Int = 0
        private set

    /** 이 프레임을 OCR로 보내도 되는가. [motion]이 null(비교할 직전 프레임이 없음 · 측정 실패)이면 통과. */
    fun allow(motion: FrameMotion?, nowMs: Long): Boolean {
        val shaking = motion != null && motion.confidence >= MIN_CONFIDENCE && hypot(motion.dx, motion.dy) > maxMotion
        if (firstAttemptMs == null || nowMs < firstAttemptMs!!) firstAttemptMs = nowMs
        val last = lastPassMs ?: firstAttemptMs!!
        val starving = nowMs - last >= maxSkipMs
        if (shaking && !starving) {
            skipped++
            return false
        }
        lastPassMs = nowMs
        return true
    }

    companion object {
        const val MAX_MOTION = 0.006f
        const val MAX_SKIP_MS = 900L

        /** 움직임 추정이 이만큼 믿을 만할 때만 흔들림으로 친다 (글자 없는 흰 벽 등은 값이 불안정) */
        const val MIN_CONFIDENCE = 0.3f
    }
}
