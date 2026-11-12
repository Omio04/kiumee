package kr.kiosklens.overlay

import kr.kiosklens.overlay.style.PulseCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 노란 테두리가 처음 나타날 때 그려지며 짚는 정도 (P1 2026-11-12) */
class PulseTraceTest {
    @Test fun `처음 나타나면 짧게 그려지고 다 그린 뒤에는 그대로`() {
        assertEquals(0f, PulseCurve.trace(0), 0f)
        assertEquals(1f, PulseCurve.trace(PulseCurve.TRACE_MS), 0f)
        assertEquals(1f, PulseCurve.trace(60_000), 0f)
        assertEquals("시계가 거꾸로 가도 안전", 0f, PulseCurve.trace(-50), 0f)
        var last = 0f
        for (t in 0L..PulseCurve.TRACE_MS step 10) { val v = PulseCurve.trace(t); assertTrue(v >= last); last = v }
        assertTrue("반 시간에 이미 80% 넘게 (짚어서 멈추는 느낌)", PulseCurve.trace(PulseCurve.TRACE_MS / 2) > 0.8f)
        assertTrue("어르신이 기다리지 않게 0.5초 안에", PulseCurve.TRACE_MS <= 500)
    }
}
