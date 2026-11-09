package kr.kiosklens.vision.ocr

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.model.NormRect

/**
 * ML Kit 한국어 글자 인식 (기기 안에서 동작, 이미지는 밖으로 나가지 않는다).
 * 이미지는 [ImageProxy](실제 카메라) 또는 [Bitmap](사진 입력 디버그 모드).
 *
 * ML Kit은 회전 각도를 넘기면 "돌려서 세운" 이미지 기준 좌표를 돌려준다. 그 크기로 나누어 0~1로 만든다.
 */
class MlKitOcrEngine : OcrEngine {
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    @OptIn(ExperimentalGetImage::class)
    override suspend fun recognize(frame: CameraFrame): List<OcrBlock> {
        val (input, w, h) = when (val img = frame.image) {
            is ImageProxy -> {
                val media = img.image ?: return emptyList()
                val rot = frame.rotationDegrees
                val upright = if (rot == 90 || rot == 270) Pair(img.height, img.width) else Pair(img.width, img.height)
                Triple(InputImage.fromMediaImage(media, rot), upright.first, upright.second)
            }
            is Bitmap -> {
                val rot = frame.rotationDegrees
                val upright = if (rot == 90 || rot == 270) Pair(img.height, img.width) else Pair(img.width, img.height)
                Triple(InputImage.fromBitmap(img, rot), upright.first, upright.second)
            }
            else -> return emptyList()
        }
        if (w <= 0 || h <= 0) return emptyList()
        val text = suspendCancellableCoroutine<Text> { cont ->
            recognizer.process(input)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val out = ArrayList<OcrBlock>()
        for (block in text.textBlocks) for (line in block.lines) {
            val box = line.boundingBox ?: continue
            val rect = NormRect(
                (box.left.toFloat() / w).coerceIn(0f, 1f), (box.top.toFloat() / h).coerceIn(0f, 1f),
                (box.right.toFloat() / w).coerceIn(0f, 1f), (box.bottom.toFloat() / h).coerceIn(0f, 1f),
            )
            out += OcrBlock(line.text, rect, line.confidence)
        }
        return out
    }

    fun close() = recognizer.close()
}
