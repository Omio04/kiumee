package kr.kiosklens.vision.pipeline

import kotlin.math.max
import kotlin.math.min
import kr.kiosklens.vision.*

internal fun labelKey(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()
internal fun NormRect.containsCenter(line: OcrLine) =
    line.rect.centerX in left..right && line.rect.centerY in top..bottom

data class SceneFocus(val name: String, val region: NormRect, val nonActionKeys: Set<String> = emptySet())

fun interface SceneRule {
    fun detect(lines: List<OcrLine>): SceneFocus?
}

/** Requires a title, numeric keypad and an explicit earn button. A menu label alone is insufficient. */
class LoyaltyDialogRule(private val skipLoyalty: Boolean = true) : SceneRule {
    private val keyRow = Regex("""[0-9](?:\s*[0-9]){0,2}""")
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { labelKey(it.text) in setOf("스탬프적립", "포인트적립") } ?: return null
        val action = lines.firstOrNull { labelKey(it.text) == "적립" && it.rect.top > title.rect.bottom } ?: return null
        val digitRows = lines.filter { keyRow.matches(it.text) &&
            it.rect.top > title.rect.bottom && it.rect.bottom < action.rect.top }
        if (digitRows.flatMap { it.text.filter(Char::isDigit).toList() }.toSet().size < 6) return null
        // ML Kit may return individual keys or one line such as "1 2 3".
        // Estimated cell centres are used only to bound the dialog, never as tappable controls.
        val centers = digitRows.flatMap { row ->
            val count = row.text.count(Char::isDigit)
            (0 until count).map { row.rect.left + row.rect.width * (it + 0.5f) / count }
        }.sorted()
        val span = centers.last() - centers.first()
        if (span < 0.12f) return null
        val icons = lines.filter { it.source == ObservationSource.VERIFIED_CLOSE_ICON }
        val region = NormRect(
            (centers.first() - span * 0.3f).coerceAtLeast(0f),
            (min(title.rect.top, icons.minOfOrNull { it.rect.top } ?: title.rect.top) - title.rect.height).coerceAtLeast(0f),
            max(centers.last() + span * 0.3f, icons.maxOfOrNull { it.rect.right + 0.02f } ?: 0f).coerceAtMost(1f),
            (action.rect.bottom + 0.09f).coerceAtMost(1f),
        )
        val nonActions = setOf("스탬프적립", "포인트적립", "010") +
            if (skipLoyalty) (0..9).map(Int::toString).toSet() + setOf("적립") else emptySet()
        return SceneFocus("loyalty", region, nonActions)
    }
}

class DisposableDialogRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { labelKey(it.text).contains("일회용품필요여부") } ?: return null
        val done = lines.firstOrNull { labelKey(it.text) == "선택완료" && it.rect.top > title.rect.bottom } ?: return null
        return SceneFocus("disposable", NormRect(
            (title.rect.left - 0.04f).coerceAtLeast(0f),
            (title.rect.top - title.rect.height * 1.5f).coerceAtLeast(0f),
            (title.rect.right + 0.04f).coerceAtMost(1f),
            (done.rect.bottom + 0.04f).coerceAtMost(1f),
        ))
    }
}

class OrderReviewDialogRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { labelKey(it.text).contains("주문세부내역") } ?: return null
        val choices = lines.filter { labelKey(it.text).let { key -> key.contains("먹고가기") || key.contains("포장하기") } }
        if (choices.size < 2) return null
        return SceneFocus("order-review", NormRect(0f,
            (title.rect.top - title.rect.height).coerceAtLeast(0f), 1f,
            (choices.maxOf { it.rect.bottom } + 0.025f).coerceAtMost(1f)))
    }
}

class PaymentDialogRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { labelKey(it.text) == "결제수단선택" } ?: return null
        if (lines.none { labelKey(it.text).startsWith("카드결제") }) return null
        val total = lines.filter { labelKey(it.text).let { key ->
            key.contains("결제할금액") || key == "주문금액"
        } }.maxOfOrNull { it.rect.bottom } ?: return null
        return SceneFocus("payment", NormRect(0f,
            (title.rect.top - title.rect.height).coerceAtLeast(0f), 1f,
            (total + 0.04f).coerceAtMost(1f)), setOf("결제수단선택"))
    }
}

class ProductOptionsDialogRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { labelKey(it.text).contains("옵션상품을모두선택") } ?: return null
        val done = lines.firstOrNull { labelKey(it.text) == "주문담기" } ?: return null
        return SceneFocus("product-options", NormRect(0f,
            (title.rect.top - title.rect.height).coerceAtLeast(0f), 1f,
            (done.rect.bottom + 0.03f).coerceAtMost(1f)))
    }
}

/** Foreground only. No choices (fulfilment, disposables, payment) are made here. */
class SceneProcessor(
    private val rules: List<SceneRule> = listOf(
        LoyaltyDialogRule(), DisposableDialogRule(), OrderReviewDialogRule(),
        PaymentDialogRule(), ProductOptionsDialogRule(),
    ),
) {
    fun process(lines: List<OcrLine>): List<OcrLine> {
        val focus = rules.firstNotNullOfOrNull { it.detect(lines) }
        val focused = if (focus == null) lines else lines.filter { focus.region.containsCenter(it) }
        val strip = focused.firstOrNull { labelKey(it.text) == "선택된옵션" }
        val cart = focused.firstOrNull { labelKey(it.text) == "장바구니조회" }
        return focused.map { line ->
            val key = labelKey(line.text)
            val summary = strip != null && line.rect.left >= strip.rect.right &&
                kotlin.math.abs(line.rect.centerY - strip.rect.centerY) < max(strip.rect.height, line.rect.height)
            val cartLabel = cart != null && line.rect.top > cart.rect.bottom &&
                !Regex("(결제|삭제|취소|다음|이전)").containsMatchIn(line.text)
            val inert = key in (focus?.nonActionKeys ?: emptySet()) || summary || cartLabel
            if (inert && line.source != ObservationSource.VERIFIED_CLOSE_ICON) line.copy(kindHint = ElementKind.TEXT) else line
        }
    }
}
