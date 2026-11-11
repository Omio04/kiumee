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

    @Test fun filtersSensitiveTextAndClassifiesPrice() {
        val clean = Elements.filter(screen("카드 １２３４-５６７８-９０１２-３４５６", "!!!", "4,500원", "HOT") +
            line("흐린 글자", 0.2f, 0.1f))
        assertEquals(listOf("4,500원", "HOT"), clean.map { it.text })
        assertEquals(ElementKind.PRICE, Elements.kind(clean[0].text))
        assertEquals(ElementKind.BUTTON_LIKE, Elements.kind(clean[1].text))
    }
}
