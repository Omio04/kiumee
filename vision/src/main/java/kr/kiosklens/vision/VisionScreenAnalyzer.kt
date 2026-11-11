package kr.kiosklens.vision

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.port.Clock
import kr.kiosklens.core.port.Logger
import kr.kiosklens.vision.ocr.OcrEngine

enum class ScanStatus { SCANNING, READY, REPOSITION }

object SilentVisionLogger : Logger {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String, error: Throwable?) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
}

/** One active OCR and at most one pending frame. submit never executes OCR or reads pixels. */
class VisionScreenAnalyzer(
    private val engine: OcrEngine,
    private val clock: Clock,
    private val logger: Logger = SilentVisionLogger,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val builder: ElementBuilder = ElementBuilder(),
    private val signature: (CameraFrame) -> IntArray? = { null },
) : FrameSink, SnapshotSource, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val tracker = ScreenTracker(builder = builder)
    private val sampler = FrameSampler()
    private val frames = Channel<CameraFrame>(1, BufferOverflow.DROP_OLDEST, ::release)
    override val snapshots = SnapshotStream()
    private val changes = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val screenChanges = changes.asSharedFlow()
    private val scan = MutableStateFlow(ScanStatus.SCANNING)
    val status = scan.asStateFlow()

    private val worker = scope.launch {
        for (frame in frames) {
            try {
                val start = clock.nowMs()
                if (!sampler.due(start)) continue
                val grid = signature(frame)
                if (!sampler.shouldAnalyze(start, tracker.needsConfirmation, grid, frame.rotationDegrees)) continue
                val lines = builder.filter(engine.read(frame))
                currentCoroutineContext().ensureActive()
                sampler.analyzed(grid, frame.rotationDegrees, start)
                val update = tracker.update(lines, frame.frameId, frame.timestampMs)
                if (update != null) {
                    snapshots.publish(update.snapshot)
                    if (update.changed) changes.tryEmit(update.snapshot.screenId)
                }
                scan.value = when {
                    lines.size < 3 -> ScanStatus.REPOSITION
                    update == null -> ScanStatus.SCANNING
                    else -> ScanStatus.READY
                }
                log { logger.d("KL_PERF", "ocr ms=${clock.nowMs() - start} lines=${lines.size} published=${update != null}") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                tracker.update(emptyList())
                scan.value = ScanStatus.REPOSITION
                log { logger.w("KL_PERF", "ocr failed") }
            } finally {
                release(frame)
            }
        }
    }.also { job ->
        job.invokeOnCompletion {
            frames.cancel()
            runCatching { engine.close() }
        }
    }

    override fun submit(frame: CameraFrame) {
        if (closed.get() || frames.trySend(frame).isFailure) release(frame)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        frames.cancel()
        scope.cancel()
    }

    /** Waits until the in-flight ML Kit task and all image releases have finished. */
    suspend fun awaitClosed() = worker.join()

    private fun release(frame: CameraFrame) { runCatching { frame.close() } }
    private inline fun log(block: () -> Unit) { runCatching(block) }
}
