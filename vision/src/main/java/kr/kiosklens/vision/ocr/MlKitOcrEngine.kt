package kr.kiosklens.vision.ocr

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.vision.NormRect
import kr.kiosklens.vision.OcrLine

class MlKitOcrEngine : OcrEngine {
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val closeDetector = CloseIconDetector()

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    override suspend fun read(frame: CameraFrame): List<OcrLine> {
        require(frame.width > 0 && frame.height > 0)
        require(frame.rotationDegrees in listOf(0, 90, 180, 270))
        val input = when (val image = frame.image) {
            is ImageProxy -> InputImage.fromMediaImage(requireNotNull(image.image), frame.rotationDegrees)
            is Bitmap -> InputImage.fromBitmap(image, frame.rotationDegrees)
            else -> error("Unsupported frame type")
        }
        // ML Kit Tasks cannot be cancelled: do not return/close the image while it is in use.
        val result = withContext(NonCancellable) { recognizer.process(input).await() }
        val sideways = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        val width = if (sideways) frame.height else frame.width
        val height = if (sideways) frame.width else frame.height
        fun x(px: Int) = (px.toFloat() / width).coerceIn(0f, 1f)
        fun y(px: Int) = (px.toFloat() / height).coerceIn(0f, 1f)
        val lines = result.textBlocks.flatMapIndexed { index, block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, NormRect(x(box.left), y(box.top), x(box.right), y(box.bottom)),
                    line.confidence.takeIf { it > 0f } ?: 1f, blockId = index)
            }
        }
        val loyaltyTitle = lines.firstOrNull {
            it.text.filter(Char::isLetterOrDigit) in setOf("스탬프적립", "포인트적립")
        }
        // Both text evidence and image evidence are required; coordinates alone never create a button.
        val close = if (loyaltyTitle == null) null else frameLumaImage(frame)?.let {
            closeDetector.detect(it, loyaltyTitle.rect)
        }
        return if (close == null) lines else lines.filterNot {
            it.text.trim() in setOf("X", "x", "×", "✕", "✖") &&
                it.rect.centerX in close.rect.left..close.rect.right &&
                it.rect.centerY in close.rect.top..close.rect.bottom
        } + close
    }

    override fun close() = recognizer.close()
}
