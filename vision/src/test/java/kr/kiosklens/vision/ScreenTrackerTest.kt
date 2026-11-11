package kr.kiosklens.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTrackerTest {
    private fun line(text: String, x: Float, confidence: Float = 0.95f) =
        OcrLine(text, NormRect(x, 0.2f, x + 0.07f, 0.25f), confidence)

    private fun screen(vararg labels: String) = labels.mapIndexed { index, label ->
        line(label, 0.1f + index * 0.2f)
    }

    @Test fun transientAndSparseFramesCannotChangeScreen() {
        val tracker = ScreenTracker()
        val menu = screen("아메리카노", "라떼", "담기")
        val pay = screen("카드", "현금", "취소")
        assertNull(tracker.update(menu))
        val original = tracker.update(menu)!!
        assertFalse(original.changed)
        assertNull(tracker.update(pay))
        assertNull(tracker.update(pay.take(2)))
        assertNull(tracker.update(pay))
        val confirmed = tracker.update(pay)!!
        assertTrue(confirmed.changed)
        assertEquals("s2", confirmed.snapshot.screenId)
    }

    @Test fun ocrTyposBetweenFramesStillConfirmTheSameScreen() {
        // 휴대폰으로 작은 글자를 찍으면 프레임마다 조금씩 다르게 읽힌다. 정확히 같은 것만 세면 3/7 = 0.43이라 확정이 안 됐다.
        val tracker = ScreenTracker()
        assertNull(tracker.update(screen("아메리카노", "카페라떼", "바닐라라떼", "담기", "취소")))
        val confirmed = tracker.update(screen("아에리카노", "카페라때", "바닐라라떼", "담기", "취소"))
        assertEquals("s1", confirmed!!.snapshot.screenId)
        // 같은 화면을 또 오타 섞어 읽어도 새 화면으로 착각하지 않는다
        val again = tracker.update(screen("아메리카노", "카페라떼", "바닐라라때", "담기", "취소"))!!
        assertFalse(again.changed)
        assertEquals("s1", again.snapshot.screenId)
    }

    @Test fun oneBlurryFrameDoesNotBreakTheCurrentScreen() {
        val tracker = ScreenTracker()
        val menu = screen("아메리카노", "카페라떼", "바닐라라떼", "에스프레소", "담기", "취소")
        tracker.update(menu); assertEquals("s1", tracker.update(menu)!!.snapshot.screenId)
        // 흐려서 절반만 제대로 읽힌 프레임 (유사도 0.5): 예전에는 새 화면 후보가 되어 확정이 끊겼다
        val blurry = screen("아메리카노", "카페라떼", "바닐라라떼", "담기", "쥐소", "에프레")
        val kept = tracker.update(blurry)!!
        assertFalse(kept.changed)
        assertEquals("s1", kept.snapshot.screenId)
        // 진짜 다른 화면은 여전히 두 번 보이면 바뀐다
        val pay = screen("신용카드", "삼성페이", "카카오페이", "쿠폰 사용", "이전")
        assertNull(tracker.update(pay))
        assertTrue(tracker.update(pay)!!.changed)
    }

    @Test fun pricesReadDifferentlyEveryFrameDoNotBlockConfirmation() {
        // 같은 메뉴 화면인데 가격을 프레임마다 다르게 읽는다 ("W 9,999" → "W 9,909" · "WS,999" · "W 8,999")
        val tracker = ScreenTracker()
        assertNull(tracker.update(screen("아메리카노", "카페라떼", "담기", "W 9,999", "W 4,500", "W 5,000")))
        val confirmed = tracker.update(screen("아메리카노", "카페라떼", "담기", "W 9,909", "WS,500", "W 8,000"))
        assertEquals("s1", confirmed!!.snapshot.screenId)
        assertEquals(6, confirmed.snapshot.elements.size)              // 가격은 비교에서만 빼고 인식 결과에는 그대로 둔다
    }

    @Test fun similarTextToleratesOcrTyposButNotDifferentNumbers() {
        assertTrue(Elements.similar("아메리카노", "아에리카노"))
        assertTrue(Elements.similar("카페라떼", "카페라때"))
        assertTrue(Elements.similar("아이스아메리카노", "아이스아메리카누"))
        assertFalse(Elements.similar("메뉴항목1", "메뉴항목2"))          // 숫자가 다르면 다른 버튼
        assertFalse(Elements.similar("결제하기", "취소하기"))            // 두 글자 다름
        assertFalse(Elements.similar("hot", "ice"))
        assertFalse(Elements.similar("담기", "닫기"))                    // 짧은 글자는 정확히 같아야
    }

    @Test fun duplicateLabelsKeepSeparateIdsWhenPositionsShift() {
        val tracker = ScreenTracker()
        tracker.update(screen("추가", "추가", "취소"))
        val first = tracker.update(screen("추가", "추가", "취소"))!!.snapshot
        val next = tracker.update(listOf(line("추가", 0.32f), line("추가", 0.12f), line("취소", 0.52f)))!!.snapshot
        val ids = first.elements.map { it.id }
        assertEquals(3, ids.toSet().size)
        assertEquals(ids, next.elements.sortedBy { it.rect.left }.map { it.id })
    }

    @Test fun keepsMissedLabelForOnlyOneObservation() {
        val tracker = ScreenTracker()
        val full = screen("카드", "현금", "취소", "이전")
        val partial = full.take(3)
        tracker.update(full)
        tracker.update(full)
        assertEquals(4, tracker.update(partial)!!.snapshot.elements.size)
        assertEquals(3, tracker.update(partial)!!.snapshot.elements.size)
    }

    // ---- 화면 일부만 바뀜 (2026-11-11 시험: 메뉴 위에 옵션 창이 떴는데 4분 동안 같은 안내만 되풀이) ----

    private fun at(text: String, x: Float, y: Float) = OcrLine(text, NormRect(x - 0.06f, y - 0.02f, x + 0.06f, y + 0.02f), 0.95f)
    private fun OcrLine.moved(dx: Float) = copy(rect = NormRect(rect.left + dx, rect.top, rect.right + dx, rect.bottom))

    /** 3열 메뉴 격자 (가격은 화면 구분에서 빠지니 이름만) */
    private val menuGrid = listOf(
        at("커피", 0.15f, 0.1f), at("음료", 0.35f, 0.1f), at("디저트", 0.55f, 0.1f),
        at("아메리카노", 0.2f, 0.3f), at("카페라떼", 0.5f, 0.3f), at("바닐라라떼", 0.8f, 0.3f),
        at("카푸치노", 0.2f, 0.5f), at("에스프레소", 0.5f, 0.5f), at("카라멜마끼아또", 0.8f, 0.5f),
        at("녹차라떼", 0.2f, 0.7f), at("초코라떼", 0.5f, 0.7f), at("딸기스무디", 0.8f, 0.7f),
        at("선택한 메뉴", 0.2f, 0.9f), at("결제하기", 0.8f, 0.9f),
    )

    /** 옵션 창이 가운데 메뉴 몇 개를 가리고 뜬다. 가장자리 메뉴 글자는 그대로 읽힌다 (전체 유사도 0.58 → 예전엔 같은 화면) */
    private val optionPopup = menuGrid.filterNot { it.text in setOf("카페라떼", "에스프레소", "초코라떼") } + listOf(
        at("옵션을 선택해 주세요", 0.5f, 0.35f), at("(ICE)아메리카노", 0.5f, 0.42f),
        at("샷 추가", 0.5f, 0.5f), at("취소", 0.4f, 0.65f), at("주문담기", 0.6f, 0.65f),
    )

    @Test fun popupOverTheMenuIsANewScreenAndClosingItIsAnother() {
        val tracker = ScreenTracker()
        tracker.update(menuGrid)
        assertEquals("s1", tracker.update(menuGrid)!!.snapshot.screenId)
        assertNull(tracker.update(optionPopup))                         // 한 프레임만으로는 확정하지 않는다
        val opened = tracker.update(optionPopup)!!
        assertTrue(opened.changed)
        assertEquals("s2", opened.snapshot.screenId)
        assertFalse(tracker.update(optionPopup)!!.changed)               // 창이 떠 있는 동안은 그대로

        assertNull(tracker.update(menuGrid))                            // 주문담기 → 창이 닫히고 메뉴가 다시 보임
        val closed = tracker.update(menuGrid)!!
        assertTrue(closed.changed)
        assertEquals("s3", closed.snapshot.screenId)
    }

    @Test fun movingTheCameraOrCoveringAButtonIsNotANewScreen() {
        val tracker = ScreenTracker()
        tracker.update(menuGrid); tracker.update(menuGrid)
        // 휴대폰을 옆으로 옮김: 왼쪽 열은 화면 밖으로 나가고 오른쪽 밖의 메뉴가 들어온다
        val panned = menuGrid.map { it.moved(-0.15f) }.filter { it.rect.centerX > 0.08f } +
            listOf(at("스콘", 0.92f, 0.3f), at("마카롱", 0.92f, 0.5f), at("쿠키", 0.92f, 0.7f))
        repeat(2) { assertEquals("s1", tracker.update(panned)!!.snapshot.screenId) }
        // 손가락이 메뉴 두 개를 가림: 사라지기만 하고 새 글자는 없다
        val covered = menuGrid.filterNot { it.text in setOf("아메리카노", "카푸치노") }
        repeat(2) { assertEquals("s1", tracker.update(covered)!!.snapshot.screenId) }
    }

    @Test fun filtersSensitiveTextAndClassifiesPrice() {
        val clean = Elements.filter(screen("카드 １２３４-５６７８-９０１２-３４５６", "!!!", "4,500원", "HOT") +
            line("흐린 글자", 0.2f, 0.1f))
        assertEquals(listOf("4,500원", "HOT"), clean.map { it.text })
        assertEquals(ElementKind.PRICE, Elements.kind(clean[0].text))
        assertEquals(ElementKind.BUTTON_LIKE, Elements.kind(clean[1].text))
    }
}
