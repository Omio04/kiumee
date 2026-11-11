package kr.kiosklens.overlay

import kr.kiosklens.overlay.tracking.TrackingHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 추적 실패 1초 → 흐리게 (TrackingHealth) · 그리기 속도 재기 (RenderStats). 시간은 직접 넣는다. */
class TrackingHealthTest {
    /** 33ms마다 신뢰도 [conf]인 움직임을 [fromMs]부터 [toMs]까지 넣는다 */
    private fun TrackingHealth.feed(fromMs: Long, toMs: Long, conf: Float) {
        var t = fromMs
        while (t <= toMs) { onMotion(conf, t); t += 33 }
    }

    @Test fun `움직임 정보가 없으면 흐리게 하지 않는다 (연결 전 동작 그대로)`() {
        val h = TrackingHealth()
        assertFalse(h.isLost(0)); assertFalse(h.isLost(10_000))
    }

    @Test fun `믿을 만한 움직임이 이어지면 정상`() {
        val h = TrackingHealth()
        h.feed(0, 3_000, 0.9f)
        assertFalse(h.isLost(3_000))
    }

    @Test fun `믿을 수 없는 움직임이 1초 넘게 이어지면 실패`() {
        val h = TrackingHealth()
        h.feed(0, 500, 0.9f)
        h.feed(533, 1_400, 0.1f)
        assertFalse("아직 1초 안 됨", h.isLost(1_400))
        h.feed(1_433, 1_600, 0.1f)
        assertTrue(h.isLost(1_600))
    }

    @Test fun `믿을 만한 움직임이 다시 오면 바로 정상`() {
        val h = TrackingHealth()
        h.feed(0, 2_000, 0.1f)
        assertTrue(h.isLost(2_000))
        h.onMotion(0.8f, 2_033)
        assertFalse(h.isLost(2_033))
    }

    @Test fun `새 위치(OCR)가 오면 바로 정상`() {
        val h = TrackingHealth()
        h.feed(0, 2_000, 0.1f)
        h.onTargetUpdated(2_010)
        assertFalse(h.isLost(2_020))
    }

    @Test fun `움직임 정보가 끊기면 판단하지 않는다`() {
        val h = TrackingHealth()
        h.feed(0, 1_500, 0.1f)
        assertTrue(h.isLost(1_500))
        assertFalse("0.5초 넘게 정보가 없음 (카메라 멈춤)", h.isLost(2_100))
    }

    @Test fun `실패 알림은 바뀐 순간 한 번만`() {
        val h = TrackingHealth()
        h.feed(0, 1_100, 0.1f)
        assertTrue(h.takeLostEvent(1_100))
        assertFalse(h.takeLostEvent(1_133))
        h.onMotion(0.9f, 1_166)                                           // 회복했다가
        h.feed(1_199, 2_300, 0.1f)                                       // 다시 실패
        assertTrue(h.takeLostEvent(2_300))
    }

    // ---------------------------------------------------------------- RenderStats

    @Test fun `60fps로 그리면 약 60 · 가장 느린 간격 17ms`() {
        val s = RenderStats()
        var t = 0L
        repeat(120) { s.onFrame(t); t += if (it % 3 == 2) 16 else 17 }
        val r = s.snapshot()
        assertEquals(60f, r.fps, 1.5f)
        assertEquals(17L, r.worstMs)
        assertEquals(true, r.meetsTarget)
    }

    @Test fun `20fps면 기준 미달`() {
        val s = RenderStats()
        for (i in 0..40) s.onFrame(i * 50L)
        assertEquals(20f, s.snapshot().fps, 0.5f)
        assertEquals(false, s.snapshot().meetsTarget)
    }

    @Test fun `한 번 크게 끊기면 가장 느린 간격에 보인다`() {
        val s = RenderStats()
        var t = 0L
        repeat(30) { s.onFrame(t); t += 16 }
        t += 184                                                          // 200ms 끊김
        repeat(10) { s.onFrame(t); t += 16 }
        assertEquals("250ms보다 짧은 끊김은 센다", 200L, s.snapshot().worstMs)
    }

    @Test fun `쉬었다가 다시 그리면 쉰 시간은 세지 않는다`() {
        val s = RenderStats()
        repeat(30) { s.onFrame(it * 16L) }
        s.pause()
        assertEquals("잴 만큼 모이지 않음", null, s.snapshot().meetsTarget)
        repeat(30) { s.onFrame(5_000 + it * 16L) }
        assertEquals(16L, s.snapshot().worstMs)
    }
}
