package kr.kiosklens.overlay

import kr.kiosklens.overlay.style.BorderStyle
import kr.kiosklens.overlay.style.PulseCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 노란 안내 테두리 굵기 · 진하기 (2026-11-13: 너무 진해 다른 글씨까지 가려서 연하게). */
class BorderStyleTest {
    @Test fun `전체 두께는 설계서 기준 6dp 이상`() {
        assertTrue(BorderStyle.BAND_DP >= 6f)
    }

    @Test fun `버튼 글자를 덮지 않게 테두리 안쪽이 버튼에서 떨어져 있다`() {
        val innerEdge = BorderStyle.PAD_DP - BorderStyle.BAND_DP / 2f
        assertEquals(BorderStyle.GAP_DP, innerEdge, 1e-6f)
        assertTrue("버튼 글자와 2dp 이상", innerEdge >= 2f)
    }

    @Test fun `어르신 눈에 또렷하게 - 전체 두께 10dp 이상`() {
        assertTrue("지금 ${BorderStyle.BAND_DP}dp", BorderStyle.BAND_DP >= 10f)
        assertTrue("칸 자체가 글자 영역보다 넓다", BorderStyle.AREA_SCALE >= 1.8f)
    }

    @Test fun `노란 선은 거의 불투명하고 어두운 선이 받쳐 밝은 배경에서도 보인다`() {
        for (t in 0..100) {
            val level = PulseCurve.MIN_LEVEL + (1f - PulseCurve.MIN_LEVEL) * t / 100f
            val a = BorderStyle.ringAlpha(level)
            assertTrue("$level → $a", a in BorderStyle.RING_ALPHA_MIN..BorderStyle.RING_ALPHA_MAX)
            assertTrue("가장 약할 때도 또렷", a >= 0.8f)
        }
        assertTrue(BorderStyle.EDGE_ALPHA >= 0.5f)
    }

    @Test fun `깜빡임은 진하기로만 - 약할 때 0_85 · 강할 때 1_0`() {
        assertEquals(0.85f, BorderStyle.ringAlpha(PulseCurve.MIN_LEVEL), 1e-6f)
        assertEquals(1.0f, BorderStyle.ringAlpha(1f), 1e-6f)
        assertEquals("범위 밖 값도 안전", 0.85f, BorderStyle.ringAlpha(0f), 1e-6f)
    }

    @Test fun `칸은 글자 영역의 약 2배 넓이로 넓어진다`() {
        val (gx, gy) = BorderStyle.grow(100f, 40f, 1f)
        val area = (100f + 2 * gx) * (40f + 2 * gy)
        assertEquals("넓이 2배", 2f, area / (100f * 40f), 0.02f)
    }

    @Test fun `큰 칸이 옆 칸까지 번지지 않게 한쪽 최대 거리가 있다`() {
        val (gx, gy) = BorderStyle.grow(900f, 600f, 2f)
        assertEquals(BorderStyle.MAX_GROW_DP * 2f, gx, 1e-3f)
        assertEquals(BorderStyle.MAX_GROW_DP * 2f, gy, 1e-3f)
        assertEquals(Pair(0f, 0f), BorderStyle.grow(0f, 0f, 3f))
    }

    @Test fun `넓어진 칸에서도 굵은 선의 안쪽 끝은 글자 칸 바깥에 있다`() {
        val inner = BorderStyle.PAD_DP - BorderStyle.BAND_DP / 2f
        assertTrue("선이 글자를 덮지 않는다: $inner", inner >= 2f)
    }
}
