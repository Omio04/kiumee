package kr.kiosklens.app.ui

import kr.kiosklens.domain.session.Phase

/**
 * 화면에 크게 보여 주는 상태 문구 (쉬운 존댓말, 비난 · 전문용어 금지).
 * 읽어 주는 안내 문장은 :domain 의 GuideTextComposer 가 만들고, 여기는 "지금 무슨 상태인지" 한 줄만 정한다.
 */
object UiTexts {
    /** 앱을 켜면 한 번 읽어 주는 인사 (2026-11-13 요청) */
    const val GREETING = "무엇을 도와드릴까요? 도움이 필요하시면 아래 빨간 버튼을 눌러 주세요."

    /** @param caption 방금 읽어 준(읽고 있는) 안내 문장. 있으면 그것을 그대로 보여 준다(소리가 안 들려도 읽을 수 있게). */
    fun status(phase: Phase, caption: String?): String {
        val base = when (phase) {
            Phase.IDLE -> "아래 빨간 버튼을 누르고\n궁금한 것을 말씀해 주세요"
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

    /**
     * 마이크 버튼 설명 (TalkBack이 읽음). 2026-11-12 화면 개편으로 자막을 없앤 대신, 지금 상태와 버튼 쓰는 법을 여기에 담는다.
     */
    fun micDescription(phase: Phase): String = when (phase) {
        Phase.IDLE -> "빨간 버튼. 누르고 궁금한 것을 말씀해 주세요"
        Phase.LISTENING -> "듣고 있어요. 천천히 말씀해 주세요"
        else -> "${status(phase, null).replace("\n", " ")}. 더 말씀하시려면 누르고, 처음부터 하려면 길게 누르세요"
    }

    /**
     * 버튼 위에 잠깐 보여 주는 안내 한 줄 (평소 화면에는 글자가 없다). 없으면 null.
     * 도움 모드에서는 **처음 켰을 때**와 **답이 끝나 다시 질문을 기다릴 때**만 보여 준다. "마이크 · 말하기" 같은 말 대신
     * 모양과 위치("아래 빨간 버튼")로 말한다: 이름을 모르는 분도 따라 할 수 있게.
     */
    fun hint(phase: Phase, helpMode: Boolean): String? = when (phase) {
        Phase.IDLE -> "아래 빨간 버튼을 누르고\n궁금한 것을 물어보세요"
        Phase.LISTENING -> "듣고 있어요\n천천히 말씀해 주세요"
        Phase.PLANNING -> "찾고 있어요…"
        Phase.WAIT_CHANGE -> if (helpMode) "더 궁금하면 아래 빨간 버튼을\n눌러 물어보세요" else null
        else -> null
    }

    /** "말하기" 버튼을 누를 수 있는 단계 (진행 중에는 무시된다) */
    fun canStart(phase: Phase) = phase != Phase.LISTENING   // 안내 중에도 눌러 말을 보탤 수 있다 (듣는 중만 막음)

    /** "그만하기" 버튼은 진행 중에만 보인다 */
    fun canStop(phase: Phase) = phase != Phase.IDLE
}
