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
    private fun similarity(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() && b.isEmpty()) 1.0 else a.intersect(b).size.toDouble() / a.union(b).size
}
