package kr.kiosklens.vision

import org.junit.Assert.*
import org.junit.Test

class KioskFlowTest {
    private fun line(text: String, x: Float, y: Float, width: Float = .2f) =
        OcrLine(text, NormRect(x,y,x+width,y+.025f),.95f)
    private fun options(selected: String?) = listOf(
        line("선택하신 상품의 옵션상품을 모두 선택해주세요",.1f,.02f,.7f),
        line("선택된 옵션",.1f,.15f),line("초기화",.8f,.15f,.1f),
        line("연하게",.2f,.4f),line("샷 추가",.5f,.4f),line("주문담기",.5f,.9f),
    ) + if(selected==null) emptyList() else listOf(line(selected,.4f,.15f))

    @Test fun cartAdditionChangesTheScreenDespiteAnUnchangedMenu() {
        val menu = listOf(
            line("(ICE)아메리카노", .1f, .2f), line("(HOT)아메리카노", .5f, .2f),
            line("장바구니 조회", .1f, .75f), line("결제하기", .7f, .9f),
        )
        val tracker = ScreenTracker()
        val empty = Elements.filter(menu)
        tracker.update(empty)
        val start = tracker.update(empty)!!
        val filled = Elements.filter(menu + line("(ICE)아메리카노", .1f, .82f))
        assertNull(tracker.update(filled))
        val added = tracker.update(filled)!!
        assertTrue(added.changed)
        assertNotEquals(start.snapshot.screenId, added.snapshot.screenId)
        assertEquals(ElementKind.TEXT, added.snapshot.elements.single { it.rect.top == .82f }.kind)
    }

    @Test fun completionCanBeConfirmedWithOnlyTheHeadingAndOrderNumber() {
        val tracker = ScreenTracker()
        val menu = Elements.filter(options(null))
        tracker.update(menu)
        tracker.update(menu)
        val done = Elements.filter(listOf(
            line("결제가 완료되었습니다", .2f, .4f, .6f), line("주문번호 101", .4f, .5f),
        ))
        assertNull(tracker.update(done))
        assertTrue(tracker.update(done)!!.changed)
    }

    @Test fun optionSelectionAndResetAreConfirmedAsScreenUpdates() {
        val tracker=ScreenTracker()
        val empty=Elements.filter(options(null));val selected=Elements.filter(options("연하게"))
        tracker.update(empty);val first=tracker.update(empty)!!
        assertNull(tracker.update(selected))
        val changed=tracker.update(selected)!!
        assertTrue(changed.changed);assertNotEquals(first.snapshot.screenId,changed.snapshot.screenId)
        assertEquals(1,changed.snapshot.elements.count { it.text=="연하게" && it.kind==ElementKind.BUTTON_LIKE })
        assertNull(tracker.update(empty));assertTrue(tracker.update(empty)!!.changed)
    }

    @Test fun oneFrameSelectionOrMissedSummaryDoesNotAdvance() {
        val tracker=ScreenTracker();val empty=Elements.filter(options(null))
        tracker.update(empty);val id=tracker.update(empty)!!.snapshot.screenId
        assertNull(tracker.update(Elements.filter(options("연하게"))));assertNull(tracker.update(empty))
        assertEquals(id,tracker.update(empty)!!.snapshot.screenId)
        assertEquals(id,tracker.update(empty.filterNot { it.text=="선택된 옵션" })!!.snapshot.screenId)
    }

    @Test fun approvalInProgressIsInertAndDifferentFromRequest() {
        fun card(action:String)=Elements.filter(listOf(line("카드 결제(간편 결제)",.2f,.2f,.6f),
            line("카드번호",.2f,.3f),line("취소",.2f,.4f),line(action,.5f,.4f)))
        val processing=card("승인 중…")
        assertEquals("card-processing",ScreenInterpreter().classifyLines(processing))
        assertTrue(ElementIds().assign("processing",processing).none { it.kind==ElementKind.BUTTON_LIKE })
        val tracker=ScreenTracker();tracker.update(card("승인 요청"));tracker.update(card("승인 요청"))
        assertNull(tracker.update(processing));assertTrue(tracker.update(processing)!!.changed)
    }

    @Test fun explicitCompletionNeedsSupportingEvidenceAndHasNoTapCandidate() {
        val title=line("결제가 완료되었습니다",.2f,.4f,.6f)
        assertNull(ScreenInterpreter().classifyLines(listOf(title)))
        val complete=Elements.filter(listOf(title,line("주문번호",.4f,.5f),line("101",.4f,.6f)))
        assertEquals("complete",ScreenInterpreter().classifyLines(complete))
        assertTrue(ElementIds().assign("done",complete).none { it.kind==ElementKind.BUTTON_LIKE })
        assertNull(ScreenInterpreter().classifyLines(listOf(line("카드결제",.2f,.3f),line("승인 요청",.4f,.4f))))
    }

    @Test fun alertDoesNotExposeTheMenuBehindIt() {
        val lines=Elements.filter(listOf(line("메뉴를 먼저 선택해 주세요",.2f,.4f,.6f),
            line("확인",.4f,.6f),line("아메리카노",.1f,.2f),line("결제하기",.6f,.9f)))
        assertEquals("alert",ScreenInterpreter().classifyLines(lines))
        assertEquals(listOf("확인"),ElementIds().assign("alert",lines).filter { it.kind==ElementKind.BUTTON_LIKE }.map { it.text })
    }

    @Test fun partiallyReadForegroundNeverExposesBackgroundReviewButtons() {
        val lines=Elements.filter(listOf(line("주문 세부 내역을 다시 한번 확인해주세요",.1f,.1f,.8f),
            line("먹고가기",.2f,.85f),line("포장하기",.6f,.85f),line("결제수단 선택",.2f,.3f)))
        assertNull(ScreenInterpreter().classifyLines(lines))
        assertTrue(ElementIds().assign("unknown",lines).none { it.kind==ElementKind.BUTTON_LIKE })
    }

    @Test fun partialDisposableAlsoBlocksAVerifiedBackgroundCloseAndOldVotes() {
        val review=listOf(line("주문 세부 내역을 다시 한번 확인해주세요",.1f,.1f,.8f),
            line("먹고가기",.2f,.85f),line("포장하기",.6f,.85f))
        val close=line("×",.8f,.2f,.05f).copy(kindHint=ElementKind.BUTTON_LIKE,
            source=ObservationSource.VERIFIED_CLOSE_ICON)
        val tracker=ScreenTracker();val original=Elements.filter(review)
        tracker.update(original);tracker.update(original)
        val partial=Elements.filter(review + line("일회용품 선택",.2f,.3f) + close)
        assertTrue(partial.all { it.kindHint==ElementKind.TEXT })
        tracker.update(partial)
        val update=tracker.update(partial)
        if(update!=null)assertTrue(update.snapshot.elements.none { it.kind==ElementKind.BUTTON_LIKE })
    }
}
