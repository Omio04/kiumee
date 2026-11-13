package kr.kiosklens.vision

import kr.kiosklens.vision.pipeline.SceneProcessor

/** P2-local interpretation. No new fields are added to the shared snapshot/HTTP contract. */
class ScreenInterpreter {
    private val scenes = SceneProcessor()

    fun classify(snapshot: ScreenSnapshot): String? = classifyLines(snapshot.elements.map {
        OcrLine(it.text, it.rect, it.confidence, kindHint = it.kind)
    })

    internal fun classifyLines(lines: List<OcrLine>): String? {
        scenes.detect(lines)?.let { return it.name.takeUnless { name-> name=="uncertain-dialog" } }
        val cart = lines.firstOrNull { Elements.key(it.text) == "장바구니조회" } ?: return null
        val checkout = lines.any { Elements.key(it.text).endsWith("결제하기") && it.rect.top >= cart.rect.top }
        return "menu-cart".takeIf { checkout }
    }
}
