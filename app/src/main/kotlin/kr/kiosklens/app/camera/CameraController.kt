package kr.kiosklens.app.camera

import android.content.Context
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kr.kiosklens.core.port.FrameSink
import kr.kiosklens.core.port.Logger

/**
 * 후면 카메라 미리보기 + 분석 프레임을 [FrameSink]로 보낸다.
 * - 미리보기와 분석은 같은 종횡비(4:3)를 써서 하이라이트 좌표가 어긋나지 않게 한다.
 * - 분석은 "최신 한 장만 유지" 전략: 처리가 느리면 CameraX 가 알아서 건너뛴다.
 * - 분석 프레임 크기(회전 보정 후)는 [onAnalysisSize] 로 알려 준다(오버레이 좌표 변환용).
 */
class CameraController(
    private val context: Context,
    private val sink: FrameSink,
    private val logger: Logger,
    private val onAnalysisSize: (width: Int, height: Int) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val ids = AtomicLong()
    private var provider: ProcessCameraProvider? = null
    @Volatile private var reportedSize = ""

    fun start(owner: LifecycleOwner, view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get(); provider = p
                val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        val rot = proxy.imageInfo.rotationDegrees
                        val w = if (rot == 90 || rot == 270) proxy.height else proxy.width
                        val h = if (rot == 90 || rot == 270) proxy.width else proxy.height
                        val key = "${w}x$h"
                        if (key != reportedSize) { reportedSize = key; onAnalysisSize(w, h) }
                        sink.submit(ImageProxyFrame(proxy, ids.incrementAndGet(), System.currentTimeMillis()))   // 닫는 책임은 sink
                    } catch (t: Throwable) {
                        proxy.close()
                    }
                }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (t: Throwable) {
                logger.e("CameraController", "camera start failed", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        executor.shutdown()
    }
}
