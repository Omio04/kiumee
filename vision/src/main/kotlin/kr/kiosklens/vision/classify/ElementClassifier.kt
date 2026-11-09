package kr.kiosklens.vision.classify

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.vision.ocr.OcrBlock

/** 글자 하나가 어떤 종류인지 판단. 모르면 null (다음 분류기에게 넘김). */
fun interface ElementClassifier {
    fun classify(block: OcrBlock): ElementKind?
}

/** 4,500원 · ₩4500 · 4.5천원 같은 가격 */
class PriceClassifier : ElementClassifier {
    private val price = Regex("""^\s*(₩\s*)?\d{1,3}([,.]\d{3})*\s*(원|₩|won)?\s*$|^\s*\d+(\.\d+)?\s*(천|만)\s*원\s*$""", RegexOption.IGNORE_CASE)
    override fun classify(block: OcrBlock): ElementKind? {
        val t = block.text.trim()
        if (t.endsWith("원") && t.dropLast(1).trim().let { it.isNotEmpty() && it.all { c -> c.isDigit() || c == ',' || c == '.' || c == ' ' } }) return ElementKind.PRICE
        if (t.startsWith("₩") || (t.contains(',') && price.matches(t) && t.any { it.isDigit() } && t.length >= 5)) return ElementKind.PRICE
        return null
    }
}

/**
 * 누를 수 있어 보이는 글자: 짧고, 문장이 아니다.
 * 키오스크 버튼은 "아메리카노", "HOT", "담기", "장바구니 보기"처럼 짧다. 안내 문장("원하시는 메뉴를 선택하세요")은 버튼이 아니다.
 */
class ButtonLikeClassifier(private val maxChars: Int = 16, private val maxWords: Int = 4) : ElementClassifier {
    private val sentenceEnd = Regex("""(세요|하세요|주세요|입니다|합니다|됩니다|십시오|니다|해요|에요|예요|[.!?。])\s*$""")
    override fun classify(block: OcrBlock): ElementKind? {
        val t = block.text.trim()
        val compact = t.filter { !it.isWhitespace() }
        if (compact.isEmpty()) return null
        if (compact.length > maxChars || t.split(Regex("\\s+")).size > maxWords) return ElementKind.TEXT
        if (sentenceEnd.containsMatchIn(t)) return ElementKind.TEXT
        return ElementKind.BUTTON_LIKE
    }
}

/** 분류기를 차례로 물어 처음 답한 것을 쓴다. 아무도 모르면 TEXT. */
class ClassifierChain(private val chain: List<ElementClassifier>) : ElementClassifier {
    override fun classify(block: OcrBlock): ElementKind? = chain.firstNotNullOfOrNull { it.classify(block) } ?: ElementKind.TEXT
    companion object { fun default() = ClassifierChain(listOf(PriceClassifier(), ButtonLikeClassifier())) }
}
