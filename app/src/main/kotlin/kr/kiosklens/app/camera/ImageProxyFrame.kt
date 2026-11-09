package kr.kiosklens.app.camera

import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean
import kr.kiosklens.core.model.CameraFrame

/** CameraX 프레임을 계약의 [CameraFrame]으로 감싼다. close()는 여러 번 불러도 한 번만 반환한다. */
class ImageProxyFrame(
    private val proxy: ImageProxy,
    override val frameId: Long,
    override val timestampMs: Long,
) : CameraFrame {
    private val closed = AtomicBoolean(false)
    override val rotationDegrees: Int = proxy.imageInfo.rotationDegrees
    override val width: Int = proxy.width
    override val height: Int = proxy.height
    override val image: Any = proxy
    override fun close() { if (closed.compareAndSet(false, true)) proxy.close() }
}
