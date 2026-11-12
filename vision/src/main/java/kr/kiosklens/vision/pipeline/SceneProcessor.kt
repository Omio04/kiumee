package kr.kiosklens.vision.pipeline

import kotlin.math.max
import kotlin.math.min
import kr.kiosklens.vision.*

internal fun labelKey(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()
/** Heading-only tolerance. Actual OCR labels and tappable text are never rewritten. */
internal fun headingMatches(text: String, expected: String): Boolean {
    val actual=labelKey(text);val target=labelKey(expected)
    if(actual.contains(target))return true
    if(target.length<6 || actual.length<target.length)return false
    return (0..actual.length-target.length).any { start ->
        target.indices.count { actual[start+it]!=target[it] }<=1
    }
}
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
        val title = lines.firstOrNull { headingMatches(it.text,"일회용품필요여부") ||
            headingMatches(it.text,"일회용품선택") } ?: return null
        val done = lines.firstOrNull { labelKey(it.text) == "선택완료" && it.rect.top > title.rect.bottom } ?: return null
        return SceneFocus("disposable", NormRect(
            (min(title.rect.left, done.rect.left) - 0.04f).coerceAtLeast(0f),
            (title.rect.top - title.rect.height * 1.5f).coerceAtLeast(0f),
            (max(title.rect.right, done.rect.right) + 0.04f).coerceAtMost(1f),
            (done.rect.bottom + 0.04f).coerceAtMost(1f),
        ), setOf(labelKey(title.text)))
    }
}

class OrderReviewDialogRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { headingMatches(it.text,"주문세부내역") } ?: return null
        val choices = lines.filter { labelKey(it.text).let { key -> key.contains("먹고가기") || key.contains("포장하기") } }
        if (choices.size < 2) return null
        return SceneFocus("order-review", NormRect(0f,
            (title.rect.top - title.rect.height).coerceAtLeast(0f), 1f,
            (choices.maxOf { it.rect.bottom } + 0.025f).coerceAtMost(1f)))
    }
}

class PaymentDialogRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { headingMatches(it.text, "결제수단선택") } ?: return null
        if (lines.none { labelKey(it.text).startsWith("카드결제") && it.rect.top > title.rect.bottom }) return null
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
        val title = lines.firstOrNull { headingMatches(it.text, "옵션상품을모두선택") } ?: return null
        val done = lines.firstOrNull { labelKey(it.text) == "주문담기" && it.rect.top > title.rect.bottom } ?: return null
        return SceneFocus("product-options", NormRect(0f,
            (title.rect.top - title.rect.height).coerceAtLeast(0f), 1f,
            (done.rect.bottom + 0.03f).coerceAtMost(1f)))
    }
}

/** Foreground only. No choices (fulfilment, disposables, payment) are made here. */
class SceneProcessor(
    private val rules: List<SceneRule> = listOf(
        CompletionRule(), ReceiptDialogRule(), CardApprovalDialogRule(), AlertDialogRule(), LoyaltyDialogRule(), DisposableDialogRule(),
        PaymentDialogRule(), OrderReviewDialogRule(), ProductOptionsDialogRule(),
    ),
) {
    fun process(lines: List<OcrLine>): List<OcrLine> {
        val focus = detect(lines)
        val focused = if (focus == null) lines else lines.filter { focus.region.containsCenter(it) }
        val strip = focused.firstOrNull { labelKey(it.text).startsWith("선택된옵션") }
        val cart = focused.firstOrNull { labelKey(it.text) == "장바구니조회" }
        return focused.map { line ->
            val key = labelKey(line.text)
            val summary = strip != null && line.rect.left >= strip.rect.right &&
                kotlin.math.abs(line.rect.centerY - strip.rect.centerY) < max(strip.rect.height, line.rect.height)
            val cartLabel = cart != null && line.rect.top > cart.rect.bottom &&
                !Regex("(결제|삭제|취소|다음|이전)").containsMatchIn(line.text)
            val inert = key in (focus?.nonActionKeys ?: emptySet()) || key.startsWith("선택된옵션") || summary || cartLabel
            if (inert && (focus?.name == "uncertain-dialog" || line.source != ObservationSource.VERIFIED_CLOSE_ICON))
                line.copy(kindHint = ElementKind.TEXT) else line
        }
    }

    fun detect(lines: List<OcrLine>): SceneFocus? {
        val focus=rules.firstNotNullOfOrNull { it.detect(lines) }
        val headings=listOf(
            "complete" to "결제가완료되었습니다", "receipt" to "영수증을출력", "receipt" to "정상승인",
            "card-approval" to "카드결제간편결제", "alert" to "메뉴를먼저선택",
            "loyalty" to "스탬프적립", "loyalty" to "포인트적립",
            "disposable" to "일회용품필요여부", "disposable" to "일회용품선택",
            "payment" to "결제수단선택", "order-review" to "주문세부내역", "product-options" to "옵션상품을모두선택",
        )
        val hinted=headings.indexOfFirst { (_,heading)-> lines.any { headingMatches(it.text,heading) } }
        val actual=headings.indexOfFirst { it.first==if(focus?.name=="card-processing") "card-approval" else focus?.name }
        return if(hinted>=0 && (actual<0||hinted<actual))
            SceneFocus("uncertain-dialog",NormRect(0f,0f,1f,1f),lines.map { labelKey(it.text) }.toSet()) else focus
    }
}

