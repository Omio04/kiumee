package kr.kiosklens.overlay.tracking

import kotlin.math.abs

/**
 * 연속한 두 밝기 격자([LumaGrid])를 비교해 **화면 전체가 얼마나 평행 이동했는지** 찾는다 (흔들림 추정).
 *
 * - 거친 격자(1/4)에서 넓게 찾고, 1/2 · 원래 격자에서 차례로 다듬는다 (피라미드). 원래 격자 기준 ±16칸까지 찾는다
 * - 마지막에 포물선으로 칸보다 작은 단위까지 맞춘다
 * - 노출이 바뀌어도 덜 흔들리도록 격자마다 평균 밝기를 빼고 비교한다
 * - 키오스크 메뉴처럼 같은 모양이 반복되면 엉뚱한 칸과 맞을 수 있어서, 같은 값이면 덜 움직인 쪽을 고른다
 * - 확대 · 축소 · 회전은 보지 않는다 (흔들림은 대부분 평행 이동)
 *
 * 결과의 dx · dy는 정규화 좌표 단위(격자 전체 = 1)이고, **화면 속 내용이 움직인 방향**이다.
 * 예: 키오스크가 프레임 안에서 오른쪽으로 갔으면 dx > 0 → 하이라이트도 그만큼 오른쪽으로 옮기면 된다.
 */
