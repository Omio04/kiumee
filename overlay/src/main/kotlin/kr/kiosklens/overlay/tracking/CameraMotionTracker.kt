package kr.kiosklens.overlay.tracking

import java.nio.ByteBuffer
import kr.kiosklens.core.model.FrameMotion

/**
 * 카메라 프레임마다 "직전 프레임보다 화면이 얼마나 움직였나"를 [FrameMotion]으로 만든다 (흔들림 따라가기).
 *
 * OCR은 1초에 몇 번만 돌아서, 그 사이 휴대폰이 흔들리면 노란 테두리가 버튼에서 벗어난다.
 * 이 값을 `ViewOverlay.onFrameMotion()`(MotionSink)에 넣으면 다음 OCR 결과가 올 때까지 테두리가 흔들림만큼 따라간다.
 *
 * 쓰는 법 (분석 스레드, 프레임마다 · 한 스레드에서만):
 * ```
 * val y = imageProxy.planes[0]
 * tracker.onLumaFrame(frameId, y.buffer, y.rowStride, y.pixelStride, imageProxy.width, imageProxy.height, rotationDegrees)
 *     ?.let(overlay::onFrameMotion)
 * ```
 * ImageProxy를 다른 곳(FrameSink)에 넘기기 **전에** 부를 것 (넘긴 뒤에는 닫혔을 수 있다).
 * 한 번에 걸리는 시간: 1280×960 프레임 기준 PC에서 약 0.5ms (휴대폰은 몇 배 느릴 수 있음).
 */
class CameraMotionTracker(private val estimator: GridMotionEstimator = GridMotionEstimator()) {
    private var cols = 0
    private var rows = 0
    private var prev: FloatArray? = null
    private var spare: FloatArray? = null

    /** 첫 프레임이거나 크기 · 방향이 바뀐 직후면 null (비교할 직전 프레임이 없음). */
    fun onLumaFrame(
        frameId: Long, y: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int, rotationDegrees: Int,
    ): FrameMotion? {
        val rot = ((rotationDegrees % 360) + 360) % 360
        val uprightW = if (rot == 90 || rot == 270) height else width
        val uprightH = if (rot == 90 || rot == 270) width else height
        val c = if (uprightW <= uprightH) SHORT else LONG
        val r = if (uprightW <= uprightH) LONG else SHORT
        if (c != cols || r != rows) { cols = c; rows = r; prev = null; spare = null }

        val target = spare ?: FloatArray(cols * rows)
        val grid = LumaGrid.sample(y, rowStride, pixelStride, width, height, rotationDegrees, cols, rows, target)
        val before = prev
        prev = grid
        spare = before
        if (before == null) return null
        val m = estimator.estimate(before, grid, cols, rows)
        return FrameMotion(frameId = frameId, dx = m.dx, dy = m.dy, confidence = m.confidence)
    }

    /** 카메라를 다시 켰을 때 등. 다음 프레임부터 새로 비교한다. */
    fun reset() { prev = null; spare = null }

    private companion object {
        const val SHORT = 96   // 4로 나뉘어야 한다 (피라미드 두 단계)
        const val LONG = 128
    }
}
