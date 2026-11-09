package kr.kiosklens.core.model

/**
 * 정규화 좌표 (설계서 4.1).
 * 회전 보정이 끝난 분석 프레임 기준, 0.0~1.0, 원점은 좌상단.
 * 화면(픽셀) 좌표로의 변환은 :overlay만 수행한다.
 */
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** 0..1 범위 안이고 넓이가 양수인지. 생성자에서 던지지 않고 호출부가 판단한다. */
    val isValid: Boolean
        get() = left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
            right > left && bottom > top
}

/**
 * 프레임 간 전역 이동 · 확대 추정값 (설계서 7.5, 선택 기능).
 * dx, dy는 정규화 좌표 단위, scale은 1.0이 변화 없음.
 */
data class FrameMotion(
    val frameId: Long,
    val dx: Float,
    val dy: Float,
    val scale: Float = 1f,
    val confidence: Float,
)
