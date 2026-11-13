package kr.kiosklens.overlay

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.overlay.tracking.BoxSmoother
import kr.kiosklens.overlay.tracking.CameraMotionTracker
import kr.kiosklens.overlay.tracking.GridMotionEstimator
import kr.kiosklens.overlay.tracking.LumaGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 흔들림 따라가기: 밝기 격자 만들기(LumaGrid) · 움직임 찾기(GridMotionEstimator) · 프레임 연결(CameraMotionTracker). */
class MotionTrackingTest {
    private val cols = 96
    private val rows = 128
    private val estimator = GridMotionEstimator()

    /** 키오스크 화면 비슷한 장면: 부드러운 명암 + 잔무늬(글자) + 밝은 칸(메뉴 카드). 단위는 격자 칸. */
    private fun scene(x: Float, y: Float): Float {
        val smooth = 30f * sin(0.11f * x + 0.7f) * cos(0.09f * y + 0.3f) + 20f * sin(0.031f * x - 0.047f * y + 1.1f)
        val fine = 25f * sin(0.83f * x + 0.29f * y) * sin(0.61f * y - 0.17f * x + 0.5f)
        val card = if (Math.floorMod((x * 0.13f + 0.37f * sin(y * 0.05f)).toInt() + (y * 0.11f).toInt(), 3) == 0) 40f else 0f
        return 120f + smooth + fine + card
    }

    /** 내용이 (sx, sy)칸만큼 움직인 격자 (+ 카메라 잡음) */
    private fun grid(sx: Float, sy: Float, gain: Float = 1f, offset: Float = 0f, noise: Float = 2f, seed: Int = 1,
        f: (Float, Float) -> Float = ::scene): FloatArray {
        val rnd = Random(seed)
        return FloatArray(cols * rows) { i ->
            val x = i % cols + 0.5f; val y = i / cols + 0.5f
            (f(x - sx, y - sy) * gain + offset + (rnd.nextFloat() - 0.5f) * 2f * noise).coerceIn(0f, 255f)
        }
    }

    private fun assertMotion(sx: Float, sy: Float, r: GridMotionEstimator.Result, tolCells: Float) {
        assertEquals("dx", sx / cols, r.dx, tolCells / cols)
        assertEquals("dy", sy / rows, r.dy, tolCells / rows)
        assertTrue("믿을 만해야 함: ${r.confidence}", r.confidence >= 0.6f)
    }

    @Test fun `가만히 있으면 움직임 0 · 믿을 만함`() {
        val r = estimator.estimate(grid(0f, 0f, seed = 1), grid(0f, 0f, seed = 2), cols, rows)
        assertMotion(0f, 0f, r, 0.2f)
    }

    @Test fun `칸 단위로 움직이면 그만큼 찾는다`() {
        val r = estimator.estimate(grid(0f, 0f), grid(3f, -2f, seed = 3), cols, rows)
        assertMotion(3f, -2f, r, 0.25f)
    }

    @Test fun `칸보다 작게 움직여도 찾는다`() {
        val r = estimator.estimate(grid(0f, 0f), grid(1.5f, 0.4f, seed = 4), cols, rows)
        assertMotion(1.5f, 0.4f, r, 0.35f)
    }

    @Test fun `크게 흔들려도 범위 안이면 찾는다`() {
        val r = estimator.estimate(grid(0f, 0f), grid(-12f, 9f, seed = 5), cols, rows)
        assertMotion(-12f, 9f, r, 0.5f)
    }

    @Test fun `밝기가 바뀌어도 움직임은 그대로 찾는다`() {
        val r = estimator.estimate(grid(0f, 0f), grid(2f, 1f, gain = 1.1f, offset = 20f, seed = 6), cols, rows)
        assertMotion(2f, 1f, r, 0.3f)
    }

    @Test fun `무늬가 없으면 믿을 수 없다`() {
        val flat = { _: Float, _: Float -> 128f }
        val r = estimator.estimate(grid(0f, 0f, f = flat, noise = 1f), grid(2f, 0f, f = flat, noise = 1f, seed = 7), cols, rows)
        assertEquals(0f, r.confidence, 0f)
    }

    @Test fun `전혀 다른 장면이면 믿을 수 없다`() {
        val other = { x: Float, y: Float -> 128f + 60f * sin(1.7f * x * y * 0.01f + 2.1f) * cos(0.37f * x - 0.81f * y) }
        val r = estimator.estimate(grid(0f, 0f), grid(0f, 0f, f = other, seed = 8), cols, rows)
        assertTrue("다른 장면인데 믿을 만하다고 함: ${r.confidence}", r.confidence < 0.5f)
    }

    @Test fun `찾는 범위를 넘게 움직이면 믿을 수 없다`() {
        val r = estimator.estimate(grid(0f, 0f), grid(30f, 0f, seed = 9), cols, rows)
        assertTrue("범위 밖인데 믿을 만하다고 함: ${r.confidence} dx=${r.dx * cols}", r.confidence < 0.5f || abs(r.dx * cols - 30f) < 1f)
    }

    @Test fun `아주 크게 흔들면 (프레임 폭의 40%) 믿을 수 없다 — 이때 위치를 놓친 것으로 본다`() {
        for ((sx, sy) in listOf(40f to 0f, 0f to 50f, -38f to 30f)) {
            val r = estimator.estimate(grid(0f, 0f), grid(sx, sy, seed = 15), cols, rows)
            assertTrue("($sx, $sy) 범위 밖인데 믿을 만하다고 함: ${r.confidence}", r.confidence < 0.5f)
        }
    }