class GridMotionEstimator(
    private val coarseRadius: Int = 4,
    private val minTexture: Float = 4f,
) {
    /** confidence: 0..1. 0이면 믿을 수 없음(무늬가 없음 · 흐림 · 화면이 크게 바뀜 · 찾는 범위를 넘음) */
    data class Result(val dx: Float, val dy: Float, val confidence: Float) {
        companion object { val NONE = Result(0f, 0f, 0f) }
    }

    private class Level(val g: FloatArray, val cols: Int, val rows: Int)

    fun estimate(prev: FloatArray, cur: FloatArray, cols: Int, rows: Int): Result {
        require(prev.size >= cols * rows && cur.size >= cols * rows) { "격자 크기가 맞지 않음" }
        if (cols % 4 != 0 || rows % 4 != 0 || cols < 16 || rows < 16) return Result.NONE
        val p0 = Level(centered(prev, cols * rows), cols, rows)
        val c0 = Level(centered(cur, cols * rows), cols, rows)
        val texture = meanAbs(p0.g)
        if (texture < minTexture) return Result.NONE                              // 무늬가 거의 없음 (흰 벽 · 손가락으로 가림)
        // 앞 프레임과 거의 같다 = 움직이지 않았다 (흐리거나 잡음만 다름). 이때는 '움직임 없음'을 믿을 만하게 낸다.
        // 그러지 않으면 가만히 있는데 초점이 잠깐 흐려진 것만으로 위치를 놓친 것으로 보게 된다 (TrackingHealth)
        val zero = cost(p0, c0, 0, 0)
        val still = zero < STILL_RATIO * texture

        val p1 = half(p0); val c1 = half(c0)
        val p2 = half(p1); val c2 = half(c1)

        // 1) 가장 거친 격자에서 넓게. 다른 자리들과 비교해 얼마나 뚜렷하게 맞는지도 본다 (믿을 만한지)
        var bx = 0; var by = 0
        var best = Float.MAX_VALUE
        var bestRaw = 0f
        var sum = 0f; var n = 0
        for (sy in -coarseRadius..coarseRadius) for (sx in -coarseRadius..coarseRadius) {
            val raw = cost(p2, c2, sx, sy)
            if (raw >= TOO_LITTLE_OVERLAP) continue
            sum += raw; n++
            val cost = raw + PENALTY * (abs(sx) + abs(sy))
            if (cost < best) { best = cost; bestRaw = raw; bx = sx; by = sy }
        }
        if (n == 0 || abs(bx) == coarseRadius || abs(by) == coarseRadius)                // 찾는 범위 끝: 너무 크게 움직임
            return if (still) STILL else Result.NONE
        val mean = sum / n
        val distinct = if (mean <= 1e-6f) 0f else 1f - bestRaw / mean            // 1에 가까울수록 한 자리만 뚜렷하게 맞음

        // 2) 한 단계씩 촘촘하게 다듬기
        for ((p, c) in listOf(p1 to c1, p0 to c0)) {
            val cx = bx * 2; val cy = by * 2
            best = Float.MAX_VALUE
            for (sy in cy - 1..cy + 1) for (sx in cx - 1..cx + 1) {
                val cost = cost(p, c, sx, sy) + PENALTY * (abs(sx) + abs(sy))
                if (cost < best) { best = cost; bx = sx; by = sy }
            }
        }

        // 3) 칸보다 작은 단위: 이웃 칸의 값으로 포물선 꼭짓점
        val center = cost(p0, c0, bx, by)
        val fx = parabola(cost(p0, c0, bx - 1, by), center, cost(p0, c0, bx + 1, by))
        val fy = parabola(cost(p0, c0, bx, by - 1), center, cost(p0, c0, bx, by + 1))
        val confidence = if (center > texture * 1.2f) 0f else (distinct * 1.6f).coerceIn(0f, 1f)
        if (still && confidence < STILL.confidence) {                             // 거의 그대로인데 어디로 갔는지 뚜렷하지 않음
            return if (center < zero * 0.7f) Result((bx + fx) / cols, (by + fy) / rows, 0.6f)   // 조금 움직인 게 확실하면 그만큼
            else STILL
        }
        return Result((bx + fx) / cols, (by + fy) / rows, confidence)
    }

    /** cur(x + sx, y + sy)와 prev(x, y)의 평균 밝기 차이 (겹치는 칸만). 겹치는 칸이 너무 적으면 [TOO_LITTLE_OVERLAP] */
    private fun cost(p: Level, c: Level, sx: Int, sy: Int): Float {
        val x0 = maxOf(0, -sx); val x1 = minOf(p.cols, p.cols - sx)
        val y0 = maxOf(0, -sy); val y1 = minOf(p.rows, p.rows - sy)
        val n = (x1 - x0) * (y1 - y0)
        if (x1 <= x0 || y1 <= y0 || n < p.cols * p.rows / 2) return TOO_LITTLE_OVERLAP
        var sum = 0f
        for (y in y0 until y1) {
            val pr = y * p.cols
            val cr = (y + sy) * c.cols + sx
            for (x in x0 until x1) sum += abs(c.g[cr + x] - p.g[pr + x])
        }
        return sum / n
    }

    private fun parabola(left: Float, mid: Float, right: Float): Float {
        val d = left - 2f * mid + right
        if (d <= 1e-6f || left >= TOO_LITTLE_OVERLAP || right >= TOO_LITTLE_OVERLAP) return 0f
        return ((left - right) / (2f * d)).coerceIn(-0.5f, 0.5f)
    }

    private fun centered(g: FloatArray, n: Int): FloatArray {
        var mean = 0f
        for (i in 0 until n) mean += g[i]
        mean /= n
        return FloatArray(n) { g[it] - mean }
    }

    private fun meanAbs(g: FloatArray): Float {
        var s = 0f
        for (v in g) s += abs(v)
        return s / g.size
    }

    private fun half(l: Level): Level {
        val cols = l.cols / 2; val rows = l.rows / 2
        val out = FloatArray(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = (y * 2) * l.cols + x * 2
            out[y * cols + x] = (l.g[i] + l.g[i + 1] + l.g[i + l.cols] + l.g[i + l.cols + 1]) / 4f
        }
        return Level(out, cols, rows)
    }

    private companion object {
        /** 같은 모양이 반복될 때 덜 움직인 쪽을 고르게 하는 아주 작은 벌점 (밝기 차이 단위) */
        const val PENALTY = 0.05f
        const val TOO_LITTLE_OVERLAP = 1e9f
        /** 제자리 차이가 무늬 세기의 이 비율보다 작으면 "거의 그대로" */
        const val STILL_RATIO = 0.35f
        /** 거의 그대로일 때 내는 값: 움직임 없음, 믿을 만함 */
        val STILL = Result(0f, 0f, 0.75f)
    }
}
