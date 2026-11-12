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
        // Menu text can remain visible behind an unreadable popup. Ordinary screens
        // keep using the generic tracker; do not guess a stage from background names.
        return null
    }
}
