package kr.kiosklens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import kotlin.math.hypot

/**
 * 카메라 화면 가장자리를 은은하게 어둡게 (비네트): 가운데 키오스크 화면에 시선이 모이고, 위아래 글자 · 구슬이 또렷해진다.
 * 노란 안내 테두리보다 아래에 깐다.
 */
class VignetteView(context: Context) : View(context) {
    private val paint = Paint()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val radius = hypot(w / 2f, h / 2f)
        paint.shader = RadialGradient(w / 2f, h * 0.45f, radius, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) = canvas.drawPaint(paint)
}

/** 카메라 화면 왼쪽 위의 작은 키우미 캐릭터 (앱 표시, 장식이라 TalkBack은 건너뛴다) */
class MiniMascotView(context: Context) : View(context) {
    private val painter = MascotPainter()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics)
        outlineProvider = object : android.view.ViewOutlineProvider() {           // 원 모양 그림자
            override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = painter.layout(w / 2f, h / 2f, minOf(w, h) / 2f)

    override fun onDraw(canvas: Canvas) = painter.draw(canvas)
}
