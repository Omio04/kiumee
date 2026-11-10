package kr.kiosklens.vision

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Core-compatible non-null stream, plus a nullable current value for the standalone demo. */
class SnapshotStream internal constructor(
    private val events: MutableSharedFlow<ScreenSnapshot> = MutableSharedFlow(
        replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ),
) : SharedFlow<ScreenSnapshot> by events {
    @Volatile private var latest: ScreenSnapshot? = null
    val value: ScreenSnapshot? get() = latest

    internal fun publish(snapshot: ScreenSnapshot) {
        require(snapshot.stableFrames >= 2)
        latest = snapshot
        events.tryEmit(snapshot)
    }
}
