package kr.kiosklens.overlay

/**
 * 하이라이트를 그린 간격으로 그리기 속도를 잰다 (설계서 부록 A: 오버레이 렌더 30fps 이상, 측정 P5).
 *
 * - 최근 [windowMs] 동안 그린 횟수로 1초에 몇 번 그렸는지(fps)를 낸다
 * - 그 안에서 가장 길었던 간격을 [Snapshot.worstMs]로 낸다 (한 번이라도 크게 끊기면 여기에 보인다)
 * - 하이라이트가 없어 그리기를 쉬었거나([pause]), 간격이 [gapMs]보다 길면 그 사이는 세지 않고 새로 잰다
 *
 * 메인 스레드(onDraw)에서만 쓴다. 시간은 밖에서 넣어 JVM에서 테스트한다.
 */
class RenderStats(private val windowMs: Long = 1_000L, private val gapMs: Long = 250L) {
    /** fps: 1초에 그린 횟수, worstMs: 가장 길었던 간격, frames: 계산에 쓴 그리기 횟수 */
    data class Snapshot(val fps: Float, val worstMs: Long, val frames: Int) {
        /** 합격 기준(30fps 이상)을 넘는지. 잴 만큼 모이지 않았으면 null */
        val meetsTarget: Boolean? get() = if (frames < MIN_FRAMES) null else fps >= TARGET_FPS
    }

    private val times = ArrayDeque<Long>()

    /** 한 번 그렸다. */
    fun onFrame(nowMs: Long) {
        val last = times.lastOrNull()
        if (last != null && (nowMs - last > gapMs || nowMs < last)) times.clear()     // 쉬었다가 다시 시작 → 새로 잰다
        times.addLast(nowMs)
        while (times.size > 2 && nowMs - times.first() > windowMs) times.removeFirst()
    }

    /** 그리기를 멈췄다 (하이라이트 없음). 다음 그리기부터 새로 잰다. */
    fun pause() = times.clear()

    fun snapshot(): Snapshot {
        if (times.size < 2) return Snapshot(0f, 0L, times.size)
        val span = times.last() - times.first()
        var worst = 0L
        var prev = times.first()
        for (t in times) { worst = maxOf(worst, t - prev); prev = t }
        val fps = if (span <= 0L) 0f else (times.size - 1) * 1000f / span
        return Snapshot(fps, worst, times.size)
    }

    companion object {
        const val TARGET_FPS = 30f
        const val MIN_FRAMES = 10
    }
}
