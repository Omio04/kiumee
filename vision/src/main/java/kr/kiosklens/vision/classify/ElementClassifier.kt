package kr.kiosklens.vision.classify

import kr.kiosklens.vision.ElementKind

fun interface ElementClassifier {
    fun classify(text: String): ElementKind?
}

class PriceClassifier : ElementClassifier {
    private val price = Regex("""[+\-−]?\s*(?:[₩￦]\s*[0-9,]+|[0-9,]+\s*(?:원|₩|￦)|[0-9]{1,3}(?:,[0-9]{3})+)""")
    override fun classify(text: String) = ElementKind.PRICE.takeIf { price.matches(text) }
}

/** Instruction sentences, countdowns and quantities are not tappable controls. */
class StatusTextClassifier : ElementClassifier {
    private val quantity = Regex("""(?:\d+\s*(?:개|초|샷)|STEP\s*\d+|\d+)""", RegexOption.IGNORE_CASE)
    private val instruction = Regex("(주세요|하세요|하십시오|합니다|됩니다|있습니다|사용 불가|선택해|선택하신|필요여부|내려보세요)")
    private val summary = Regex("""^(?:총\s*(?:수량|결제\s*금액)|주문\s*금액|결제할\s*금액|남은\s*시간|선택한\s*상품)\s*[:：]?\s*[\d,]+""")
    private val headings = setOf("선택된옵션", "텀블러선택", "샷선택", "당도선택", "남은시간",
        "메뉴", "수량", "가격", "총수량", "총결제금액", "선택한상품", "주문금액",
        "선택옵션", "신메뉴", "베스트", "BEST", "ONLYICE")
    override fun classify(text: String): ElementKind? = ElementKind.TEXT.takeIf {
        quantity.matches(text) || instruction.containsMatchIn(text) || summary.containsMatchIn(text) ||
            text.filterNot(Char::isWhitespace) in headings || text.length > 32
    }
}

class TemperatureMenuClassifier : ElementClassifier {
    private val temperature = Regex("""^\((?:ICE|HOT)\)\s*\S+""", RegexOption.IGNORE_CASE)
    override fun classify(text: String) = ElementKind.BUTTON_LIKE.takeIf {
        text.length <= 32 && temperature.containsMatchIn(text)
    }
}

class ButtonLikeClassifier : ElementClassifier {
    private val action = Regex(
        "(선택|주문|결제|담기|취소|추가|이전|다음|확인|포장|매장|카드|현금|HOT|ICE)",
        RegexOption.IGNORE_CASE,
    )
    override fun classify(text: String): ElementKind? =
        ElementKind.BUTTON_LIKE.takeIf {
            (text.length <= 18 && action.containsMatchIn(text)) ||
                (text.length <= 16 && text.any(Char::isLetter) && text.count { it == ' ' } <= 2)
        }
}
