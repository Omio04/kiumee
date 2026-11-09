package kr.kiosklens.core.model

/** 5일 계획에서는 PULSE_BORDER만 구현 (MAGNIFY는 보류). */
enum class HighlightStyle { PULSE_BORDER, MAGNIFY }

data class HighlightTarget(
    val elementId: String,
    /** 분석 프레임 기준 정규화 좌표 */
    val rect: NormRect,
    val style: HighlightStyle = HighlightStyle.PULSE_BORDER,
)
