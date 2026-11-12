package kr.kiosklens.vision

import org.junit.Assert.*
import org.junit.Test

/** Manually transcribed reference labels, not OCR accuracy measurements. */
class HuddlingScreenTest {
    private fun line(text: String, top: Float, left: Float = .2f, right: Float = .8f) =
        OcrLine(text, NormRect(left, top, right, top + .02f), .95f)
    private val review = listOf(line("주문 세부 내역을 다시 한번 확인하여주세요", .1f),
        line("(ICE)아메리카노", .2f), line("먹고가기 (다회용컵)", .85f), line("포장하기 (일회용컵)", .9f))
    private val payment = listOf(line("결제수단 선택", .3f), line("카드 결제", .45f),
        line("앱카드", .5f), line("결제할 금액 2,000원", .7f))
    private val disposable = listOf(line("일회용품 선택", .35f), line("빨대/스틱 필요", .4f),
        line("캐리어/봉투 필요", .45f), line("직접 가져 갈게요", .5f), line("선택완료", .6f))

    @Test fun paymentIsForegroundWhenReviewIsStillVisible() {
        val result = Elements.filter(review + payment)
        assertEquals("payment", ScreenInterpreter().classifyLines(result))
        assertFalse(result.any { it.text.contains("먹고가기") || it.text.contains("주문 세부") })
        assertTrue(result.any { it.text == "카드 결제" })
    }

    @Test fun newDisposableTitleIsRecognizedAndBothChoicesRemain() {
        val result = Elements.filter(review + disposable)
        assertEquals("disposable", ScreenInterpreter().classifyLines(result))
        assertTrue(result.any { it.text == "직접 가져 갈게요" })
        assertFalse(result.any { it.text.contains("포장하기") })
        assertEquals(ElementKind.TEXT, result.first { it.text == "일회용품 선택" }.kindHint)
    }

    @Test fun semanticallyDifferentPopupRequiresTwoMatchingObservationsDespiteSharedLabels() {
        val tracker = ScreenTracker()
        val common = (0..40).map { line("배경 메뉴 $it", .05f) }
        val a = common + payment
        val b = common + disposable
        tracker.update(a)
        val first = tracker.update(a)!!
        assertEquals("s1", first.snapshot.screenId)
        assertNull(tracker.update(b))
        val second = tracker.update(b)!!
        assertTrue(second.changed)
        assertEquals("s2", second.snapshot.screenId)
        assertEquals(2, second.snapshot.stableFrames)
    }

    @Test fun alternatingPopupsAndSparseFramesDoNotConfirmTransition() {
        val tracker = ScreenTracker()
        val common = (0..40).map { line("배경 $it", .05f) }
        assertNull(tracker.update(common + payment))
        assertNull(tracker.update(common + disposable))
        assertNull(tracker.update(common + payment))
        assertNull(tracker.update(emptyList()))
        assertNull(tracker.update(common + payment))
        assertNotNull(tracker.update(common + payment))
    }

    @Test fun oneMissedTitleDoesNotCreateNewScreenOrPreserveAFalseTransition() {
        val tracker = ScreenTracker()
        val full = payment + (0..10).map { line("항목 $it", .55f) }
        tracker.update(full)
        val first = tracker.update(full)!!.snapshot.screenId
        assertEquals(first, tracker.update(full.drop(1))!!.snapshot.screenId)
        assertNull(tracker.update(disposable))
        assertNull(tracker.update(full))
        assertEquals(first, tracker.update(full)!!.snapshot.screenId)
    }

    @Test fun cardApprovalAndReceiptAreNotPaymentCompletion() {
        val card = Elements.filter(review + listOf(line("카드 결제(간편 결제)", .3f),
            line("할부개월", .4f), line("승인 요청", .5f)))
        assertEquals("card-approval", ScreenInterpreter().classifyLines(card))
        val receipt = Elements.filter(review + listOf(line("영수증을 출력하시겠습니까?", .4f),
            line("취소", .5f), line("미출력", .55f)))
        assertEquals("receipt", ScreenInterpreter().classifyLines(receipt))
        assertFalse(receipt.any { it.text.contains("먹고가기") })
    }

    @Test fun isolatedTitlesNeverInventAScreenOrAnAction() {
        assertNull(ScreenInterpreter().classifyLines(listOf(line("일회용품 선택", .3f))))
        assertNull(ScreenInterpreter().classifyLines(listOf(line("카드 결제", .3f), line("취소", .4f))))
    }

    @Test fun menuBehindIncompletePopupDoesNotOverrideUncertainty() {
        val menu = listOf(line("(ICE)아메리카노", .2f), line("(HOT)아메리카노", .3f),
            line("장바구니 조회", .7f), line("결제하기", .9f))
        assertNull(ScreenInterpreter().classifyLines(menu))
        assertNull(ScreenInterpreter().classifyLines(menu + disposable.take(1)))
    }
}
