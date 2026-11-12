package kr.kiosklens.app.ui

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kr.kiosklens.app.R
import kotlin.math.min

/**
 * 앱을 켜고 카메라가 영상을 보내기 시작할 때까지 보이는 로딩 화면 (2026-11-12).
 *
 * 하나의 움직임만 둔다: 노란 안내 테두리가 하늘색 구슬 둘레를 위 가운데에서 출발해 한 바퀴 그리고 멈춘다
 * — 키오스크 버튼을 짚어 주는 키우미의 몸짓 그대로. 그 뒤로는 구슬이 숨만 쉰다.
 * 바탕은 밝은 아침색(곧 나올 어두운 카메라 화면과 대비). 애니메이션을 끈 기기에서는 다 그려진 모습만.
 */
class LoadingScreen(context: Context) : FrameLayout(context) {
    private val status: TextView
    private val mark = GuideMark(context)
    private var dismissed = false

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.MORNING, Palette.MORNING_LOW))
        isClickable = true                                                        // 로딩 중 아래 화면이 눌리지 않게
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.app_name) + ". " + context.getString(R.string.loading_camera)

        val wordmark = TextView(context).apply {
            text = context.getString(R.string.app_name)
            setTextColor(Palette.DEEP); setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
            typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 800, false) else Typeface.DEFAULT_BOLD
            letterSpacing = -0.02f; gravity = Gravity.CENTER
        }
        val tagline = TextView(context).apply {
            text = context.getString(R.string.loading_tagline)
            setTextColor(Palette.DEEP_SOFT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); gravity = Gravity.CENTER
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(mark, LinearLayout.LayoutParams(dp(232), dp(232)))
            addView(wordmark, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(tagline, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { bottomMargin = dp(48) })

        status = TextView(context).apply {
            text = context.getString(R.string.loading_camera)
            setTextColor(Palette.DEEP_SOFT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(72) })
    }

    /** 카메라가 늦게 켜질 때 */
    fun slow() {
        status.text = context.getString(R.string.loading_slow)
    }

    /** 부드럽게 사라진다 (애니메이션을 끈 기기에서는 바로) */
    fun dismiss(onGone: () -> Unit = {}) {
        if (dismissed) return
        dismissed = true
        animate().alpha(0f).setDuration(if (ValueAnimator.areAnimatorsEnabled()) 380L else 0L).withEndAction {
            visibility = View.GONE
            mark.stop()
            onGone()
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** 구슬 + 그 둘레를 그려 나가는 노란 안내 테두리 */
    private class GuideMark(context: Context) : View(context) {
        private val painter = OrbPainter()
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.SIGNAL; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.RIPPLE }
        private val outline = Path()
        private val drawn = Path()
        private var length = 0f
        private var elapsed = 0L
        private val moving = ValueAnimator.areAnimatorsEnabled()
        private val clock = TimeAnimator().apply { setTimeListener { _, total, _ -> elapsed = total; invalidate() } }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (moving) clock.start()
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        fun stop() = clock.cancel()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val cx = w / 2f
            val cy = h / 2f
            val side = min(w, h).toFloat()
            painter.layout(cx, cy, side * 0.25f)
            frame.strokeWidth = side * 0.03f
            // 둥근 사각 테두리: 위 가운데에서 시작해 시계 방향으로 한 바퀴 (짚어 주는 손짓처럼)
            val half = side * 0.40f
            val rad = side * 0.13f
            val box = RectF(cx - half, cy - half, cx + half, cy + half)
            outline.reset()
            outline.moveTo(cx, box.top)
            outline.lineTo(box.right - rad, box.top)
            outline.arcTo(RectF(box.right - 2 * rad, box.top, box.right, box.top + 2 * rad), 270f, 90f)
            outline.lineTo(box.right, box.bottom - rad)
            outline.arcTo(RectF(box.right - 2 * rad, box.bottom - 2 * rad, box.right, box.bottom), 0f, 90f)
            outline.lineTo(box.left + rad, box.bottom)
            outline.arcTo(RectF(box.left, box.bottom - 2 * rad, box.left + 2 * rad, box.bottom), 90f, 90f)
            outline.lineTo(box.left, box.top + rad)
            outline.arcTo(RectF(box.left, box.top, box.left + 2 * rad, box.top + 2 * rad), 180f, 90f)
            outline.close()
            length = PathMeasure(outline, false).length
        }

        override fun onDraw(canvas: Canvas) {
            val progress = if (moving) OrbMotion.trace(elapsed) else 1f
            if (progress >= 1f && moving) {                                         // 다 그린 뒤에는 구슬만 숨 쉰다
                val b = OrbMotion.breath(elapsed - OrbMotion.TRACE_DELAY_MS - OrbMotion.TRACE_MS)
                halo.alpha = ((0.12f + 0.18f * b) * 255).toInt()
                canvas.drawCircle(painter.cx, painter.cy, painter.r * (1.05f + 0.07f * b), halo)
            }
            painter.drawOrb(canvas)
            painter.drawMic(canvas)
            if (progress > 0f) {
                drawn.reset()
                PathMeasure(outline, false).getSegment(0f, length * progress, drawn, true)
                canvas.drawPath(drawn, frame)
            }
        }
    }
}
