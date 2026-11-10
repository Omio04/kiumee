package kr.kiosklens.overlay.transform

import kr.kiosklens.core.model.NormRect

/**
 * 화면 가장자리에서 다른 것에 가려진 두께(픽셀). 예: 아래쪽 "말하기" 버튼판 높이, 위쪽 상태 표시줄 높이.
 * 하이라이트가 이 영역에 걸리면 어르신 눈에 안 보인다.
 */
data class ScreenInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    companion object {
        val NONE = ScreenInsets()
    }
}

/**
 * 하이라이트가 어르신 눈에 보이는지 판단한다 (P5).
 *
 * 기준: 노란 테두리의 **중심**이 휴대폰 화면 안에 있고, 가려진 영역([ScreenInsets]) 밖에 있으면 "보임".
 * - 채우기(FILL) 화면에서는 분석 프레임 좌우가 화면 밖으로 잘린다 → 가장자리 버튼은 "안 보임"
 * - 아래쪽 버튼판 뒤에 걸린 버튼도 "안 보임"
 */
object VisibleArea {
    fun isVisible(
        rect: NormRect,
        mapper: CoordinateMapper,
        viewW: Float,
        viewH: Float,
        insets: ScreenInsets = ScreenInsets.NONE,
    ): Boolean {
        if (viewW <= 0f || viewH <= 0f) return true          // 화면 크기를 아직 모르면 판단하지 않는다 (안 보인다고 알리지 않음)
        val p = mapper.map(rect, viewW, viewH)
        val cx = (p.left + p.right) / 2f
        val cy = (p.top + p.bottom) / 2f
        return cx >= insets.left && cx <= viewW - insets.right &&
            cy >= insets.top && cy <= viewH - insets.bottom
    }
}
