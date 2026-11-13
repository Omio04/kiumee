package kr.kiosklens.domain.policy

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightAreaTest {
    private fun el(id: String, text: String, l: Float, t: Float, r: Float, b: Float, kind: ElementKind = ElementKind.BUTTON_LIKE) =
        UiElement(id, text, NormRect(l, t, r, b), 0.9f, kind)

    /** 2 x 2 메뉴판: 칸마다 그림(위) + 이름 + 가격(아래). 이름 가운데 간격 가로 0.25 · 세로 0.2 */
    private val grid = listOf(
        el("n1", "아메리카노", 0.10f, 0.30f, 0.30f, 0.33f), el("p1", "W 4,500", 0.12f, 0.335f, 0.28f, 0.36f, ElementKind.PRICE),
        el("n2", "카페라떼", 0.35f, 0.30f, 0.55f, 0.33f), el("p2", "W 5,000", 0.37f, 0.335f, 0.53f, 0.36f, ElementKind.PRICE),
        el("n3", "에스프레소", 0.10f, 0.50f, 0.30f, 0.53f), el("p3", "W 4,000", 0.12f, 0.535f, 0.28f, 0.56f, ElementKind.PRICE),
        el("n4", "카페모카", 0.35f, 0.50f, 0.55f, 0.53f), el("p4", "W 5,500", 0.37f, 0.535f, 0.53f, 0.56f, ElementKind.PRICE),
        el("tab", "커피", 0.10f, 0.10f, 0.20f, 0.14f),
        el("order", "주문하기", 0.70f, 0.85f, 0.95f, 0.92f),
    )

    @Test fun `메뉴 칸이면 그림 · 이름 · 가격을 함께 감싸고 옆 칸을 넘지 않는다`() {
        val area = HighlightArea.of(grid[4], grid)                    // 에스프레소 (아래 줄 왼쪽)
        val name = grid[4].rect; val price = grid[5].rect
        assertTrue("이름 · 가격을 감쌈", area.left <= name.left && area.right >= name.right && area.bottom >= price.bottom)
        assertTrue("그림이 있는 위쪽으로 넓힘", area.top < name.top - 0.1f)
        assertTrue("위 칸 가격에 닿지 않음", area.top > grid[1].rect.bottom)
        assertTrue("오른쪽 칸 가운데를 넘지 않음", area.right < grid[6].rect.centerX)
    }

    @Test fun `가격이 없는 버튼이나 격자가 아니면 글자 상자 그대로`() {
        assertEquals(grid[8].rect, HighlightArea.of(grid[8], grid))   // 탭
        assertEquals(grid[9].rect, HighlightArea.of(grid[9], grid))   // 주문하기
        val alone = listOf(grid[0], grid[1])
        assertEquals(grid[0].rect, HighlightArea.of(grid[0], alone))  // 메뉴 칸이지만 이웃이 없음
    }

    @Test fun `가려지면 주변이 움직인 만큼 옮기고 단서가 없거나 화면 밖이면 숨긴다`() {
        val before = ScreenSnapshot("s1", 1, 0, grid, 2)
        val moved = grid.filter { it.id != "n1" }.map { it.copy(rect = NormRect(it.rect.left, it.rect.top - 0.125f, it.rect.right, it.rect.bottom - 0.125f)) }
        val after = ScreenSnapshot("s1", 2, 0, moved, 2)
        val last = NormRect(0.08f, 0.2f, 0.32f, 0.37f)
        val shifted = HighlightArea.shift(last, before, after, "n1")!!
        assertEquals(last.top - 0.125f, shifted.top, 1e-4f)
        assertEquals(last.left, shifted.left, 1e-4f)
        assertNull("단서 1개", HighlightArea.shift(last, before, after.copy(elements = moved.take(1)), "n1"))
        val farUp = moved.map { it.copy(rect = NormRect(it.rect.left, it.rect.top - 0.6f, it.rect.right, it.rect.bottom - 0.6f)) }
        assertNull("화면 밖", HighlightArea.shift(last, before, after.copy(elements = farUp), "n1"))
    }

    // ---- 버튼 전체 칸 (2026-11-13 요청: "버튼을 다 감싸듯이") — 서버가 사진에서 본 버튼 전체 상자

    @Test fun `버튼 전체 칸은 글자 상자까지 품고, 글자와 어긋난 칸은 버린다`() {
        val text = NormRect(0.75f, 0.87f, 0.90f, 0.90f)                      // "결제하기" 글자
        val whole = HighlightArea.button(NormRect(0.70f, 0.85f, 0.94f, 0.95f), text)
        assertEquals(NormRect(0.70f, 0.85f, 0.94f, 0.95f), whole)
        val cut = HighlightArea.button(NormRect(0.70f, 0.88f, 0.85f, 0.95f), text)   // 글자 일부만 덮음 → 글자까지 넓힌다
        assertEquals(NormRect(0.70f, 0.87f, 0.90f, 0.95f), cut)
        assertNull(HighlightArea.button(NormRect(0.10f, 0.10f, 0.30f, 0.20f), text))  // 엉뚱한 곳 (휴대폰이 움직였거나 AI가 빗나감)
    }

    private fun assertRect(expected: NormRect, actual: NormRect) {
        assertEquals(expected.left, actual.left, 1e-4f); assertEquals(expected.top, actual.top, 1e-4f)
        assertEquals(expected.right, actual.right, 1e-4f); assertEquals(expected.bottom, actual.bottom, 1e-4f)
    }

    @Test fun `글자가 움직이고 커진 만큼 버튼 전체 칸도 옮기고 키운다`() {
        val anchor = NormRect(0.40f, 0.50f, 0.60f, 0.54f)                     // 글자 높이 0.04, 가운데 (0.5, 0.52)
        val button = NormRect(0.30f, 0.45f, 0.70f, 0.60f)
        assertRect(button, HighlightArea.follow(button, anchor, anchor))      // 그대로면 그대로
        assertRect(NormRect(0.20f, 0.35f, 0.60f, 0.50f),                      // 왼쪽 위로 0.1씩
            HighlightArea.follow(button, anchor, NormRect(0.30f, 0.40f, 0.50f, 0.44f)))
        assertRect(NormRect(0.20f, 0.405f, 0.80f, 0.63f),                     // 가까워짐: 글자 높이 1.5배 → 칸도 1.5배, 글자 기준 자리는 그대로
            HighlightArea.follow(button, anchor, NormRect(0.35f, 0.48f, 0.65f, 0.54f)))
        val wild = HighlightArea.follow(button, anchor, NormRect(0.40f, 0.40f, 0.60f, 0.60f))     // 글자가 5배? 잘못 읽은 것 → 1.7배까지만
        assertEquals(button.height * 1.7f, wild.height, 1e-4f)
    }
}
