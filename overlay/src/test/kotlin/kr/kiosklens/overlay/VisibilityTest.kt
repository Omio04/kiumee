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
        assertNull(t.update(null, true))
        assertEquals(false, t.update("e4", false))      // 같은 버튼이라도 다시 가리키기 시작하면 새로 판단
    }
}
