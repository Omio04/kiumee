package kr.kiosklens.vision

import kotlin.math.hypot

internal data class ScreenUpdate(val snapshot: ScreenSnapshot, val changed: Boolean)

/**
 * 화면을 구분할 때 두 글자를 같은 글자로 볼 것인가 (둘 다 [Elements.key] 결과).
 *
 * 짧은 글자(버튼 · 메뉴 이름)는 [Elements.similar]를 그대로 쓴다: 1~2글자 오타만 허용하고 숫자가 다르면 다른 글자다 ("1잔" ≠ "2잔").
 * 긴 글자(안내 문구 · 부제)는 카메라가 프레임마다 뒤쪽을 다른 쓰레기 글자로 읽는다
 * ("pleaseselectyourlanguage표ruttt1iti금" / "…폐륙m표rutt61i" / "…미춘rutti", 2026-11-14 실기기).
 * 허용 오타를 길이와 상관없이 2글자로 두면 같은 줄이 매번 "사라진 글자 + 처음 보는 글자"로 세어져 화면이 바뀐 것으로 착각한다.
 * 그래서 긴 글자는 길이에 비례해([LONG_TOLERANCE]) 허용하고, 그 안의 숫자는 오타로 본다 (이 길이에서는 숫자 하나로 다른 버튼이 되지 않는다).
 */
internal fun sameText(a: String, b: String): Boolean {
    if (Elements.similar(a, b)) return true
    val longest = maxOf(a.length, b.length)
    if (minOf(a.length, b.length) < LONG_TEXT || longest > MAX_COMPARE) return false
    return boundedEditDistance(a, b, (longest * LONG_TOLERANCE).toInt()) <= (longest * LONG_TOLERANCE).toInt()
}

private const val LONG_TEXT = 12
private const val MAX_COMPARE = 80
private const val LONG_TOLERANCE = 0.35

