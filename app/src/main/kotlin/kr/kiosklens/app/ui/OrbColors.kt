package kr.kiosklens.app.ui

/**
 * 구슬(마이크 버튼) 색. 상태가 바뀌면 [FADE_MS] 동안 부드럽게 바뀐다 (그리는 쪽과 떼어 기기 없이 테스트).
 *
 * 2026-11-13 팀 시험: "음성 인식이 되고 있는지 잘 안 보인다" → 듣는 동안은 구슬을 **빨간 녹음 색**으로.
 * 예전엔 대기 · 듣기 · 말하기가 모두 같은 하늘색이고 둘레 파동만 달라서, 눌렀는지 · 듣고 있는지 한눈에 구분이 잘 안 됐다.
 * 빨강은 녹음기 · 음성 검색의 "녹음 중" 표시로 익숙하고, 하늘색(대기) · 주황(문제) · 노랑(안내 테두리)과 겹치지 않는다.
 * 질문을 다 읽고 대답을 들을 때(ASKING → LISTENING)도 빨강으로 바뀌니 "이제 말할 차례"인 것이 보인다.
 */
object OrbColors {
    /** 구슬 가운데 · 가장자리 · 둘레 빛(숨쉬기 · 파동) */
    data class Shade(val center: Int, val edge: Int, val halo: Int)

    /** 대기 · 생각 중 · 말하는 중 · 주문 끝: 하늘색 */
    val SKY = Shade(Palette.SKY_LIGHT, Palette.SKY, Palette.RIPPLE)
    /** 듣는 중: 빨간 녹음 색 */
    val LISTEN = Shade(Palette.LISTEN_LIGHT, Palette.LISTEN, Palette.LISTEN_RIPPLE)
    /** 문제 (화면 놓침 · 오류): 주황 */
    val WARM = Shade(Palette.AMBER_LIGHT, Palette.AMBER, Palette.AMBER_LIGHT)

    /** 색이 바뀌는 데 걸리는 시간 (깜빡이지 않고 한 번 스르륵) */
    const val FADE_MS = 200L

    fun of(state: OrbState): Shade = when (state) {
        OrbState.LISTENING, OrbState.IDLE -> LISTEN      // 누를 수 있을 때(대기)와 듣는 중은 빨강, 앱이 말하거나 생각 중이면 하늘색(지금은 못 누름)
        OrbState.PROBLEM -> WARM
        else -> SKY
    }

    /** [from]에서 [to]로 바뀌기 시작한 지 [sinceMs] 지난 색 */
    fun fade(from: Shade, to: Shade, sinceMs: Long): Shade {
        val t = (sinceMs.toFloat() / FADE_MS).coerceIn(0f, 1f)
        if (t >= 1f) return to
        return Shade(mix(from.center, to.center, t), mix(from.edge, to.edge, t), mix(from.halo, to.halo, t))
    }

    /** 두 ARGB 색을 [t](0 = a, 1 = b)만큼 섞는다 */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            val c = (ca + (cb - ca) * k + 0.5f).toInt().coerceIn(0, 255)
            out = out or (c shl shift)
        }
        return out
    }
}
