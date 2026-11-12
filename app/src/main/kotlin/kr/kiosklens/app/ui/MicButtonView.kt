package kr.kiosklens.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import kotlin.math.hypot
import kotlin.math.min

/**
 * 화면 아래 가운데의 동그란 마이크 버튼 (2026-11-12 화면 개편: "말하기" · "그만하기" 버튼과 자막을 이것 하나로).
 *
 * - 옅은 하늘색 원: 가운데로 갈수록 더 연해지는 원형 그라데이션 + 하얀 마이크
 * - 듣는 중([listening])에는 둘레로 파동이 퍼진다. 퍼지는 거리 · 진하기는 목소리 크기([level], 0~1)를 따른다 ([MicRipple])
 * - 누르면 살짝 작아진다. 뷰는 파동이 퍼질 자리까지 포함해 크게 잡고, 누르기는 원 안(조금 여유)만 받는다
 */
class MicButtonView(context: Context) : View(context) {

    /** 듣는 중이면 둘레에 파동을 그린다 */
    var listening = false
        set(value) {
            if (field == value) return
            field = value
            if (value) ripple.start() else { ripple.cancel(); level = 0f; shown = 0f }
            invalidate()
        }

    /** 목소리 크기 0~1. 그릴 때는 부드럽게 따라간다 (소리 크기 값은 들쭉날쭉하다) */
    var level = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    private var shown = 0f
    private var phase = 0f

    private val ripple: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = MicRipple.PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            shown += (level - shown) * 0.2f
            invalidate()
        }
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RING }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val capsule = Path()
    private val holder = Path()
    private var stemTop = 0f
    private var stemBottom = 0f
    private var cx = 0f
    private var cy = 0f
    private var r = 0f

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        // 버튼 원 모양의 그림자: 밝은 카메라 화면 위에서도 버튼이 떠 보이게
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval((cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt())
            }
        }
        elevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        r = min(w, h) / 2f / MicRipple.MAX_SCALE
        circlePaint.shader = RadialGradient(cx, cy, r, intArrayOf(CENTER, EDGE), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)

        // 마이크: 512칸 그림(둥근 캡슐 · 받침 호 · 기둥, 선 끝은 둥글게)을 원 지름의 55% 크기로 가운데에
        val s = r * 1.1f / 512f
        val ox = cx - 256f * s
        val oy = cy - 252f * s                                       // 그림의 세로 가운데(48~456)를 원 가운데로
        capsule.reset()
        capsule.addRoundRect(RectF(ox + 192f * s, oy + 48f * s, ox + 320f * s, oy + 304f * s), 64f * s, 64f * s, Path.Direction.CW)
        holder.reset()
        holder.addArc(RectF(ox + 144f * s, oy + 128f * s, ox + 368f * s, oy + 352f * s), 0f, 180f)
        iconLine.strokeWidth = 32f * s
        stemTop = oy + 352f * s
        stemBottom = oy + 456f * s
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        if (listening) {
            for (i in 0 until MicRipple.RINGS) {
                val ring = MicRipple.ring(i, phase, shown)
                ringPaint.alpha = (ring.alpha * 255).toInt()
                canvas.drawCircle(cx, cy, r * ring.scale, ringPaint)
            }
        }
        val press = if (isPressed) 0.94f else 1f
        val saved = canvas.save()
        canvas.scale(press, press, cx, cy)
        canvas.drawCircle(cx, cy, r, circlePaint)
        canvas.drawPath(capsule, iconFill)
        canvas.drawPath(holder, iconLine)
        canvas.drawLine(cx, stemTop, cx, stemBottom, iconLine)
        canvas.restoreToCount(saved)
    }

    /** 파동 자리(원 밖)를 누른 것은 버튼으로 치지 않는다 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && hypot(event.x - cx, event.y - cy) > r * 1.15f) return false
        return super.onTouchEvent(event)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()                                                 // 눌림 모양 바꾸기
    }

    override fun onDetachedFromWindow() {
        ripple.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 옅은 하늘색 (원 가장자리) */
        val EDGE = Color.rgb(0x5E, 0xBA, 0xEC)
        /** 가운데로 갈수록 더 연하게 */
        val CENTER = Color.rgb(0xB4, 0xE1, 0xF8)
        /** 파동 */
        val RING = Color.rgb(0x8F, 0xD0, 0xF5)
    }
}
