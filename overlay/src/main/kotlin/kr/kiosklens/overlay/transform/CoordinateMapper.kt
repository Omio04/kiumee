package kr.kiosklens.overlay.transform

import kr.kiosklens.core.model.NormRect
import kotlin.math.max
import kotlin.math.min

/** 화면(픽셀) 사각형. android.graphics.RectF 를 쓰지 않아 JVM 에서 테스트할 수 있다. */
data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * 정규화 좌표(분석 프레임 기준) → 화면 픽셀. 이 변환은 :overlay 만 한다.
 * 분석 프레임 좌표는 이미 회전이 보정되어 있다(contract 1장). 여기서는 프리뷰의 크기 맞춤(크롭)만 보정한다.
 */
interface CoordinateMapper {
    fun map(rect: NormRect, viewW: Float, viewH: Float): PxRect
}

/**
 * CameraX PreviewView 의 FILL_CENTER(기본): 프리뷰를 화면 가득 채우도록 키우고 넘치는 가장자리를 자른다.
 * 분석 프레임이 프리뷰와 같은 시야(같은 종횡비)이면 [cropRatio] 는 1.
 * 비율이 다르면 분석 프레임이 프리뷰의 일부만 보는 것이 아니라 같은 센서 영역이라고 가정하고 종횡비만 같게 맞춘다.
 */
class FillCenterMapper(private val frameW: Int, private val frameH: Int, private val mirrorX: Boolean = false) : CoordinateMapper {
    override fun map(rect: NormRect, viewW: Float, viewH: Float): PxRect {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0f || viewH <= 0f) return PxRect(0f, 0f, 0f, 0f)
        val scale = max(viewW / frameW, viewH / frameH)
        val dx = (viewW - frameW * scale) / 2f
        val dy = (viewH - frameH * scale) / 2f
        var l = rect.left * frameW * scale + dx
        var r = rect.right * frameW * scale + dx
        val t = rect.top * frameH * scale + dy
        val b = rect.bottom * frameH * scale + dy
        if (mirrorX) { val nl = viewW - r; r = viewW - l; l = nl }
        return PxRect(l, t, r, b)
    }

    /** 프리뷰가 잘려서 화면 밖으로 나간 부분이 있는가 (디버그/경고용): 박스 중심이 보이는 영역 안인지 */
    fun isCentreVisible(rect: NormRect, viewW: Float, viewH: Float): Boolean {
        val p = map(rect, viewW, viewH)
        val cx = (p.left + p.right) / 2f; val cy = (p.top + p.bottom) / 2f
        return cx in 0f..viewW && cy in 0f..viewH
    }
}

/** FIT_CENTER: 프리뷰 전체가 보이고 남는 곳은 검은 띠. */
class FitCenterMapper(private val frameW: Int, private val frameH: Int) : CoordinateMapper {
    override fun map(rect: NormRect, viewW: Float, viewH: Float): PxRect {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0f || viewH <= 0f) return PxRect(0f, 0f, 0f, 0f)
        val scale = min(viewW / frameW, viewH / frameH)
        val dx = (viewW - frameW * scale) / 2f
        val dy = (viewH - frameH * scale) / 2f
        return PxRect(rect.left * frameW * scale + dx, rect.top * frameH * scale + dy, rect.right * frameW * scale + dx, rect.bottom * frameH * scale + dy)
    }
}

/** 화면 전체에 늘려서 그린다(크롭 없음). 카메라가 없는 mock 화면이나 디버그용. */
class StretchMapper : CoordinateMapper {
    override fun map(rect: NormRect, viewW: Float, viewH: Float) =
        PxRect(rect.left * viewW, rect.top * viewH, rect.right * viewW, rect.bottom * viewH)
}
