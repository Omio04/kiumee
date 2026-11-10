package kr.kiosklens.vision

import kotlin.math.abs

/** Pure sampling policy; time comes from the injected Clock. */
internal class FrameSampler {
    private var lastAttempt: Long? = null
    private var lastAnalysis: Long? = null
    private var baseline: IntArray? = null
    private var orientation: Int? = null

    fun due(now: Long): Boolean = lastAttempt?.let { now - it >= 334 || now < it } ?: true

    fun shouldAnalyze(now: Long, confirm: Boolean, grid: IntArray?, angle: Int): Boolean {
        if (!due(now)) return false
        lastAttempt = now
        val before = baseline
        val elapsed = lastAnalysis?.let { now - it }
        return confirm || grid == null || before == null || grid.size != before.size ||
            orientation != angle || elapsed == null || elapsed !in 0L..1_999L ||
            grid.indices.sumOf { abs(grid[it] - before[it]) } >= grid.size * 4
    }

    fun analyzed(grid: IntArray?, angle: Int, now: Long) {
        baseline = grid
        orientation = angle
        lastAnalysis = now
    }
}
