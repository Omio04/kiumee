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
