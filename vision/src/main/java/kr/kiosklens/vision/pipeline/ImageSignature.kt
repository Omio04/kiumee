package kr.kiosklens.vision.pipeline

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kr.kiosklens.core.model.CameraFrame

internal fun imageSignature(frame: CameraFrame): IntArray? = when (val image = frame.image) {
    is ImageProxy -> {
        val plane = image.planes.firstOrNull()
        if (plane == null) null else {
            val buffer = plane.buffer
            val offset = buffer.position()
            IntArray(64 * 36) { index ->
                val x = (index % 64 * 2 + 1) * image.width / 128
                val y = (index / 64 * 2 + 1) * image.height / 72
                buffer.get(offset + y * plane.rowStride + x * plane.pixelStride).toInt() and 0xff
            }
        }
    }
    is Bitmap -> IntArray(64 * 36) { index ->
        val color = image.getPixel((index % 64 * 2 + 1) * image.width / 128,
            (index / 64 * 2 + 1) * image.height / 72)
        (((color shr 16) and 255) * 77 + ((color shr 8) and 255) * 150 + (color and 255) * 29) shr 8
    }
    else -> null
}
