package kr.kiosklens.overlay

import kr.kiosklens.core.model.NormRect
import kr.kiosklens.overlay.transform.FillCenterMapper
import kr.kiosklens.overlay.transform.FitCenterMapper
import kr.kiosklens.overlay.transform.ScreenInsets
import kr.kiosklens.overlay.transform.VisibleArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 하이라이트가 보이는지 판단하는 계산(VisibleArea)과, 바뀔 때만 알리는 장치(VisibilityTracker). */
class VisibilityTest {
    // 세로로 긴 폰(1080×2400)에 4:3 분석 프레임(세로 960×1280), 아래 버튼판 430px
    private val fill = FillCenterMapper(960, 1280)
    private val fit = FitCenterMapper(960, 1280)
    private val panel = ScreenInsets(bottom = 430f)
    private val center = NormRect(0.40f, 0.45f, 0.60f, 0.55f)
    private val leftEdge = NormRect(0.02f, 0.30f, 0.15f, 0.40f)      // 채우기면 화면 왼쪽 밖
    private val bottomRight = NormRect(0.62f, 0.85f, 0.78f, 0.95f)   // 버튼판 뒤 (예: 결제하기)

    @Test fun `가운데 버튼은 보인다`() {
        assertTrue(VisibleArea.isVisible(center, fill, 1080f, 2400f, panel))
    }

    @Test fun `채우기면 왼쪽 끝 버튼은 화면 밖이라 안 보인다`() {
        assertFalse(VisibleArea.isVisible(leftEdge, fill, 1080f, 2400f, panel))
    }

    @Test fun `버튼판 뒤에 걸린 아래쪽 버튼은 안 보인다`() {
        assertFalse(VisibleArea.isVisible(bottomRight, fill, 1080f, 2400f, panel))
        assertTrue("버튼판이 없으면 보임", VisibleArea.isVisible(bottomRight, fill, 1080f, 2400f))
    }

    @Test fun `맞추기면 왼쪽 끝 버튼도 화면 안이다`() {
        assertTrue(VisibleArea.isVisible(leftEdge, fit, 1080f, 2400f, panel))
    }

    @Test fun `화면 크기를 아직 모르면 안 보인다고 하지 않는다`() {
        assertTrue(VisibleArea.isVisible(leftEdge, fill, 0f, 0f, panel))
    }

    @Test fun `처음부터 안 보이면 한 번만 알린다`() {
        val t = VisibilityTracker()
        assertEquals(false, t.update("e9", false))
        assertNull("같은 버튼이 계속 안 보이면 다시 알리지 않음", t.update("e9", false))
        assertNull(t.update("e9", false))
    }

    @Test fun `처음부터 보이면 알리지 않는다`() {
        val t = VisibilityTracker()
        assertNull(t.update("e4", true))
        assertNull(t.update("e4", true))
    }

    @Test fun `보이다가 안 보이면 알리고, 다시 보이면 또 알린다`() {
        val t = VisibilityTracker()
        assertNull(t.update("e4", true))
        assertEquals(false, t.update("e4", false))
        assertEquals(true, t.update("e4", true))
    }

    @Test fun `다른 버튼으로 바뀌면 처음부터 다시 판단한다`() {
        val t = VisibilityTracker()
        assertEquals(false, t.update("e4", false))
        assertEquals(false, t.update("e9", false))      // 새 버튼도 안 보이면 다시 한 번 알림
    }

    @Test fun `하이라이트가 없어지면 기록을 지운다`() {
        val t = VisibilityTracker()
        assertEquals(false, t.update("e4", false))
        assertEquals("안 보임을 알렸으니 풀어 준다", true, t.update(null, true))
        assertNull("이미 풀었으니 또 알리지 않음", t.update(null, true))
        assertEquals(false, t.update("e4", false))      // 같은 버튼이라도 다시 가리키기 시작하면 새로 판단
    }

    // ---- 2026-11-11 P1 지적: 안 보임을 알린 뒤 다른 버튼으로 바뀌면 "다시 보임"을 알리지 않던 문제 ----

    @Test fun `안 보이던 버튼이 보이는 다른 버튼으로 바뀌면 다시 보임을 알린다`() {
        val t = VisibilityTracker()
        assertEquals(false, t.update("e4", false))      // 아메리카노가 화면 밖
        assertEquals(true, t.update("e9", true))        // 옵션 창의 "확인"으로 바뀌었고 보임
        assertNull(t.update("e9", true))
    }

    @Test fun `보이는 버튼끼리 바뀌면 알리지 않는다`() {
        val t = VisibilityTracker()
        assertNull(t.update("e4", true))
        assertNull(t.update("e9", true))
    }

    @Test fun `안 보임이 없었으면 하이라이트가 없어져도 알리지 않는다`() {
        val t = VisibilityTracker()
        assertNull(t.update("e4", true))
        assertNull(t.update(null, true))
    }

    @Test fun `받은 값을 차례로 따라가면 늘 지금 상태와 같다`() {
        // (버튼 id, 지금 보이는가). null id는 하이라이트 없음 = 안 보이는 하이라이트도 없음
        val steps = listOf(
            "e4" to true, "e4" to false, "e9" to true, "e9" to false, "e9" to false,
            null to true, "e4" to false, "e9" to false, "e9" to true, "e4" to true, null to true,
        )
        val t = VisibilityTracker()
        var lastNotified = true                          // 받는 쪽의 처음 상태: 안 보이는 하이라이트 없음
        for ((id, visible) in steps) {
            t.update(id, visible)?.let { lastNotified = it }
            val expected = id == null || visible
            assertEquals("$id · $visible 뒤", expected, lastNotified)
        }
    }
}