    /** 3×3 평균으로 흐리게 (초점이 잠깐 나간 프레임) */
    private fun blur(g: FloatArray): FloatArray = FloatArray(g.size) { i ->
        val x = i % cols; val y = i / cols
        var s = 0f; var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until cols && yy in 0 until rows) { s += g[yy * cols + xx]; n++ }
        }
        s / n
    }

    @Test fun `가만히 있는데 흐려지기만 하면 움직임 없음을 믿을 만하게 낸다 (위치를 놓치지 않게)`() {
        val sharp = grid(0f, 0f, seed = 11)
        val r = estimator.estimate(sharp, blur(grid(0f, 0f, seed = 12)), cols, rows)
        assertTrue("흐리기만 했는데 놓친 것으로 보면 안 됨: ${r.confidence}", r.confidence >= 0.6f)
        assertEquals(0f, r.dx, 0.5f / cols)
        assertEquals(0f, r.dy, 0.5f / rows)
    }

    @Test fun `흐려지면서 조금 움직이면 움직인 만큼 낸다`() {
        val r = estimator.estimate(grid(0f, 0f), blur(grid(2f, 1f, seed = 13)), cols, rows)
        assertMotion(2f, 1f, r, 0.5f)
    }

    @Test fun `손으로 카메라를 가리면 믿을 수 없다`() {
        val hand = { _: Float, _: Float -> 30f }
        val r = estimator.estimate(grid(0f, 0f), grid(0f, 0f, f = hand, noise = 1f, seed = 14), cols, rows)
        assertTrue("가렸는데 믿을 만하다고 함: ${r.confidence}", r.confidence < 0.5f)
    }

    // ---------------------------------------------------------------- LumaGrid: 회전 보정

    /** 센서 기준 40×30 이미지, 왼쪽 위 10×10만 밝음. rowStride에 여백, pixelStride 2 (다른 값이 끼어 있는 형식) */
    private fun cornerImage(): ByteBuffer {
        val w = 40; val h = 30; val rowStride = w * 2 + 8
        val bytes = ByteArray(rowStride * h) { 77 }                       // 끼어 있는 다른 값 · 여백은 엉뚱한 값
        for (y in 0 until h) for (x in 0 until w) bytes[y * rowStride + x * 2] = if (x < 10 && y < 10) 250.toByte() else 10
        return ByteBuffer.wrap(bytes)
    }

    private fun brightCell(rot: Int, cols: Int, rows: Int): Pair<Int, Int> {
        val g = LumaGrid.sample(cornerImage(), 40 * 2 + 8, 2, 40, 30, rot, cols, rows)
        val i = g.indices.maxBy { g[it] }
        assertTrue("밝은 칸은 하나: ${g.toList()}", g.count { it > 128f } == 1)
        return (i / cols) to (i % cols)                                   // (행, 열)
    }

    @Test fun `회전 0이면 왼쪽 위 그대로`() = assertEquals(0 to 0, brightCell(0, 4, 3))
    @Test fun `회전 90이면 왼쪽 위가 오른쪽 위로`() = assertEquals(0 to 2, brightCell(90, 3, 4))
    @Test fun `회전 180이면 오른쪽 아래로`() = assertEquals(2 to 3, brightCell(180, 4, 3))
    @Test fun `회전 270이면 왼쪽 아래로`() = assertEquals(3 to 0, brightCell(270, 3, 4))

    // ---------------------------------------------------------------- CameraMotionTracker: 실제 프레임 크기

    /** 센서 640×480(가로), 회전 90 → 똑바로 세우면 480×640. 똑바로 선 화면에서 내용이 오른쪽으로 [shiftPx] 움직인 프레임 */
    private fun sensorFrame(shiftPx: Float, seed: Int): ByteBuffer {
        val w = 640; val h = 480
        val rnd = Random(seed)
        val bytes = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val ux = (h - 1 - y).toFloat(); val uy = x.toFloat()          // 센서 (x, y) → 똑바로 선 (ux, uy)
            val v = scene((ux - shiftPx) / 5f, uy / 5f) + (rnd.nextFloat() - 0.5f) * 4f
            bytes[y * w + x] = v.toInt().coerceIn(0, 255).toByte()
        }
        return ByteBuffer.wrap(bytes)
    }

    @Test fun `회전된 실제 크기 프레임에서도 똑바로 선 방향으로 움직임을 낸다`() {
        val tracker = CameraMotionTracker()
        assertNull("첫 프레임은 비교할 것이 없음", tracker.onLumaFrame(1, sensorFrame(0f, 1), 640, 1, 640, 480, 90))
        val m = tracker.onLumaFrame(2, sensorFrame(15f, 2), 640, 1, 640, 480, 90)
        assertNotNull(m)
        assertEquals(2L, m!!.frameId)
        assertEquals("오른쪽으로 15px = 480의 3.1%", 15f / 480f, m.dx, 0.4f / 96f)
        assertEquals(0f, m.dy, 0.4f / 128f)
        assertTrue("믿을 만해야 함: ${m.confidence}", m.confidence >= 0.6f)
    }

    @Test fun `흔들림만큼 박스와 목표를 함께 옮긴다`() {
        val s = BoxSmoother()
        s.setTarget(NormRect(0.4f, 0.4f, 0.6f, 0.5f))
        s.nudge(0.05f, -0.02f)
        val t = s.target!!; val c = s.current!!
        for ((got, want) in listOf(t.left to 0.45f, t.top to 0.38f, t.right to 0.65f, t.bottom to 0.48f)) assertEquals(want, got, 1e-5f)
        assertEquals(t, c)
    }
}
