package kr.kiosklens.app.ui

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.hypot
import kotlin.math.min

/**
 * 화면 아래 가운데의 동그란 마이크 버튼 = 하늘색 구슬 (2026-11-12 화면 개편).
 *
 * 자막을 없앴으니 지금 상태를 글자 대신 구슬의 색과 움직임으로 보여 준다 ([OrbState]):
 * 대기 숨쉬기 · 듣는 중 파동(목소리 크기를 따름) · 생각 중 도는 호 · 말하는 중 소리 막대 · 문제 주황빛.
 * 기기에서 애니메이션을 끄면 움직이지 않고 그 상태의 한 장면만 그린다.
 * 뷰는 파동이 퍼질 자리까지 포함해 크게 잡고, 누르기는 구슬 안(조금 여유)만 받는다.
 */
class MicButtonView(context: Context) : View(context) {

    var state = OrbState.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (value != OrbState.LISTENING) { level = 0f; shown = 0f }
            painter.setWarm(value == OrbState.PROBLEM)
            invalidate()
        }

    /** 목소리 크기 0~1. 그릴 때는 부드럽게 따라간다 (소리 크기 값은 들쭉날쭉하다) */
    var level = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    private var shown = 0f
    private var elapsed = 0L
    private val painter = OrbPainter()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE
    }
    private val arcBox = RectF()

    private val clock = TimeAnimator().apply {
        setTimeListener { _, total, _ ->
            elapsed = total
            shown += (level - shown) * 0.2f
            invalidate()
        }
    }

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        outlineProvider = object : ViewOutlineProvider() {                       // 구슬 모양 그림자 (밝은 화면 위에서도 떠 보이게)
            override fun getOutline(view: View, outline: Outline) {
                val r = painter.r
                outline.setOval((painter.cx - r).toInt(), (painter.cy - r).toInt(), (painter.cx + r).toInt(), (painter.cy + r).toInt())
            }
        }
        elevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)
    }

    private val moving get() = ValueAnimator.areAnimatorsEnabled()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (moving) clock.start()
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        painter.layout(w / 2f, h / 2f, min(w, h) / 2f / MicRipple.MAX_SCALE)
        arc.strokeWidth = painter.r * 0.07f
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        val t = if (moving) elapsed else STILL_MS
        val cx = painter.cx
        val cy = painter.cy
        val r = painter.r
        when (state) {
            OrbState.IDLE, OrbState.PROBLEM -> {                                   // 숨 쉬는 빛: 눌러 달라는 신호
                val b = if (moving) OrbMotion.breath(t) else 0.5f
                halo.color = if (state == OrbState.PROBLEM) Palette.AMBER_LIGHT else Palette.RIPPLE
                halo.alpha = ((0.30f + 0.30f * b) * 255).toInt()
                canvas.drawCircle(cx, cy, r * (1.04f + 0.08f * b), halo)
            }
            OrbState.LISTENING -> {                                                // 목소리 크기를 따르는 파동
                halo.color = Palette.RIPPLE
                val p = (t % MicRipple.PERIOD_MS).toFloat() / MicRipple.PERIOD_MS
                val lv = if (moving) shown else 0.5f
                for (i in 0 until MicRipple.RINGS) {
                    val ring = MicRipple.ring(i, p, lv)
                    halo.alpha = (ring.alpha * 255).toInt()
                    canvas.drawCircle(cx, cy, r * ring.scale, halo)
                }
            }
            OrbState.THINKING -> {                                                 // 둘레를 도는 하얀 호
                val rr = r * 1.14f
                arcBox.set(cx - rr, cy - rr, cx + rr, cy + rr)
                arc.alpha = 70
                canvas.drawOval(arcBox, arc)
                arc.alpha = 240
                canvas.drawArc(arcBox, OrbMotion.spin(t) - 90f, 110f, false, arc)
            }
            OrbState.SPEAKING -> {                                                 // 말하는 동안 잔잔한 빛
                halo.color = Palette.RIPPLE
                halo.alpha = 55
                canvas.drawCircle(cx, cy, r * 1.08f, halo)
            }
        }
        val press = if (isPressed) 0.94f else 1f
        val saved = canvas.save()
        canvas.scale(press, press, cx, cy)
        painter.drawOrb(canvas)
        if (state == OrbState.SPEAKING) painter.drawBars(canvas, t) else painter.drawMic(canvas)
        canvas.restoreToCount(saved)
    }

    /** 파동 자리(구슬 밖)를 누른 것은 버튼으로 치지 않는다 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            hypot(event.x - painter.cx, event.y - painter.cy) > painter.r * 1.15f) return false
        return super.onTouchEvent(event)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()                                                               // 눌림 모양 바꾸기
    }

    private companion object {
        /** 애니메이션을 끈 기기에서 그릴 한 장면 */
        const val STILL_MS = 300L
    }
}
