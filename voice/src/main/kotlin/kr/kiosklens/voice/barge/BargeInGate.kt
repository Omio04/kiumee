package kr.kiosklens.voice.barge

/**
 * 말 끊기(barge-in) 판단: 앱이 안내를 읽는 중에 **사람이 말을 시작했는가**를 마이크 소리 크기로 판단한다.
 *
 * 어려운 점: 앱 자기 소리(스피커 → 마이크 에코)와 사람 목소리를 구분해야 한다. 구분하지 못하면 앱이 자기 소리를 사람 말로 듣고
 * 스스로 끊긴다 (2026-11-13 실기기: 앱이 자기가 한 "잠시만 기다려 주세요"를 어르신 말로 받아 적은 기록이 있다).
 * 그래서 이렇게 한다:
 * 1. 읽기 시작한 처음 [calibrationFrames] 동안은 판단하지 않고 에코의 크기를 잰다 (사람은 아직 말하지 않았다고 본다).
 *    사람이 일찍 말해도 기준이 부풀지 않게 가장 조용한 절반만 평균한다.
 * 2. 그 뒤 소리가 `max(minRms, 에코 × ratio)`를 넘는 프레임이 [holdFrames] 이어지면(사이에 [gapFrames]까지는 끊겨도 됨) 사람이 말한 것이다.
 *    에코는 크기가 거의 일정하지만, 사람이 폰 가까이에서 말하면 에코보다 확실히 크다.
 * 3. 한 번 울린 뒤에는 [reset]할 때까지 다시 울리지 않는다.
 *
 * 프레임 하나는 보통 20ms. 소리 크기는 PCM16 샘플의 RMS(0~32767). 시간과 마이크에 의존하지 않는 순수 계산이라 JVM 테스트로 확인한다.
 * 실제 기기마다 에코 크기가 달라서 상수는 현장에서 조정할 값이다 (아래 값은 첫 추정).
 */
class BargeInGate(
    private val calibrationFrames: Int = 15,        // 0.3초: 에코 크기를 재는 구간
    private val holdFrames: Int = 12,               // 0.24초: 이만큼 이어져야 사람 말
    private val gapFrames: Int = 2,                 // 말 사이의 짧은 끊김 허용
    private val minRms: Float = 900f,               // 이보다 작은 소리는 아무리 에코보다 커도 말이 아니다 (-31dBFS)
    private val ratio: Float = 2.0f,                // 에코의 몇 배면 사람 목소리로 볼까
) {
    private val calibration = ArrayList<Float>(calibrationFrames)
    private var echoLevel = 0f
    private var run = 0                             // 기준을 넘은 프레임이 연속된 수
    private var gap = 0
    private var fired = false

    val threshold: Float get() = maxOf(minRms, echoLevel * ratio)
    val calibrated: Boolean get() = calibration.size >= calibrationFrames

    /** 프레임 하나의 소리 크기를 넣는다. 사람이 말을 시작했다고 판단한 순간 한 번만 true. */
    fun onFrame(rms: Float): Boolean {
        if (fired) return false
        if (!calibrated) {
            calibration += rms
            if (calibrated) echoLevel = quietHalfMean(calibration)
            return false
        }
        if (rms >= threshold) {
            run++; gap = 0
            if (run >= holdFrames) { fired = true; return true }
        } else if (run > 0) {
            gap++
            if (gap > gapFrames) { run = 0; gap = 0 }          // 끊김이 너무 길면 처음부터
        }
        return false
    }

    /** 다음 안내를 읽기 시작할 때 처음부터 (에코 크기를 다시 잰다). */
    fun reset() {
        calibration.clear(); echoLevel = 0f; run = 0; gap = 0; fired = false
    }

    private fun quietHalfMean(values: List<Float>): Float {
        val low = values.sorted().take(maxOf(1, values.size / 2))
        return low.average().toFloat()
    }
}
