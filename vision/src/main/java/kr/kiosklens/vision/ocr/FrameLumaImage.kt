package kr.kiosklens.vision.ocr

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kr.kiosklens.core.model.CameraFrame

/** Reads a small ROI directly; no full-frame bitmap allocation or image upload. */
internal fun frameLumaImage(frame: CameraFrame): LumaImage? {
    val source = frame.image
    val bitmap = source as? Bitmap
    val proxy = source as? ImageProxy
    if (bitmap == null && proxy == null) return null
    val plane = proxy?.planes?.firstOrNull()
    if (proxy != null && plane == null) return null
    val buffer = plane?.buffer
    val offset = buffer?.position() ?: 0
    val rawWidth = bitmap?.width ?: proxy!!.width
    val rawHeight = bitmap?.height ?: proxy!!.height
    val angle = frame.rotationDegrees
    return object : LumaImage {
        override val width = if (angle == 90 || angle == 270) rawHeight else rawWidth
        override val height = if (angle == 90 || angle == 270) rawWidth else rawHeight
        override fun at(x: Int, y: Int): Int {
            val (rawX, rawY) = when (angle) {
                90 -> y to rawHeight - 1 - x
                180 -> rawWidth - 1 - x to rawHeight - 1 - y
                270 -> rawWidth - 1 - y to x
                else -> x to y
            }
            if (bitmap != null) {
                val color = bitmap.getPixel(rawX.coerceIn(0, rawWidth - 1), rawY.coerceIn(0, rawHeight - 1))
                return (((color shr 16) and 255) * 77 + ((color shr 8) and 255) * 150 + (color and 255) * 29) shr 8
            }
            return buffer!!.get(offset + rawY * plane!!.rowStride + rawX * plane.pixelStride).toInt() and 255
        }
    }
}
