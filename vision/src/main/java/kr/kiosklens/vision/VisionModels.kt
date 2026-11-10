package kr.kiosklens.vision

// Source compatibility for the standalone demo; actual contracts belong to :core-model.
typealias NormRect = kr.kiosklens.core.model.NormRect
typealias ElementKind = kr.kiosklens.core.model.ElementKind
typealias ScreenElement = kr.kiosklens.core.model.UiElement
typealias ScreenSnapshot = kr.kiosklens.core.model.ScreenSnapshot
typealias FrameSink = kr.kiosklens.core.port.FrameSink
typealias SnapshotSource = kr.kiosklens.core.port.SnapshotSource

// Local OCR metadata; shared :core-model types and wire format are unchanged.
enum class ObservationSource { OCR, VERIFIED_CLOSE_ICON }

data class OcrLine(
    val text: String,
    val rect: NormRect,
    val confidence: Float,
    val blockId: Int? = null,
    val kindHint: ElementKind? = null,
    val source: ObservationSource = ObservationSource.OCR,
)
