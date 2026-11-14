package kr.kiosklens.domain.policy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement

/**
 * 노란 테두리를 어디에 그릴지 (OCR은 글자 상자만 준다).
 *
 * [of] — **메뉴 칸 전체**: 이름 바로 아래 가격이 있는 "그림 + 이름 + 가격" 칸이 격자로 놓여 있으면
 * 옆 칸 · 위아래 칸과의 간격으로 칸을 추정해 칸 전체를 감싼다 (이름 · 가격은 칸 아래쪽, 그림은 위쪽).
 * 탭("커피") · "주문하기"처럼 가격이 없는 버튼이나 격자가 아니면 글자 상자 그대로.
 *
 * [shift] — **가려졌을 때**: 손가락 등으로 목표 글자가 안 읽히면 같이 보이는 다른 요소들이 움직인 만큼(중간값)
 * 마지막 위치를 옮긴다. 단서가 2개 미만이거나 화면 밖이면 null (틀린 자리를 가리키느니 숨긴다).
 */
object HighlightArea {
    fun of(target: UiElement, all: List<UiElement>): NormRect {
        val t = target.rect
        val price = priceBelow(target, all) ?: return t
        val peers = all.filter { it.id != target.id && it.kind == ElementKind.BUTTON_LIKE && priceBelow(it, all) != null }
        val dxs = peers.filter { abs(it.rect.centerY - t.centerY) < t.height }
            .map { abs(it.rect.centerX - t.centerX) }.filter { it > t.width * 0.5f }
        val dys = peers.filter { abs(it.rect.centerX - t.centerX) < t.width * 0.6f }
            .map { abs(it.rect.centerY - t.centerY) }.filter { it > t.height }
        if (dxs.isEmpty() && dys.isEmpty()) return t
        val halfW = max(dxs.minOrNull()?.let { it * 0.46f } ?: (t.width * 0.7f), t.width / 2f)
        val height = dys.minOrNull()?.let { it * 0.92f } ?: (halfW * 2f * 1.15f)
        val bottom = max(t.bottom, price.rect.bottom) + t.height * 0.15f
        return NormRect(
            (t.centerX - halfW).coerceIn(0f, 1f), (bottom - height).coerceIn(0f, 1f),
            (t.centerX + halfW).coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
        )
    }

    /**
     * 서버가 사진에서 본 **버튼 전체 칸**을 쓸 수 있나 (2026-11-13 요청: "버튼을 다 감싸듯이").
     * 글자 상자의 가운데가 칸 안에 있어야 한다: 사진과 글자 읽기는 다른 순간이라 휴대폰이 움직였으면 어긋난다.
     * 쓸 수 있으면 글자 상자까지 품은 칸, 아니면 null (글자 상자 · 메뉴 칸으로 그린다).
     */
    fun button(box: NormRect, text: NormRect): NormRect? {
        if (!box.isValid) return null
        val s = BUTTON_SLACK
        if (text.centerX !in (box.left - s)..(box.right + s) || text.centerY !in (box.top - s)..(box.bottom + s)) return null
        val l = min(box.left, text.left)
        val t = min(box.top, text.top)
        val r = max(box.right, text.right)
        val b = max(box.bottom, text.bottom)
        // 2026-11-14 요청: 버튼 칸이 글자의 5배를 넘게 커 보임 → 글자 상자의 가로 · 세로 BUTTON_MAX_RATIO배까지만. 글자 가운데를 기준으로 줄이되 원래 칸 밖으로는 안 나간다
        val (cl, cr) = capAxis(l, r, text.left, text.right)
        val (ct, cb) = capAxis(t, b, text.top, text.bottom)
        return NormRect(cl, ct, cr, cb)
    }

    /** [lo]~[hi] 구간을 글자 구간 길이의 BUTTON_MAX_RATIO배 이하로 줄인다. 글자 구간은 항상 안에 남는다. */
    private fun capAxis(lo: Float, hi: Float, textLo: Float, textHi: Float): Pair<Float, Float> {
        val maxLen = (textHi - textLo) * BUTTON_MAX_RATIO
        if (hi - lo <= maxLen) return lo to hi
        val center = (textLo + textHi) / 2f
        val newLo = (center - maxLen / 2f).coerceIn(lo, hi - maxLen)
        return newLo to (newLo + maxLen)
    }

    /**
     * 버튼 전체 칸 따라가기: 칸을 처음 글자 상자([anchor])에 붙여 두고, 지금 글자 상자([now])가 움직이고 커진 만큼 칸도 옮기고 키운다.
     * 배율은 글자 높이의 비 (한 번에 크게 달라지면 잘못 읽은 것이라 0.6~1.7배로 묶는다).
     */
    fun follow(button: NormRect, anchor: NormRect, now: NormRect): NormRect {
        val scale = if (anchor.height > 0f) (now.height / anchor.height).coerceIn(MIN_SCALE, MAX_SCALE) else 1f
        fun x(v: Float) = (now.centerX + (v - anchor.centerX) * scale).coerceIn(0f, 1f)
        fun y(v: Float) = (now.centerY + (v - anchor.centerY) * scale).coerceIn(0f, 1f)
        return NormRect(x(button.left), y(button.top), x(button.right), y(button.bottom))
    }

    private const val BUTTON_SLACK = 0.01f
    private const val BUTTON_MAX_RATIO = 2f
    private const val MIN_SCALE = 0.6f
    private const val MAX_SCALE = 1.7f

    fun shift(last: NormRect?, prev: ScreenSnapshot?, now: ScreenSnapshot, targetId: String): NormRect? {
        if (last == null || prev == null) return null
        val moves = now.elements.mapNotNull { e ->
            if (e.id == targetId) null
            else prev.findElement(e.id)?.let { p -> Pair(e.rect.centerX - p.rect.centerX, e.rect.centerY - p.rect.centerY) }
        }
        if (moves.size < 2) return null
        val dx = moves.map { it.first }.sorted()[moves.size / 2]
        val dy = moves.map { it.second }.sorted()[moves.size / 2]
        val moved = NormRect(last.left + dx, last.top + dy, last.right + dx, last.bottom + dy)
        return moved.takeIf { it.centerX in 0f..1f && it.centerY in 0f..1f }
    }

    /** 이 글자 바로 아래(글자 높이 1.5배 안) 겹치는 가격이 있으면 메뉴 칸이다. */
    private fun priceBelow(e: UiElement, all: List<UiElement>): UiElement? = all.firstOrNull { p ->
        p.kind == ElementKind.PRICE &&
            p.rect.top >= e.rect.bottom - e.rect.height * 0.3f &&
            p.rect.top <= e.rect.bottom + e.rect.height * 1.5f &&
            abs(p.rect.centerX - e.rect.centerX) < max(e.rect.width, p.rect.width)
    }
}
