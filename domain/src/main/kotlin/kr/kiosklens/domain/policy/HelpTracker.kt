package kr.kiosklens.domain.policy

import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 도움 모드에서 노란 칸이 카메라가 흔들려도 그 버튼을 따라가게 한다 (2026-11-13 요청: "흔들려도 잘 따라가게, 글자에 정확히").
 *
 * 예전 방식의 한계: 흔들리면 화면 번호(screenId)와 칸 번호가 바뀌는데 칸 번호로 비교해서 따라가기가 끊겼고,
 * 버튼 글자가 프레임마다 다르게 읽히면(푸드 · 부드) 같은 버튼을 못 찾았다. 사진 격자로 찾은 칸은 번호가 없어 아예 못 따라갔다.
 *
 * 순서: ① 이름으로 다시 찾기 — 깨진 글자도 자모 유사도로. 찾으면 그 글자 칸에 정확히 붙는다(사진 격자로 대략 친 칸도 글자가 읽히면 바로잡힌다).
 *       ② 주변 글자로 옮기기 — 앞 화면과 지금 화면에 함께 읽힌 글자(번호가 아니라 글자로 맞춘다)의 이동과 배율(가까워짐 · 멀어짐)만큼 옮긴다.
 *       ③ 둘 다 안 되면 그대로 둔다 (overlay 의 흔들림 보정이 프레임 사이를 메운다).
 */
object HelpTracker {
    /** 주변 글자 짝이 이만큼은 있어야 이동을 믿는다 */
    private const val MIN_ANCHORS = 2
    /** 한 번에 이보다 크게 줄거나 커지면 짝을 잘못 맞춘 것으로 본다 */
    private const val MIN_SCALE = 0.6f
    private const val MAX_SCALE = 1.7f

    data class Result(val element: UiElement?, val rect: NormRect)

    fun track(name: String?, last: NormRect?, prev: ScreenSnapshot?, now: ScreenSnapshot): Result? {
        refind(name, last, now)?.let { return Result(it, HighlightArea.of(it, now.elements)) }
        if (last == null || prev == null) return null
        return moveWithNeighbours(last, prev, now)?.let { Result(null, it) }
    }

    /** 이름이 비슷한 칸. 비슷한 칸이 여럿이면 마지막 위치에서 가장 가까운 것 (같은 이름이 여러 줄에 있는 화면) */
    fun refind(name: String?, last: NormRect?, now: ScreenSnapshot): UiElement? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        NameMatcher.find(n, now.elements)?.let { return it }
        val close = now.elements.filter { NameMatcher.score(n, it.text) >= NameMatcher.MIN_SCORE }
        if (close.isEmpty()) return null
        if (last == null) return null                                            // 위치 단서 없이 여럿 중 고르지 않는다
        return close.minByOrNull { abs(it.rect.centerX - last.centerX) + abs(it.rect.centerY - last.centerY) }
    }

    /** 앞 화면과 지금 화면에 함께 있는 글자(화면에 한 번만 나오는 글자만)의 이동 · 배율로 노란 칸을 옮긴다. */
    fun moveWithNeighbours(last: NormRect, prev: ScreenSnapshot, now: ScreenSnapshot): NormRect? {
        val before = uniqueByText(prev.elements)
        val after = uniqueByText(now.elements)
        val pairs = before.keys.intersect(after.keys).map { before.getValue(it) to after.getValue(it) }
        if (pairs.size < MIN_ANCHORS) return null
        val pcx = pairs.map { it.first.rect.centerX }.average().toFloat()
        val pcy = pairs.map { it.first.rect.centerY }.average().toFloat()
        val ncx = pairs.map { it.second.rect.centerX }.average().toFloat()
        val ncy = pairs.map { it.second.rect.centerY }.average().toFloat()
        val ratios = pairs.mapNotNull { (a, b) ->
            val da = hypot(a.rect.centerX - pcx, a.rect.centerY - pcy)
            val db = hypot(b.rect.centerX - ncx, b.rect.centerY - ncy)
            if (da > 0.02f) db / da else null
        }.sorted()
        val scale = (if (ratios.isEmpty()) 1f else ratios[ratios.size / 2]).coerceIn(MIN_SCALE, MAX_SCALE)
        val cx = ncx + scale * (last.centerX - pcx)
        val cy = ncy + scale * (last.centerY - pcy)
        val hw = last.width * scale / 2f
        val hh = last.height * scale / 2f
        val moved = NormRect(cx - hw, cy - hh, cx + hw, cy + hh)
        return moved.takeIf { it.centerX in 0f..1f && it.centerY in 0f..1f }
    }

    private fun uniqueByText(els: List<UiElement>): Map<String, UiElement> =
        els.groupBy { NameMatcher.normalize(it.text) }
            .filter { (k, v) -> k.length >= 2 && v.size == 1 }
            .mapValues { it.value.single() }
}
