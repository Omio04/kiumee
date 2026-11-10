package kr.kiosklens.vision

import java.text.Normalizer
import java.util.Locale
import kotlin.math.hypot
import kr.kiosklens.vision.classify.*
import kr.kiosklens.vision.filter.*
import kr.kiosklens.vision.pipeline.OcrBlockLineMerger
import kr.kiosklens.vision.pipeline.SceneProcessor

class ElementBuilder(
    private val filters: List<TextFilter> = listOf(
        LowConfidenceFilter(), TinyAreaFilter(), MeaningfulTextFilter(),
        CardNumberFilter(), CardExpiryFilter(), PhoneNumberFilter(),
    ),
    private val classifiers: List<ElementClassifier> = listOf(
        PriceClassifier(), StatusTextClassifier(), TemperatureMenuClassifier(), ButtonLikeClassifier(),
    ),
    private val merger: OcrBlockLineMerger = OcrBlockLineMerger(),
    private val scene: SceneProcessor = SceneProcessor(),
) {
    fun filter(lines: List<OcrLine>): List<OcrLine> {
        val clean = lines.asSequence()
        .map { it.copy(text = Normalizer.normalize(it.text, Normalizer.Form.NFKC).trim().replace(spaces, " ")) }
        // Security filters see the complete string BEFORE truncation.
        .filter { line -> filters.all { it.accepts(line) } }
        .map { it.copy(text = it.text.take(ScreenElement.MAX_TEXT_LENGTH)) }
        .sortedByDescending { it.confidence }
        .distinctBy { Pair(Elements.key(it.text), it.rect) }
        .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        .toList()
        // Focus before applying the output limit, so background labels cannot crowd out a dialog.
        return scene.process(merger.merge(clean)).take(ScreenSnapshot.MAX_ELEMENTS)
    }

    fun kind(text: String): ElementKind =
        classifiers.firstNotNullOfOrNull { it.classify(text) } ?: ElementKind.TEXT

    private companion object { val spaces = Regex("\\s+") }
}

internal object Elements {
    private val builder = ElementBuilder()
    fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
    fun filter(lines: List<OcrLine>) = builder.filter(lines)
    fun kind(text: String) = builder.kind(text)
    fun distance(first: NormRect, second: NormRect): Float =
        hypot(first.centerX - second.centerX, first.centerY - second.centerY)
}

internal class ElementIds(private val builder: ElementBuilder = ElementBuilder()) {
    private var lastSeen = emptyList<ScreenElement>()
    private val reserved = mutableSetOf<String>()

    fun reset() {
        lastSeen = emptyList()
        reserved.clear()
    }

    @Suppress("UNUSED_PARAMETER")
    fun assign(screenId: String, lines: List<OcrLine>): List<ScreenElement> {
        // Normalize historical labels once per frame, rather than once per candidate pair.
        val available = lastSeen.groupBy { Elements.key(it.text) }
            .mapValues { it.value.toMutableList() }
        reserved.clear()
        reserved.addAll(lastSeen.map { it.id })
        val result = lines.map { line ->
            val candidates = available[Elements.key(line.text)]
            val match = candidates?.minByOrNull { Elements.distance(it.rect, line.rect) }
                ?.takeIf { Elements.distance(it.rect, line.rect) <= 0.06f }
            if (match != null) candidates.remove(match)
            val id = match?.id ?: newId(line)
            // Smooth small OCR box jitter; larger motion follows the actual new box.
            val box = if (match != null && Elements.distance(match.rect, line.rect) < 0.025f) {
                fun blend(old: Float, new: Float) = old * 0.35f + new * 0.65f
                NormRect(blend(match.rect.left, line.rect.left), blend(match.rect.top, line.rect.top),
                    blend(match.rect.right, line.rect.right), blend(match.rect.bottom, line.rect.bottom))
            } else line.rect
            ScreenElement(id, line.text, box, line.confidence, line.kindHint ?: builder.kind(line.text))
        }
        lastSeen = (result + available.values.flatten()).take(600)
        return result
    }

    private fun newId(line: OcrLine): String {
        val column = (line.rect.centerX * 20).toInt().coerceIn(0, 19)
        val row = (line.rect.centerY * 20).toInt().coerceIn(0, 19)
        val base = "e" + "${Elements.key(line.text)}:$column:$row".hashCode().toUInt().toString(16)
        var id = base
        var suffix = 2
        while (!reserved.add(id)) id = "${base}_${suffix++}"
        return id
    }
}
