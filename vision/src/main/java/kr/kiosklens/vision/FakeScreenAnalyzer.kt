package kr.kiosklens.vision

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kr.kiosklens.core.model.CameraFrame

/** Each submitted frame advances one fixture; images are never interpreted. */
class FakeScreenAnalyzer(
    private val fixtures: List<ScreenSnapshot> = emptyList(),
) : FrameSink, SnapshotSource, AutoCloseable {
    override val snapshots = SnapshotStream()
    private val changes = MutableSharedFlow<String>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val screenChanges = changes.asSharedFlow()
    private var index = 0
    private var closed = false

    @Synchronized
    fun show(snapshot: ScreenSnapshot) {
        if (closed || snapshot.stableFrames < 2) return
        SnapshotJson.validate(snapshot)
        val previousId = snapshots.value?.screenId
        snapshots.publish(snapshot)
        if (previousId != null && previousId != snapshot.screenId) changes.tryEmit(snapshot.screenId)
    }

    @Synchronized
    override fun submit(frame: CameraFrame) {
        try {
            if (!closed && fixtures.isNotEmpty()) {
                val next = fixtures[index]
                index = (index + 1) % fixtures.size
                show(next.copy(frameId = frame.frameId, timestampMs = frame.timestampMs))
            }
        } catch (_: Exception) {
            // Port contract: unsupported/malformed input must never escape to CameraX.
        } finally {
            runCatching { frame.close() }
        }
    }

    @Synchronized
    override fun close() { closed = true }
}
