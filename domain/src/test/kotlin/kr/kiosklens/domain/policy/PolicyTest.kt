package kr.kiosklens.domain.policy

import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScrollDir
import org.junit.Assert.assertEquals
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

    @Test fun `안내 문장은 위치 + 버튼 글자 + 버튼을 누르세요`() {
        assertEquals("화면 오른쪽 아래 깜빡이는 결제하기 버튼을 누르세요", texts.tap(at(0.8f, 0.9f), "결제하기"))
        assertEquals("화면 왼쪽 위 깜빡이는 HOT 버튼을 누르세요", texts.tap(at(0.2f, 0.3f), " HOT "))
    }

    @Test fun `버튼 글자가 이미 버튼으로 끝나면 버튼을 또 붙이지 않는다`() {
        assertEquals("화면 가운데 깜빡이는 확인 버튼을 누르세요", texts.tap(at(0.5f, 0.5f), "확인 버튼"))
    }

    @Test fun `밀기 안내`() {
        assertEquals("화면을 아래로 밀어서 딸기스무디 찾아 주세요", texts.scroll(ScrollDir.DOWN, "딸기스무디"))
    }
}