/** Card approval is a separate stage from choosing a payment method, not proof of payment. */
class CardApprovalDialogRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { headingMatches(it.text, "카드결제간편결제") } ?: return null
        val approve = lines.firstOrNull { labelKey(it.text) in setOf("승인요청", "승인중") && it.rect.top > title.rect.bottom } ?: return null
        val processing = labelKey(approve.text) == "승인중"
        return SceneFocus(if (processing) "card-processing" else "card-approval", NormRect(0f,
            (title.rect.top - title.rect.height).coerceAtLeast(0f), 1f,
            (approve.rect.bottom + 0.04f).coerceAtMost(1f)),
            if (processing) lines.map { labelKey(it.text) }.toSet() else setOf(labelKey(title.text)))
    }
}

/** Explicit completion plus order/pickup evidence, never inferred from a card or receipt screen. */
class CompletionRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { labelKey(it.text).contains("결제가완료되었습니다") } ?: return null
        if (lines.none { it.rect.top > title.rect.bottom && labelKey(it.text).let { k ->
            k.contains("주문번호") || k.contains("픽업대") || it.text.trim().matches(Regex("[0-9]{1,6}"))
        } }) return null
        return SceneFocus("complete", NormRect(0f, 0f, 1f, 1f), lines.map { labelKey(it.text) }.toSet())
    }
}

/** Only known instructional alerts with an observed confirmation control are recognized. */
class AlertDialogRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val message = lines.firstOrNull { labelKey(it.text).let { k ->
            k.contains("메뉴를먼저선택") || k.contains("처음으로돌아가시겠습니까") ||
                k.contains("현재는한국어만지원") || k.contains("남은시간이지나")
        } } ?: return null
        val confirm = lines.firstOrNull { labelKey(it.text) == "확인" && it.rect.top > message.rect.bottom } ?: return null
        return SceneFocus("alert", NormRect(0f,
            (message.rect.top - message.rect.height).coerceAtLeast(0f), 1f,
            (confirm.rect.bottom + .03f).coerceAtMost(1f)), setOf(labelKey(message.text)))
    }
}

class ReceiptDialogRule : SceneRule {
    override fun detect(lines: List<OcrLine>): SceneFocus? {
        val title = lines.firstOrNull { headingMatches(it.text,"영수증을출력") } ?: return null
        val action = lines.firstOrNull { labelKey(it.text) == "미출력" && it.rect.top > title.rect.bottom } ?: return null
        return SceneFocus("receipt", NormRect(0f,
            (title.rect.top - title.rect.height * 2).coerceAtLeast(0f), 1f,
            (action.rect.bottom + 0.03f).coerceAtMost(1f)), setOf(labelKey(title.text)))
    }
}
