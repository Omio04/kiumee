package kr.kiosklens.overlay.style

import kotlin.math.PI
import kotlin.math.sin

/**
 * 깜빡임 곡선. 1.5Hz(1초에 1.5번)로 부드럽게 커졌다 작아졌다 한다.
 * 광과민 안전 (설계서 10장): 3Hz 초과 금지, 완전히 꺼지지 않고(최소 밝기 유지) 급격한 점멸 없음.
 */
object PulseCurve {
    const val HZ = 1.5
    const val MAX_SAFE_HZ = 3.0
    private const val MIN = 0.55f

    /** 0.55..1.0 */
    fun level(elapsedMs: Long): Float {
        val phase = (elapsedMs / 1000.0) * HZ * 2.0 * PI
        return (MIN + (1f - MIN) * (0.5f + 0.5f * sin(phase).toFloat()))
    }
}
