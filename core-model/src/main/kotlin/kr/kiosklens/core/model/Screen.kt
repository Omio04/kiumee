package kr.kiosklens.core.model

enum class ElementKind { TEXT, PRICE, BUTTON_LIKE }

data class UiElement(
    /** 같은 화면 안에서 안정적인 ID (설계서 4.3, 생성 규칙은 :vision 소유). */
    val id: String,
    /** 정규화 전 원문. */
    val text: String,
    val rect: NormRect,
    /** 0..1 */
    val confidence: Float,
    val kind: ElementKind,
)

data class ScreenSnapshot(
    /** 화면 단위 식별자. 화면 전환 시 바뀐다. */
    val screenId: String,
    val frameId: Long,
    val timestampMs: Long,
    val elements: List<UiElement>,
    /** 연속으로 같은 화면으로 판정된 프레임 수. 발행 조건은 2 이상. */
    val stableFrames: Int,
) {
    fun findElement(id: String): UiElement? = elements.firstOrNull { it.id == id }
}
