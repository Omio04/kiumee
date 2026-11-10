package kr.kiosklens.overlay.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.overlay.ViewOverlay
import kr.kiosklens.overlay.transform.CoordinateMapper
import kr.kiosklens.overlay.transform.FillCenterMapper
import kr.kiosklens.overlay.transform.FitCenterMapper

/**
 * 하이라이트 위치 확인 화면 (P5 PoC, 디버그 빌드 전용).
 *
 * 음성 · 서버 · OCR 없이 "정규화 좌표 → 화면 좌표" 변환이 맞는지 눈으로 확인한다.
 * - 고정 노란 테두리 5개: 분석 프레임의 네 모서리 근처 + 가운데
 * - 분석 프레임 사진을 같은 변환으로 반투명하게 겹쳐 그림(하늘색 테두리 = 분석 프레임 전체).
 *   변환이 맞으면 카메라 미리보기와 정확히 겹쳐서 이중으로 보이지 않는다. 틀리면 어긋나서 겹쳐 보인다.
 * - 위쪽 글자: 분석 프레임 크기 · 회전 · 화면 크기 · 화면에 실제로 보이는 분석 프레임 범위
 * - 버튼: 채우기(FILL)/맞추기(FIT) 전환 · 같은 값 다시 보내기 · 숨김/보임 · 흔들기 (가운데 테두리 대상)
 *
 * 카메라 설정은 앱의 CameraController와 같다 (미리보기 · 분석 모두 4:3).
 * 실행: adb shell am start -n kr.kiosklens.app/kr.kiosklens.overlay.debug.OverlayCalibrationActivity
 */
class OverlayCalibrationActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var preview: PreviewView
    private lateinit var ghost: GhostView
    private lateinit var info: TextView
    private val boxes = mutableListOf<ViewOverlay>()

    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    @Volatile private var rotation = 0
    private var fill = true
    private var centerShown = true
    private var lastGhostMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else info.text = "카메라 권한이 필요합니다"
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    // ---------------- 카메라 (앱의 CameraController와 같은 설정) ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                .also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    val rot = proxy.imageInfo.rotationDegrees
                    val w = if (rot == 90 || rot == 270) proxy.height else proxy.width
                    val h = if (rot == 90 || rot == 270) proxy.width else proxy.height
                    if (w != frameW || h != frameH || rot != rotation) {
                        frameW = w; frameH = h; rotation = rot
                        runOnUiThread { applyMapper() }
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastGhostMs > 400) {                       // 겹쳐 그릴 사진은 0.4초마다 한 장이면 충분
                        lastGhostMs = now
                        val upright = rotate(proxy.toBitmap(), rot)
                        ghost.post { ghost.bitmap = upright; ghost.invalidate() }
                    }
                } finally {
                    proxy.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // ---------------- 변환 방식 적용 · 정보 표시 ----------------

    private fun currentMapper(): CoordinateMapper? {
        if (frameW <= 0 || frameH <= 0) return null
        return if (fill) FillCenterMapper(frameW, frameH) else FitCenterMapper(frameW, frameH)
    }

    private fun applyMapper() {
        preview.scaleType = if (fill) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
        val m = currentMapper() ?: return
        boxes.forEach { it.setMapper(m) }
        ghost.mapper = m
        ghost.invalidate()
        updateInfo()
    }

    private fun updateInfo() {
        val m = currentMapper() ?: return
        val vw = preview.width.toFloat()
        val vh = preview.height.toFloat()
        if (vw <= 0f || vh <= 0f) { preview.post { updateInfo() }; return }
        val r = m.map(FULL, vw, vh)
        // 화면 안에 실제로 보이는 분석 프레임 범위 (0~1)
        val x0 = ((0f - r.left) / r.width).coerceIn(0f, 1f)
        val x1 = ((vw - r.left) / r.width).coerceIn(0f, 1f)
        val y0 = ((0f - r.top) / r.height).coerceIn(0f, 1f)
        val y1 = ((vh - r.top) / r.height).coerceIn(0f, 1f)
        info.text = String.format(
            "방식 %s · 분석 %d×%d (회전 %d°) · 화면 %d×%d\n보이는 범위 x %.2f~%.2f · y %.2f~%.2f",
            if (fill) "채우기(FILL)" else "맞추기(FIT)", frameW, frameH, rotation, vw.toInt(), vh.toInt(), x0, x1, y0, y1,
        )
    }

    // ---------------- 화면 ----------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(match, match))
        ghost = GhostView(this)
        root.addView(ghost, FrameLayout.LayoutParams(match, match))
        TARGETS.forEach { t ->
            val v = ViewOverlay(this).also { it.setTarget(t) }
            boxes += v
            root.addView(v, FrameLayout.LayoutParams(match, match))
        }

        info = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.argb(170, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(12), dp(40), dp(12), dp(8))
            text = "카메라를 켜는 중…"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(170, 0, 0, 0)); setPadding(dp(4), 0, dp(4), dp(4))
            addView(small("FILL/FIT") { fill = !fill; applyMapper() })
            addView(small("같은 값") { if (centerShown) boxes[CENTER].setTarget(TARGETS[CENTER].copy()) })
            addView(small("숨김/보임") {
                centerShown = !centerShown
                boxes[CENTER].setTarget(if (centerShown) TARGETS[CENTER] else null)
            })
            addView(small("흔들기") {
                if (!centerShown) return@small
                val c = TARGETS[CENTER].rect
                val dx = (Math.random().toFloat() - 0.5f) * 0.04f
                val dy = (Math.random().toFloat() - 0.5f) * 0.03f
                boxes[CENTER].setTarget(HighlightTarget(TARGETS[CENTER].elementId,
                    NormRect(c.left + dx, c.top + dy, c.right + dx, c.bottom + dy)))
            })
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(info); addView(row) }
        root.addView(top, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun small(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    /** 분석 프레임 사진을 같은 변환으로 반투명하게 겹쳐 그리는 View. 하늘색 테두리 = 분석 프레임 전체. */
    private class GhostView(context: Context) : View(context) {
        var bitmap: Bitmap? = null
        var mapper: CoordinateMapper? = null
        private val photo = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 110 }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.CYAN; strokeWidth = 4f
        }
        private val dst = RectF()

        override fun onDraw(canvas: Canvas) {
            val m = mapper ?: return
            val r = m.map(FULL, width.toFloat(), height.toFloat())
            dst.set(r.left, r.top, r.right, r.bottom)
            bitmap?.let { canvas.drawBitmap(it, null, dst, photo) }
            canvas.drawRect(dst, edge)
        }
    }

    private companion object {
        const val REQ = 7
        const val CENTER = 4
        val FULL = NormRect(0f, 0f, 1f, 1f)
        val TARGETS = listOf(
            HighlightTarget("tl", NormRect(0.05f, 0.05f, 0.20f, 0.15f)),
            HighlightTarget("tr", NormRect(0.80f, 0.05f, 0.95f, 0.15f)),
            HighlightTarget("bl", NormRect(0.05f, 0.85f, 0.20f, 0.95f)),
            HighlightTarget("br", NormRect(0.80f, 0.85f, 0.95f, 0.95f)),
            HighlightTarget("center", NormRect(0.40f, 0.45f, 0.60f, 0.55f)),
        )
    }
}
