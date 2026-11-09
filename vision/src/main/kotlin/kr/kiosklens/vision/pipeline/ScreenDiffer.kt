package kr.kiosklens.vision.pipeline

import kr.kiosklens.core.model.UiElement

/**
 * 화면이 바뀌었는지 판단하고 `stableFrames` 를 센다 (설계서 7.2).
 * - 글자 집합의 Jaccard 유사도가 [threshold] 미만인 프레임이 **연속 [confirmFrames]** 이어야 새 화면.
 *   (카메라가 잠깐 흔들려 한 프레임만 다르게 읽혀도 새 화면으로 보지 않는다)
 * - 요소가 [minElements]개 미만인 프레임은 판단에서 제외 (손가락이 가리거나 화면이 아닐 때).
 */
class ScreenDiffer(
    private val threshold: Float = 0.6f,
    private val confirmFrames: Int = 2,
    private val minElements: Int = 3,
) {
    data class Result(
        /** 판단에서 제외된 프레임이면 null */
        val screenId: String?,
        val stableFrames: Int,
        /** 이번 프레임에서 새 화면으로 확정됐는가 (맨 처음 화면 포함) */
        val isNewScreen: Boolean,
    )

    private var seq = 0
    private var currentId: String? = null
    private var reference: Set<String> = emptySet()
    private var stable = 0
    private var candidate: Set<String>? = null
    private var candidateCount = 0

    fun signature(elements: List<UiElement>): Set<String> = elements.map { TextNorm.norm(it.text) }.filter { it.isNotEmpty() }.toSet()

    fun update(elements: List<UiElement>): Result {
        val sig = signature(elements)
        if (sig.size < minElements) return Result(null, stable, false)
        if (currentId == null) return startScreen(sig)

        if (jaccard(reference, sig) >= threshold) {                    // 같은 화면
            candidate = null; candidateCount = 0
            reference = sig                                            // 천천히 바뀌는 내용(합계 등)을 따라간다
            stable++
            return Result(currentId, stable, false)
        }
        // 다른 화면 후보. 후보끼리도 서로 비슷해야 같은 새 화면의 연속 프레임으로 센다.
        val c = candidate
        if (c != null && jaccard(c, sig) >= threshold) candidateCount++ else { candidateCount = 1 }
        candidate = sig
        if (candidateCount >= confirmFrames) return startScreen(sig)
        return Result(currentId, 0, false)                              // 확정 전: 안정 아님
    }

    /** OCR을 건너뛴(앞 프레임과 똑같은) 프레임. 같은 화면으로 계속 센다. */
    fun repeat(): Result {
        val id = currentId ?: return Result(null, 0, false)
        stable++
        candidate = null; candidateCount = 0
        return Result(id, stable, false)
    }

    private fun startScreen(sig: Set<String>): Result {
        seq++
        currentId = "s%03d".format(seq)
        reference = sig; stable = confirmFrames.coerceAtLeast(1)
        candidate = null; candidateCount = 0
        return Result(currentId, stable, true)
    }

    fun reset() { currentId = null; reference = emptySet(); stable = 0; candidate = null; candidateCount = 0 }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val inter = a.count { it in b }
        return inter.toFloat() / (a.size + b.size - inter)
    }
}