/** 편집 거리 (limit을 넘으면 limit + 1에서 멈춘다) */
private fun boundedEditDistance(a: String, b: String, limit: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        var rowMin = cur[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            rowMin = minOf(rowMin, cur[j])
        }
        if (rowMin > limit) return limit + 1
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

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
    private var pendingScene: String? = null
    private var pendingSelection: String? = null
    private var pendingInner: Inner? = null
    private var referenceScene: String? = null
    private var referenceSelection: String? = null
    private val interpreter = ScreenInterpreter()
    private var previous = emptyList<OcrLine>()
    private var stableFrames = 0
    /** 지금 화면으로 읽은 최근 프레임들의 글자 (오래된 것부터). 기준(reference)을 "계속 읽히는 글자"로 만드는 재료 */
    private val recent = ArrayDeque<Map<String, NormRect>>()
    private val ids = ElementIds(builder)
    private val volatileCounter = Regex("""\d+\s*초""")
    private val temperaturePrefix = Regex("^(?:1ce|lce|ce)(?=[가-힣])")

    val needsConfirmation get() = pending != null || stableFrames < 2

    /**
     * 진단: 이번 프레임이 "새 화면 후보"가 된 이유 (유사도 · 안쪽에서 나타나고 사라진 글자). 후보가 아니면 null.
     * 같은 화면인데 번호가 바뀔 때 어떤 글자 때문인지 시험 기록으로 알아내려고 남긴다 (2026-11-14).
     */
    internal var lastTrace: String? = null
        private set

    fun update(lines: List<OcrLine>, frameId: Long = 0L, timestampMs: Long = 0L): ScreenUpdate? {
        lastTrace = null
        val scene = interpreter.classifyLines(lines)
        if (lines.size < minimumElements && scene == null) {
            pending = null
            pendingScene = null
            pendingSelection = null
            pendingInner = null
            previous = emptyList()
            stableFrames = 0
            return null
        }
        val at = signature(lines)
        val keys = at.keys
        val selection = selectionSignature(lines, scene)
        // 히스테리시스: 지금 화면은 STAY만 넘으면 유지하고, 새 화면은 연속 두 프레임이 CONFIRM을 넘어야 확정한다.
        // 흐린 프레임 하나에 "새 화면 후보"가 되어 두 프레임씩 확정이 끊기던 문제 (2026-11-11 웹캠 측정)
        // 전체가 비슷해도 원래 보이던 자리 안쪽이 바뀌었으면(옵션 창이 뜨거나 닫힘) 새 화면이다 ([inner]).
        val inner = if (screenNumber == 0) Inner.NONE else inner(referenceAt, at)
        val structuralChange = screenNumber == 0 || containment(reference, keys) < STAY ||
            (scene != null && scene != referenceScene) ||
            (selection != null && referenceSelection != null && selection != referenceSelection)
        val isNew = structuralChange || inner.changed
        if (isNew && screenNumber > 0) {
            lastTrace = "kept=${"%.2f".format(containment(reference, keys))} structural=$structuralChange inner=${inner.changed}" +
                " keys=${keys.size} ref=${reference.size} appeared=${inner.appearedKeys.take(6)} vanished=${inner.vanishedKeys.take(6)}"
        }
        if (isNew) {
            val candidate = pending
            val candidateScene = pendingScene
            val candidateSelection = pendingSelection
            val candidateInner = pendingInner
            pending = keys
            pendingScene = scene
            pendingSelection = selection
            pendingInner = inner
            previous = emptyList()
            stableFrames = 0
            val confirmedStage = scene != null && scene != referenceScene
            if (candidate == null || candidateScene != scene || candidateSelection != selection ||
                (!confirmedStage && similarity(candidate, keys) < CONFIRM)) return null
            // Shared background can make two noisy frames look similar even though different
            // labels broke in each. Confirm the actual appeared/vanished text, not just background.
            if (!structuralChange && (candidateInner == null || !inner.confirmedBy(candidateInner))) return null
            screenNumber++
            reference = keys
            referenceAt = at
            recent.clear(); recent.addLast(at)
            referenceScene = scene
            referenceSelection = selection
            ids.reset()
            stableFrames = 2
        } else {
            stableFrames = if (pending != null) 1 else (stableFrames + 1).coerceAtMost(Int.MAX_VALUE - 1)
            // 기준 = 최근 프레임들에서 계속 읽히는 글자. 예전에는 "지금까지 본 가장 큰 프레임"으로 키웠는데, 한 번 잡음 글자
            // (화면과 같이 찍힌 키보드 · 프레임마다 다르게 깨지는 긴 줄)가 많이 읽힌 프레임이 기준이 되면 그 잡음이 박제되어
            // 평범한 프레임은 매번 기준과 안 맞았다 (2026-11-14 실기기: 정지 화면 하나가 s1→s2→s3, 기준 14개 vs 프레임 6~10개).
            // 단, 안쪽에 새 글자가 섞인 프레임(옵션 창이 뜨는 중)은 창에 넣지 않는다 — 창 글자가 기준에 들어가 버리면
            // 창이 다 뜬 뒤에도 바뀐 것을 못 알아챈다
            if (inner.appeared <= 1) {
                recent.addLast(at)
                while (recent.size > WINDOW) recent.removeFirst()
                if (recent.size < MIN_WINDOW) {
                    if (keys.size > reference.size) { reference = keys; referenceAt = at }      // 처음 몇 프레임은 더 많이 읽은 쪽으로
                } else {
                    val core = persistentCore(recent.toList())
                    if (core.size >= minimumElements) { reference = core.keys; referenceAt = core }
                }
            }
        }
        pending = null
        pendingScene = null
        pendingSelection = null
        pendingInner = null
        if (stableFrames < 2) {
            previous = lines
            return null
        }
        // An incomplete foreground dialog demotes every observed label. Do not revive old buttons.
        val canVote = lines.any { it.kindHint != ElementKind.TEXT }
        val voted = (lines + previous.filter { old -> canVote &&
            lines.none { current -> Elements.distance(current.rect, old.rect) < 0.04f ||
                Elements.similar(Elements.key(current.text), Elements.key(old.text)) }
        }).take(ScreenSnapshot.MAX_ELEMENTS)
        previous = lines
        val screenId = "s$screenNumber"
        return ScreenUpdate(
            ScreenSnapshot(screenId, frameId, timestampMs, CartRegion.demoteDuplicates(ids.assign(screenId, voted)), stableFrames),
            isNew && screenNumber > 1,
        )
    }

    /** Missing summary OCR is unknown, not proof that the user's choice disappeared. */
    private fun selectionSignature(lines: List<OcrLine>, scene: String?): String? {
        if (scene == "menu-cart") {
            val cart = lines.first { Elements.key(it.text) == "장바구니조회" }
            return lines.asSequence().filter { it.rect.top >= cart.rect.bottom }
                .map { screenKey(it.text) }.filter { it.startsWith("ice") || it.startsWith("hot") }
                .sorted().joinToString("|")
        }
        if (scene != "product-options") return null
        val strip = lines.firstOrNull { Elements.key(it.text).startsWith("선택된옵션") } ?: return null
        val inline = Elements.key(strip.text).removePrefix("선택된옵션")
        val values = lines.filter { it !== strip && it.rect.left >= strip.rect.right &&
            kotlin.math.abs(it.rect.centerY - strip.rect.centerY) < maxOf(it.rect.height, strip.rect.height) &&
            Elements.key(it.text) != "초기화" }.map { Elements.key(it.text) }
        return (listOf(inline) + values).filter(String::isNotEmpty).sorted().joinToString("|")
    }

    /**
     * 화면을 구분하는 글자 집합. 남은 시간("57초")과, 숫자가 3개 이상인 글자(가격 · 금액 · 시각)는 뺀다:
     * 휴대폰 카메라로 찍으면 "W9,999"를 프레임마다 "W9,909" · "WS,999"처럼 제각각 읽어서 같은 화면의 유사도를
     * 크게 떨어뜨리고, 화면 구분에는 메뉴 이름 · 버튼 글자로 충분하다 (2026-11-11 웹캠 측정: 프레임당 36개를 읽어도
     * 확정 10%). 숫자뿐인 화면이라 다 빠지면 원래대로 전부 쓴다. 인식 결과(스냅샷)에서는 빼지 않는다.
     */
    private fun signature(lines: List<OcrLine>): Map<String, NormRect> {
        val all = lines.filterNot { it.text.matches(volatileCounter) }.map { screenKey(it.text) to it.rect }
        val words = all.filterNot { (key, _) -> key.count { it.isDigit() } >= 3 }
        return words.ifEmpty { all }.toMap()
    }

    // Camera OCR confuses the I in ICE with 1/l or drops it. Comparison only:
    // preserve observed labels/IDs and never normalize quantities or HOT into ICE.
    private fun screenKey(text: String) = Elements.key(text)
        .replace(temperaturePrefix, "ice")

    /** 원래 보이던 자리 안쪽의 변화: 처음 보는 글자가 나타난 수 · 보여야 하는데 사라진 글자 수 */
    private data class Inner(val appearedKeys: Set<String>, val vanishedKeys: Set<String>) {
        val appeared get() = appearedKeys.size
        val vanished get() = vanishedKeys.size
        /** 한쪽은 창 글자(여럿), 다른 쪽은 창에 가려졌거나 다시 드러난 메뉴 글자(적을 수 있음) */
        val changed get() = appeared >= INNER_EACH && vanished >= INNER_EACH && appeared + vanished >= INNER_TOTAL

        fun confirmedBy(other: Inner): Boolean {
            val appeared = appearedKeys.count { key -> other.appearedKeys.any { sameText(key, it) } }
            val vanished = vanishedKeys.count { key -> other.vanishedKeys.any { sameText(key, it) } }
            return appeared >= INNER_EACH && vanished >= INNER_EACH && appeared + vanished >= INNER_TOTAL
        }

        companion object {
            val NONE = Inner(emptySet(), emptySet())
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
        val missing = LinkedHashMap<String, NormRect>()                  // 기준에는 있는데 지금 안 보이는 글자의 (기준) 위치
        for ((key, r) in ref) {
            val hit = if (key in now) key else unmatched.firstOrNull { sameText(key, it) }?.also { unmatched.remove(it) }
            when {
                hit != null -> pairs += r to now.getValue(hit)
                key.length >= 2 -> missing[key] = r
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

        val appeared = unmatched.filter { key ->
            val c = now.getValue(key)
            key.length >= 2 && c.centerX in seenX && c.centerY in seenY
        }
        val vanished = missing.filterValues { r -> mapX(r.centerX) in nowX && mapY(r.centerY) in nowY }.keys
        return Inner(appeared.toSet(), vanished)
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
        /** 지금 화면 유지: 기준(계속 읽히던 글자)의 이만큼이 아직 보이면 같은 화면. 진짜 화면 전환은 기준 글자가 대부분 사라진다 */
        const val STAY = 0.5
        /** 기준을 만드는 최근 프레임 수 · 최소 프레임 수 · 이 비율 이상의 프레임에서 읽혀야 계속 읽히는 글자 */
        const val WINDOW = 12
        const val MIN_WINDOW = 3
        const val PERSIST = 0.6
        /** 안쪽 변화: 나타난 글자 · 사라진 글자가 각각 이만큼 이상이고 */
        const val INNER_EACH = 2
        /** 합쳐서 이만큼 이상이면 화면 일부가 바뀐 것 (손가락 가림 · OCR 깜빡임은 보통 한쪽만 1~3개) */
        const val INNER_TOTAL = 5
        /** 영역 가장자리는 세지 않는다 (카메라 흔들림 · 위치 맞춤 오차) */
        const val MARGIN = 0.03f
    }

    /** a의 글자 중 b에도 있는 것의 수 (정확히 같은 것을 먼저, 남은 것은 [sameText]로 하나씩 짝짓는다) */
    private fun matchedCount(a: Set<String>, b: Set<String>): Int {
        val exact = a.intersect(b)
        val restB = (b - exact).toMutableList()
        var fuzzy = 0
        for (key in a - exact) {
            val i = restB.indexOfFirst { sameText(key, it) }
            if (i >= 0) { restB.removeAt(i); fuzzy++ }
        }
        return exact.size + fuzzy
    }

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val matched = matchedCount(a, b)
        return matched.toDouble() / (a.size + b.size - matched)
    }

    /**
     * 기준의 글자 중 지금 프레임에도 보이는 비율. 지금 프레임에 기준에 없는 글자(키보드 · 깨진 글자)가 더 있어도 깎이지 않는다:
     * "이 화면의 계속 읽히던 글자가 아직 보이는가"가 같은 화면인지의 질문이고, 잡음이 얼마나 섞였는지는 상관없다.
     */
    private fun containment(reference: Set<String>, now: Set<String>): Double =
        if (reference.isEmpty()) 1.0 else matchedCount(reference, now).toDouble() / reference.size

    /**
     * 최근 프레임들에서 계속 읽힌 글자와 그 최근 위치. 프레임 수의 [PERSIST] 이상(최소 2프레임)에서 읽힌 글자만 남긴다.
     * 깨진 글자 변형은 [sameText]로 같은 글자로 묶어서 센다.
     */
    private fun persistentCore(frames: List<Map<String, NormRect>>): Map<String, NormRect> {
        val need = if (frames.size <= 2) 1 else maxOf(2, kotlin.math.ceil(frames.size * PERSIST).toInt())
        val reps = ArrayList<String>(); val counts = ArrayList<Int>(); val where = ArrayList<NormRect>()
        for (frame in frames) {
            val counted = HashSet<Int>()
            for ((key, rect) in frame) {
                val i = reps.indexOfFirst { sameText(it, key) }
                val idx = if (i >= 0) i else { reps += key; counts += 0; where += rect; reps.lastIndex }
                if (counted.add(idx)) { counts[idx] = counts[idx] + 1; where[idx] = rect }
            }
        }
        return reps.indices.filter { counts[it] >= need }.associate { reps[it] to where[it] }
    }
}
