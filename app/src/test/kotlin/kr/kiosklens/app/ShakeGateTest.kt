package kr.kiosklens.app

import kr.kiosklens.app.camera.ShakeGate
import kr.kiosklens.app.camera.StabilizationTuning
import kr.kiosklens.core.model.FrameMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeGateTest {
    private fun m(dx: Float, dy: Float = 0f, c: Float = 0.9f) = FrameMotion(1, dx, dy, confidence = c)

    @Test fun continuousMotionFromStartupStillAllowsPeriodicRecognition() {
        val gate = ShakeGate()
        assertFalse(gate.allow(m(.02f), 0))
        assertFalse(gate.allow(m(.02f), 899))
        assertTrue(gate.allow(m(.02f), 900))
        assertFalse(gate.allow(m(.02f), 1000))
        assertTrue(gate.allow(m(.02f), 1800))
    }

    @Test fun `가만히 있으면 통과한다`() {
        val g = ShakeGate()
        assertTrue(g.allow(m(0.001f), 0)); assertEquals(0, g.skipped)
    }

    @Test fun `크게 흔들리면 건너뛴다`() {
        val g = ShakeGate()
        assertTrue(g.allow(m(0f), 0))
        assertFalse(g.allow(m(0.02f), 100)); assertFalse(g.allow(m(0f, -0.03f), 200)); assertEquals(2, g.skipped)
    }

    @Test fun `대각선 움직임도 합쳐서 본다`() {
        val g = ShakeGate()
        g.allow(null, 0)
        assertFalse(g.allow(m(0.005f, 0.005f), 100))      // 0.0071 > 0.006
    }

    @Test fun `계속 흔들려도 일정 시간이 지나면 한 장은 통과시킨다`() {
        val g = ShakeGate(maxSkipMs = 900)
        assertTrue(g.allow(m(0f), 0))
        for (t in listOf(100L, 300L, 600L, 899L)) assertFalse(g.allow(m(0.05f), t))
        assertTrue(g.allow(m(0.05f), 900))                // 굶지 않게
        assertFalse(g.allow(m(0.05f), 1000))              // 통과 뒤에는 다시 건너뜀
    }

    @Test fun `움직임을 모르거나 믿기 어려우면 통과한다`() {
        val g = ShakeGate()
        assertTrue(g.allow(null, 0))
        assertTrue(g.allow(m(0.5f, c = 0.1f), 100))       // 신뢰도 낮음 = 글자 없는 벽 등
    }

    @Test fun `fps 범위는 30에 닿는 것 중 노출이 짧은 쪽`() {
        assertEquals(30 to 30, StabilizationTuning.pickFpsRange(listOf(15 to 30, 30 to 30, 7 to 30, 24 to 60)))
        assertEquals(24 to 60, StabilizationTuning.pickFpsRange(listOf(15 to 30, 24 to 60)))
        assertEquals(15 to 24, StabilizationTuning.pickFpsRange(listOf(10 to 15, 15 to 24)))   // 30에 못 닿으면 가장 높은 것
        assertNull(StabilizationTuning.pickFpsRange(emptyList()))
        assertNull(StabilizationTuning.pickFpsRange(listOf(30 to 15)))                         // 잘못된 값만 있으면 건드리지 않음
    }

    @Test fun `OIS는 기기가 켜기를 지원할 때만`() {
        assertTrue(StabilizationTuning.canUseOis(intArrayOf(0, 1), 1))
        assertFalse(StabilizationTuning.canUseOis(intArrayOf(0), 1))
        assertFalse(StabilizationTuning.canUseOis(null, 1))
    }
}
