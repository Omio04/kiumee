package kr.kiosklens.overlay

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.UiElement
import kr.kiosklens.overlay.transform.CoordinateMapper
import kr.kiosklens.overlay.transform.PxRect

/** 확인용 화면에 그릴 상자 하나: 화면 픽셀 위치 · 보여 줄 글자 · 버튼으로 본 것인지 */
data class RecognitionBox(val px: PxRect, val label: String, val isButton: Boolean)

/**
 * 카메라가 인식한 요소 → 화면에 그릴 상자들 ([RecognitionView]가 그린다).
 * 노란 하이라이트([ViewOverlay])와 **같은 좌표 변환**을 쓰므로, 같은 버튼이면 같은 자리에 그려진다.
 * JVM에서 테스트할 수 있게 Android 타입을 쓰지 않는다.
 */
object RecognitionLayout {
    const val MAX_LABEL = 14

    /** 화면 밖으로 완전히 나간 요소(채우기 미리보기에서 잘린 가장자리)는 뺀다. */
    fun boxes(elements: List<UiElement>, mapper: CoordinateMapper, viewW: Float, viewH: Float): List<RecognitionBox> {
        if (viewW <= 0f || viewH <= 0f) return emptyList()
        return elements.mapNotNull { e ->
            val p = mapper.map(e.rect, viewW, viewH)
            if (p.right <= 0f || p.left >= viewW || p.bottom <= 0f || p.top >= viewH) null
            else RecognitionBox(p, label(e.text), e.kind == ElementKind.BUTTON_LIKE)
        }
    }

    fun label(text: String): String {
        val t = text.trim()
        return if (t.length <= MAX_LABEL) t else t.take(MAX_LABEL - 1) + "…"
    }
}
