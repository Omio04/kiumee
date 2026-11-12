package kr.kiosklens.app

import kr.kiosklens.app.ui.MicRipple
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 마이크 버튼 둘레 파동 (2026-11-12 화면 개편) */
class MicRippleTest {
    @Test fun `고리는 바깥으로 퍼지며 옅어진다`() {
        val near = MicRipple.ring(0, 0.1f, 0.5f)
        val far = MicRipple.ring(0, 0.8f, 0.5f)
        assertTrue(far.scale > near.scale)
        assertTrue(far.alpha < near.alpha)
    }

    @Test fun `목소리가 크면 더 멀리 더 진하게 퍼진다`() {
        val quiet = MicRipple.ring(0, 0.5f, 0f)
        val loud = MicRipple.ring(0, 0.5f, 1f)
        assertTrue(loud.scale > quiet.scale)
        assertTrue(loud.alpha > quiet.alpha)
        assertTrue("조용해도 듣는 중이라는 표시는 보인다", quiet.alpha > 0f && quiet.scale > 1f)
    }

    @Test fun `파동은 뷰 밖으로 나가지 않고 진하기는 0~1`() {
        for (i in 0 until MicRipple.RINGS) for (k in 0..20) for (lv in listOf(-1f, 0f, 0.5f, 1f, 2f)) {
            val r = MicRipple.ring(i, k / 20f, lv)
            assertTrue("$i $k $lv → ${r.scale}", r.scale in 1f..MicRipple.MAX_SCALE)
            assertTrue("$i $k $lv → ${r.alpha}", r.alpha in 0f..1f)
        }
    }

    @Test fun `고리들은 서로 다른 때에 출발한다`() {
        assertNotEquals(MicRipple.ring(0, 0.2f, 0.5f).scale, MicRipple.ring(1, 0.2f, 0.5f).scale)
    }
}
