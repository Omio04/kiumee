package kr.kiosklens.overlay.style

/**
 * 노란 안내 테두리의 굵기 · 진하기 (Android 타입 없이 숫자만 — JVM에서 테스트한다).
 *
 * 2026-11-13 팀 시험: "네모박스 색이 너무 진해서 다른 글씨들도 가린다" → 연하게.
 * 예전: 노란 선 약 6~7dp(거의 불투명) 밑에 검은 선 약 11dp + 버튼 안쪽 노란 칠 → 버튼 둘레 글씨까지 덮었다.
 * 지금: 노란 선 4dp 반투명 + 양쪽에 얇은 어두운 선 1dp(반투명) = **전체 6dp**(설계서 10장 "6dp 이상"은 지킴),
 *       안쪽 칠 없음, 버튼 글자와 2dp 띄움. 깜빡임은 굵기가 아니라 진하기로만 (굵기가 변하면 옆 글씨를 덮었다 말았다 한다).
 */
object BorderStyle {
    /** 노란 선 굵기 */
    const val RING_DP = 4f
    /** 노란 선 양쪽의 어두운 선 굵기 (밝은 배경에서도 테두리가 보이게) */
    const val EDGE_DP = 1f
    /** 버튼 네모와 테두리 안쪽 끝 사이 거리 (버튼 글자를 덮지 않게) */
    const val GAP_DP = 2f
    /** 모서리 둥글기 */
    const val RADIUS_DP = 8f

    /** 테두리 전체 두께 (어두운 선 + 노란 선 + 어두운 선) */
    const val BAND_DP = RING_DP + 2 * EDGE_DP
    /** 버튼 네모에서 노란 선 가운데까지 거리 */
    const val PAD_DP = GAP_DP + BAND_DP / 2f
    /** 버튼 네모 밖으로 테두리가 나가는 최대 거리 (옆 버튼 글씨를 덮는 정도) */
    const val OUTER_DP = GAP_DP + BAND_DP

    /** 어두운 선 진하기 (0~1) */
    const val EDGE_ALPHA = 0.45f
    /** 노란 선 진하기: 깜빡임이 가장 약할 때 · 강할 때 (1보다 작아 아래 글씨가 비친다) */
    const val RING_ALPHA_MIN = 0.6f
    const val RING_ALPHA_MAX = 0.85f

    /** 깜빡임 정도([PulseCurve.level], 0.55~1.0) → 노란 선 진하기 */
    fun ringAlpha(level: Float): Float {
        val t = ((level - PulseCurve.MIN_LEVEL) / (1f - PulseCurve.MIN_LEVEL)).coerceIn(0f, 1f)
        return RING_ALPHA_MIN + (RING_ALPHA_MAX - RING_ALPHA_MIN) * t
    }
}
