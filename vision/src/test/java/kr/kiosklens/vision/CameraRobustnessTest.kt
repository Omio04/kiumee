package kr.kiosklens.vision

import org.junit.Assert.*
import org.junit.Test

/** Synthetic OCR observations model camera translation and temporary unreadable frames. */
class CameraRobustnessTest {
    private fun menu(dx: Float) = listOf(
        OcrLine("(ICE)아메리카노", NormRect(.1f + dx, .2f, .3f + dx, .24f), .95f),
        OcrLine("(HOT)아메리카노", NormRect(.5f + dx, .2f, .7f + dx, .24f), .95f),
        OcrLine("장바구니 조회", NormRect(.1f + dx, .7f, .3f + dx, .74f), .95f),
        OcrLine("결제하기", NormRect(.5f + dx, .9f, .7f + dx, .94f), .95f),
    )

    @Test fun cameraTranslationDoesNotReviveOldMenuCoordinates() {
        for (distance in listOf(.05f, .08f, .12f)) {
            val tracker = ScreenTracker()
            tracker.update(menu(0f))
            val initial = tracker.update(menu(0f))!!
            val moved = tracker.update(menu(distance))!!
            assertEquals(initial.snapshot.screenId, moved.snapshot.screenId)
            assertEquals(1, moved.snapshot.elements.count { it.text == "(ICE)아메리카노" })
            assertEquals(.1f + distance, moved.snapshot.elements.first { it.text == "(ICE)아메리카노" }.rect.left, .001f)
        }
    }

    @Test fun unreadableFrameRequiresConfirmationBeforeGuidanceResumes() {
        val tracker = ScreenTracker()
        tracker.update(menu(0f))
        val initial = tracker.update(menu(0f))!!
        assertNull(tracker.update(emptyList()))
        assertNull(tracker.update(menu(.08f)))
        assertEquals(initial.snapshot.screenId, tracker.update(menu(.08f))!!.snapshot.screenId)
    }
}
