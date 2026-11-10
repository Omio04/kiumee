package kr.kiosklens.app.ui

import kr.kiosklens.domain.session.Phase

/**
 * 화면에 크게 보여 주는 상태 문구 (쉬운 존댓말, 비난 · 전문용어 금지).
 * 읽어 주는 안내 문장은 :domain 의 GuideTextComposer 가 만들고, 여기는 "지금 무슨 상태인지" 한 줄만 정한다.
 */
object UiTexts {
    /** @param caption 방금 읽어 준(읽고 있는) 안내 문장. 있으면 그것을 그대로 보여 준다(소리가 안 들려도 읽을 수 있게). */
    fun status(phase: Phase, caption: String?): String {
        val base = when (phase) {
            Phase.IDLE -> "아래 '말하기' 버튼을 누르고\n원하는 것을 말씀해 주세요"
            Phase.LISTENING -> "듣고 있어요…\n천천히 말씀해 주세요"
            Phase.PLANNING -> "찾고 있어요…"
            Phase.SCANNING -> "키오스크 화면을 찾고 있어요…"
            Phase.GUIDING, Phase.WAIT_CHANGE -> "노란 테두리를 눌러 주세요"
            Phase.ASKING -> "질문에 답해 주세요"
            Phase.DONE -> "다 됐어요. 잘 하셨어요!"
            Phase.LOST -> "키오스크 화면이 잘 보이게\n비춰 주세요"
            Phase.ERROR -> "잠시 문제가 생겼어요"
        }
        val c = caption?.trim().orEmpty()
        return if (c.isNotEmpty() && phase != Phase.IDLE && phase != Phase.LISTENING && phase != Phase.PLANNING) c else base
    }

    /** "말하기" 버튼을 누를 수 있는 단계 (진행 중에는 무시된다) */
    fun canStart(phase: Phase) = phase == Phase.IDLE

    /** "그만하기" 버튼은 진행 중에만 보인다 */
    fun canStop(phase: Phase) = phase != Phase.IDLE
}
