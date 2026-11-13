package kr.kiosklens.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import kr.kiosklens.core.model.FrameMotion
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.port.HighlightSurface
import kr.kiosklens.core.port.MotionSink
import kr.kiosklens.overlay.style.PulseBorderPainter
import kr.kiosklens.overlay.style.PulseCurve
import kr.kiosklens.overlay.tracking.BoxSmoother
import kr.kiosklens.overlay.tracking.TrackingHealth
import kr.kiosklens.overlay.transform.CoordinateMapper
import kr.kiosklens.overlay.transform.FillCenterMapper
import kr.kiosklens.overlay.transform.ScreenInsets
import kr.kiosklens.overlay.transform.VisibleArea

/**
 * 카메라 화면 위에 겹쳐 놓는 투명 View. 눌러야 할 버튼에 노란 테두리를 깜빡이게 그린다.
 * (README 의 이름은 ComposeOverlay 였지만 Compose 플러그인 없이 쓰려고 일반 View 로 만들었다. 포트 계약은 같다.)
 *
 * - [setTarget]은 어느 스레드에서 불러도 안전. null 이면 숨김. 같은 값을 또 줘도 깜빡임이 리셋되지 않는다([HighlightHolder]).
 * - [setAnalysisSize]로 분석 프레임 크기(회전 보정 후)를 알려 주면 프리뷰 크롭까지 보정해 정확한 위치에 그린다.
 * - 새 위치가 오면 EMA 로 부드럽게 옮긴다([BoxSmoother]).
 * - 하이라이트가 화면 밖이나 가려진 곳(아래 버튼판 등)에 걸려 어르신 눈에 안 보이게 되면 [onTargetVisibilityChanged]로 알린다.
 *   가려진 영역은 [setObstructedInsets]로 알려 준다. 음성 안내("휴대폰을 조금 뒤로 빼 주세요")는 이 신호를 받은 쪽(:app → :domain)이 한다.
 * - 흔들림 따라가기: [onFrameMotion](MotionSink)에 카메라 움직임을 넣으면 다음 OCR 결과가 올 때까지 테두리가 흔들림만큼 따라간다
 *   (움직임은 tracking.CameraMotionTracker가 만든다). 크게 움직여 **못 따라간 순간 위치를 놓친 것으로 보고 테두리를 숨긴다.**
 *   새 위치(OCR)가 오면 그 자리에 다시 짚으며 나타난다. 놓친 순간 [onTrackingLost]를 한 번 부른다 (재인식 요청용).
 *   엉뚱한 자리를 계속 가리키는 것보다 잠깐 안 보이는 편이 안전하다 (2026-11-13 팀 시험). 움직임을 넣지 않으면 이 동작은 꺼진 것과 같다.
 * - 그리기 속도: [renderStats] · [onRenderStats] (합격 기준 30fps 이상).
 */
class ViewOverlay(context: Context) : View(context), HighlightSurface, MotionSink {
    private val painter = PulseBorderPainter(context.resources.displayMetrics.density)
    private val smoother = BoxSmoother()
    private val lock = Any()
    private val holder = HighlightHolder { t -> onTargetChanged(t) }

    @Volatile private var mapper: CoordinateMapper? = null
    @Volatile private var insets = ScreenInsets.NONE
    private val visibility = VisibilityTracker()                     // 메인 스레드에서만 쓴다
    private val health = TrackingHealth()                            // lock 안에서만 쓴다
    private val stats = RenderStats()                                // 메인 스레드에서만 쓴다
    private var shownSince = 0L
    private var lastDraw = 0L
    private var lastStatsReport = 0L

    /** 지금 위치를 놓쳐 테두리를 숨긴 상태인지. 메인 스레드에서 읽는다. */
    var isTrackingLost = false
        private set

    /** 위치를 놓친 순간(크게 움직여 못 따라감) 메인 스레드에서 한 번 불린다. 재인식 요청 등에 쓴다. */
    var onTrackingLost: (() -> Unit)? = null

    /** 하이라이트를 그리는 동안 약 1초마다 메인 스레드에서 불린다. 예: KL_PERF 로그. */
    var onRenderStats: ((RenderStats.Snapshot) -> Unit)? = null

    /** 최근 1초의 그리기 속도. 메인 스레드에서 읽는다. */
    val renderStats: RenderStats.Snapshot get() = stats.snapshot()

    /**
     * 하이라이트가 보이는지가 바뀔 때 메인 스레드에서 불린다. false = 화면 밖이나 가려진 곳이라 안 보임,
     * true = 안 보임이 끝남 (다시 보임 · 보이는 다른 버튼으로 바뀜 · 하이라이트가 없어짐).
     * 같은 버튼이 계속 안 보이면 다시 부르지 않는다 ([VisibilityTracker]). 마지막으로 받은 값이 지금 상태다.
     */
    var onTargetVisibilityChanged: ((visible: Boolean) -> Unit)? = null

