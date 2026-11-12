package kr.kiosklens.app

import kr.kiosklens.app.ui.OrbMotion
import kr.kiosklens.app.ui.OrbState
import kr.kiosklens.app.ui.ViewfinderLook
import kr.kiosklens.domain.session.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 자막 대신 구슬의 색 · 움직임으로 상태를 보여 준다 (2026-11-12 화면 개편) */
class OrbStateTest {
    @Test fun `단계마다 구슬 모습이 정해진다`() {
        assertEquals(OrbState.IDLE, OrbState.of(Phase.IDLE))
        assertEquals("누르기를 기다릴 때도 숨쉬기", OrbState.IDLE, OrbState.of(Phase.WAIT_CHANGE))
        assertEquals(OrbState.LISTENING, OrbState.of(Phase.LISTENING))
        assertEquals(OrbState.THINKING, OrbState.of(Phase.PLANNING))
        assertEquals(OrbState.THINKING, OrbState.of(Phase.SCANNING))
        for (p in listOf(Phase.GUIDING, Phase.ASKING)) assertEquals("$p", OrbState.SPEAKING, OrbState.of(p))
        assertEquals("주문 끝은 체크 표시", OrbState.DONE, OrbState.of(Phase.DONE))
        for (p in listOf(Phase.LOST, Phase.ERROR)) assertEquals("$p", OrbState.PROBLEM, OrbState.of(p))
    }

    @Test fun `체크 표시는 짧게 그려지고, 로딩 캐릭터는 가끔만 눈을 깜빡인다`() {
        assertEquals(0f, OrbMotion.check(0), 0f)
        assertEquals(1f, OrbMotion.check(OrbMotion.CHECK_MS), 0f)
        assertEquals(0f, OrbMotion.blink(0), 0f)                                        // 처음엔 눈을 뜨고 있다
        assertTrue("글자가 떠오른 뒤 첫 깜빡임", OrbMotion.blink(1600 + OrbMotion.BLINK_MS / 2) > 0.9f)
        val closed = (0L until OrbMotion.BLINK_EVERY_MS step 10).count { OrbMotion.blink(it) > 0f }
        assertTrue("한 주기에서 감은 시간은 짧다", closed * 10 <= OrbMotion.BLINK_MS + 10)
        for (t in 0L..6000L step 13) assertTrue(OrbMotion.blink(t) in 0f..1f)
    }

    @Test fun `움직임 값은 정해진 범위 안에서 반복된다`() {
        for (t in 0L..5000L step 37) {
            assertTrue(OrbMotion.breath(t) in 0f..1f)
            assertTrue(OrbMotion.spin(t) in 0f..360f)
            for (i in 0 until OrbMotion.BARS) assertTrue("$i $t", OrbMotion.bar(i, t) in 0.2f..0.62f)
        }
        assertEquals(OrbMotion.breath(100), OrbMotion.breath(100 + OrbMotion.BREATH_MS), 1e-4f)
        assertEquals(0f, OrbMotion.breath(0), 1e-4f)                                    // 숨쉬기는 작게 시작
    }

    @Test fun `가운데 소리 막대가 가장 크게 출렁인다`() {
        val peak = { i: Int -> (0L until OrbMotion.WAVE_MS step 10).maxOf { OrbMotion.bar(i, it) } }
        assertTrue(peak(2) > peak(1) && peak(1) > peak(0))
    }

    @Test fun `로딩 화면의 노란 테두리는 잠깐 뒤 그려지기 시작해 끝까지 그려지고 멈춘다`() {
        assertEquals(0f, OrbMotion.trace(0), 0f)
        assertEquals(0f, OrbMotion.trace(OrbMotion.TRACE_DELAY_MS), 0f)
        assertEquals(1f, OrbMotion.trace(OrbMotion.TRACE_DELAY_MS + OrbMotion.TRACE_MS), 0f)
        assertEquals(1f, OrbMotion.trace(10_000), 0f)
        var last = 0f
        for (t in 0L..1500L step 20) { val v = OrbMotion.trace(t); assertTrue(v >= last); last = v }
        // 처음엔 빠르고 끝에서 천천히 (짚어서 멈추는 느낌): 앞 절반에 이미 80% 넘게 그린다
        assertTrue(OrbMotion.trace(OrbMotion.TRACE_DELAY_MS + OrbMotion.TRACE_MS / 2) > 0.8f)
    }

    @Test fun `조준 모서리는 키오스크를 찾았는지와 안내 상태를 보여 준다`() {
        assertEquals(ViewfinderLook.SEARCHING, ViewfinderLook.of(false, OrbState.IDLE))
        assertEquals("글자를 읽으면 찾았다는 신호", ViewfinderLook.FOUND, ViewfinderLook.of(true, OrbState.IDLE))
        assertEquals(ViewfinderLook.FOUND, ViewfinderLook.of(true, OrbState.LISTENING))
        assertEquals("노란 안내 테두리가 있는 동안엔 옅게", ViewfinderLook.QUIET, ViewfinderLook.of(true, OrbState.SPEAKING))
        assertEquals(ViewfinderLook.PROBLEM, ViewfinderLook.of(true, OrbState.PROBLEM))
        assertEquals(ViewfinderLook.PROBLEM, ViewfinderLook.of(false, OrbState.PROBLEM))
    }
}
