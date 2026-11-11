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

    private fun signature(lines: List<OcrLine>) = lines
        .filterNot { it.text.matches(volatileCounter) }
        .map { Elements.key(it.text) }.toSet()
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
