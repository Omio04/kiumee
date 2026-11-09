package kr.kiosklens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NormRectTest {
    @Test fun `중심점은 좌우 상하의 평균이다`() {
        val r = NormRect(0.1f, 0.2f, 0.5f, 0.6f)
        assertEquals(0.3f, r.centerX, 1e-6f)
        assertEquals(0.4f, r.centerY, 1e-6f)
    }

    @Test fun `범위 밖이거나 넓이가 0이면 유효하지 않다`() {
        assertTrue(NormRect(0f, 0f, 1f, 1f).isValid)
        assertFalse(NormRect(0.5f, 0.2f, 0.5f, 0.6f).isValid)
        assertFalse(NormRect(-0.1f, 0.2f, 0.5f, 0.6f).isValid)
    }

    @Test fun `스냅샷에서 id로 요소를 찾는다`() {
        val e = UiElement("e1", "아메리카노", NormRect(0.1f, 0.1f, 0.3f, 0.2f), 0.9f, ElementKind.BUTTON_LIKE)
        val s = ScreenSnapshot("s1", 1, 0, listOf(e), stableFrames = 2)
        assertEquals(e, s.findElement("e1"))
        assertEquals(null, s.findElement("nope"))
    }
}
