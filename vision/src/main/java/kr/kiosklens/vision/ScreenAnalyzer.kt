package kr.kiosklens.vision

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.port.Clock
import kr.kiosklens.core.port.Logger
import kr.kiosklens.vision.ocr.MlKitOcrEngine
import kr.kiosklens.vision.ocr.OcrEngine
import kr.kiosklens.vision.pipeline.imageSignature

/** Android composition boundary. Tests inject a clock, logger and engine instead. */
fun createVisionScreenAnalyzer(
    clock: Clock = Clock { SystemClock.elapsedRealtime() },
    logger: Logger = AndroidVisionLogger,
): VisionScreenAnalyzer = VisionScreenAnalyzer(
    engine = MlKitOcrEngine(), clock = clock, logger = logger, signature = ::imageSignature,
)

object AndroidVisionLogger : Logger {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun w(tag: String, message: String, error: Throwable?) { Log.w(tag, message) }
    override fun e(tag: String, message: String, error: Throwable?) { Log.e(tag, message) }
}

/** Old demo entry point; the team app can inject VisionScreenAnalyzer through the core ports. */
class ScreenAnalyzer(
    private val analyzer: VisionScreenAnalyzer = createVisionScreenAnalyzer(),
    private val epochClock: Clock = Clock(System::currentTimeMillis),
) : FrameSink, SnapshotSource, AutoCloseable {
    private val sequence = AtomicLong()
    override val snapshots get() = analyzer.snapshots
    override val screenChanges get() = analyzer.screenChanges
    val status get() = analyzer.status

    override fun submit(frame: CameraFrame) = analyzer.submit(frame)

    fun submit(image: ImageProxy) {
        analyzer.submit(object : CameraFrame {
            override val frameId = sequence.incrementAndGet()
            override val timestampMs = epochClock.nowMs()
            override val rotationDegrees = image.imageInfo.rotationDegrees
            override val width = image.width
            override val height = image.height
            override val image: Any = image
            override fun close() = image.close()
        })
    }

    override fun close() = analyzer.close()
    suspend fun awaitClosed() = analyzer.awaitClosed()
}

/** Two independent OCR observations; a single photo does not fabricate stability metadata. */
class PhotoAnalyzer(
    private val engine: OcrEngine = MlKitOcrEngine(),
    private val clock: Clock = Clock(System::currentTimeMillis),
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var active = false

    suspend fun analyze(bitmap: Bitmap): ScreenSnapshot {
        synchronized(lock) {
            check(!closed && !active)
            active = true
        }
        try {
            val tracker = ScreenTracker(minimumElements = 1)
            repeat(2) { index ->
                val frame = object : CameraFrame {
                    override val frameId = index.toLong() + 1
                    override val timestampMs = clock.nowMs()
                    override val rotationDegrees = 0
                    override val width = bitmap.width
                    override val height = bitmap.height
                    override val image: Any = bitmap
                    override fun close() {} // Bitmap belongs to the photo preview.
                }
                val lines = Elements.filter(engine.read(frame))
                currentCoroutineContext().ensureActive()
                val update = tracker.update(lines, frame.frameId, frame.timestampMs)
                if (update != null) return update.snapshot.copy(screenId = "photo_${java.util.UUID.randomUUID()}")
            }
            error("No stable readable elements")
        } finally {
            synchronized(lock) {
                active = false
                if (closed) engine.close()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (!active) engine.close()
        }
    }
}
