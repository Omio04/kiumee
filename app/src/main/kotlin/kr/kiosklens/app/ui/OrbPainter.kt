package kr.kiosklens.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * 하늘색 구슬(가운데로 갈수록 연한 원형 그라데이션) + 하얀 마이크 · 소리 막대 그리기.
 * 마이크 버튼([MicButtonView])과 로딩 화면([LoadingScreen])이 같이 쓴다.
 * 마이크는 시안 그림의 비율(512칸: 둥근 캡슐 · 받침 호 · 기둥, 선 끝은 둥글게)을 따른다.
 */
class OrbPainter {
    var cx = 0f; private set
    var cy = 0f; private set
    var r = 0f; private set
    private var warm = false

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val capsule = Path()
    private val holder = Path()
    private var stemTop = 0f
    private var stemBottom = 0f

    /** 구슬 자리와 크기를 정한다 (뷰 크기가 바뀔 때) */
    fun layout(cx: Float, cy: Float, r: Float) {
        this.cx = cx; this.cy = cy; this.r = r
        shade()
        val s = r * 1.1f / 512f                                       // 마이크 = 구슬 지름의 55%
        val ox = cx - 256f * s
        val oy = cy - 252f * s                                        // 그림의 세로 가운데(48~456)를 구슬 가운데로
        capsule.reset()
        capsule.addRoundRect(RectF(ox + 192f * s, oy + 48f * s, ox + 320f * s, oy + 304f * s), 64f * s, 64f * s, Path.Direction.CW)
        holder.reset()
        holder.addArc(RectF(ox + 144f * s, oy + 128f * s, ox + 368f * s, oy + 352f * s), 0f, 180f)
        stemTop = oy + 352f * s
        stemBottom = oy + 456f * s
        line.strokeWidth = 32f * s
    }

    /** 문제 상태면 주황빛 구슬 */
    fun setWarm(value: Boolean) {
        if (warm == value) return
        warm = value
        shade()
    }

    private fun shade() {
        if (r <= 0f) return
        val colors = if (warm) intArrayOf(Palette.AMBER_LIGHT, Palette.AMBER) else intArrayOf(Palette.SKY_LIGHT, Palette.SKY)
        circle.shader = RadialGradient(cx, cy, r, colors, floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }

    fun drawOrb(canvas: Canvas) = canvas.drawCircle(cx, cy, r, circle)

    fun drawMic(canvas: Canvas) {
        canvas.drawPath(capsule, fill)
        canvas.drawPath(holder, line)
        canvas.drawLine(cx, stemTop, cx, stemBottom, line)
    }

    /** 말하는 중: 마이크 대신 출렁이는 하얀 소리 막대 (시안 두 번째 그림) */
    fun drawBars(canvas: Canvas, elapsedMs: Long) {
        val w = r * 0.12f
        val gap = r * 0.08f
        val start = cx - (OrbMotion.BARS * w + (OrbMotion.BARS - 1) * gap) / 2f + w / 2f
        val saved = line.strokeWidth
        line.strokeWidth = w
        for (i in 0 until OrbMotion.BARS) {
            val half = r * OrbMotion.bar(i, elapsedMs) * 0.8f
            val x = start + i * (w + gap)
            canvas.drawLine(x, cy - half, x, cy + half, line)
        }
        line.strokeWidth = saved
    }
}
