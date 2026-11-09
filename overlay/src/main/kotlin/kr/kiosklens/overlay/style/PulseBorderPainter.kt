package kr.kiosklens.overlay.style

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kr.kiosklens.overlay.transform.PxRect

/**
 * 노란 테두리 + 검은 바깥선 (설계서 10장): 두께 6dp 이상, 어떤 배경 위에서도 보이게.
 * [level] 0.55..1.0 은 깜빡임 정도(굵기와 투명도가 함께 변한다).
 */
class PulseBorderPainter(density: Float) {
    private val minStroke = 6f * density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(255, 214, 0) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(40, 255, 214, 0) }
    private val box = RectF()
    private val pad = 6f * density

    fun draw(canvas: Canvas, r: PxRect, level: Float, alpha: Float = 1f) {
        box.set(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
        val w = minStroke * (0.9f + 0.3f * level)                    // 6dp 아래로 내려가지 않는다
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        glow.alpha = (40 * level * alpha).toInt()
        canvas.drawRoundRect(box, w * 1.5f, w * 1.5f, glow)
        outline.strokeWidth = w + 2f * minStroke * 0.35f
        outline.alpha = a
        canvas.drawRoundRect(box, w * 1.5f, w * 1.5f, outline)
        ring.strokeWidth = maxOf(w, minStroke)
        ring.alpha = (a * (0.7f + 0.3f * level)).toInt()
        canvas.drawRoundRect(box, w * 1.5f, w * 1.5f, ring)
    }
}
