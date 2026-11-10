package kr.kiosklens.overlay

import kr.kiosklens.core.model.NormRect
import kr.kiosklens.overlay.transform.FillCenterMapper
import kr.kiosklens.overlay.transform.FitCenterMapper
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세로로 긴 휴대폰에서 분석 프레임의 어디까지가 화면에 보이는지 (P5 PoC에서 확인한 내용을 숫자로 고정).
 *
 * 앱은 미리보기 · 분석을 모두 4:3으로 받는다 → 세로 화면에서는 가로:세로 = 3:4 (예: 960×1280).
 * 휴대폰 화면은 훨씬 길쭉하다 (예: 1080×2400 = 9:20).
 */
class VisibleAreaTest {
    private val full = NormRect(0f, 0f, 1f, 1f)
    private fun near(a: Float, b: Float) = assertTrue("$a vs $b", kotlin.math.abs(a - b) < 0.01f)

    @Test fun `채우기(FILL)면 길쭉한 화면에서 분석 프레임의 좌우 20퍼센트씩이 화면 밖으로 잘린다`() {
        val r = FillCenterMapper(960, 1280).map(full, 1080f, 2400f)
        val visibleLeft = (0f - r.left) / r.width
        val visibleRight = (1080f - r.left) / r.width
        near(visibleLeft, 0.2f)     // x 0.0 ~ 0.2 는 화면 왼쪽 밖
        near(visibleRight, 0.8f)    // x 0.8 ~ 1.0 은 화면 오른쪽 밖
        near(r.top, 0f); near(r.bottom, 2400f)   // 위아래는 꽉 참
    }

    @Test fun `맞추기(FIT)면 분석 프레임 전체가 보이고 위아래에 빈 띠가 생긴다`() {
        val r = FitCenterMapper(960, 1280).map(full, 1080f, 2400f)
        near(r.left, 0f); near(r.right, 1080f)
        near(r.top, 480f); near(r.bottom, 1920f)  // 위아래 480픽셀씩 빈 띠
    }

    @Test fun `채우기(FILL)에서 가장자리 버튼은 하이라이트 중심이 화면 밖일 수 있다`() {
        val m = FillCenterMapper(960, 1280)
        assertTrue(m.isCentreVisible(NormRect(0.40f, 0.45f, 0.60f, 0.55f), 1080f, 2400f))   // 가운데: 보임
        assertTrue(!m.isCentreVisible(NormRect(0.02f, 0.30f, 0.15f, 0.40f), 1080f, 2400f))  // 왼쪽 끝 버튼: 안 보임
    }
}
