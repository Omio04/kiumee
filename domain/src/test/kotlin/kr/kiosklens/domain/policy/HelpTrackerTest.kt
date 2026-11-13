package kr.kiosklens.domain.policy

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpTrackerTest {
    private fun el(id: String, text: String, l: Float, t: Float, r: Float, b: Float) = UiElement(id, text, NormRect(l, t, r, b), 0.9f, ElementKind.BUTTON_LIKE)
    private fun snap(id: String, vararg e: UiElement) = ScreenSnapshot(screenId = id, frameId = 1, timestampMs = 0, elements = e.toList(), stableFrames = 2)
    private val prev = snap("a",
        el("a1", "커피", 0.05f, 0.08f, 0.18f, 0.12f), el("a2", "음료", 0.40f, 0.08f, 0.53f, 0.12f),
        el("a3", "장바구니 조회", 0.05f, 0.90f, 0.45f, 0.96f), el("a4", "아메리카노", 0.10f, 0.30f, 0.40f, 0.36f))
    private val target = NormRect(0.72f, 0.07f, 0.88f, 0.13f)                                  // '푸드' 탭 (글자로는 안 읽혔다)

    @Test fun `흔들려 화면 번호와 칸 번호가 바뀌어도 주변 글자가 움직인 만큼 따라간다`() {
        val now = snap("b", *prev.elements.map { it.copy(id = "n" + it.id, rect = NormRect(it.rect.left + 0.05f, it.rect.top + 0.03f, it.rect.right + 0.05f, it.rect.bottom + 0.03f)) }.toTypedArray())
        val r = HelpTracker.track("푸드", target, prev, now)!!
        assertEquals(target.centerX + 0.05f, r.rect.centerX, 0.01f)
        assertEquals(target.centerY + 0.03f, r.rect.centerY, 0.01f)
    }

    @Test fun `가까이 가면 노란 칸도 같이 커진다`() {
        fun zoom(v: Float, c: Float) = c + (v - c) * 1.25f
        val now = snap("b", *prev.elements.map { val q = it.rect; it.copy(id = "z" + it.id, rect = NormRect(zoom(q.left, 0.5f), zoom(q.top, 0.3f), zoom(q.right, 0.5f), zoom(q.bottom, 0.3f))) }.toTypedArray())
        val r = HelpTracker.track("푸드", target, prev, now)!!
        assertEquals(target.width * 1.25f, r.rect.width, 0.02f)
    }

    @Test fun `글자가 읽히는 순간 그 글자 칸에 정확히 붙는다 (깨져 읽혀도)`() {
        val now = snap("b", *(prev.elements + el("x9", "주문달기", 0.55f, 0.90f, 0.95f, 0.96f)).toTypedArray())
        val r = HelpTracker.track("주문담기", NormRect(0.5f, 0.85f, 0.9f, 0.99f), prev, now)!!
        assertEquals("x9", r.element?.id)
    }

    @Test fun `같은 이름이 둘이면 마지막 위치에서 가까운 쪽을 따라간다`() {
        val now = snap("b", el("t1", "푸드", 0.72f, 0.08f, 0.86f, 0.12f), el("t2", "푸드", 0.05f, 0.20f, 0.30f, 0.25f))
        assertEquals("t1", HelpTracker.track("푸드", target, prev, now)?.element?.id)
    }

    @Test fun `단서가 없으면 옮기지 않는다`() {
        assertNull(HelpTracker.track("푸드", target, prev, snap("c", el("q", "완전히 다른 화면", 0.1f, 0.1f, 0.9f, 0.2f))))
        assertNotNull(HelpTracker.track("푸드", target, prev, prev))
        assertTrue(HelpTracker.track(null, null, null, prev) == null)
    }
}
