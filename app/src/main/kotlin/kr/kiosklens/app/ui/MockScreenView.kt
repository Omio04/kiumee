package kr.kiosklens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.ScreenSnapshot

/** mock 플레이버 전용: 카메라 대신 가짜 키오스크 화면(글자 상자들)을 그린다. 하이라이트가 어디에 뜨는지 볼 수 있다. */
class MockScreenView(context: Context) : View(context) {
    @Volatile var snapshot: ScreenSnapshot? = null
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.GRAY; strokeWidth = 3f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f }

    init { setBackgroundColor(Color.rgb(30, 30, 40)) }

    override fun onDraw(canvas: Canvas) {
        val s = snapshot ?: return
        for (e in s.elements) {
            val r = e.rect
            val l = r.left * width; val t = r.top * height
            box.color = if (e.kind == ElementKind.BUTTON_LIKE) Color.rgb(120, 170, 255) else Color.GRAY
            canvas.drawRect(l, t, r.right * width, r.bottom * height, box)
            canvas.drawText(e.text, l + 12f, t + (r.bottom - r.top) * height / 2f + 14f, text)
        }
    }
}
