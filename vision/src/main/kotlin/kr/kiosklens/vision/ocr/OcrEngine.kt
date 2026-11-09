package kr.kiosklens.vision.ocr

import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.model.NormRect

/** OCR이 읽은 글자 한 덩어리 (줄 단위). 좌표는 회전 보정된 분석 프레임 기준 정규화. */
data class OcrBlock(val text: String, val rect: NormRect, val confidence: Float)

/**
 * 글자 읽기 엔진. 구현을 바꿔 끼울 수 있게 인터페이스로 둔다 (설계서 16.3).
 * 프레임을 닫는 것은 호출한 쪽([kr.kiosklens.vision.VisionScreenAnalyzer])의 일이다.
 */
interface OcrEngine {
    /** 실패하면 예외를 던져도 된다 (호출한 쪽이 잡는다). */
    suspend fun recognize(frame: CameraFrame): List<OcrBlock>
}
