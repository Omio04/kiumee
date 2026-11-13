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

    @Test fun `옆 버튼 글씨를 덜 덮게 바깥으로 나가는 거리가 예전보다 짧다`() {
        val before = 11.7f                                               // 예전: 패딩 6dp + 검은 선 절반 약 5.7dp
        assertTrue("지금 ${BorderStyle.OUTER_DP}dp", BorderStyle.OUTER_DP <= 8f && BorderStyle.OUTER_DP < before)
    }

    @Test fun `노란 선은 반투명이라 아래 글씨가 비친다`() {
        for (t in 0..100) {
            val level = PulseCurve.MIN_LEVEL + (1f - PulseCurve.MIN_LEVEL) * t / 100f
            val a = BorderStyle.ringAlpha(level)
            assertTrue("$level → $a", a in BorderStyle.RING_ALPHA_MIN..BorderStyle.RING_ALPHA_MAX)
            assertTrue(a < 1f)
        }
        assertTrue(BorderStyle.EDGE_ALPHA < 0.5f)
    }

    @Test fun `깜빡임은 진하기로만 - 약할 때 0_6 · 강할 때 0_85`() {
        assertEquals(0.6f, BorderStyle.ringAlpha(PulseCurve.MIN_LEVEL), 1e-6f)
        assertEquals(0.85f, BorderStyle.ringAlpha(1f), 1e-6f)
        assertEquals("범위 밖 값도 안전", 0.6f, BorderStyle.ringAlpha(0f), 1e-6f)
    }
}
