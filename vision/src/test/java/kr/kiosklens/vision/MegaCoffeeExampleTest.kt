package kr.kiosklens.vision

import kr.kiosklens.vision.pipeline.*
import org.junit.Assert.*
import org.junit.Test

/** Hand-labelled observations based on the ten supplied screenshots, NOT measured ML Kit output. */
class MegaCoffeeExampleTest {
    private fun line(text: String, left: Float, top: Float, right: Float = left + 0.15f,
        bottom: Float = top + 0.02f, block: Int? = null) =
        OcrLine(text, NormRect(left, top, right, bottom), 0.95f, blockId = block)

    private fun elements(lines: List<OcrLine>) = ElementIds().assign("test", Elements.filter(lines))

    @Test fun signedOptionPricesQuantitiesAndInstructionsAreNotButtons() {
        listOf("+0원", "+600원", "+1,200원", "2,000 원").forEach {
            assertEquals(it, ElementKind.PRICE, Elements.kind(it))
        }
        listOf("1개", "119초", "STEP2", "일회용품 필요여부를 선택(체크) 해주세요",
            "결제수단을 선택해주세요.", "선택된 옵션", "결제할 금액: 2,000원", "주문금액 2,000원").forEach {
            assertEquals(it, ElementKind.TEXT, Elements.kind(it))
        }
        listOf("연하게", "주문담기", "카드결제", "선택안함", "직접 가져 갈게요",
            "먹고가기", "포장하기", "선택완료", "(ICE)아메리카노").forEach {
            assertEquals(it, ElementKind.BUTTON_LIKE, Elements.kind(it))
        }
    }

    @Test fun icedAndHotAmericanoHaveSeparateStableIds() {
        val result = elements(listOf(
            line("(ICE)아메리카노", 0.5f, 0.4f),
            line("(HOT)아메리카노", 0.7f, 0.6f),
        ))
        assertEquals(2, result.map { it.id }.toSet().size)
        assertTrue(result.all { it.kind == ElementKind.BUTTON_LIKE })
    }

    @Test fun wrappedProductNameJoinsWithinBlockWithoutAbsorbingPriceOrNeighbor() {
        val result = Elements.filter(listOf(
            line("(ICE)헤이즐넛아메리카", 0.5f, 0.4f, 0.75f, 0.42f, 1),
            line("노", 0.51f, 0.425f, 0.54f, 0.445f, 1),
            line("2,700원", 0.52f, 0.45f, 0.65f, 0.47f, 1),
            line("(HOT)아메리카노", 0.78f, 0.4f, 0.98f, 0.42f, 2),
        ))
        assertEquals(listOf("(ICE)헤이즐넛아메리카노", "(HOT)아메리카노", "2,700원"), result.map { it.text })
    }

    @Test fun distinctOptionRowsAndTemperatureLabelsAreNeverMergedAcrossBlocks() {
        val lines = listOf(line("(ICE)아메리카노", 0.1f, 0.3f, block = 1),
            line("연하게", 0.1f, 0.325f, block = 2), line("샷 추가", 0.4f, 0.325f, block = 3))
        assertEquals(3, Elements.filter(lines).size)
    }

    @Test fun selectedOptionStripAndCartRowDoNotDuplicateActionableButtons() {
        val result = elements(listOf(
            line("(ICE)아메리카노", 0.5f, 0.35f),
            line("선택된 옵션", 0.05f, 0.25f, 0.2f),
            line("연하게", 0.3f, 0.25f),
            line("연하게", 0.15f, 0.45f),
            line("장바구니 조회", 0.05f, 0.6f),
            line("(ICE)아메리카노", 0.1f, 0.7f),
            line("결제하기", 0.7f, 0.72f),
        ))
        assertEquals(1, result.count { it.text == "연하게" && it.kind == ElementKind.BUTTON_LIKE })
        assertEquals(1, result.count { it.text == "(ICE)아메리카노" && it.kind == ElementKind.BUTTON_LIKE })
        assertEquals(ElementKind.BUTTON_LIKE, result.first { it.text == "결제하기" }.kind)
    }

    @Test fun disposablePopupKeepsAllUserChoicesAndHidesUnderlyingFulfilmentButtons() {
        val result = elements(listOf(
            line("(ICE)아메리카노", 0.1f, 0.27f),
            line("일회용품 필요여부를 선택(체크) 해주세요", 0.17f, 0.34f, 0.8f),
            line("선택안함", 0.22f, 0.4f),
            line("빨대/스틱 필요", 0.22f, 0.43f),
            line("캐리어/봉투 필요", 0.22f, 0.46f),
            line("직접 가져 갈게요", 0.22f, 0.49f),
            line("선택완료", 0.45f, 0.57f),
            line("먹고가기", 0.3f, 0.67f),
            line("포장하기", 0.65f, 0.68f),
        ))
        assertFalse(result.any { it.text == "먹고가기" || it.text == "포장하기" })
        assertEquals(5, result.count { it.kind == ElementKind.BUTTON_LIKE })
    }

