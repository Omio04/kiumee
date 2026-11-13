package kr.kiosklens.domain.policy

import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScrollDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    private val phraser = PositionPhraser()
    private val texts = GuideTextComposer(phraser)

    /** 중심점 (x, y)에 작은 버튼 하나 */
    private fun at(x: Float, y: Float) = NormRect(x - 0.05f, y - 0.05f, x + 0.05f, y + 0.05f)

    @Test fun `가로 세로를 3등분해서 위치를 말한다`() {
        assertEquals("화면 왼쪽 위", phraser.phrase(at(0.15f, 0.15f)))
        assertEquals("화면 오른쪽 아래", phraser.phrase(at(0.85f, 0.85f)))
        assertEquals("화면 가운데 위", phraser.phrase(at(0.5f, 0.15f)))
        assertEquals("화면 왼쪽 가운데", phraser.phrase(at(0.15f, 0.5f)))
    }

    @Test fun `가로 세로 모두 가운데면 화면 가운데`() {
        assertEquals("화면 가운데", phraser.phrase(at(0.5f, 0.5f)))
    }

    @Test fun `안내 문장은 위치 + 노란색 칸을 눌러 주세요 + 적힌 글자`() {
        assertEquals("화면 오른쪽 아래 노란색 칸을 눌러 주세요. 적힌 글자는 결제하기", texts.tap(at(0.8f, 0.9f), "결제하기"))
        assertEquals("화면 왼쪽 위 노란색 칸을 눌러 주세요. 적힌 글자는 HOT", texts.tap(at(0.2f, 0.3f), " HOT "))
    }

    @Test fun `글자 뒤에 조사를 붙이지 않아서 어떤 이름이 와도 말이 맞다`() {
        for (name in listOf("확인 버튼", "아메리카노", "ICE", "샷 추가", "×")) {
            val t = texts.tap(at(0.5f, 0.5f), name)
            assertEquals("화면 가운데 노란색 칸을 눌러 주세요. 적힌 글자는 $name", t)
            assertTrue(t.endsWith(name))
        }
    }

    @Test fun `밀기 안내`() {
        assertEquals("화면을 아래로 밀어서 딸기스무디 찾아 주세요", texts.scroll(ScrollDir.DOWN, "딸기스무디"))
    }
}
