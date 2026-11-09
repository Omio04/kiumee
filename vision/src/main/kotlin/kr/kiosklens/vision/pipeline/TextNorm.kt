package kr.kiosklens.vision.pipeline

/** 비교용 글자 정리: 글자·숫자만 남기고 소문자로. */
object TextNorm {
    fun norm(t: String): String = buildString(t.length) {
        for (c in t) if (c.isLetterOrDigit() || c in '가'..'힣' || c in 'ㄱ'..'ㅣ') append(c.lowercaseChar())
    }
}
