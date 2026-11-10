package kr.kiosklens.vision

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.port.Clock
import kr.kiosklens.vision.ocr.OcrEngine
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {
    @Test fun keepsOnlyNewestWaitingFrameAndDrainsInFlightBeforeClosingEngine() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Long>()
        var engineCloses = 0
        val engine = object : OcrEngine {
            override suspend fun read(frame: CameraFrame): List<OcrLine> {
                started += frame.frameId
                withContext(NonCancellable) { gate.await() }
                return menuLines
            }
            override fun close() { engineCloses++ }
        }
        val analyzer = VisionScreenAnalyzer(engine, Clock { testScheduler.currentTime },
            dispatcher = StandardTestDispatcher(testScheduler))
        val first = TestFrame(1)
        val old = TestFrame(2)
        val newest = TestFrame(3)
        analyzer.submit(first)
        runCurrent()
        analyzer.submit(old)
        analyzer.submit(newest)
        assertEquals(1, old.releases)
        assertEquals(0, first.releases)
        assertEquals(listOf(1L), started)
        analyzer.close()
        runCurrent()
        assertEquals(1, newest.releases)
        assertEquals(0, engineCloses)
        gate.complete(Unit)
        runCurrent()
        analyzer.awaitClosed()
        assertEquals(1, first.releases)
        assertEquals(1, engineCloses)
        assertNull(analyzer.snapshots.value)
    }

    @Test fun slowSubscriberDoesNotBlockAndEventuallyReceivesLatestSnapshot() = runTest {
        val fake = FakeScreenAnalyzer()
        val received = mutableListOf<Long>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.snapshots.collect {
                received += it.frameId
                delay(1_000)
            }
        }
        repeat(50) { fake.show(ScreenSnapshot("s1", it.toLong(), 0, emptyList(), 2)) }
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(49L, received.last())
        collector.cancel()
        fake.close()
    }

    @Test fun samplerForcesHeartbeatConfirmationAndRotationButHonorsRateLimit() {
        val sampler = FrameSampler()
        val grid = IntArray(64 * 36) { 20 }
        assertTrue(sampler.shouldAnalyze(0, false, grid, 0))
        sampler.analyzed(grid, 0, 0)
        assertFalse(sampler.shouldAnalyze(333, true, grid, 0))
        assertFalse(sampler.shouldAnalyze(334, false, grid, 0))
        assertTrue(sampler.shouldAnalyze(668, true, grid, 0))
        assertTrue(sampler.shouldAnalyze(1_002, false, grid, 90))
        assertTrue(sampler.shouldAnalyze(2_000, false, grid, 0))
    }

    @Test fun sparseOrFailedFrameRequiresTwoFreshObservationsBeforePublication() {
        val tracker = ScreenTracker()
        tracker.update(menuLines)
        assertEquals(2, tracker.update(menuLines)!!.snapshot.stableFrames)
        tracker.update(emptyList())
        assertNull(tracker.update(menuLines))
        assertEquals(2, tracker.update(menuLines)!!.snapshot.stableFrames)
    }

    @Test fun transientNewScreenDoesNotCountAsStableOldScreen() {
        val tracker = ScreenTracker()
        tracker.update(menuLines)
        tracker.update(menuLines)
        val other = menuLines.mapIndexed { i, line -> line.copy(text = "결제항목$i") }
        assertNull(tracker.update(other))
        assertNull(tracker.update(menuLines))
        assertEquals(2, tracker.update(menuLines)!!.snapshot.stableFrames)
    }

    @Test fun elementIdsAreDeterministicAndSurviveGridBoundaryJitter() {
        val first = ElementIds().assign("s1", menuLines)
        assertEquals(first.map { it.id }, ElementIds().assign("s99", menuLines).map { it.id })
        assertTrue(first.all { it.id.startsWith("e") && it.id.length <= 64 })
        val ids = ElementIds()
        val before = ids.assign("s1", menuLines)
        val shifted = menuLines.map { it.copy(rect = it.rect.copy(
            left = it.rect.left + 0.01f, right = it.rect.right + 0.01f)) }
        assertEquals(before.map { it.id }, ids.assign("s1", shifted).map { it.id })
        val duplicates = ElementIds().assign("s1", listOf(menuLines[0], menuLines[0]))
        assertEquals(2, duplicates.map { it.id }.toSet().size)
        assertTrue(duplicates[1].id.endsWith("_2"))
    }

    @Test fun filtersInvalidBoxesExpiryAndSensitiveSuffixBeforeTextTruncation() {
        val original = menuLines[0]
        val unsafe = listOf("유효기간 １２／２９", "01/2030", "12-29", "x".repeat(81) + " 1234-5678-9012-3456")
        val clean = Elements.filter(unsafe.map { original.copy(text = it) } +
            original.copy(rect = NormRect(Float.NaN, 0f, 1f, 1f)) +
            original.copy(rect = NormRect(0.5f, 0.5f, 0.1f, 0.1f)) +
            original.copy(text = "4,500원"))
        assertEquals(listOf("4,500원"), clean.map { it.text })
    }

    @Test fun elementAndTextLimitsAreEnforced() {
        val lines = (1..400).map { menuLines[0].copy(text = "긴글".repeat(50) + it) }
        val clean = Elements.filter(lines)
        assertTrue(clean.size <= 300)
        assertTrue(clean.all { it.text.length <= 80 })
    }
}
