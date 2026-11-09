package kr.kiosklens.overlay

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import kr.kiosklens.core.model.FrameMotion
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.port.HighlightSurface
import kr.kiosklens.core.port.MotionSink
import kr.kiosklens.overlay.style.PulseBorderPainter
import kr.kiosklens.overlay.style.PulseCurve
import kr.kiosklens.overlay.tracking.BoxSmoother
import kr.kiosklens.overlay.transform.CoordinateMapper
import kr.kiosklens.overlay.transform.FillCenterMapper

/**
 * 카메라 화면 위에 겹쳐 놓는 투명 View. 눌러야 할 버튼에 노란 테두리를 깜빡이게 그린다.
 * (README 의 이름은 ComposeOverlay 였지만 Compose 플러그인 없이 쓰려고 일반 View 로 만들었다. 포트 계약은 같다.)
 *
 * - [setTarget]은 어느 스레드에서 불러도 안전. null 이면 숨김. 같은 값을 또 줘도 깜빡임이 리셋되지 않는다([HighlightHolder]).
 * - [setAnalysisSize]로 분석 프레임 크기(회전 보정 후)를 알려 주면 프리뷰 크롭까지 보정해 정확한 위치에 그린다.
 * - 새 위치가 오면 EMA 로 부드럽게 옮긴다([BoxSmoother]).
 */
class ViewOverlay(context: Context) : View(context), HighlightSurface, MotionSink {
    private val painter = PulseBorderPainter(context.resources.displayMetrics.density)
    private val smoother = BoxSmoother()
    private val lock = Any()
    private val holder = HighlightHolder { t -> onTargetChanged(t) }

    @Volatile private var mapper: CoordinateMapper? = null
    private var shownSince = 0L
    private var lastDraw = 0L

    init { setWillNotDraw(false); isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** 분석 프레임의 (회전 보정된) 가로 · 세로 픽셀 수. 프리뷰와 같은 방향이어야 한다. */
    fun setAnalysisSize(width: Int, height: Int, mirrorX: Boolean = false) { mapper = FillCenterMapper(width, height, mirrorX); postInvalidateOnAnimation() }
    fun setMapper(m: CoordinateMapper) { mapper = m; postInvalidateOnAnimation() }

    override fun setTarget(target: HighlightTarget?) = holder.setTarget(target)

    override fun onFrameMotion(motion: FrameMotion) {
        if (motion.confidence < 0.5f) return
        synchronized(lock) { smoother.nudge(motion.dx, motion.dy) }
        postInvalidateOnAnimation()
    }

    private fun onTargetChanged(t: HighlightTarget?) {
        synchronized(lock) {
            val firstShow = smoother.current == null
            smoother.setTarget(t?.rect)
            if (t != null && firstShow) shownSince = SystemClock.uptimeMillis()
        }
        postInvalidateOnAnimation()                                  // 어느 스레드든 안전
    }

    override fun onDraw(canvas: Canvas) {
        val m = mapper
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDraw == 0L) 16L else now - lastDraw
        lastDraw = now
        val rect = synchronized(lock) { smoother.step(dt); smoother.current } ?: run { lastDraw = 0L; return }
        if (m != null) {
            painter.draw(canvas, m.map(rect, width.toFloat(), height.toFloat()), PulseCurve.level(now - shownSince))
        }
        postInvalidateOnAnimation()                                  // 보이는 동안 계속 깜빡임
    }
}
