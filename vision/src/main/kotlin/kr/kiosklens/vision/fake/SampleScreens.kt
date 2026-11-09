package kr.kiosklens.vision.fake

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement

/** 카페 키오스크 손 픽스처 (server/testdata/snapshots 와 같은 배치). 대상 키오스크 사진이 나오기 전까지 mock 플레이버가 쓴다. */
object SampleScreens {
    private fun el(id: String, text: String, l: Float, t: Float, r: Float, b: Float, kind: ElementKind = ElementKind.BUTTON_LIKE) =
        UiElement(id, text, NormRect(l, t, r, b), 0.95f, kind)

    val cafe: List<ScreenSnapshot> = listOf(
        ScreenSnapshot("s01_dine", 1, 0, listOf(
            el("e1", "매장에서 드시겠어요?", 0.2f, 0.1f, 0.8f, 0.18f, ElementKind.TEXT),
            el("e2", "매장", 0.1f, 0.35f, 0.45f, 0.6f), el("e3", "포장", 0.55f, 0.35f, 0.9f, 0.6f)), 2),
        ScreenSnapshot("s02_menu", 2, 0, listOf(
            el("e1", "커피", 0.02f, 0.12f, 0.2f, 0.18f), el("e2", "음료", 0.22f, 0.12f, 0.4f, 0.18f), el("e3", "디저트", 0.42f, 0.12f, 0.6f, 0.18f),
            el("e4", "아메리카노", 0.05f, 0.25f, 0.3f, 0.4f), el("e5", "카페라떼", 0.35f, 0.25f, 0.6f, 0.4f),
            el("e9", "4,500원", 0.05f, 0.41f, 0.3f, 0.45f, ElementKind.PRICE), el("e10", "5,000원", 0.35f, 0.41f, 0.6f, 0.45f, ElementKind.PRICE),
            el("e11", "장바구니 보기", 0.6f, 0.9f, 0.95f, 0.97f)), 2),
        ScreenSnapshot("s03_options", 3, 0, listOf(
            el("e1", "아메리카노 옵션 선택", 0.2f, 0.08f, 0.8f, 0.15f, ElementKind.TEXT),
            el("e2", "HOT", 0.1f, 0.25f, 0.4f, 0.38f), el("e3", "ICE", 0.55f, 0.25f, 0.85f, 0.38f), el("e6", "담기", 0.55f, 0.88f, 0.95f, 0.96f)), 2),
        ScreenSnapshot("s04_cart", 4, 0, listOf(
            el("e1", "주문 내역", 0.3f, 0.06f, 0.7f, 0.12f, ElementKind.TEXT), el("e2", "아메리카노 1잔", 0.05f, 0.2f, 0.6f, 0.28f, ElementKind.TEXT),
            el("e3", "결제하기", 0.55f, 0.9f, 0.95f, 0.97f), el("e4", "더 담기", 0.05f, 0.9f, 0.45f, 0.97f)), 2),
        ScreenSnapshot("s05_payment", 5, 0, listOf(
            el("e1", "결제 수단을 선택하세요", 0.2f, 0.08f, 0.8f, 0.15f, ElementKind.TEXT),
            el("e2", "신용카드", 0.1f, 0.3f, 0.45f, 0.5f), el("e3", "카카오페이", 0.55f, 0.3f, 0.9f, 0.5f), el("e4", "삼성페이", 0.1f, 0.55f, 0.45f, 0.75f)), 2),
    )
}
