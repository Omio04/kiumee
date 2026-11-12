package kr.kiosklens.app.ui

/**
 * 마이크 버튼 둘레 파동 계산. 그리는 쪽([MicButtonView])과 떼어 기기 없이 테스트한다.
 *
 * 고리 [RINGS]개가 1/[RINGS]씩 늦게 출발해 버튼 가장자리에서 바깥으로 퍼지며 옅어진다.
 * 조용해도 조금은 퍼지고(듣는 중이라는 표시), 목소리가 크면 더 멀리 · 더 진하게 퍼진다.
 */
object MicRipple {
    /** 동시에 퍼지는 고리 수 */
    const val RINGS = 3

    /** 고리 하나가 버튼에서 끝까지 퍼지는 시간 */
    const val PERIOD_MS = 1800L

    /** 가장 큰 파동의 반지름 ÷ 버튼 반지름. 뷰 크기도 이걸로 정한다 (버튼 지름 × 이 값) */
    const val MAX_SCALE = 1.8f

    /** 고리 하나: 반지름 배율(버튼 반지름 기준) · 진하기(0~1) */
    data class Ring(val scale: Float, val alpha: Float)

    /**
     * @param i 몇 번째 고리 (0부터)
     * @param t 0~1을 반복하는 시간
     * @param level 목소리 크기 0~1
     */
    fun ring(i: Int, t: Float, level: Float): Ring {
        val p = ((t + i.toFloat() / RINGS) % 1f + 1f) % 1f          // 이 고리가 얼마나 퍼졌나 0~1
        val l = level.coerceIn(0f, 1f)
        val reach = 0.55f + 0.45f * l                                 // 조용함 55% ~ 큰 소리 100%까지 퍼짐
        val scale = 1f + (MAX_SCALE - 1f) * reach * p
        // 퍼질수록 옅게, 큰 소리일수록 진하게. 반투명한 밝은 색은 어두운 카메라 화면 위에서 회색으로 섞이므로 진하게 시작한다
        val alpha = (1f - p) * (0.60f + 0.30f * l)
        return Ring(scale, alpha)
    }
}
