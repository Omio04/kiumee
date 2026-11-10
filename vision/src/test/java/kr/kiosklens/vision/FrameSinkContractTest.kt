package kr.kiosklens.vision

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.port.Clock
import kr.kiosklens.vision.ocr.OcrEngine
import org.junit.Assert.*
import org.junit.Test

internal val menuLines = listOf("아메리카노", "라떼", "담기").mapIndexed { index, text ->
    OcrLine(text, NormRect(0.1f + index * 0.2f, 0.2f, 0.2f + index * 0.2f, 0.3f), 0.95f)
}

internal class TestFrame(
    override val frameId: Long = 1L,
    override val timestampMs: Long = 1234L,
    private val payload: Any = menuLines,
    private val broken: Boolean = false,
) : CameraFrame {
    var releases = 0
    override val width = 1280
    override val height = 720
    override val rotationDegrees = 0
    override val image: Any get() = if (broken) error("Bad image") else payload
    override fun close() { releases++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
abstract class FrameSinkContractTest {
    protected data class Harness(
        val sink: FrameSink, val stream: SnapshotStream, val close: () -> Unit,
    )
    protected abstract fun create(scheduler: TestCoroutineScheduler): Harness

    @Test fun neverPublishesUnstableSnapshotsAndPreservesFrameMetadata() = runTest {
        val h = create(testScheduler)
        val seen = mutableListOf<ScreenSnapshot>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.stream.collect { seen += it }
        }
        try {
            h.sink.submit(TestFrame(frameId = 1))
            runCurrent()
            assertTrue(seen.isEmpty())
            advanceTimeBy(334)
            h.sink.submit(TestFrame(frameId = 2, timestampMs = 5678))
            runCurrent()
            assertEquals(1, seen.size)
            assertTrue(seen.all { it.stableFrames >= 2 })
            assertEquals(2L, seen.last().frameId)
            assertEquals(5678L, seen.last().timestampMs)
        } finally {
            collector.cancel()
            h.close()
            runCurrent()
        }
    }

    @Test fun malformedAndSupersededFramesAreClosedExactlyOnce() = runTest {
        val h = create(testScheduler)
        val frames = (1L..30L).map { TestFrame(it, broken = it == 30L) }
        try {
            // No scheduler work is allowed to execute until all submits have returned.
            frames.forEach(h.sink::submit)
            runCurrent()
        } finally {
            h.close()
            runCurrent()
        }
        assertTrue(frames.all { it.releases == 1 })
    }

    @Test fun framesSubmittedAfterCloseAreReleased() = runTest {
        val h = create(testScheduler)
        h.close()
        h.close()
        val frame = TestFrame()
        h.sink.submit(frame)
        runCurrent()
        assertEquals(1, frame.releases)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RealFrameSinkContractTest : FrameSinkContractTest() {
    override fun create(scheduler: TestCoroutineScheduler): Harness {
        val engine = object : OcrEngine {
            @Suppress("UNCHECKED_CAST")
            override suspend fun read(frame: CameraFrame) = frame.image as List<OcrLine>
        }
        val analyzer = VisionScreenAnalyzer(engine, Clock { scheduler.currentTime },
            dispatcher = StandardTestDispatcher(scheduler))
        return Harness(analyzer, analyzer.snapshots, analyzer::close)
    }
}

class FakeFrameSinkContractTest : FrameSinkContractTest() {
    override fun create(scheduler: TestCoroutineScheduler): Harness {
        val fixtures = listOf(
            ScreenSnapshot("menu", 0, 0, emptyList(), 1),
            ScreenSnapshot("menu", 0, 0, emptyList(), 2),
        )
        val analyzer = FakeScreenAnalyzer(fixtures)
        return Harness(analyzer, analyzer.snapshots, analyzer::close)
    }
}