    init { setWillNotDraw(false); isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** 분석 프레임의 (회전 보정된) 가로 · 세로 픽셀 수. 프리뷰와 같은 방향이어야 한다. */
    fun setAnalysisSize(width: Int, height: Int, mirrorX: Boolean = false) { mapper = FillCenterMapper(width, height, mirrorX); refresh() }
    fun setMapper(m: CoordinateMapper) { mapper = m; refresh() }

    /** 이 View 가장자리 중 다른 것에 가려진 두께(픽셀). 예: 아래 버튼판 높이. 어느 스레드에서 불러도 된다. */
    fun setObstructedInsets(left: Int, top: Int, right: Int, bottom: Int) {
        insets = ScreenInsets(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        refresh()
    }

    override fun setTarget(target: HighlightTarget?) {
        if (target != null && target == holder.target) {
            // 같은 값: HighlightHolder가 걸러서 다시 그리지 않는다(깜빡임 유지). 다만 위치를 놓친 상태였다면 그 자리로 다시 맞춘다
            val reanchored = synchronized(lock) { health.isLost.also { if (it) reanchor(target.rect) } }
            if (reanchored) refresh()
            return
        }
        holder.setTarget(target)
    }

    /** 새 위치로 다시 맞춘다: 놓친 상태를 풀고, 이어서 옮기지 않고 바로 그 자리에, 다시 짚으며 나타나게. lock 안에서 부른다. */
    private fun reanchor(rect: NormRect) {
        health.onAnchor()
        smoother.setTarget(null)
        smoother.setTarget(rect)
        shownSince = SystemClock.uptimeMillis()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        checkVisibility()
    }

    private fun refresh() {
        postInvalidateOnAnimation()
        post { checkVisibility() }                                   // 판단은 메인 스레드에서
    }

    private fun checkVisibility() {
        val t = holder.target
        val m = mapper
        val rect = if (t == null) null else synchronized(lock) { smoother.target } ?: t.rect   // 흔들림만큼 옮겨진 자리
        val visible = if (rect == null || m == null) true
        else VisibleArea.isVisible(rect, m, width.toFloat(), height.toFloat(), insets)
        visibility.update(t?.elementId, visible)?.let { onTargetVisibilityChanged?.invoke(it) }
    }

    /**
     * 카메라 움직임 (분석 스레드 등 어느 스레드에서 불러도 된다). 믿을 만하면 테두리를 그만큼 옮긴다.
     * 테두리가 떠 있는데 믿을 수 없는 움직임이 오면 위치를 놓친 것으로 보고 새 위치가 올 때까지 숨긴다 ([TrackingHealth]).
     */
    override fun onFrameMotion(motion: FrameMotion) {
        var moved = false
        var lostNow = false
        synchronized(lock) {
            val wasLost = health.isLost
            val ok = health.onMotion(motion.confidence, showing = smoother.current != null)
            if (ok && (motion.dx != 0f || motion.dy != 0f)) { smoother.nudge(motion.dx, motion.dy); moved = true }
            lostNow = !wasLost && health.isLost
        }
        if (holder.target == null) return
        if (moved || lostNow) refresh()                              // 옮겼거나 숨겼으면 다시 그리고 보이는지도 다시 판단
    }

    private fun onTargetChanged(t: HighlightTarget?) {
        synchronized(lock) {
            if (t != null && health.isLost) reanchor(t.rect)         // 놓친 뒤 새 위치: 이어서 옮기지 않고 그 자리에 다시 짚기
            else {
                val firstShow = smoother.current == null
                smoother.setTarget(t?.rect)
                if (t != null && firstShow) shownSince = SystemClock.uptimeMillis()
                health.onAnchor()                                    // 새 위치가 왔으니 믿을 수 있다
            }
        }
        refresh()                                                    // 어느 스레드든 안전
    }

    override fun onDraw(canvas: Canvas) {
        val m = mapper
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDraw == 0L) 16L else now - lastDraw
        lastDraw = now
        var rect: NormRect? = null
        var lost = false
        var lostEvent = false
        synchronized(lock) {
            smoother.step(dt)
            rect = smoother.current
            lost = health.isLost
            lostEvent = health.takeLostEvent()
        }
        val r = rect
        isTrackingLost = r != null && lost
        if (lostEvent) post { onTrackingLost?.invoke() }
        if (r == null || lost) { lastDraw = 0L; stats.pause(); return }   // 없음 · 위치를 놓침: 그리지 않는다 (새 위치가 오면 다시 그림)
        if (m != null) {
            val since = now - shownSince
            val traced = if (ValueAnimator.areAnimatorsEnabled()) PulseCurve.trace(since) else 1f   // 나타날 때 그려지며 짚기 (P1)
            painter.draw(canvas, m.map(r, width.toFloat(), height.toFloat()), PulseCurve.level(since), 1f, traced)
        }
        stats.onFrame(now)
        if (now - lastStatsReport >= 1_000L) {
            lastStatsReport = now
            onRenderStats?.let { cb -> val s = stats.snapshot(); post { cb(s) } }
        }
        postInvalidateOnAnimation()                                  // 보이는 동안 계속 깜빡임
    }

}
