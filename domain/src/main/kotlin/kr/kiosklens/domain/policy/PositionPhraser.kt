package kr.kiosklens.domain.policy

import kr.kiosklens.core.model.NormRect

/**
 * 버튼 위치 → "화면 오른쪽 아래" 같은 문구 (설계서 7.4).
 * 중심점을 가로 · 세로 3등분해서 정한다. 가로 · 세로 모두 가운데면 "화면 가운데".
 */
class PositionPhraser {
    fun phrase(rect: NormRect): String {
        val horizontal = third(rect.centerX, "왼쪽", "가운데", "오른쪽")
        val vertical = third(rect.centerY, "위", "가운데", "아래")
        return if (horizontal == "가운데" && vertical == "가운데") "화면 가운데" else "화면 $horizontal $vertical"
    }

    private fun third(v: Float, low: String, mid: String, high: String): String = when {
        v < 1f / 3f -> low
        v < 2f / 3f -> mid
        else -> high
    }
}
