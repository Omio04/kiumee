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
    /** 깜빡임이 가장 약할 때 값 (완전히 꺼지지 않음) */
    const val MIN_LEVEL = 0.55f

    /** 0.55..1.0 */
    fun level(elapsedMs: Long): Float {
        val phase = (elapsedMs / 1000.0) * HZ * 2.0 * PI
        return (MIN_LEVEL + (1f - MIN_LEVEL) * (0.5f + 0.5f * sin(phase).toFloat()))
    }

    /** 처음 나타날 때 테두리가 그려지는 시간 (P1 2026-11-12: 버튼을 짚어 주는 몸짓, 키우미 로딩 화면과 같은 언어) */
    const val TRACE_MS = 400L

    /** 처음 나타난 뒤 테두리가 그려진 정도 0~1. 처음엔 빠르게, 끝에서 천천히 (짚어서 멈추는 느낌) */
    fun trace(sinceShownMs: Long): Float {
        val x = (sinceShownMs.toFloat() / TRACE_MS).coerceIn(0f, 1f)
        val inv = 1f - x
        return 1f - inv * inv * inv
    }
}
