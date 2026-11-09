package kr.kiosklens.vision.pipeline

import androidx.camera.core.ImageProxy
import kr.kiosklens.core.model.CameraFrame

/**
 * 카메라 프레임의 밝기(Y) 평면을 64×36 흑백으로 줄인다. [FrameSampler]가 "앞 프레임과 거의 같은가"를 볼 때 쓴다.
 * 줄이기만 하므로 매우 가볍다(한 프레임에 수백 번 읽기). 이미지는 어디에도 저장하거나 보내지 않는다.
 *
 * 사용: `VisionScreenAnalyzer(..., thumbnailer = ImageProxyThumbnail::of)`
 */
object ImageProxyThumbnail {
    const val W = 64
    const val H = 36

    fun of(frame: CameraFrame): ByteArray? {
        val img = frame.image as? ImageProxy ?: return null
        val plane = img.planes.firstOrNull() ?: return null
        val w = img.width; val h = img.height
        if (w <= 0 || h <= 0) return null
        val buf = plane.buffer
        val stride = plane.rowStride
        val out = ByteArray(W * H)
        for (y in 0 until H) {
            val sy = (y * h / H).coerceAtMost(h - 1)
            for (x in 0 until W) {
                val sx = (x * w / W).coerceAtMost(w - 1)
                val pos = sy * stride + sx
                out[y * W + x] = if (pos < buf.limit()) buf.get(pos) else 0
            }
        }
        return out
    }
}
