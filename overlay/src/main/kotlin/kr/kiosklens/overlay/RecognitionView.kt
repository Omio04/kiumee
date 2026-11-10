package kr.kiosklens.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.overlay.transform.CoordinateMapper
import kr.kiosklens.overlay.transform.FillCenterMapper

/**
 * 카메라가 지금 무엇을 인식했는지 미리보기 위에 그려 보이는 **확인용** View (`:app`이 디버그 빌드에서만 붙인다).
 *
 * - 인식한 글자 덩어리마다 얇은 테두리 + 글자. 버튼으로 본 것(BUTTON_LIKE)은 하늘색, 그 밖의 글자는 흰색
 * - 새 인식이 [STALE_MS]보다 오래 없으면 지운다 (카메라를 다른 데로 돌렸을 때 옛 결과가 남지 않게)
 * - 좌표 변환은 [ViewOverlay]와 같은 [FillCenterMapper] → 노란 하이라이트와 같은 자리에 그려진다
 * - [show] · [setAnalysisSize]는 어느 스레드에서 불러도 된다
 */
class RecognitionView(context: Context) : View(context) {
    @Volatile private var mapper: CoordinateMapper? = null
    @Volatile private var snapshot: ScreenSnapshot? = null
    @Volatile private var shownAt = 0L

    private val density = context.resources.displayMetrics.density
    private val buttonPaint = stroke(Color.rgb(0, 229, 255))
    private val textPaint = stroke(Color.argb(210, 255, 255, 255))
    private val labelBg = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * density }

    init { setWillNotDraw(false); isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** 분석 프레임의 (회전 보정된) 가로 · 세로 픽셀 수. [ViewOverlay.setAnalysisSize]와 같은 값을 넣는다. */
    fun setAnalysisSize(width: Int, height: Int, mirrorX: Boolean = false) { mapper = FillCenterMapper(width, height, mirrorX); postInvalidate() }

    /** 새 인식 결과. */
    fun show(s: ScreenSnapshot) { snapshot = s; shownAt = SystemClock.uptimeMillis(); postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = snapshot ?: return
        val m = mapper ?: return
        val age = SystemClock.uptimeMillis() - shownAt
        if (age > STALE_MS) return                                           // 오래된 결과는 그리지 않는다
        val pad = 2f * density
        for (b in RecognitionLayout.boxes(s.elements, m, width.toFloat(), height.toFloat())) {
            canvas.drawRect(b.px.left, b.px.top, b.px.right, b.px.bottom, if (b.isButton) buttonPaint else textPaint)
            val w = label.measureText(b.label)
            val base = maxOf(b.px.top - pad, label.textSize + pad)               // 상자 바로 위 (화면 위로 넘치면 안쪽으로)
            canvas.drawRect(b.px.left, base - label.textSize - pad, b.px.left + w + 2 * pad, base + pad, labelBg)
            canvas.drawText(b.label, b.px.left + pad, base - pad, label)
        }
        postInvalidateDelayed(STALE_MS - age + 50)                            // 새 결과가 안 오면 오래된 뒤 지워지도록
    }

    private fun stroke(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = c }

    companion object { const val STALE_MS = 1_500L }
}
