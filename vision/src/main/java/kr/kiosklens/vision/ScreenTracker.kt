package kr.kiosklens.vision

internal data class ScreenUpdate(val snapshot: ScreenSnapshot, val changed: Boolean)

/** Called only by the sequential worker. Sparse/failed frames break confirmation. */
internal class ScreenTracker(
    private val minimumElements: Int = 3,
    builder: ElementBuilder = ElementBuilder(),
) {
    private var screenNumber = 0
    private var reference = emptySet<String>()
    private var pending: Set<String>? = null
    private var previous = emptyList<OcrLine>()
    private var stableFrames = 0
    private val ids = ElementIds(builder)
    private val volatileCounter = Regex("""\d+\s*초""")

    val needsConfirmation get() = pending != null || stableFrames < 2

    fun update(lines: List<OcrLine>, frameId: Long = 0L, timestampMs: Long = 0L): ScreenUpdate? {
        if (lines.size < minimumElements) {
            pending = null
            previous = emptyList()
            stableFrames = 0
            return null
        }
        val keys = signature(lines)
        val isNew = screenNumber == 0 || similarity(reference, keys) < 0.6
        if (isNew) {
            val candidate = pending
            pending = keys
            previous = emptyList()
            stableFrames = 0
            if (candidate == null || similarity(candidate, keys) < 0.6) return null
            screenNumber++
            reference = keys
            ids.reset()
            stableFrames = 2
        } else {
            stableFrames = if (pending != null) 1 else (stableFrames + 1).coerceAtMost(Int.MAX_VALUE - 1)
        }
        pending = null
        if (stableFrames < 2) {
            previous = lines
            return null
        }
        val voted = (lines + previous.filter { old ->
            lines.none { current -> Elements.distance(current.rect, old.rect) < 0.04f }
        }).take(ScreenSnapshot.MAX_ELEMENTS)
        previous = lines
        val screenId = "s$screenNumber"
        return ScreenUpdate(
            ScreenSnapshot(screenId, frameId, timestampMs, ids.assign(screenId, voted), stableFrames),
            isNew && screenNumber > 1,
        )
    }

    /**
     * 화면을 구분하는 글자 집합. 남은 시간("57초")과, 숫자가 3개 이상인 글자(가격 · 금액 · 시각)는 뺀다:
     * 휴대폰 카메라로 찍으면 "W9,999"를 프레임마다 "W9,909" · "WS,999"처럼 제각각 읽어서 같은 화면의 유사도를
     * 크게 떨어뜨리고, 화면 구분에는 메뉴 이름 · 버튼 글자로 충분하다 (2026-11-11 웹캠 측정: 프레임당 36개를 읽어도
     * 확정 10%). 숫자뿐인 화면이라 다 빠지면 원래대로 전부 쓴다. 인식 결과(스냅샷)에서는 빼지 않는다.
     */
    private fun signature(lines: List<OcrLine>): Set<String> {
        val keys = lines.filterNot { it.text.matches(volatileCounter) }.map { Elements.key(it.text) }
        val words = keys.filterNot { key -> key.count { it.isDigit() } >= 3 }
        return (words.ifEmpty { keys }).toSet()
    }
    /**
     * 두 화면의 글자 집합이 얼마나 같은가 (자카드: 짝지은 수 ÷ 합친 수).
     * 정확히 같은 글자를 먼저 짝짓고, 남은 것끼리 OCR 오타 정도로 비슷한 글자([Elements.similar])를 하나씩 짝짓는다.
     * 휴대폰으로 작은 글자를 찍으면 프레임마다 "아메리카노" · "아에리카노"처럼 조금씩 다르게 읽혀서,
     * 정확히 같은 것만 세면 같은 화면인데도 0.6을 못 넘어 확정이 안 됐다 (2026-11-11 웹캠 확인).
     * 오타가 없으면 예전 자카드와 값이 같다.
     */
    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val exact = a.intersect(b)
        val restB = (b - exact).toMutableList()
        var fuzzy = 0
        for (key in a - exact) {
            val i = restB.indexOfFirst { Elements.similar(key, it) }
            if (i >= 0) { restB.removeAt(i); fuzzy++ }
        }
        val matched = exact.size + fuzzy
        return matched.toDouble() / (a.size + b.size - matched)
    }
}
