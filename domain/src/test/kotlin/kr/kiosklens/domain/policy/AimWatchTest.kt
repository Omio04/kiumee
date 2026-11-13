package kr.kiosklens.domain.policy

import kr.kiosklens.domain.session.AimIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 비추는 문제가 몇 초째 이어질 때만, 너무 자주 않게 알린다 (2026-11-13 P5) */
class AimWatchTest {
    @Test fun `잠깐 못 읽은 것은 알리지 않고 3초 이어지면 알린다`() {
        val w = AimWatch()
        w.textFound(false, 1_000)
        assertNull(w.due(3_900))
        assertEquals(AimIssue.NO_TEXT, w.due(4_000))
    }

    @Test fun `중간에 한 번이라도 읽히면 처음부터 다시 센다`() {
        val w = AimWatch()
        w.textFound(false, 0)
        w.textFound(false, 2_000)                                  // 계속 못 읽음: 시작 시각은 그대로
        w.textFound(true, 2_500)
        w.textFound(false, 2_600)
        assertNull(w.due(5_000))
        assertEquals(AimIssue.NO_TEXT, w.due(5_600))
    }

    @Test fun `한 번 알린 뒤 8초 동안은 다시 알리지 않고, 그래도 계속이면 다시 알린다`() {
        val w = AimWatch()
        w.textFound(false, 0)
        assertEquals(AimIssue.NO_TEXT, w.due(3_000))
        for (t in 3_500L until 11_000L step 500) assertNull("$t", w.due(t))
        assertEquals(AimIssue.NO_TEXT, w.due(11_000))
    }

    @Test fun `버튼이 2초 넘게 안 보이면 알리고, 다시 보이면 알리지 않는다`() {
        val w = AimWatch()
        w.targetVisible(false, 0)
        assertNull(w.due(1_900))
        assertEquals(AimIssue.TARGET_HIDDEN, w.due(2_000))

        val v = AimWatch()
        v.targetVisible(false, 0)
        v.targetVisible(true, 1_500)
        assertNull(v.due(5_000))
    }

    @Test fun `둘 다면 글자를 못 읽는 쪽을 먼저 알린다`() {
        val w = AimWatch()
        w.targetVisible(false, 0)
        w.textFound(false, 0)
        assertNull(w.due(1_000))
        assertEquals("버튼만 2초 넘음", AimIssue.TARGET_HIDDEN, w.due(2_000))
        val both = AimWatch()
        both.targetVisible(false, 0)
        both.textFound(false, 0)
        assertEquals(AimIssue.NO_TEXT, both.due(3_000))
    }

    @Test fun `아무 문제가 없으면 알리지 않는다`() {
        val w = AimWatch()
        w.textFound(true, 0)
        w.targetVisible(true, 0)
        for (t in 0L..30_000L step 500) assertNull(w.due(t))
    }
}
