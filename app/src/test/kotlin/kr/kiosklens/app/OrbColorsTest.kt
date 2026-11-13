package kr.kiosklens.app

import kr.kiosklens.app.ui.OrbColors
import kr.kiosklens.app.ui.OrbState
import kr.kiosklens.app.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** 듣는 동안 마이크 버튼이 빨간 녹음 색으로 바뀐다 (2026-11-13: 음성 인식이 되고 있는지 한눈에 보이게) */
class OrbColorsTest {
    private fun red(c: Int) = (c ushr 16) and 0xFF
    private fun green(c: Int) = (c ushr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    /** 상대 밝기 (WCAG) */
    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
    }

    /** 하얀색과의 대비 */
    private fun contrastWithWhite(c: Int) = 1.05 / (luminance(c) + 0.05)

    @Test fun `듣는 중만 빨강, 문제는 주황, 나머지는 하늘색`() {
        assertEquals(OrbColors.LISTEN, OrbColors.of(OrbState.LISTENING))
        assertEquals(OrbColors.WARM, OrbColors.of(OrbState.PROBLEM))
        for (s in listOf(OrbState.IDLE, OrbState.THINKING, OrbState.SPEAKING, OrbState.DONE)) {
            assertEquals("$s", OrbColors.SKY, OrbColors.of(s))
        }
    }

    @Test fun `듣는 중 색은 대기 하늘색과 한눈에 다르다 - 빨강이 많고 파랑이 적다`() {
        for (c in listOf(OrbColors.LISTEN.center, OrbColors.LISTEN.edge, OrbColors.LISTEN.halo)) {
            assertTrue("%08X".format(c), red(c) > blue(c) + 40 && red(c) > green(c) + 40)
        }
        for (c in listOf(OrbColors.SKY.center, OrbColors.SKY.edge)) {
            assertTrue("%08X".format(c), blue(c) > red(c) + 40)
        }
    }

    @Test fun `안내 노랑 · 문제 주황과 겹치지 않는다`() {
        val listen = OrbColors.LISTEN.edge
        for (other in listOf(Palette.SIGNAL, Palette.AMBER, Palette.AMBER_LIGHT)) {
            assertNotEquals(other, listen)
            assertTrue("초록이 적어 노랑 · 주황과 다르다", green(listen) < green(other) - 60)
        }
    }

    @Test fun `듣는 중에는 하얀 마이크가 대기 때보다 더 또렷하다`() {
        val listen = contrastWithWhite(OrbColors.LISTEN.center)
        val sky = contrastWithWhite(OrbColors.SKY.center)
        assertTrue("듣는 중 %.2f · 대기 %.2f".format(listen, sky), listen > sky)
        assertTrue("가장자리 대비 %.2f".format(contrastWithWhite(OrbColors.LISTEN.edge)), contrastWithWhite(OrbColors.LISTEN.edge) >= 3.0)
    }

    @Test fun `색은 깜빡이지 않고 0_2초 동안 한 번 스르륵 바뀐다`() {
        val from = OrbColors.SKY
        val to = OrbColors.LISTEN
        assertEquals(from, OrbColors.fade(from, to, 0))
        assertEquals(to, OrbColors.fade(from, to, OrbColors.FADE_MS))
        assertEquals("다 바뀐 뒤엔 그대로", to, OrbColors.fade(from, to, 5000))
        assertEquals("시계가 거꾸로 가도 안전", from, OrbColors.fade(from, to, -100))
        var lastRed = red(from.edge)
        for (t in 0L..OrbColors.FADE_MS step 10) {                                       // 빨강이 줄곧 늘기만 한다 (오락가락 없음)
            val r = red(OrbColors.fade(from, to, t).edge)
            assertTrue("$t ms: $r < $lastRed", r >= lastRed)
            lastRed = r
        }
        assertTrue("0.2초면 충분히 짧다 (누르자마자 바뀐 게 보인다)", OrbColors.FADE_MS <= 250)
    }

    @Test fun `색 섞기는 불투명을 지키고 끝값이 정확하다`() {
        val a = 0xFF000000.toInt()
        val b = 0xFFFFFFFF.toInt()
        assertEquals(a, OrbColors.mix(a, b, 0f))
        assertEquals(b, OrbColors.mix(a, b, 1f))
        assertEquals(0xFF808080.toInt(), OrbColors.mix(a, b, 0.5f))
        assertEquals("범위 밖은 끝값", b, OrbColors.mix(a, b, 2f))
        for (t in 0..10) assertEquals(0xFF, OrbColors.mix(OrbColors.SKY.edge, OrbColors.LISTEN.edge, t / 10f) ushr 24)
    }
}
