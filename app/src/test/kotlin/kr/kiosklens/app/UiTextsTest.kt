package kr.kiosklens.app

import kr.kiosklens.app.ui.UiTexts
import kr.kiosklens.domain.session.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextsTest {
    @Test fun `모든 단계에 문구가 있고 비어 있지 않다`() {
        for (p in Phase.values()) assertTrue(p.name, UiTexts.status(p, null).isNotBlank())
    }

    @Test fun `안내 문장이 있으면 그대로 크게 보여 준다`() {
        assertEquals("화면 왼쪽 위 깜빡이는 아메리카노 버튼을 누르세요", UiTexts.status(Phase.GUIDING, "화면 왼쪽 위 깜빡이는 아메리카노 버튼을 누르세요"))
        assertEquals("질문입니다", UiTexts.status(Phase.ASKING, " 질문입니다 "))
    }

    @Test fun `듣는 중에는 이전 안내가 남아 있지 않다`() {
        assertEquals(UiTexts.status(Phase.LISTENING, null), UiTexts.status(Phase.LISTENING, "지난 안내"))
        assertEquals(UiTexts.status(Phase.IDLE, null), UiTexts.status(Phase.IDLE, "지난 안내"))
    }

    @Test fun `말하기는 대기 중에만 그만하기는 진행 중에만`() {
        assertTrue(UiTexts.canStart(Phase.IDLE)); assertFalse(UiTexts.canStop(Phase.IDLE))
        for (p in Phase.values().filter { it != Phase.IDLE }) { assertFalse(UiTexts.canStart(p)); assertTrue(UiTexts.canStop(p)) }
    }

    @Test fun `문구에 비난이나 전문용어가 없다`() {
        val banned = listOf("실패", "오류", "에러", "잘못", "OCR", "서버", "STT", "TTS")
        for (p in Phase.values()) { val t = UiTexts.status(p, null); assertTrue("$p: $t", banned.none { t.contains(it) }) }
    }
}
