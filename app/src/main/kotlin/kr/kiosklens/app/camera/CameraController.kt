package kr.kiosklens.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kr.kiosklens.core.model.FrameMotion
import kr.kiosklens.core.port.FrameSink
import kr.kiosklens.core.port.Logger
import kr.kiosklens.overlay.tracking.CameraMotionTracker

/**
 * 후면 카메라 미리보기 + 분석 프레임을 [FrameSink]로 보낸다.
 * - 미리보기와 분석은 같은 종횡비(4:3)를 써서 하이라이트 좌표가 어긋나지 않게 한다.
 * - 분석 해상도는 [ANALYSIS_SIZE](1280×960)를 목표로 한다. 기본값 640×480에서는 키오스크 메뉴 이름처럼
 *   작은 글자를 거의 못 읽었다 (2026-11-11 웹캠 확인). 지원하지 않으면 가장 가까운 낮은 해상도로 내려간다.
 * - 분석은 "최신 한 장만 유지" 전략: 처리가 느리면 CameraX 가 알아서 건너뛴다.
 * - 분석 프레임 크기(회전 보정 후)는 [onAnalysisSize] 로 알려 준다(오버레이 좌표 변환용).
 * - 프레임마다 화면 전체의 움직임을 [onMotion] 으로 알려 준다(흔들림 따라가기, P5 `CameraMotionTracker`).
 *   OCR은 같은 화면이면 2초에 한 번만 다시 읽어서, 그 사이 흔들리면 노란 테두리가 버튼에서 벗어나기 때문.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val sink: FrameSink,
    private val logger: Logger,
    /** 프레임마다의 카메라 움직임. 분석 스레드에서 불린다 (`ViewOverlay.onFrameMotion`은 어느 스레드든 안전). */
    private val onMotion: (FrameMotion) -> Unit = {},
    private val onAnalysisSize: (width: Int, height: Int) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val ids = AtomicLong()
    private val motion = CameraMotionTracker()                             // 분석 스레드에서만 쓴다
    private var motionFailed = false
    private val shakeGate = ShakeGate()                                    // 분석 스레드에서만 쓴다
    private var provider: ProcessCameraProvider? = null
    @Volatile private var reportedSize = ""

    fun start(owner: LifecycleOwner, view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get(); provider = p
                val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysisResolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        val rot = proxy.imageInfo.rotationDegrees
                        val w = if (rot == 90 || rot == 270) proxy.height else proxy.width
                        val h = if (rot == 90 || rot == 270) proxy.width else proxy.height
                        val key = "${w}x$h"
                        if (key != reportedSize) {
                            reportedSize = key; onAnalysisSize(w, h)
                            logger.d("CameraController", "analysis size $key (rotation $rot)")
                        }
                        val id = ids.incrementAndGet()
                        val moved = trackMotion(proxy, id, rot)                       // sink에 넘기기 전에 (넘긴 뒤에는 닫혔을 수 있음)
                        val now = System.currentTimeMillis()
                        if (shakeGate.allow(moved, now)) {
                            sink.submit(ImageProxyFrame(proxy, id, now))                  // 닫는 책임은 sink
                        } else {
                            proxy.close()                                                  // 흔들려 번진 프레임은 읽지 않는다 (ShakeGate)
                            if (shakeGate.skipped % 30 == 1) logger.d("KL_PERF", "shake skipped=${shakeGate.skipped}")
                        }
                    } catch (t: Throwable) {
                        proxy.close()
                    }
                }
                p.unbindAll()
                val camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                runCatching { tuneExposure(camera) }.onFailure { logger.w("CameraController", "exposure tuning skipped: $it") }
                runCatching { tuneStabilization(camera) }.onFailure { logger.w("CameraController", "stabilization tuning skipped: $it") }
            } catch (t: Throwable) {
                logger.e("CameraController", "camera start failed", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 스스로 빛나는 키오스크 화면이 하얗게 날아가지 않게 ([ExposureTuning]).
     * - 노출을 약 0.5EV 낮춘다
     * - 측광 영역을 받는 기기면 노출을 가운데(조준 모서리 안 = 키오스크 자리) 기준으로 맞추고 계속 유지한다(자동 취소 끔)
     * - 초점은 건드리지 않는다: 영역으로 초점을 잡으면 한 번 맞추고 멈춰서, 거리가 바뀌어도 다시 맞추지 않는다
     * 못 받는 기기는 그냥 넘어간다 (에뮬레이터 웹캠은 노출 보정만 받고 측광 영역은 0개).
     */
    private fun tuneExposure(camera: Camera) {
        val exposure = camera.cameraInfo.exposureState
        if (exposure.isExposureCompensationSupported) {
            val range = exposure.exposureCompensationRange
            val index = ExposureTuning.index(ExposureTuning.TARGET_EV, exposure.exposureCompensationStep.toFloat(), range.lower, range.upper)
            camera.cameraControl.setExposureCompensationIndex(index)
            logger.d("CameraController", "exposure index $index (step ${exposure.exposureCompensationStep}, range $range)")
        }
        val center = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, ExposureTuning.METERING_SIZE)
        val metering = FocusMeteringAction.Builder(center, FocusMeteringAction.FLAG_AE).disableAutoCancel().build()
        val supported = camera.cameraInfo.isFocusMeteringSupported(metering)
        if (supported) camera.cameraControl.startFocusAndMetering(metering)
        logger.d("CameraController", "center metering ${if (supported) "on" else "unsupported"}")
    }

    /**
     * 흔들림에 강하게 ([StabilizationTuning]): 광학식 손떨림 보정이 있으면 켜고, 노출 시간이 길어지지 않게 fps를 30 근처로 고정.
     * 기기가 안 받는 값은 건드리지 않는다.
     */
    private fun tuneStabilization(camera: Camera) {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val options = CaptureRequestOptions.Builder()
        val ois = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val oisOn = StabilizationTuning.canUseOis(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        if (oisOn) options.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        val ranges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val fps = StabilizationTuning.pickFpsRange(ranges?.map { it.lower to it.upper }.orEmpty())
        if (fps != null) options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps.first, fps.second))
        Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(options.build())
        logger.d("CameraController", "stabilization ois=$oisOn fps=${fps ?: "default"}")
    }

    fun stop() {
        provider?.unbindAll()
        executor.shutdown()
    }

    /** 밝기(Y) 평면만 읽어 직전 프레임과 비교한다. 실패해도 인식(sink)은 그대로 이어지게 삼킨다. */
    private fun trackMotion(proxy: ImageProxy, id: Long, rot: Int): FrameMotion? {
        return try {
            val y = proxy.planes[0]
            motion.onLumaFrame(id, y.buffer, y.rowStride, y.pixelStride, proxy.width, proxy.height, rot)?.also(onMotion)
        } catch (t: Throwable) {
            if (!motionFailed) { motionFailed = true; logger.w("CameraController", "motion tracking failed", t) }   // 한 번만 남긴다
            null
        }
    }

    companion object {
        /** 분석 프레임 목표 크기 (센서 방향 기준 가로×세로, 4:3). */
        val ANALYSIS_SIZE = Size(1280, 960)
    }
}
