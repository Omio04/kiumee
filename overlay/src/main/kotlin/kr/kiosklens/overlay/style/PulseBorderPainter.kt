package kr.kiosklens.overlay.style

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kr.kiosklens.overlay.transform.PxRect

/**
 * 노란 테두리 + 검은 바깥선 (설계서 10장): 두께 6dp 이상, 어떤 배경 위에서도 보이게.
 * [level] 0.55..1.0 은 깜빡임 정도(굵기와 투명도가 함께 변한다).
 * [progress] 0..1 은 처음 나타날 때 그려진 정도: 위 가운데에서 시계 방향으로 그려지며 버튼을 짚는다 (P1 2026-11-12).
 */
class PulseBorderPainter(density: Float) {
    private val minStroke = 6f * density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeCap = Paint.Cap.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(255, 214, 0); strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(40, 255, 214, 0) }
    private val box = RectF()
    private val pad = 6f * density
    private val path = Path()
    private val drawn = Path()
    private val measure = PathMeasure()
    private val corner = RectF()

    fun draw(canvas: Canvas, r: PxRect, level: Float, alpha: Float = 1f, progress: Float = 1f) {
        box.set(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
        val w = minStroke * (0.9f + 0.3f * level)                    // 6dp 아래로 내려가지 않는다
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        if (progress < 1f) {                                         // 그려지는 중: 바깥선 · 노란 선만 그린 만큼
            traceOutline(box, w * 1.5f)
            measure.setPath(path, false)
            drawn.reset()
            measure.getSegment(0f, measure.length * progress.coerceAtLeast(0f), drawn, true)
            outline.strokeWidth = w + 2f * minStroke * 0.35f
            outline.alpha = a
            canvas.drawPath(drawn, outline)
            ring.strokeWidth = maxOf(w, minStroke)
            ring.alpha = a
            canvas.drawPath(drawn, ring)
            return
        }
        glow.alpha = (40 * level * alpha).toInt()
        canvas.drawRoundRect(box, w * 1.5f, w * 1.5f, glow)
        outline.strokeWidth = w + 2f * minStroke * 0.35f
        outline.alpha = a
        canvas.drawRoundRect(box, w * 1.5f, w * 1.5f, outline)
        ring.strokeWidth = maxOf(w, minStroke)
        ring.alpha = (a * (0.7f + 0.3f * level)).toInt()
        canvas.drawRoundRect(box, w * 1.5f, w * 1.5f, ring)
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
