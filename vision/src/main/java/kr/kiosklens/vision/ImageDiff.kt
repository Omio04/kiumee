package kr.kiosklens.vision

import kotlin.math.abs

/**
 * 두 프레임의 "큰 모양"이 얼마나 다른가 (화면 전환을 이미지로도 확인하려는 용도, 2026-11-14).
 *
 * 글자 인식(OCR)은 같은 화면도 프레임마다 글자를 다르게 읽고(깨짐) 화면 밖 글자(키보드 · 주소창)가 들락거려 화면 번호가 흔들린다.
 * 반면 작게 줄인 밝기 지문([FrameSampler]가 쓰는 64×36)을 큰 칸(16×9)으로 다시 뭉치면 작은 글자는 평균에 묻히므로
 * 글자가 깨져 읽혀도 값이 거의 안 변하고, 화면 배치가 바뀌면 크게 변한다. 노출이 달라지는 것은 평균을 빼서 없앤다.
 */
internal object ImageDiff {
    const val WIDTH = 64
    const val HEIGHT = 36
    private const val BLOCK = 4

    /** 0(같음)~255(완전 다름). 지문 크기가 다르면 -1 */
    fun blocks(a: IntArray?, b: IntArray?): Float {
        if (a == null || b == null || a.size != WIDTH * HEIGHT || b.size != a.size) return -1f
        val ba = coarse(a); val bb = coarse(b)
        val ma = ba.average().toFloat(); val mb = bb.average().toFloat()
        var sum = 0f
        for (i in ba.indices) sum += abs((ba[i] - ma) - (bb[i] - mb))
        return sum / ba.size
    }

    private fun coarse(grid: IntArray): FloatArray {
        val cols = WIDTH / BLOCK; val rows = HEIGHT / BLOCK
        return FloatArray(cols * rows) { index ->
            val cx = index % cols * BLOCK; val cy = index / cols * BLOCK
            var total = 0
            for (y in 0 until BLOCK) for (x in 0 until BLOCK) total += grid[(cy + y) * WIDTH + cx + x]
            total.toFloat() / (BLOCK * BLOCK)
        }
    }
}
