package kr.kiosklens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideActionTest {
    @Test fun `행동 종류는 JSON type 값과 같다`() {
        assertEquals(ActionType.TAP, GuideAction.Tap("e1", "아메리카노").type)
        assertEquals(ActionType.SCROLL, GuideAction.Scroll(ScrollDir.DOWN, "메뉴 목록").type)
        assertEquals(ActionType.WAIT, GuideAction.Wait("잠시 기다려 주세요").type)
        assertEquals(ActionType.ASK_USER, GuideAction.AskUser("무엇을 드시고 싶으세요?").type)
        assertEquals(ActionType.DONE, GuideAction.Done("직원 안내를 따라주세요.").type)
        assertEquals(ActionType.UNKNOWN, GuideAction.Unknown("버튼을 찾지 못했어요.").type)
        assertEquals("ASK_USER", ActionType.ASK_USER.name)
    }

    @Test fun `이력에는 행동마다 정해진 글자가 남는다`() {
        val tap = HistoryItem.of("s02", GuideAction.Tap("e4", "아메리카노"))
        assertEquals(HistoryItem("s02", ActionType.TAP, "아메리카노"), tap)

        val ask = HistoryItem.of("s03", GuideAction.AskUser("따뜻한 걸로 드릴까요?"))
        assertEquals("따뜻한 걸로 드릴까요?", ask.targetText)

        assertNull(HistoryItem.of("s03", GuideAction.Unknown("못 찾음")).targetText)
    }
}
