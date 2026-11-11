package kr.kiosklens.vision

import kotlin.math.hypot

internal data class ScreenUpdate(val snapshot: ScreenSnapshot, val changed: Boolean)

/** Called only by the sequential worker. Sparse/failed frames break confirmation. */
internal class ScreenTracker(
    private val minimumElements: Int = 3,
    builder: ElementBuilder = ElementBuilder(),
) {
    private var screenNumber = 0
    private var reference = emptySet<String>()
    /** 기준 글자의 위치. 화면 일부만 바뀐 것(메뉴 위에 뜬 옵션 창)을 찾는 데 쓴다 */
    private var referenceAt = emptyMap<String, NormRect>()
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
        val at = signature(lines)
        val keys = at.keys
        // 히스테리시스: 지금 화면은 STAY만 넘으면 유지하고, 새 화면은 연속 두 프레임이 CONFIRM을 넘어야 확정한다.
        // 흐린 프레임 하나에 "새 화면 후보"가 되어 두 프레임씩 확정이 끊기던 문제 (2026-11-11 웹캠 측정)
        // 전체가 비슷해도 원래 보이던 자리 안쪽이 바뀌었으면(옵션 창이 뜨거나 닫힘) 새 화면이다 ([inner]).
        val inner = if (screenNumber == 0) Inner.NONE else inner(referenceAt, at)
        val isNew = screenNumber == 0 || similarity(reference, keys) < STAY || inner.changed
        if (isNew) {
            val candidate = pending
            pending = keys
            previous = emptyList()
            stableFrames = 0
            if (candidate == null || similarity(candidate, keys) < CONFIRM) return null
            screenNumber++
            reference = keys
            referenceAt = at
            ids.reset()
            stableFrames = 2
        } else {
            stableFrames = if (pending != null) 1 else (stableFrames + 1).coerceAtMost(Int.MAX_VALUE - 1)
            // 같은 화면을 더 많이 읽은 프레임을 새 기준으로. 단, 안쪽에 새 글자가 섞인 프레임(옵션 창이 뜨는 중)은 안 된다 —
            // 창 글자가 기준에 들어가 버리면 창이 다 뜬 뒤에도 바뀐 것을 못 알아챈다
            if (keys.size > reference.size && inner.appeared <= 1) {
                reference = keys
                referenceAt = at
            }
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
            ScreenSnapshot(screenId, frameId, timestampMs, CartRegion.demoteDuplicates(ids.assign(screenId, voted)), stableFrames),
            isNew && screenNumber > 1,
        )
    }

    /**
     * 화면을 구분하는 글자 집합. 남은 시간("57초")과, 숫자가 3개 이상인 글자(가격 · 금액 · 시각)는 뺀다:
     * 휴대폰 카메라로 찍으면 "W9,999"를 프레임마다 "W9,909" · "WS,999"처럼 제각각 읽어서 같은 화면의 유사도를
     * 크게 떨어뜨리고, 화면 구분에는 메뉴 이름 · 버튼 글자로 충분하다 (2026-11-11 웹캠 측정: 프레임당 36개를 읽어도
     * 확정 10%). 숫자뿐인 화면이라 다 빠지면 원래대로 전부 쓴다. 인식 결과(스냅샷)에서는 빼지 않는다.
     */
    private fun signature(lines: List<OcrLine>): Map<String, NormRect> {
        val all = lines.filterNot { it.text.matches(volatileCounter) }.map { Elements.key(it.text) to it.rect }
        val words = all.filterNot { (key, _) -> key.count { it.isDigit() } >= 3 }
        return words.ifEmpty { all }.toMap()
    }

    /** 원래 보이던 자리 안쪽의 변화: 처음 보는 글자가 나타난 수 · 보여야 하는데 사라진 글자 수 */
    private data class Inner(val appeared: Int, val vanished: Int) {
        /** 한쪽은 창 글자(여럿), 다른 쪽은 창에 가려졌거나 다시 드러난 메뉴 글자(적을 수 있음) */
        val changed get() = appeared >= INNER_EACH && vanished >= INNER_EACH && appeared + vanished >= INNER_TOTAL

        companion object {
            val NONE = Inner(0, 0)
        }
    }

    /**
     * 화면 일부만 바뀌었는가 (메뉴 위에 옵션 창이 뜨거나 닫힘). 창 뒤의 메뉴 글자가 그대로 읽혀서 전체 유사도로는 같은 화면이 된다
     * (2026-11-11 시험: 아메리카노를 눌러 옵션 창이 떴는데도 4분 동안 같은 안내만 되풀이).
     * 1) 기준과 지금에 함께 있는 글자로 카메라 움직임(이동 · 확대)을 맞춘다: 지금 ≈ 가운데 + 배율 × (기준 − 기준 가운데)
     * 2) 원래 보이던 영역 안쪽에 처음 보는 글자가 나타났는지, 지금 읽히는 영역 안쪽에서 있던 글자가 사라졌는지 센다.
     * 카메라를 옮기면 새 글자는 가장자리 밖에서 들어오고 사라지는 글자는 밖으로 나가므로 세지 않는다.
     * 손가락으로 가리면 사라지기만 하고 새 글자는 없다.
     */
    private fun inner(ref: Map<String, NormRect>, now: Map<String, NormRect>): Inner {
        val unmatched = (now.keys - ref.keys).toMutableList()          // 처음 보는 글자 후보 (오타 짝은 아래에서 뺀다)
        val pairs = ArrayList<Pair<NormRect, NormRect>>()
        val missing = ArrayList<NormRect>()                             // 기준에는 있는데 지금 안 보이는 글자의 (기준) 위치
        for ((key, r) in ref) {
            val hit = if (key in now) key else unmatched.firstOrNull { Elements.similar(key, it) }?.also { unmatched.remove(it) }
            when {
                hit != null -> pairs += r to now.getValue(hit)
                key.length >= 2 -> missing += r
            }
        }
        if (pairs.size < 3) return Inner.NONE                          // 움직임을 맞출 수 없음 → 전체 유사도에 맡긴다
        val rx = median(pairs.map { it.first.centerX }); val ry = median(pairs.map { it.first.centerY })
        val nx = median(pairs.map { it.second.centerX }); val ny = median(pairs.map { it.second.centerY })
        val ratios = pairs.mapNotNull { (r, n) ->
            val d = hypot(r.centerX - rx, r.centerY - ry)
            if (d < 0.05f) null else hypot(n.centerX - nx, n.centerY - ny) / d
        }
        val scale = (if (ratios.isEmpty()) 1f else median(ratios)).coerceIn(0.5f, 2f)
        fun mapX(x: Float) = nx + scale * (x - rx)
        fun mapY(y: Float) = ny + scale * (y - ry)

        val seen = ref.values                                           // 원래 보이던 영역을 지금 좌표로 옮겨 조금 안쪽만
        val seenX = (mapX(seen.minOf { it.centerX }) + MARGIN)..(mapX(seen.maxOf { it.centerX }) - MARGIN)
        val seenY = (mapY(seen.minOf { it.centerY }) + MARGIN)..(mapY(seen.maxOf { it.centerY }) - MARGIN)
        val nowX = (now.values.minOf { it.centerX } + MARGIN)..(now.values.maxOf { it.centerX } - MARGIN)
        val nowY = (now.values.minOf { it.centerY } + MARGIN)..(now.values.maxOf { it.centerY } - MARGIN)

        val appeared = unmatched.count { key ->
            val c = now.getValue(key)
            key.length >= 2 && c.centerX in seenX && c.centerY in seenY
        }
        val vanished = missing.count { r -> mapX(r.centerX) in nowX && mapY(r.centerY) in nowY }
        return Inner(appeared, vanished)
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }
    /**
     * 두 화면의 글자 집합이 얼마나 같은가 (자카드: 짝지은 수 ÷ 합친 수).
     * 정확히 같은 글자를 먼저 짝짓고, 남은 것끼리 OCR 오타 정도로 비슷한 글자([Elements.similar])를 하나씩 짝짓는다.
     * 휴대폰으로 작은 글자를 찍으면 프레임마다 "아메리카노" · "아에리카노"처럼 조금씩 다르게 읽혀서,
     * 정확히 같은 것만 세면 같은 화면인데도 0.6을 못 넘어 확정이 안 됐다 (2026-11-11 웹캠 확인).
     * 오타가 없으면 예전 자카드와 값이 같다.
     */
    private companion object {
        /** 새 화면 확정: 연속 두 프레임이 이만큼 같아야 한다 (예전 기준 그대로). */
        const val CONFIRM = 0.6
        /** 지금 화면 유지: 기준과 이만큼만 같아도 같은 화면. 진짜 화면 전환(같은 틀에 메뉴만 바뀜)은 보통 0.3대 */
        const val STAY = 0.45
        /** 안쪽 변화: 나타난 글자 · 사라진 글자가 각각 이만큼 이상이고 */
        const val INNER_EACH = 2
        /** 합쳐서 이만큼 이상이면 화면 일부가 바뀐 것 (손가락 가림 · OCR 깜빡임은 보통 한쪽만 1~3개) */
        const val INNER_TOTAL = 5
        /** 영역 가장자리는 세지 않는다 (카메라 흔들림 · 위치 맞춤 오차) */
        const val MARGIN = 0.03f
    }

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
