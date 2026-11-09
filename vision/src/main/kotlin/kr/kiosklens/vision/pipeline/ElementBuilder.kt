package kr.kiosklens.vision.pipeline

import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement
import kr.kiosklens.vision.classify.ElementClassifier
import kr.kiosklens.vision.filter.TextFilter
import kr.kiosklens.vision.ocr.OcrBlock

/**
 * OCR 결과 → 필터 → 분류 → [UiElement] (id 부여).
 *
 * 요소 ID 규칙 (설계서 4.3): "e" + hash(정규화 텍스트 + 중심점을 20×20 격자로 양자화한 셀).
 * 프레임이 바뀌어도 같은 버튼이면 같은 id. 충돌하면 `_2`, `_3`…
 */
class ElementBuilder(private val filters: List<TextFilter>, private val classifier: ElementClassifier) {

    fun build(blocks: List<OcrBlock>): List<UiElement> {
        val used = HashSet<String>()
        val out = ArrayList<UiElement>()
        val sorted = blocks.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))            // 같은 입력이면 같은 id가 나오게
        for (b in sorted) {
            if (out.size >= ScreenSnapshot.MAX_ELEMENTS) break
            val text = b.text.replace(Regex("\\s+"), " ").trim().take(UiElement.MAX_TEXT_LENGTH)
            if (text.isEmpty()) continue
            val block = b.copy(text = text, rect = clamp(b.rect), confidence = b.confidence.coerceIn(0f, 1f))
            if (!block.rect.isValid) continue
            if (!filters.all { it.keep(block) }) continue
            val kind = classifier.classify(block) ?: continue
            out += UiElement(unique(idOf(text, block.rect), used), text, block.rect, block.confidence, kind)
        }
        return out
    }

    private fun clamp(r: NormRect) = NormRect(r.left.coerceIn(0f, 1f), r.top.coerceIn(0f, 1f), r.right.coerceIn(0f, 1f), r.bottom.coerceIn(0f, 1f))

    private fun unique(base: String, used: MutableSet<String>): String {
        var id = base; var n = 2
        while (!used.add(id)) id = base + "_" + n++
        return id
    }

    companion object {
        const val GRID = 20

        fun idOf(text: String, rect: NormRect): String {
            val cx = (rect.centerX * GRID).toInt().coerceIn(0, GRID - 1)
            val cy = (rect.centerY * GRID).toInt().coerceIn(0, GRID - 1)
            var h = 0x811C9DC5.toInt()                                                         // FNV-1a 32비트: 기기·실행마다 같은 값
            for (c in TextNorm.norm(text) + "@" + cx + "," + cy) { h = h xor c.code; h *= 0x01000193 }
            return "e" + Integer.toHexString(h)
        }
    }
}
