package kr.kiosklens.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * 키우미 캐릭터(앱 아이콘과 같은 그림)를 하늘색 원 안에 그린다. 로딩 화면이 쓴다.
 *
 * 앱 아이콘(res/drawable/ic_launcher_*.xml)의 108칸 좌표를 그대로 쓴다. 원은 아이콘의 보이는 영역(반지름 36칸)이라
 * Android 12+ 시스템 시작 화면에 나오는 아이콘과 같은 모습 · 같은 크기로 이어진다.
 */
class MascotPainter {
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var u = 0f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DEEP }
    private val inkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DEEP; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SIGNAL }
    private val body = Path()
    private val smile = Path()
    private val oval = RectF()

    /** (cx, cy)를 가운데로, 반지름 [radius]의 하늘색 원 */
    fun layout(cx: Float, cy: Float, radius: Float) {
        this.cx = cx; this.cy = cy; this.radius = radius
        u = radius / VISIBLE_RADIUS
        bg.shader = RadialGradient(x(54f), y(46f), 80f * u, intArrayOf(0xFF86D0F6.toInt(), 0xFF43A6DF.toInt()), null, Shader.TileMode.CLAMP)
        body.reset()
        body.addRoundRect(RectF(x(33f), y(30f), x(75f), y(66f)), 10f * u, 10f * u, Path.Direction.CW)      // 화면
        body.addRect(x(50f), y(64f), x(58f), y(72f), Path.Direction.CW)                                    // 목
        body.addRoundRect(RectF(x(42f), y(71.5f), x(66f), y(76f)), 2.25f * u, 2.25f * u, Path.Direction.CW) // 받침
        smile.reset()
        smile.moveTo(x(47.5f), y(51.5f))
        smile.quadTo(x(54f), y(57.5f), x(60.5f), y(51.5f))
        inkLine.strokeWidth = 3f * u
    }

    /** @param blink 0 = 눈 뜸, 1 = 눈 감음 */
    fun draw(canvas: Canvas, blink: Float = 0f) {
        canvas.drawCircle(cx, cy, radius, bg)
        canvas.drawPath(body, white)
        ellipse(canvas, 41.5f, 51.5f, 3.4f, 2.3f, cheek)                                                   // 볼 (안내 노랑)
        ellipse(canvas, 66.5f, 51.5f, 3.4f, 2.3f, cheek)
        val ry = 3f * (1f - 0.85f * blink.coerceIn(0f, 1f))                                                // 눈: 깜빡이면 납작하게
        ellipse(canvas, 46.5f, 44.5f, 3f, ry, ink)
        ellipse(canvas, 61.5f, 44.5f, 3f, ry, ink)
        canvas.drawPath(smile, inkLine)
    }

    private fun ellipse(canvas: Canvas, ux: Float, uy: Float, rx: Float, ry: Float, paint: Paint) {
        oval.set(x(ux - rx), y(uy - ry), x(ux + rx), y(uy + ry))
        canvas.drawOval(oval, paint)
    }

    private fun x(v: Float) = cx + (v - 54f) * u
    private fun y(v: Float) = cy + (v - 54f) * u

    private companion object {
        /**
         * 원에 담는 아이콘 반지름 (108칸 중): 적응형 아이콘의 보이는 영역 72칸의 절반.
         * 시스템 시작 화면이 이 영역을 원(지름 160dp)에 맞추므로 같아야 넘어갈 때 크기가 안 바뀐다 (33칸이면 9% 커 보였다)
         */
        const val VISIBLE_RADIUS = 36f
    }
}
