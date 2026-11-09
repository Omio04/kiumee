package kr.kiosklens.vision.fake

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.port.FrameSink
import kr.kiosklens.core.port.SnapshotSource

/**
 * 카메라 없이 미리 만든 화면을 차례로 재생하는 가짜 (mock 플레이버 · 테스트용).
 * [show]로 화면을 하나 보여 주면 새 화면일 때 screenChanges 와 snapshots 를 낸다.
 * 프레임은 받는 즉시 close() 한다.
 */
class FakeScreenAnalyzer(private val screens: List<ScreenSnapshot> = emptyList()) : FrameSink, SnapshotSource {
    private val snapFlow = MutableSharedFlow<ScreenSnapshot>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val changeFlow = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val snapshots: Flow<ScreenSnapshot> = snapFlow
    override val screenChanges: Flow<String> = changeFlow

    private var current: String? = null
    var index = -1
        private set

    override fun submit(frame: CameraFrame) { runCatching { frame.close() } }

    fun show(snapshot: ScreenSnapshot) {
        if (snapshot.screenId != current) { current = snapshot.screenId; changeFlow.tryEmit(snapshot.screenId) }
        snapFlow.tryEmit(snapshot.copy(stableFrames = maxOf(2, snapshot.stableFrames)))
    }

    fun show(i: Int) { index = i.coerceIn(0, screens.lastIndex); show(screens[index]) }

    /** 다음 화면으로 (끝이면 마지막 화면 유지) */
    fun next() = show(index + 1)
    fun hasScreens() = screens.isNotEmpty()
}
