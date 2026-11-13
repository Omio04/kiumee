package kr.kiosklens.app.ui

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.min

/**
 * 카메라 화면의 조준 모서리 (2026-11-12): "키오스크 화면을 여기에 담아 주세요"를 글자 없이 보여 준다.
 *
 * - 찾는 중: 하얀 모서리가 천천히 숨 쉰다
 * - 키오스크 글자를 읽음: 맑은 하늘색으로 바뀌며 살짝 조여든다 (찾았다는 신호)
 * - 노란 안내 테두리가 나온 뒤(안내 · 기다림): 옅어져서 노란 테두리가 돋보이게
 * - 화면을 놓침 · 오류: 주황빛
 * 밝은 화면에서도 보이게 모서리마다 옅은 그림자 선을 먼저 깐다. 애니메이션을 끄면 숨쉬기 · 조여들기 없이 바로 바뀐다.
 * 누르기를 받지 않는다(아래 화면 그대로).
 */
class ViewfinderView(context: Context) : View(context) {

    var look = ViewfinderLook.SEARCHING
        set(value) {
            if (field == value) return
            if (value == ViewfinderLook.FOUND) foundAt = elapsed
            field = value
            invalidate()
        }

    private val moving = ValueAnimator.areAnimatorsEnabled()
    private var elapsed = 0L
    private var foundAt = 0L
    private val clock = TimeAnimator().apply { setTimeListener { _, total, _ -> elapsed = total; invalidate() } }

    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.BLACK
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val frame = RectF()
    private val inner = RectF()
    private val corners = Path()
    private val stroke = dp(5f)

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (moving) clock.start()
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 키오스크 화면을 담을 자리: 가로 90% · 세로 74% (2026-11-12 "가로 90%, 세로를 더 길게", 예전 82% · 52%).
        // 위는 왼쪽 위 키우미 표시 바로 아래(9%), 아래는 83%까지. 아래 모서리는 화면 양끝에만 그려져
        // 가운데 마이크 구슬 · 파동(가로 29~71%)과 겹치지 않는다
        val fw = w * 0.90f
        val fh = h * 0.74f
        val cy = h * 0.46f
        frame.set((w - fw) / 2f, cy - fh / 2f, (w + fw) / 2f, cy + fh / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        // 찾았을 때 살짝 조여든다 (0.45초)
        val snap = if (look == ViewfinderLook.FOUND && moving) OrbMotion.check(elapsed - foundAt) else if (look == ViewfinderLook.FOUND) 1f else 0f
        val inset = min(frame.width(), frame.height()) * 0.035f * snap
        inner.set(frame.left + inset, frame.top + inset, frame.right - inset, frame.bottom - inset)
        buildCorners(inner)

        val b = if (moving) OrbMotion.breath(elapsed) else 0.5f
        val (color, alpha) = when (look) {
            ViewfinderLook.SEARCHING -> Color.WHITE to (0.55f + 0.35f * b)
            ViewfinderLook.FOUND -> FOUND to 0.95f
            ViewfinderLook.QUIET -> Color.WHITE to 0.22f
            ViewfinderLook.PROBLEM -> Palette.AMBER to (0.6f + 0.35f * b)
        }
        shade.strokeWidth = stroke + dp(3f)
        shade.alpha = (alpha * 0.35f * 255).toInt()
        canvas.drawPath(corners, shade)
        line.color = color
        line.strokeWidth = stroke
        line.alpha = (alpha * 255).toInt()
        canvas.drawPath(corners, line)
    }

    /** 네 모서리 (둥글게 꺾인 짧은 선) */
    private fun buildCorners(r: RectF) {
        val arm = min(r.width(), r.height()) * 0.14f
        val rad = dp(18f).coerceAtMost(arm * 0.8f)
        corners.reset()
        // 왼쪽 위
        corners.moveTo(r.left, r.top + arm); corners.lineTo(r.left, r.top + rad)
        corners.quadTo(r.left, r.top, r.left + rad, r.top); corners.lineTo(r.left + arm, r.top)
        // 오른쪽 위
        corners.moveTo(r.right - arm, r.top); corners.lineTo(r.right - rad, r.top)
        corners.quadTo(r.right, r.top, r.right, r.top + rad); corners.lineTo(r.right, r.top + arm)
        // 오른쪽 아래
        corners.moveTo(r.right, r.bottom - arm); corners.lineTo(r.right, r.bottom - rad)
        corners.quadTo(r.right, r.bottom, r.right - rad, r.bottom); corners.lineTo(r.right - arm, r.bottom)
        // 왼쪽 아래
        corners.moveTo(r.left + arm, r.bottom); corners.lineTo(r.left + rad, r.bottom)
        corners.quadTo(r.left, r.bottom, r.left, r.bottom - rad); corners.lineTo(r.left, r.bottom - arm)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        /** 찾았을 때: 카메라 화면 위에서도 또렷한 맑은 하늘 */
        private val FOUND = Color.rgb(0x8F, 0xD8, 0xFF)
    }
}
