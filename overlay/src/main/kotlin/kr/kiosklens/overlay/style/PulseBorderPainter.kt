package kr.kiosklens.overlay.style

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kr.kiosklens.overlay.transform.PxRect

/**
 * 노란 안내 테두리 (설계서 10장: 전체 6dp 이상, 노란색 + 어두운 바깥선, 어떤 배경 위에서도 보이게).
 * 굵기 · 진하기는 [BorderStyle] (2026-11-13: 너무 진해 다른 글씨까지 가려서 연하게 — 반투명 노란 선, 얇은 어두운 선, 안쪽 칠 없음).
 *
 * - [level] 0.55..1.0 은 깜빡임 정도: 노란 선의 **진하기만** 변한다 (굵기가 변하면 옆 글씨를 덮었다 말았다 해서)
 * - [alpha] 전체 진하기 (1 = 그대로)
 * - [progress] 0..1 은 처음 나타날 때 그려진 정도: 위 가운데에서 시계 방향으로 그려지며 버튼을 짚는다 (P1 2026-11-12)
 *
 * 어두운 선은 노란 선 **밑에 깔지 않고 양쪽 가장자리에만** 그린다 — 밑에 깔면 반투명 노란 선이 거무스름해진다.
 */
class PulseBorderPainter(density: Float) {
    private val ringW = BorderStyle.RING_DP * density
    private val edgeW = BorderStyle.EDGE_DP * density
    private val pad = BorderStyle.PAD_DP * density
    private val radius = BorderStyle.RADIUS_DP * density
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = edgeW; strokeCap = Paint.Cap.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(255, 214, 0); strokeWidth = ringW; strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()
    private val inner = RectF()
    private val outer = RectF()
    private val path = Path()
    private val drawn = Path()
    private val measure = PathMeasure()
    private val corner = RectF()

    fun draw(canvas: Canvas, r: PxRect, level: Float, alpha: Float = 1f, progress: Float = 1f) {
        box.set(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)               // 노란 선 가운데
        val off = (ringW + edgeW) / 2f                                                    // 노란 선 가장자리 바로 바깥
        outer.set(box.left - off, box.top - off, box.right + off, box.bottom + off)
        inner.set(box.left + off, box.top + off, box.right - off, box.bottom - off)
        val hasInner = inner.width() > 0f && inner.height() > 0f
        edge.alpha = (255 * BorderStyle.EDGE_ALPHA * alpha).toInt().coerceIn(0, 255)
        ring.alpha = (255 * BorderStyle.ringAlpha(level) * alpha).toInt().coerceIn(0, 255)

        if (progress < 1f) {                                                              // 그려지는 중: 그린 만큼만
            val p = progress.coerceIn(0f, 1f)
            drawPart(canvas, outer, radius + off, p, edge)
            if (hasInner) drawPart(canvas, inner, (radius - off).coerceAtLeast(0f), p, edge)
            drawPart(canvas, box, radius, p, ring)
            return
        }
        canvas.drawRoundRect(outer, radius + off, radius + off, edge)
        if (hasInner) canvas.drawRoundRect(inner, (radius - off).coerceAtLeast(0f), (radius - off).coerceAtLeast(0f), edge)
        canvas.drawRoundRect(box, radius, radius, ring)
    }

    /** [rect]의 둥근 테두리를 위 가운데에서 시계 방향으로 [p]만큼 그린다 */
    private fun drawPart(canvas: Canvas, rect: RectF, rad: Float, p: Float, paint: Paint) {
        traceOutline(rect, rad)
        measure.setPath(path, false)
        drawn.reset()
        measure.getSegment(0f, measure.length * p, drawn, true)
        canvas.drawPath(drawn, paint)
    }

    /** 둥근 사각 테두리를 위 가운데에서 시작해 시계 방향으로 ([path]에) */
    private fun traceOutline(b: RectF, radius: Float) {
        val rad = minOf(radius, b.width() / 2f, b.height() / 2f)
        path.reset()
        path.moveTo(b.centerX(), b.top)
        path.lineTo(b.right - rad, b.top)
        corner.set(b.right - 2 * rad, b.top, b.right, b.top + 2 * rad); path.arcTo(corner, 270f, 90f)
        path.lineTo(b.right, b.bottom - rad)
        corner.set(b.right - 2 * rad, b.bottom - 2 * rad, b.right, b.bottom); path.arcTo(corner, 0f, 90f)
        path.lineTo(b.left + rad, b.bottom)
        corner.set(b.left, b.bottom - 2 * rad, b.left + 2 * rad, b.bottom); path.arcTo(corner, 90f, 90f)
        path.lineTo(b.left, b.top + rad)
        corner.set(b.left, b.top, b.left + 2 * rad, b.top + 2 * rad); path.arcTo(corner, 180f, 90f)
        path.close()
    }
}
