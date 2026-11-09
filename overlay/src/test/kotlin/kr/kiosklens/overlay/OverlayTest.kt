package kr.kiosklens.overlay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.port.HighlightSurface
import kr.kiosklens.overlay.style.PulseCurve
import kr.kiosklens.overlay.tracking.BoxSmoother
import kr.kiosklens.overlay.transform.FillCenterMapper
import kr.kiosklens.overlay.transform.FitCenterMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

abstract class HighlightSurfaceContractSpec {
    protected abstract fun create(): Pair<HighlightSurface, () -> HighlightTarget?>
    private fun t(id: String, l: Float = 0.1f) = HighlightTarget(id, NormRect(l, 0.2f, l + 0.2f, 0.3f))

    @Test fun `여러 스레드에서 동시에 불러도 예외 없이 마지막 값이 남는다`() {
        val (s, cur) = create()
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1); val done = CountDownLatch(8)
        repeat(8) { i -> pool.execute { go.await(); repeat(500) { s.setTarget(t("e$i", 0.1f + (it % 5) * 0.01f)) }; done.countDown() } }
        go.countDown(); done.await(); pool.shutdown()
        s.setTarget(t("last"))
        assertEquals("last", cur()?.elementId)
    }

    @Test fun `null이면 숨긴다`() { val (s, cur) = create(); s.setTarget(t("a")); s.setTarget(null); assertNull(cur()) }

    @Test fun `같은 값을 연속으로 넣어도 상태가 같다`() {
        val (s, cur) = create(); val a = t("a")
        s.setTarget(a); val first = cur(); s.setTarget(a.copy()); s.setTarget(a)
        assertEquals(first, cur())
    }
}

class FakeOverlayContractTest : HighlightSurfaceContractSpec() {
    override fun create(): Pair<HighlightSurface, () -> HighlightTarget?> = FakeOverlay().let { it to { it.current } }
}
class HighlightHolderContractTest : HighlightSurfaceContractSpec() {
    override fun create(): Pair<HighlightSurface, () -> HighlightTarget?> = HighlightHolder().let { it to { it.target } }
}

class OverlayMathTest {
    private val eps = 0.01f
    private fun near(a: Float, b: Float) = assertTrue("$a vs $b", kotlin.math.abs(a - b) < eps)

    @Test fun `프리뷰와 비율이 같으면 그대로 비례한다`() {
        val m = FillCenterMapper(1080, 1920)
        val p = m.map(NormRect(0.1f, 0.2f, 0.5f, 0.3f), 540f, 960f)
        near(p.left, 54f); near(p.top, 192f); near(p.right, 270f); near(p.bottom, 288f)
    }

    @Test fun `프리뷰가 더 길쭉하면 좌우가 잘리고 중앙이 맞는다`() {
        val m = FillCenterMapper(1000, 1000)                 // 정사각 분석 프레임
        val p = m.map(NormRect(0.4f, 0.4f, 0.6f, 0.6f), 500f, 1000f)   // 세로로 긴 화면 → scale=1, 좌우 250씩 잘림
        near(p.left, 150f); near(p.width, 200f)
        near((p.left + p.right) / 2f, 250f); near((p.top + p.bottom) / 2f, 500f)
    }

    @Test fun `좌우 반전`() {
        val a = FillCenterMapper(100, 100, mirrorX = false).map(NormRect(0f, 0f, 0.2f, 0.2f), 100f, 100f)
        val b = FillCenterMapper(100, 100, mirrorX = true).map(NormRect(0f, 0f, 0.2f, 0.2f), 100f, 100f)
        near(b.left, 100f - a.right); near(b.right, 100f - a.left)
    }

    @Test fun `FIT은 검은 띠를 둔다`() {
        val p = FitCenterMapper(1000, 500).map(NormRect(0f, 0f, 1f, 1f), 1000f, 1000f)
        near(p.top, 250f); near(p.bottom, 750f); near(p.left, 0f); near(p.right, 1000f)
    }

    @Test fun `늘려 그리기`() {
        val p = kr.kiosklens.overlay.transform.StretchMapper().map(NormRect(0.1f, 0.2f, 0.5f, 0.4f), 1000f, 500f)
        near(p.left, 100f); near(p.top, 100f); near(p.right, 500f); near(p.bottom, 200f)
    }

    @Test fun `크기가 0이어도 죽지 않는다`() {
        val p = FillCenterMapper(0, 0).map(NormRect(0f, 0f, 1f, 1f), 0f, 0f)
        assertEquals(0f, p.width)
    }

    @Test fun `부드럽게 따라가고 결국 목표에 닿는다`() {
        val s = BoxSmoother()
        s.setTarget(NormRect(0.1f, 0.1f, 0.3f, 0.2f))
        s.setTarget(NormRect(0.15f, 0.12f, 0.35f, 0.22f))              // 작은 이동 → 따라감
        val first = s.current!!.left
        assertTrue(first < 0.15f)
        s.step(33); val second = s.current!!.left
        assertTrue(second > first && second < 0.15f)
        repeat(60) { s.step(33) }
        near(s.current!!.left, 0.15f)
    }

    @Test fun `다른 버튼이면 바로 옮긴다`() {
        val s = BoxSmoother(); s.setTarget(NormRect(0.1f, 0.1f, 0.3f, 0.2f)); s.setTarget(NormRect(0.6f, 0.7f, 0.9f, 0.8f))
        near(s.current!!.left, 0.6f)
    }

    @Test fun `프레임 간격이 달라도 속도가 비슷하다`() {
        val a = BoxSmoother(); val b = BoxSmoother()
        for (s in listOf(a, b)) { s.setTarget(NormRect(0.1f, 0.1f, 0.3f, 0.2f)); s.setTarget(NormRect(0.2f, 0.1f, 0.4f, 0.2f)) }
        repeat(10) { a.step(33) }; repeat(5) { b.step(66) }
        near(a.current!!.left, b.current!!.left)
    }

    @Test fun `카메라 흔들림만큼 박스를 옮긴다`() {
        val s = BoxSmoother(); s.setTarget(NormRect(0.1f, 0.1f, 0.3f, 0.2f)); s.nudge(0.05f, -0.02f)
        near(s.current!!.left, 0.15f); near(s.current!!.top, 0.08f)
    }

    @Test fun `깜빡임은 3Hz를 넘지 않고 완전히 꺼지지 않는다`() {
        assertTrue(PulseCurve.HZ <= PulseCurve.MAX_SAFE_HZ)
        var min = 9f; var max = 0f; var flips = 0; var prevUp = true; var prev = PulseCurve.level(0)
        for (ms in 0..3000 step 5) {
            val v = PulseCurve.level(ms.toLong()); min = minOf(min, v); max = maxOf(max, v)
            val up = v >= prev; if (up != prevUp) flips++; prevUp = up; prev = v
        }
        assertTrue(min >= 0.5f && max <= 1.0f)
        assertTrue("3초에 방향 전환 ${flips}번", flips <= 10)       // 1.5Hz × 3초 × 2 = 9
    }
}
