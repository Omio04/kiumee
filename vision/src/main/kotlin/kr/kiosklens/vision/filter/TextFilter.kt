package kr.kiosklens.vision.filter

import kr.kiosklens.vision.ocr.OcrBlock

/** 요소로 만들기 전에 걸러 내는 규칙 하나. 새 규칙은 이 인터페이스를 구현한 새 클래스로 추가한다. */
fun interface TextFilter {
    /** true면 남긴다. */
    fun keep(block: OcrBlock): Boolean
}

/** 카드 번호(숫자 13~19자리)와 유효기간(MM/YY)은 절대 앱 안에서도 요소로 만들지 않는다 (개인정보). */
class CardNumberFilter : TextFilter {
    private val expiry = Regex("""(?<!\d)(0[1-9]|1[0-2])\s*[/\-.]\s*(\d{2}|\d{4})(?!\d)""")
    override fun keep(block: OcrBlock): Boolean {
        val t = block.text
        val digits = t.filter { it.isDigit() }
        val onlyDigitsAndSeparators = t.all { it.isDigit() || it in " -·.*xX•" }
        if (onlyDigitsAndSeparators && digits.length in 13..19) return false
        if (digits.length in 13..19 && digits.length >= t.count { !it.isWhitespace() } - 4) return false   // 숫자가 거의 전부
        if (expiry.containsMatchIn(t) && digits.length <= 8 && t.none { it in '가'..'힣' }) return false
        return true
    }
}

class LowConfidenceFilter(private val min: Float = 0.5f) : TextFilter {
    override fun keep(block: OcrBlock) = block.confidence >= min
}

/** 너무 작은 글자(먼지 · 노이즈)와 화면 대부분을 덮는 상자를 거른다. */
class TinyAreaFilter(private val minHeight: Float = 0.008f, private val minWidth: Float = 0.01f, private val maxArea: Float = 0.6f) : TextFilter {
    override fun keep(block: OcrBlock): Boolean {
        val r = block.rect
        return r.height >= minHeight && r.width >= minWidth && r.width * r.height <= maxArea
    }
}

class BlankFilter : TextFilter {
    override fun keep(block: OcrBlock) = block.text.isNotBlank() && block.text.any { it.isLetterOrDigit() || it in '가'..'힣' }
}
