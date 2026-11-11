package kr.kiosklens.overlay

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.UiElement
import kr.kiosklens.overlay.transform.FillCenterMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionLayoutTest {
    private fun el(id: String, text: String, rect: NormRect, kind: ElementKind = ElementKind.BUTTON_LIKE) =
        UiElement(id, text, rect, 0.9f, kind)

    @Test fun `인식 상자는 노란 하이라이트와 같은 자리에 그려진다`() {
        val m = FillCenterMapper(960, 1280)
        val rect = NormRect(0.30f, 0.40f, 0.70f, 0.50f)
        val box = RecognitionLayout.boxes(listOf(el("e1", "아메리카노", rect)), m, 1080f, 2400f).single()
        assertEquals(m.map(rect, 1080f, 2400f), box.px)                         // ViewOverlay가 쓰는 변환과 같다
        assertEquals("아메리카노", box.label)
        assertTrue(box.isButton)
    }

    @Test fun `채우기 미리보기에서 화면 밖으로 잘린 요소는 그리지 않는다`() {
        // 4:3 분석 프레임을 세로로 긴 화면에 채우면 좌우가 잘린다 → 맨 왼쪽 끝 요소는 화면 밖
        val m = FillCenterMapper(960, 1280)
        val outside = el("e1", "왼쪽 끝", NormRect(0.00f, 0.40f, 0.05f, 0.45f))
        val inside = el("e2", "가운데", NormRect(0.45f, 0.40f, 0.55f, 0.45f), ElementKind.TEXT)
        val boxes = RecognitionLayout.boxes(listOf(outside, inside), m, 1080f, 2400f)
        assertEquals(listOf("가운데"), boxes.map { it.label })
        assertFalse(boxes.single().isButton)
    }

    @Test fun `긴 글자는 줄여서 보여 주고 화면 크기를 모르면 그리지 않는다`() {
        assertEquals(RecognitionLayout.MAX_LABEL, RecognitionLayout.label("가".repeat(40)).length)
        assertTrue(RecognitionLayout.label("가".repeat(40)).endsWith("…"))
        val m = FillCenterMapper(960, 1280)
        assertTrue(RecognitionLayout.boxes(listOf(el("e1", "a", NormRect(0.4f, 0.4f, 0.6f, 0.6f))), m, 0f, 0f).isEmpty())
    }
}
