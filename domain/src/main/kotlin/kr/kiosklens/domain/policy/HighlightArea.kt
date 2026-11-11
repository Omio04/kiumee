package kr.kiosklens.domain.policy

import kotlin.math.abs
import kotlin.math.max
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
