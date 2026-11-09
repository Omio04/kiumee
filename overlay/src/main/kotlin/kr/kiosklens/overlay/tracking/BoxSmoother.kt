package kr.kiosklens.overlay.tracking

import kr.kiosklens.core.model.NormRect
import kotlin.math.abs
import kotlin.math.pow

/**
 * 새 OCR 결과가 와서 목표 위치가 바뀔 때 박스가 튀지 않고 부드럽게 따라가게 한다 (설계서 7.5).
 * 지수 이동 평균(EMA): 한 걸음마다 목표와의 거리의 일부만 이동. 기준 α=0.3 @ 30fps(33ms).
 * 프레임 간격이 달라도 같은 속도로 움직이도록 시간으로 보정한다.
 * 목표가 크게 달라지면(다른 버튼) 따라가지 않고 바로 옮긴다.
 */
class BoxSmoother(private val alpha: Float = 0.3f, private val snapDistance: Float = 0.25f, private val refMs: Float = 33f) {
    private var cur: FloatArray? = null
    private var tgt: FloatArray? = null

    val current: NormRect? get() = cur?.let { NormRect(it[0], it[1], it[2], it[3]) }

    fun setTarget(r: NormRect?) {
        if (r == null) { cur = null; tgt = null; return }
        val t = floatArrayOf(r.left, r.top, r.right, r.bottom)
        val c = cur
        if (c == null || distance(c, t) > snapDistance) cur = t.copyOf()      // 처음이거나 다른 버튼: 바로 옮김
        tgt = t
    }

    /** dtMs 만큼 시간이 흘렀을 때의 위치. 움직임이 남아 있으면 true. */
    fun step(dtMs: Long): Boolean {
        val c = cur ?: return false; val t = tgt ?: return false
        val a = 1f - (1f - alpha).pow(dtMs.coerceIn(1, 200) / refMs)
        var moving = false
        for (i in 0..3) {
            val d = t[i] - c[i]
            if (abs(d) < 0.0005f) c[i] = t[i] else { c[i] += d * a; moving = true }
        }
        return moving
    }

    /** 카메라가 흔들린 만큼 박스를 바로 옮긴다 (MotionSink). */
    fun nudge(dx: Float, dy: Float) {
        cur?.let { it[0] += dx; it[2] += dx; it[1] += dy; it[3] += dy }
        tgt?.let { it[0] += dx; it[2] += dx; it[1] += dy; it[3] += dy }
    }

    private fun distance(a: FloatArray, b: FloatArray): Float =
        max4(abs(a[0] - b[0]), abs(a[1] - b[1]), abs(a[2] - b[2]), abs(a[3] - b[3]))

    private fun max4(a: Float, b: Float, c: Float, d: Float) = maxOf(maxOf(a, b), maxOf(c, d))
}
