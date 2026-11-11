package kr.kiosklens.vision

/**
 * "선택 메뉴" · "장바구니" 같은 **담은 목록 안의 메뉴 이름은 고를 버튼이 아니다.**
 * 메뉴판에도 같은 이름(OCR 오타 포함)이 있으면 담은 목록 쪽을 글자(TEXT)로 낮춰 서버 후보에서 뺀다.
 * 메뉴판의 "아메리카노"를 고르라고 해야 하는데 담은 목록의 "아메리카노"를 가리키면 어르신이 헷갈린다 (2026-11-11 시험).
 *
 * - 담은 목록 = 제목 아래 영역. 제목이 화면 오른쪽(가운데 > 0.6)에 있으면 그 열만 (옆에 붙은 목록)
 * - "주문하기" · "+" · "−"처럼 담은 목록에만 있는 버튼은 그대로 둔다 (다음 단계에서 눌러야 함)
 */
internal object CartRegion {
    private val titles = setOf("선택메뉴", "선택한메뉴", "장바구니", "주문내역", "주문목록", "담은메뉴")

    fun demoteDuplicates(elements: List<ScreenElement>): List<ScreenElement> {
        val title = elements.filter { Elements.key(it.text) in titles }.minByOrNull { it.rect.top } ?: return elements
        val sidePanel = title.rect.centerX > 0.6f
        fun inCart(e: ScreenElement) = e !== title && e.rect.top >= title.rect.top - 0.01f &&
            (!sidePanel || e.rect.centerX >= title.rect.left - 0.05f)
        val menuKeys = elements.filter { it !== title && !inCart(it) && it.kind == ElementKind.BUTTON_LIKE }
            .map { Elements.key(it.text) }
        if (menuKeys.isEmpty()) return elements
        return elements.map { e ->
            val duplicate = e.kind == ElementKind.BUTTON_LIKE && inCart(e) &&
                Elements.key(e.text).let { k -> menuKeys.any { Elements.similar(k, it) } }
            if (duplicate) e.copy(kind = ElementKind.TEXT) else e
        }
    }
}
