package kr.kiosklens.vision

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 팝업창 버튼 (P1, 2026-11-13).
 *
 * 녹화 자료(`testdata/kiosk/ocr-replay.json`, Tesseract)에서는 팝업 버튼이 뭉개져 읽혀서("| 선 택 안함 | 선 택 완료 ㅣ")
 * 일부러 버튼이 0개가 된다(제목만 보이는 불확실한 창 → 뒤에 깔린 메뉴를 안내하지 않으려고, P2 c86cf41).
 * ML Kit처럼 제목과 버튼이 또렷하게 읽히면, 창 안의 버튼은 남고 뒤에 깔린 메뉴 · 결제 버튼은 빠져야 한다.
 */
class DialogButtonsTest {
    private fun line(text: String, top: Float, left: Float, right: Float) =
        OcrLine(text, NormRect(left, top, right, top + 0.025f), 0.95f)

    /** 팝업 뒤에 깔린 메뉴 화면 */
    private val background = listOf(
        line("아메리카노", 0.20f, 0.46f, 0.62f), line("2,000원", 0.23f, 0.49f, 0.60f),
        line("카페라떼", 0.20f, 0.66f, 0.82f), line("결제하기", 0.86f, 0.62f, 0.86f),
    )

    private fun buttons(lines: List<OcrLine>) =
        ElementIds().assign("t", Elements.filter(lines)).filter { it.kind == ElementKind.BUTTON_LIKE }.map { it.text }

    @Test fun `또렷하게 읽힌 일회용품 창은 창 버튼만 남는다`() {
        val b = buttons(background + listOf(
            line("일회용품 필요여부를 선택해주세요", 0.41f, 0.18f, 0.82f),
            line("빨대/스틱 필요", 0.45f, 0.18f, 0.45f),
            line("직접 가져갈게요", 0.52f, 0.18f, 0.45f),
            line("선택 안함", 0.56f, 0.23f, 0.45f), line("선택 완료", 0.56f, 0.55f, 0.77f),
        ))
        assertTrue(b.toString(), "선택 안함" in b && "선택 완료" in b)
        assertTrue("뒤에 깔린 버튼은 빠진다: $b", "결제하기" !in b && "아메리카노" !in b)
    }

    @Test fun `또렷하게 읽힌 알림창은 확인 버튼이 남는다`() {
        val b = buttons(background + listOf(
            line("메뉴를 먼저 선택해 주세요", 0.42f, 0.29f, 0.71f), line("확인", 0.50f, 0.45f, 0.55f),
        ))
        assertTrue(b.toString(), "확인" in b)
        assertTrue("뒤에 깔린 버튼은 빠진다: $b", "결제하기" !in b)
    }

    @Test fun `제목만 보이고 버튼이 뭉개진 창은 아무 버튼도 안내하지 않는다 (P2 의도)`() {
        val b = buttons(background + listOf(
            line("일회용품 필요여부를 선택해주세요", 0.41f, 0.18f, 0.82f),
            line("| 선 택 안함 | 선 택 완료 ㅣ", 0.56f, 0.23f, 0.77f),
        ))
        assertTrue(b.toString(), b.isEmpty())
    }
}
