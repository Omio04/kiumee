package kr.kiosklens.app.ui

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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

/**
 * 앱을 켜고 카메라가 영상을 보내기 시작할 때까지 보이는 로딩 화면 (2026-11-12, "깔끔하게").
 *
 * - 바탕은 한 색(아침). 화면 정가운데에 키우미 캐릭터(앱 아이콘과 같은 그림 · 같은 크기)가 있어,
 *   Android 12+ 시스템 시작 화면의 아이콘이 그대로 남은 것처럼 이어진다
 * - 그 아래로 "키우미 / 키오스크, 같이 해요"가 살짝 떠오른다 (이 화면의 단 한 번의 등장)
 * - 기다리는 동안 캐릭터가 가끔 눈을 깜빡인다. 맨 아래 작은 상태 문구
 * - 권한이 없으면 같은 화면에서 이유와 "설정 열기"를 보여 준다 (검은 카메라 화면 위 카드 대신)
 * 애니메이션을 끈 기기에서는 움직임 없이 그대로 보인다.
 */
class LoadingScreen(context: Context) : FrameLayout(context) {
    private val moving = ValueAnimator.areAnimatorsEnabled()
    private val mark = Mark(context)
    private val wordmark: TextView
    private val tagline: TextView
    private val action: TextView
    private val column: LinearLayout
    private val status: TextView
    private var dismissed = false

    init {
        setBackgroundColor(Palette.MORNING)
        isClickable = true                                                        // 로딩 중 아래 화면이 눌리지 않게
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.app_name) + ". " + context.getString(R.string.loading_camera)
        addView(mark, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        wordmark = TextView(context).apply {
            text = context.getString(R.string.app_name)
            setTextColor(Palette.DEEP); setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 800, false) else Typeface.DEFAULT_BOLD
            letterSpacing = -0.02f; gravity = Gravity.CENTER
        }
        tagline = TextView(context).apply {
            text = context.getString(R.string.loading_tagline)
            setTextColor(Palette.DEEP_SOFT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        action = TextView(context).apply {                                        // 권한이 없을 때만: 알약 모양 "설정 열기"
            text = context.getString(R.string.open_settings)
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; minHeight = dp(64); setPadding(dp(36), 0, dp(36), 0)
            background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(Palette.SKY_DEEP) }
            isClickable = true; isFocusable = true; visibility = View.GONE
        }
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(wordmark)
            addView(tagline, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(action, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })
        }
        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP))

        status = TextView(context).apply {
            text = context.getString(R.string.loading_camera)
            setTextColor(Palette.DEEP_SOFT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(56) })

        if (moving) {                                                              // 글자가 캐릭터 아래로 살짝 떠오른다
            column.alpha = 0f
            column.translationY = dp(14).toFloat()
            column.animate().alpha(1f).translationY(0f).setStartDelay(150L).setDuration(450L).start()
        }
    }

    /** 글자 묶음은 캐릭터(화면 정가운데, 반지름 [MARK_DP]) 바로 아래에 둔다 */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val want = height / 2 + dp(MARK_DP) + dp(28)
        val shift = (want - column.top).toFloat()
        if (column.top != want) column.offsetTopAndBottom(shift.toInt())
    }

    /** 카메라가 늦게 켜질 때 */
    fun slow() {
        status.text = context.getString(R.string.loading_slow)
    }

    /** 카메라 · 마이크 권한이 없을 때: 같은 화면에서 이유와 "설정 열기" */
    fun needPermission(onOpenSettings: () -> Unit) {
        tagline.text = context.getString(R.string.loading_need_permission)   // 짧은 두 줄로 (읽기 쉽게)
        tagline.setTextColor(Palette.DEEP)
        status.visibility = View.GONE
        action.visibility = View.VISIBLE
        action.setOnClickListener { onOpenSettings() }
        contentDescription = context.getString(R.string.need_permission)
    }

    /** 권한을 받아 다시 카메라를 켤 때 */
    fun waiting() {
        tagline.text = context.getString(R.string.loading_tagline)
        tagline.setTextColor(Palette.DEEP_SOFT)
        status.text = context.getString(R.string.loading_camera)
        status.visibility = View.VISIBLE
        action.visibility = View.GONE
        contentDescription = context.getString(R.string.app_name) + ". " + context.getString(R.string.loading_camera)
    }

    /** 부드럽게 사라진다 (애니메이션을 끈 기기에서는 바로) */
    fun dismiss(onGone: () -> Unit = {}) {
        if (dismissed) return
        dismissed = true
        animate().alpha(0f).setDuration(if (moving) 380L else 0L).withEndAction {
            visibility = View.GONE
            mark.stop()
            onGone()
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** 화면 정가운데의 키우미 캐릭터. 가끔 눈을 깜빡인다 */
    private class Mark(context: Context) : View(context) {
        private val painter = MascotPainter()
        private val moving = ValueAnimator.areAnimatorsEnabled()
        private var elapsed = 0L
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
            painter.layout(w / 2f, h / 2f, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, MARK_DP.toFloat(), resources.displayMetrics))
        }

        override fun onDraw(canvas: Canvas) = painter.draw(canvas, if (moving) OrbMotion.blink(elapsed) else 0f)
    }

    private companion object {
        /** 캐릭터 원의 반지름: Android 12+ 시스템 시작 화면의 아이콘(지름 160dp)과 같게 */
        const val MARK_DP = 80
    }
}
