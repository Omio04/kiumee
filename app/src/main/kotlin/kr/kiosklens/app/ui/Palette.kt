package kr.kiosklens.app.ui

/**
 * 키우미 색 (2026-11-12 디자인 계획). 화면 코드에서는 여기 이름으로만 쓴다.
 * 아이콘 · 테마 XML은 res/values/colors.xml의 같은 값을 쓴다.
 */
object Palette {
    /** 하늘: 구슬(마이크 버튼) 가장자리 · 아이콘 바탕 */
    const val SKY = 0xFF4FB2EA.toInt()
    /** 진한 하늘: 밝은 바탕 위 버튼 (하얀 글자가 또렷하게) */
    const val SKY_DEEP = 0xFF2B8FCF.toInt()
    /** 맑은 하늘: 구슬 가운데 (가운데로 갈수록 연하게) */
    const val SKY_LIGHT = 0xFFA6DBF7.toInt()
    /** 파동 · 숨쉬는 빛. 어두운 카메라 화면 위에서도 밝은 하늘빛으로 보이게 밝게 (8FD0F5는 검은 화면에서 남색 고리로 보였다) */
    const val RIPPLE = 0xFFBDE6FC.toInt()
    /** 안내 노랑: 버튼을 짚는 노란 테두리. 앱의 서명 색이라 아껴 쓴다 */
    const val SIGNAL = 0xFFFFD600.toInt()
    /** 아침: 로딩 화면 바탕 */
    const val MORNING = 0xFFF4FAFE.toInt()
    /** 아침 아래쪽 (로딩 화면 바탕이 아래로 갈수록 살짝 하늘빛) */
    const val MORNING_LOW = 0xFFE1F1FB.toInt()
    /** 깊은 바다: 로딩 화면 글자 */
    const val DEEP = 0xFF14344D.toInt()
    /** 깊은 바다 옅게: 로딩 화면 보조 글자 */
    const val DEEP_SOFT = 0xFF4E6E86.toInt()
    /** 주황: 문제 상태 (화면 놓침 · 오류) */
    const val AMBER = 0xFFF5A524.toInt()
    /** 주황 가운데 */
    const val AMBER_LIGHT = 0xFFFCD9A0.toInt()
}
