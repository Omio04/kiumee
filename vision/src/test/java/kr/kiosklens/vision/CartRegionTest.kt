package kr.kiosklens.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class CartRegionTest {
    private fun el(id: String, text: String, l: Float, t: Float, kind: ElementKind = ElementKind.BUTTON_LIKE) =
        ScreenElement(id, text, NormRect(l, t, l + 0.15f, t + 0.03f), 0.9f, kind)

    private fun kinds(elements: List<ScreenElement>) = elements.associate { it.id to it.kind }

    @Test fun cartItemsThatAlsoAppearOnTheMenuAreNotCandidates() {
        val screen = listOf(
            el("m1", "아메리카노", 0.10f, 0.30f), el("m2", "카페라떼", 0.40f, 0.30f),
            el("title", "선택 메뉴", 0.05f, 0.70f, ElementKind.TEXT),
            el("c1", "아메리카노", 0.10f, 0.80f), el("c2", "아에리카노", 0.30f, 0.80f),     // OCR 오타도 같은 메뉴
            el("order", "주문하기", 0.75f, 0.85f),                                         // 담은 목록에만 있는 버튼
        )
        val k = kinds(CartRegion.demoteDuplicates(screen))
        assertEquals(ElementKind.BUTTON_LIKE, k["m1"])
        assertEquals(ElementKind.TEXT, k["c1"])
        assertEquals(ElementKind.TEXT, k["c2"])
        assertEquals(ElementKind.BUTTON_LIKE, k["order"])
    }

    @Test fun sideCartPanelOnlyCoversItsColumn() {
        val screen = listOf(
            el("title", "장바구니", 0.80f, 0.10f, ElementKind.TEXT),
            el("c1", "아메리카노", 0.80f, 0.30f),
            el("m1", "아메리카노", 0.20f, 0.50f),                                          // 제목보다 아래지만 왼쪽 메뉴판
        )
        val k = kinds(CartRegion.demoteDuplicates(screen))
        assertEquals(ElementKind.TEXT, k["c1"])
        assertEquals(ElementKind.BUTTON_LIKE, k["m1"])
    }

    @Test fun withoutCartTitleNothingChanges() {
        val screen = listOf(el("a", "아메리카노", 0.1f, 0.3f), el("b", "아메리카노", 0.1f, 0.8f))
        assertEquals(screen, CartRegion.demoteDuplicates(screen))
    }
}
