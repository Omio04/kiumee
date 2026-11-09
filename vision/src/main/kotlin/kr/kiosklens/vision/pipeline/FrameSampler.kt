package kr.kiosklens.vision.pipeline

import kotlin.math.abs

/**
 * 어떤 프레임에 OCR을 돌릴지 정한다 (설계서 7.1): 초당 [fps]장 이하로, 앞 프레임과 거의 같으면 건너뛴다.
 * 건너뛴 프레임도 "같은 화면이 한 번 더 보였다"는 뜻이라 ScreenDiffer.repeat()로 센다.
 */
class FrameSampler(fps: Int = 3, private val diffThreshold: Float = 2.0f) {
    enum class Decision { RUN_OCR, SAME_AS_LAST, DROP }

    private val minIntervalMs = 1000L / fps.coerceAtLeast(1)
    private var lastRunMs = Long.MIN_VALUE / 2
    private var lastThumb: ByteArray? = null
    private var sinceOcr = 0

    /** thumb: 64×36 흑백 축소 이미지 (없으면 시간만 본다) */
    fun decide(nowMs: Long, thumb: ByteArray?): Decision {
        if (nowMs - lastRunMs < minIntervalMs) return Decision.DROP
        lastRunMs = nowMs
        val prev = lastThumb
        if (thumb != null && prev != null && prev.size == thumb.size && meanAbsDiff(prev, thumb) < diffThreshold && sinceOcr < MAX_SKIPS) {
            sinceOcr++
            return Decision.SAME_AS_LAST
        }
        lastThumb = thumb; sinceOcr = 0
        return Decision.RUN_OCR
    }

    private fun meanAbsDiff(a: ByteArray, b: ByteArray): Float {
        var s = 0L
        for (i in a.indices) s += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return s.toFloat() / a.size
    }

    private companion object { const val MAX_SKIPS = 6 }          // 화면이 정지해 있어도 가끔은 다시 읽는다
}
