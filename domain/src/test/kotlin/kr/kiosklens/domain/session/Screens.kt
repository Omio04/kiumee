package kr.kiosklens.domain.session

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement

/** 테스트용 화면 (server/testdata/snapshots의 카페 키오스크와 같은 배치). */
object Screens {
    fun el(id: String, text: String, l: Float, t: Float, r: Float, b: Float, kind: ElementKind = ElementKind.BUTTON_LIKE) =
        UiElement(id, text, NormRect(l, t, r, b), 0.95f, kind)

    val menu = ScreenSnapshot(
        screenId = "s02_menu", frameId = 10, timestampMs = 0, stableFrames = 2,
        elements = listOf(
            el("e1", "커피", 0.02f, 0.12f, 0.2f, 0.18f),
            el("e4", "아메리카노", 0.05f, 0.25f, 0.3f, 0.4f),
            el("e9", "4,500원", 0.05f, 0.41f, 0.3f, 0.45f, ElementKind.PRICE),
            el("e11", "장바구니 보기", 0.6f, 0.9f, 0.95f, 0.97f),
        ),
    )

    val options = ScreenSnapshot(
        screenId = "s03_options", frameId = 20, timestampMs = 0, stableFrames = 2,
        elements = listOf(
            el("e2", "HOT", 0.1f, 0.25f, 0.4f, 0.38f),
            el("e3", "ICE", 0.55f, 0.25f, 0.85f, 0.38f),
            el("e6", "담기", 0.55f, 0.88f, 0.95f, 0.96f),
        ),
    )
}
