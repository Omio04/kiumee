package kr.kiosklens.vision.ocr

import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.vision.OcrLine

/** Engines return rotation-corrected normalized boxes; the pipeline owns frame.close(). */
interface OcrEngine : AutoCloseable {
    suspend fun read(frame: CameraFrame): List<OcrLine>
    override fun close() {}
}
