package kr.kiosklens.vision.filter

import kr.kiosklens.vision.OcrLine
import kr.kiosklens.vision.ObservationSource

fun interface TextFilter {
    fun accepts(line: OcrLine): Boolean
}

class LowConfidenceFilter(private val minimum: Float = 0.35f) : TextFilter {
    override fun accepts(line: OcrLine) = line.confidence in minimum..1f
}

class TinyAreaFilter(private val minimum: Float = 0.00008f) : TextFilter {
    override fun accepts(line: OcrLine) =
        line.rect.isValid && line.rect.width * line.rect.height >= minimum
}

class MeaningfulTextFilter : TextFilter {
    override fun accepts(line: OcrLine) = line.text.any { it.isLetterOrDigit() } ||
        (line.source == ObservationSource.VERIFIED_CLOSE_ICON && line.text == "×")
}

class CardNumberFilter : TextFilter {
    private val number = Regex("""\p{Nd}(?:[\p{Z}\s\p{Pd}]*\p{Nd}){12,}""")
    override fun accepts(line: OcrLine) = !number.containsMatchIn(line.text)
}

class CardExpiryFilter : TextFilter {
    private val expiry = Regex("""(?<!\d)(?:0[1-9]|1[0-2])\s*[/／-]\s*(?:\d{4}|\d{2})(?!\d)""")
    override fun accepts(line: OcrLine) = !expiry.containsMatchIn(line.text)
}

class PhoneNumberFilter : TextFilter {
    private val phone = Regex("""(?<!\d)01[016789][\s-]*\d{3,4}[\s-]*\d{4}(?!\d)""")
    override fun accepts(line: OcrLine) = !phone.containsMatchIn(line.text)
}