    private fun loyaltyLines(withClose: Boolean = true): List<OcrLine> {
        val foreground = mutableListOf(
            line("스탬프 적립", 0.42f, 0.245f, 0.64f, 0.265f),
            line("010-", 0.45f, 0.31f),
            line("적립", 0.45f, 0.60f),
        )
        (1..9).forEach { digit ->
            foreground += line(digit.toString(), 0.27f + (digit - 1) % 3 * 0.22f,
                0.37f + (digit - 1) / 3 * 0.06f, 0.31f + (digit - 1) % 3 * 0.22f)
        }
        foreground += line("0", 0.49f, 0.55f)
        if (withClose) foreground += OcrLine("×", NormRect(0.80f, 0.24f, 0.87f, 0.27f), 0.9f,
            kindHint = ElementKind.BUTTON_LIKE, source = ObservationSource.VERIFIED_CLOSE_ICON)
        return foreground + listOf(
            line("(ICE)아메리카노", 0.02f, 0.4f, 0.15f),
            line("(HOT)아메리카노", 0.86f, 0.53f, 0.99f),
            line("결제하기", 0.7f, 0.75f),
        )
    }

    @Test fun loyaltySkipExposesOnlyVerifiedCloseAsActionableControl() {
        val result = elements(loyaltyLines())
        assertEquals(listOf("×"), result.filter { it.kind == ElementKind.BUTTON_LIKE }.map { it.text })
        assertFalse(result.any { it.text.contains("아메리카노") || it.text == "결제하기" })
    }

    @Test fun loyaltyWithoutVisibleCloseNeverFabricatesOne() {
        val result = elements(loyaltyLines(withClose = false))
        assertFalse(result.any { it.text == "×" })
        assertFalse(result.any { it.kind == ElementKind.BUTTON_LIKE })
    }

    @Test fun loyaltySupportsKeypadRowsReturnedAsSingleOcrLines() {
        val lines = loyaltyLines().filterNot { it.text.matches(Regex("[0-9]")) } +
            listOf(line("1 2 3", 0.27f, 0.37f, 0.75f),
                line("456", 0.27f, 0.43f, 0.75f), line("7 8 9", 0.27f, 0.49f, 0.75f))
        val result = elements(lines)
        assertEquals(listOf("×"), result.filter { it.kind == ElementKind.BUTTON_LIKE }.map { it.text })
        assertFalse(result.any { it.text.contains("아메리카노") })
    }

    @Test fun titleAloneDoesNotSuppressOrdinaryMenu() {
        val lines = listOf(line("스탬프 적립", 0.1f, 0.2f), line("아메리카노", 0.2f, 0.4f))
        assertEquals(2, Elements.filter(lines).size)
    }

    @Test fun loyaltyOverlayChangesScreenEvenWithManyBackgroundLabels() {
        val builder = ElementBuilder()
        val background = (1..30).map { line("메뉴항목$it", 0.01f, 0.01f + it * 0.005f) }
        val tracker = ScreenTracker()
        val menu = builder.filter(background + listOf(line("커피", 0.2f, 0.2f)))
        tracker.update(menu)
        tracker.update(menu)
        val popup = builder.filter(background + loyaltyLines())
        assertNull(tracker.update(popup))
        val updated = tracker.update(popup)!!
        assertTrue(updated.changed)
        assertEquals("s2", updated.snapshot.screenId)
        assertEquals(listOf("×"), updated.snapshot.elements.filter { it.kind == ElementKind.BUTTON_LIKE }.map { it.text })
    }

    @Test fun phoneNumbersAreRemovedButIncompletePrefixDoesNotLeakCompleteNumber() {
        val lines = listOf("010-1234-5678", "01012345678", "０１０ １２３４ ５６７８", "010-").map {
            line(it, 0.3f, 0.4f)
        }
        assertEquals(listOf("010-"), Elements.filter(lines).map { it.text })
    }

    @Test fun countdownAloneDoesNotCreateScreenTransitions() {
        val tracker = ScreenTracker()
        fun view(seconds: Int) = listOf(line("커피", 0.1f, 0.3f), line("결제하기", 0.7f, 0.7f),
            line("${seconds}초", 0.65f, 0.65f))
        tracker.update(view(120))
        assertNotNull(tracker.update(view(119)))
        assertFalse(tracker.update(view(118))!!.changed)
    }

    @Test fun paymentPopupHidesMenuButKeepsCardPaymentCandidate() {
        val result = elements(listOf(
            line("커피", 0.2f, 0.15f),
            line("결제수단 선택", 0.1f, 0.2f),
            line("카드결제", 0.2f, 0.4f),
            line("앱카드", 0.5f, 0.4f),
            line("네이버페이", 0.6f, 0.5f),
            line("결제할 금액: 2,000원", 0.5f, 0.7f),
        ))
        assertFalse(result.any { it.text == "커피" })
        assertEquals(ElementKind.TEXT, result.first { it.text == "결제수단 선택" }.kind)
        assertEquals(ElementKind.BUTTON_LIKE, result.first { it.text == "카드결제" }.kind)
    }

    @Test fun orderReviewPreservesBothFulfilmentChoicesForUserQuestion() {
        val result = elements(listOf(
            line("커피", 0.2f, 0.15f),
            line("주문 세부내역을 다시 한번 확인하여 주세요", 0.1f, 0.2f, 0.8f),
            line("(ICE)아메리카노", 0.2f, 0.3f),
            line("연하게", 0.2f, 0.35f),
            line("먹고가기", 0.3f, 0.65f),
            line("포장하기", 0.65f, 0.65f),
            line("결제하기", 0.7f, 0.78f),
        ))
        assertFalse(result.any { it.text == "커피" || it.text == "결제하기" })
        assertEquals(2, result.count {
            it.text in setOf("먹고가기", "포장하기") && it.kind == ElementKind.BUTTON_LIKE
        })
    }
}
