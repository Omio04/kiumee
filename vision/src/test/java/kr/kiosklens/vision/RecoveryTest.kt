package kr.kiosklens.vision

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.port.Clock
import kr.kiosklens.core.port.Logger
import kr.kiosklens.vision.ocr.OcrEngine
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryTest {
    @Test fun recognizerFailureRequiresFreshConfirmationAndPreservesWorker() = runTest {
        var reads = 0
        val engine = object : OcrEngine {
            override suspend fun read(frame: CameraFrame): List<OcrLine> {
                reads++
                if (reads == 3) error("Recognizer failure")
                return menuLines
            }
        }
        val analyzer = VisionScreenAnalyzer(engine, Clock { testScheduler.currentTime },
            dispatcher = StandardTestDispatcher(testScheduler))
        val frames = (1L..5L).map { TestFrame(it) }
        try {
            frames.forEachIndexed { index, frame ->
                analyzer.submit(frame)
                runCurrent()
                if (index == 2) assertEquals(ScanStatus.REPOSITION, analyzer.status.value)
                if (index == 3) assertEquals(2L, analyzer.snapshots.value!!.frameId)
                advanceTimeBy(334)
            }
            assertEquals(5L, analyzer.snapshots.value!!.frameId)
            assertEquals(2, analyzer.snapshots.value!!.stableFrames)
            assertTrue(frames.all { it.releases == 1 })
        } finally { analyzer.close(); runCurrent() }
    }

    @Test fun throwingLoggerCannotKillWorkerOrLeakFrames() = runTest {
        val engine = object : OcrEngine {
            override suspend fun read(frame: CameraFrame) = menuLines
        }
        val logger = object : Logger {
            override fun d(tag: String, message: String) { error("logger failure") }
            override fun w(tag: String, message: String, error: Throwable?) { error("logger failure") }
            override fun e(tag: String, message: String, error: Throwable?) { error("logger failure") }
        }
        val analyzer = VisionScreenAnalyzer(engine, Clock { testScheduler.currentTime }, logger,
            dispatcher = StandardTestDispatcher(testScheduler))
        try {
            repeat(3) { index ->
                val frame = TestFrame(index.toLong())
                analyzer.submit(frame)
                runCurrent()
                assertEquals(1, frame.releases)
                advanceTimeBy(334)
            }
            assertEquals(2L, analyzer.snapshots.value!!.frameId)
        } finally { analyzer.close(); runCurrent() }
    }

    @Test fun newestWaitingFrameIsProcessedAfterCurrentReadCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reads = mutableListOf<Long>()
        val engine = object : OcrEngine {
            override suspend fun read(frame: CameraFrame): List<OcrLine> {
                reads += frame.frameId
                if (frame.frameId == 1L) gate.await()
                return menuLines
            }
        }
        val analyzer = VisionScreenAnalyzer(engine, Clock { testScheduler.currentTime },
            dispatcher = StandardTestDispatcher(testScheduler))
        val first = TestFrame(1)
        val old = TestFrame(2)
        val newest = TestFrame(3)
        try {
            analyzer.submit(first)
            runCurrent()
            advanceTimeBy(334)
            analyzer.submit(old)
            analyzer.submit(newest)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(1L, 3L), reads)
            assertEquals(3L, analyzer.snapshots.value!!.frameId)
            assertTrue(listOf(first, old, newest).all { it.releases == 1 })
        } finally { analyzer.close(); runCurrent() }
    }
}
