package kr.kiosklens.overlay

import kr.kiosklens.overlay.tracking.TrackingHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 위치를 놓치면 숨기기 (TrackingHealth) · 그리기 속도 재기 (RenderStats). */
class TrackingHealthTest {
    @Test fun `믿을 만한 움직임이면 옮겨도 되고 놓치지 않는다`() {
        val h = TrackingHealth()
        repeat(90) { assertTrue(h.onMotion(0.9f, showing = true)) }
        assertFalse(h.isLost)
    }

    @Test fun `테두리가 없을 때 믿을 수 없는 움직임은 놓침으로 보지 않는다`() {
        val h = TrackingHealth()
        assertFalse(h.onMotion(0.1f, showing = false))
        assertFalse(h.isLost)
        assertTrue("나중에 테두리가 떠도 정상", h.onMotion(0.9f, showing = true))
    }

    @Test fun `테두리가 떠 있을 때 믿을 수 없는 움직임이 한 번 오면 바로 놓침`() {
        val h = TrackingHealth()
        assertTrue(h.onMotion(0.9f, showing = true))
        assertFalse(h.onMotion(0.2f, showing = true))
        assertTrue(h.isLost)
    }

    @Test fun `놓친 뒤에는 믿을 만한 움직임이 다시 와도 옮기지 않고 계속 놓침 (2026-11-13 시험 회귀)`() {
        // 크게 움직여 놓친 뒤 다시 가만히 있으면, 예전에는 믿을 만한 움직임이 돌아와 엉뚱한 자리에 또렷하게 남았다
        val h = TrackingHealth()
        h.onMotion(0.0f, showing = true)
        repeat(60) { assertFalse("놓친 사이의 움직임을 모르므로 옮기면 안 됨", h.onMotion(0.95f, showing = true)) }
        assertTrue(h.isLost)
    }

    @Test fun `새 위치가 오면 다시 믿고 옮긴다`() {
        val h = TrackingHealth()
        h.onMotion(0.0f, showing = true)
        h.onAnchor()
        assertFalse(h.isLost)
        assertTrue(h.onMotion(0.8f, showing = true))
    }

    @Test fun `놓침 알림은 놓칠 때마다 한 번씩`() {
        val h = TrackingHealth()
        assertFalse("놓치기 전에는 없음", h.takeLostEvent())
        h.onMotion(0.1f, showing = true)
        assertTrue(h.takeLostEvent())
        assertFalse(h.takeLostEvent())
        h.onAnchor()
        h.onMotion(0.1f, showing = true)
        assertTrue("다시 놓치면 또 한 번", h.takeLostEvent())
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
