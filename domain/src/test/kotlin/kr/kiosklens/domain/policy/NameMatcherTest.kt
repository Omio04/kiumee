package kr.kiosklens.domain.policy

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatcherTest {
    private fun el(id: String, text: String) = UiElement(id, text, NormRect(0.1f, 0.1f, 0.3f, 0.2f), 0.9f, ElementKind.BUTTON_LIKE)

    @Test fun `서버 jamo_dice 와 같은 점수가 나온다 (파이썬 값과 맞춘 기준)`() {
        assertEquals(0.71, NameMatcher.dice("주문담기", "주문달기"), 0.01)
        assertEquals(1.0, NameMatcher.dice("주문담기", "주문담기"), 1e-9)
        assertEquals(0.0, NameMatcher.dice("주문담기", "sbo"), 1e-9)
        assertTrue(NameMatcher.dice("주문담기", "초기화") < 0.1)
        assertEquals(0.81, NameMatcher.dice("치즈케이크", "치즈케익"), 0.01)
    }

    @Test fun `OCR이 깨뜨린 이름도 찾고 다른 칸은 찾지 않는다`() {
        val els = listOf(el("a", "초기화|"), el("b", "텀블러 선택"), el("c", "주문달기"), el("d", "sbo"))
        assertEquals("c", NameMatcher.find("주문담기", els)?.id)
        assertNull(NameMatcher.find("장바구니 조회", els))                       // 전혀 다른 이름
    }

    @Test fun `가격이 붙어 읽혀도 이름을 품으면 찾는다`() {
        val els = listOf(el("a", "선택된 옵션"), el("b", "2,000원 결제하기"))
        assertEquals("b", NameMatcher.find("결제하기", els)?.id)
    }

    @Test fun `비슷한 칸이 둘이면 어느 쪽인지 모르니 찾지 않는다`() {
        assertNull(NameMatcher.find("주문담기", listOf(el("a", "주문달기"), el("b", "주문담7"))))
        assertNull(NameMatcher.find("주문담기", emptyList()))
    }
}
