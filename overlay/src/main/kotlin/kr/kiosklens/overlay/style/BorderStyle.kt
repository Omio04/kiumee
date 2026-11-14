package kr.kiosklens.overlay.style

/**
 * 노란 안내 테두리의 굵기 · 진하기 (Android 타입 없이 숫자만 — JVM에서 테스트한다).
 *
 * 2026-11-13 (이후) 어르신 요청: "어르신들은 안 보이니까 노란 칸을 키워서 뭘 눌러야 하는지 명확하게" → 다시 **또렷하게**:
 *       노란 선 7dp + 양쪽 어두운 선 2dp = 전체 11dp, 거의 불투명(0.85~1.0), 버튼에서 5dp 띄워 칸이 더 크게 보인다.
 *       아래 "연하게" 설명은 그 직전의 값이다 (팀 시험의 "옆 글씨를 가린다"와 부딪히므로, 이 값이 옆 글씨를 너무 가리면
 *       굵기보다 먼저 아래 GAP_DP · RING_ALPHA_MIN을 조정할 것).
 * 2026-11-13 팀 시험: "네모박스 색이 너무 진해서 다른 글씨들도 가린다" → 연하게.
 * 예전: 노란 선 약 6~7dp(거의 불투명) 밑에 검은 선 약 11dp + 버튼 안쪽 노란 칠 → 버튼 둘레 글씨까지 덮었다.
 * 지금: 노란 선 4dp 반투명 + 양쪽에 얇은 어두운 선 1dp(반투명) = **전체 6dp**(설계서 10장 "6dp 이상"은 지킴),
 *       안쪽 칠 없음, 버튼 글자와 2dp 띄움. 깜빡임은 굵기가 아니라 진하기로만 (굵기가 변하면 옆 글씨를 덮었다 말았다 한다).
 */
object BorderStyle {
    /** 노란 선 굵기 */
    const val RING_DP = 7f
    /** 노란 선 양쪽의 어두운 선 굵기 (밝은 배경에서도 테두리가 보이게) */
    const val EDGE_DP = 2f
    /** 버튼 네모와 테두리 안쪽 끝 사이 거리 (버튼 글자를 덮지 않게) */
    const val GAP_DP = 3f
    /**
     * 칸을 글자 영역보다 **넓게** 잡는 정도 (넓이 기준 배수). 2026-11-13 어르신 요청: "글씨를 안 가리게 정확한 글자 칸의 두 배쯤 넓게,
     * 선은 얇으면 안 보이니 또렷하게". 칸이 글자 바깥으로 넓어지니 굵은 선이 글자를 덮지 않는다.
     */
    const val AREA_SCALE = 2f
    /** 한쪽으로 넓어지는 최대 거리 (큰 칸이 옆 칸까지 크게 번지지 않게) */
    const val MAX_GROW_DP = 28f

    /** 가로 · 세로 배율 (넓이 배수의 제곱근) */
    val LINEAR_SCALE: Float = Math.sqrt(AREA_SCALE.toDouble()).toFloat()

    /**
     * 글자 영역([width] x [height] px)의 넓이가 [AREA_SCALE]배가 되도록 **한쪽마다** 넓힐 거리 (가로, 세로) px.
     * 한쪽 최대 [MAX_GROW_DP]를 넘지 않는다. 좌표 변환 없이 숫자만이라 JVM에서 테스트한다.
     */
    fun grow(width: Float, height: Float, density: Float): Pair<Float, Float> {
        val limit = MAX_GROW_DP * density
        return Pair(
            (width * (LINEAR_SCALE - 1f) / 2f).coerceIn(0f, limit),
            (height * (LINEAR_SCALE - 1f) / 2f).coerceIn(0f, limit),
        )
    }

    /** 모서리 둥글기 */
    const val RADIUS_DP = 8f

    /** 테두리 전체 두께 (어두운 선 + 노란 선 + 어두운 선) */
    const val BAND_DP = RING_DP + 2 * EDGE_DP
    /** 버튼 네모에서 노란 선 가운데까지 거리 */
    const val PAD_DP = GAP_DP + BAND_DP / 2f

    /** 어두운 선 진하기 (0~1) */
    const val EDGE_ALPHA = 0.7f
    /** 노란 선 진하기: 깜빡임이 가장 약할 때 · 강할 때 (1보다 작아 아래 글씨가 비친다) */
    const val RING_ALPHA_MIN = 0.85f
    const val RING_ALPHA_MAX = 1.0f

    /** 깜빡임 정도([PulseCurve.level], 0.55~1.0) → 노란 선 진하기 */
    fun ringAlpha(level: Float): Float {
        val t = ((level - PulseCurve.MIN_LEVEL) / (1f - PulseCurve.MIN_LEVEL)).coerceIn(0f, 1f)
        return RING_ALPHA_MIN + (RING_ALPHA_MAX - RING_ALPHA_MIN) * t
    }
}
