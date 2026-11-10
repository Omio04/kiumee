package kr.kiosklens.core.model

enum class ElementKind {
    /** 그 밖의 글자. 화면 문구로만 쓰이고 후보가 되지 않는다. */
    TEXT,

    /** 가격 글자 (예: 4,500원). 후보가 되지 않는다. */
    PRICE,

    /** 누를 수 있어 보이는 글자. 서버가 고르는 후보는 이것뿐이다. */
    BUTTON_LIKE,
}

data class UiElement(
    /** 같은 화면 안에서 안정적인 ID (설계서 4.3, 생성 규칙은 :vision 소유). 화면 안에서 겹치면 안 된다. */
    val id: String,
    /** 정규화 전 원문. 1~[MAX_TEXT_LENGTH]자. */
    val text: String,
    val rect: NormRect,
    /** 0..1 */
    val confidence: Float,
    val kind: ElementKind,
) {
    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_TEXT_LENGTH = 80
    }
}

data class ScreenSnapshot(
    /** 화면 단위 식별자. 화면 전환 시 바뀐다. */
    val screenId: String,
    val frameId: Long,
    val timestampMs: Long,
    /** 최대 [MAX_ELEMENTS]개 */
    val elements: List<UiElement>,
    /** 연속으로 같은 화면으로 판정된 프레임 수. 발행 조건은 2 이상. */
    val stableFrames: Int,
) {
    fun findElement(id: String): UiElement? = elements.firstOrNull { it.id == id }

    companion object {
        const val MAX_ELEMENTS = 300
    }
}
