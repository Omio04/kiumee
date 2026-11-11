package kr.kiosklens.app

import kr.kiosklens.app.ui.RecognitionStatus
import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement
import kr.kiosklens.vision.ScanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionStatusTest {
    private fun snap() = ScreenSnapshot(
        "s3", 1L, 0L,
        listOf(
            UiElement("e1", "아메리카노", NormRect(0.1f, 0.1f, 0.3f, 0.2f), 0.9f, ElementKind.BUTTON_LIKE),
            UiElement("e2", "4,500원", NormRect(0.1f, 0.2f, 0.3f, 0.25f), 0.9f, ElementKind.TEXT),
        ),
        2,
    )

    @Test fun `인식되면 글자 수 · 버튼 수 · 화면을 보여 준다`() {
        val t = RecognitionStatus.text(ScanStatus.READY, snap(), visible = true)
        assertTrue(t, t.contains("글자 2개") && t.contains("버튼 1개") && t.contains("화면 s3"))
    }

    @Test fun `글자가 적게 보이면 다시 비추라고 한다`() {
        assertTrue(RecognitionStatus.text(ScanStatus.REPOSITION, null, visible = true).contains("3개 이상"))
    }

    @Test fun `모든 상태에 문구가 있고 표시를 끄면 꺼졌다고만 한다`() {
        for (s in ScanStatus.values()) assertTrue(s.name, RecognitionStatus.text(s, null, visible = true).isNotBlank())
        assertEquals("[확인용] 인식 표시 꺼짐 · 누르면 켜짐", RecognitionStatus.text(ScanStatus.READY, snap(), visible = false))
    }
}
