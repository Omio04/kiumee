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

    /**
     * OCR 오타를 넘어 같은 글자로 볼 만큼 비슷한가 (둘 다 [key] 결과).
     * - 같으면 같다. 3글자 이상끼리는 1글자 틀림까지, 8글자 이상이면 2글자까지 같다고 본다 ("아메리카노" ≈ "아에리카노")
     * - 숫자가 다르면 다른 글자다 ("1잔" ≠ "2잔", "메뉴항목1" ≠ "메뉴항목2"): 숫자는 서로 다른 버튼 · 값을 뜻한다
     */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 3 || b.length < 3) return false
        if (a.filter { it.isDigit() } != b.filter { it.isDigit() }) return false
        val allowed = if (maxOf(a.length, b.length) >= 8) 2 else 1
        return editDistance(a, b, allowed) <= allowed
    }

    /** 편집 거리 (limit을 넘으면 limit + 1에서 멈춤). */
    private fun editDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
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
