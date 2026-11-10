package kr.kiosklens.vision.pipeline

import kotlin.math.max
import kotlin.math.min
import kr.kiosklens.vision.*
import kr.kiosklens.vision.classify.PriceClassifier

/** Rejoins wrapped labels only inside one ML Kit block; never crosses product columns. */
class OcrBlockLineMerger {
    private val price = PriceClassifier()

    fun merge(lines: List<OcrLine>): List<OcrLine> {
        val result = mutableListOf<OcrLine>()
        val consumed = mutableSetOf<Int>()
        val sorted = lines.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val indicesByBlock = sorted.indices.filter { sorted[it].blockId != null }
            .groupBy { sorted[it].blockId }
        sorted.forEachIndexed { index, line ->
            if (index in consumed) return@forEachIndexed
            var merged = line
            if (line.blockId != null) {
                repeat(2) {
                    val next = indicesByBlock[line.blockId]?.firstOrNull { candidate ->
                        candidate > index && candidate !in consumed && canJoin(merged, sorted[candidate])
                    }
                    if (next != null) {
                        val below = sorted[next]
                        consumed += next
                        // Only a very short trailing fragment is joined without a space.
                        val separator = if (below.text.length <= 3 && !below.text.contains(' ')) "" else " "
                        merged = merged.copy(
                            text = merged.text + separator + below.text,
                            rect = NormRect(min(merged.rect.left, below.rect.left), merged.rect.top,
                                max(merged.rect.right, below.rect.right), below.rect.bottom),
                            confidence = min(merged.confidence, below.confidence),
                        )
                    }
                }
            }
            result += merged
        }
        return result
    }

    private fun canJoin(first: OcrLine, second: OcrLine): Boolean {
        if (first.blockId != second.blockId || second.kindHint != null ||
            first.source != ObservationSource.OCR || second.source != ObservationSource.OCR) return false
        if (first.text.length < 8 || first.text.length + second.text.length > 32 ||
            price.classify(first.text) != null || price.classify(second.text) != null) return false
        if (first.text.any { it in ".!?。" } || second.text.any { it in ".!?。" }) return false
        val height = min(first.rect.height, second.rect.height)
        if (height <= 0f || second.rect.top < first.rect.bottom - height * 0.15f ||
            second.rect.top - first.rect.bottom > height * 0.65f) return false
        val overlap = min(first.rect.right, second.rect.right) - max(first.rect.left, second.rect.left)
        val narrower = min(first.rect.width, second.rect.width)
        return narrower > 0f && overlap / narrower >= 0.65f &&
            second.text.length <= 8 && second.text.any(Char::isLetter)
    }
}
