package kr.kiosklens.domain.policy

import kr.kiosklens.core.model.UiElement

/**
 * 버튼 이름 맞추기 (서버 `app/jamo.py`의 자모 n-그램 Dice와 같은 방식).
 *
 * 왜: AI는 사진에서 "주문담기"를 정확히 읽는데 글자 읽기(OCR)는 "주문달기"로 깨뜨리거나 아예 못 읽는다 (2026-11-13 실기기).
 * 서버가 "그 칸이 아직 안 읽혀요"라고 알리면 앱이 이 이름을 기억했다가, 화면 글자에 비슷한 것이 읽히는 순간 노란 칸을 친다.
 * 자모로 쪼개 비교하므로 한 글자가 틀려도(담 → 달) 가깝게 나오고, 다른 버튼(초기화 · 결제하기)과는 크게 떨어진다.
 */
object NameMatcher {
    /** 이 점수 이상이어야 같은 칸으로 본다 (주문담기 ↔ 주문달기 0.71, 다른 칸은 0.1 이하) */
    const val MIN_SCORE = 0.65
    /** 두 번째로 비슷한 칸과 이만큼 차이 나야 믿는다 (비슷한 칸이 둘이면 어느 쪽인지 모른다) */
    const val MARGIN = 0.15

    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val JONG = listOf(
        "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ",
        "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
    )

    /** 공백 · 기호를 지우고 영문은 소문자로 (서버 jamo.normalize 와 같다) */
    fun normalize(text: String): String = buildString {
        for (c in text) if (c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c in '가'..'힣' || c in 'ㄱ'..'ㅣ') append(c.lowercaseChar())
    }

    private fun toJamo(text: String): String = buildString {
        for (c in normalize(text)) {
            if (c in '가'..'힣') {
                val i = c - '가'
                append(CHO[i / 588]); append(JUNG[(i % 588) / 28])
                val j = JONG[i % 28]
                if (j.isNotEmpty()) append(j)
            } else append(c)
        }
    }

    private fun grams(s: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (n in 2..3) for (i in 0..s.length - n) out.merge(s.substring(i, i + n), 1, Int::plus)
        return out
    }

    /** 자모 n-그램 Dice 계수 (0~1) */
    fun dice(a: String, b: String): Double {
        val ga = grams(toJamo(a)); val gb = grams(toJamo(b))
        val ta = ga.values.sum(); val tb = gb.values.sum()
        if (ta == 0 || tb == 0) return 0.0
        val small = if (ga.size <= gb.size) ga else gb
        val big = if (ga.size <= gb.size) gb else ga
        var inter = 0
        for ((k, v) in small) big[k]?.let { inter += minOf(v, it) }
        return 2.0 * inter / (ta + tb)
    }

    /** 이름 하나와 한 칸의 글자가 얼마나 같은가. 칸 글자가 이름을 품으면("2,000원 결제하기" ⊃ "결제하기") 거의 같은 것으로 본다 */
    fun score(name: String, text: String): Double {
        val n = normalize(name); val t = normalize(text)
        if (n.length < 2 || t.length < 2) return 0.0
        if (n == t) return 1.0
        if (n.length >= 3 && t.contains(n)) return 0.95
        return dice(n, t)
    }

    /** 화면 글자 중 [name]과 가장 같은 칸. 확신이 없으면(점수가 낮거나 비슷한 칸이 둘) null */
    fun find(name: String, elements: List<UiElement>): UiElement? {
        val scored = elements.map { it to score(name, it.text) }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: 0.0
        return best.first.takeIf { best.second >= MIN_SCORE && best.second - second >= MARGIN }
    }
}
