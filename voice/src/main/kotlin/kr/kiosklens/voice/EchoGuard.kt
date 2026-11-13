package kr.kiosklens.voice

import java.util.concurrent.atomic.AtomicLong

/**
 * 에코 방지: 안내 소리가 끝난 뒤 [quietMs](300ms) 동안은 마이크를 켜지 않는다 (설계서 7.6).
 * 스피커 소리가 마이크로 다시 들어가 "내가 한 말"로 오인되는 것을 막는다.
 * 같은 인스턴스를 [kr.kiosklens.voice.tts.AndroidSpeechOutput]과 [kr.kiosklens.voice.stt.AndroidSpeechInput]에 함께 넘긴다.
 */
class EchoGuard(private val nowMs: () -> Long = System::currentTimeMillis, val quietMs: Long = 300) {
    private val lastEnd = AtomicLong(Long.MIN_VALUE / 2)
    @Volatile private var speaking = false

    fun speakingStarted() { speaking = true }
    fun speakingEnded() { lastEnd.set(nowMs()); speaking = false }

    /**
     * 소리를 (중간에) 멈췄다: 실제로 읽던 중이었을 때만 "방금 끝남"으로 적는다.
     * 마이크 버튼을 누르면 늘 '소리 멈춤' 뒤에 '듣기'가 오는데, 조용할 때도 끝남으로 적으면 누를 때마다 [quietMs]를 괜히
     * 기다려 그동안 한 말 앞부분이 잘린다 (2026-11-13 에뮬레이터: 버튼 → 마이크 열림 0.36초 → 0.15초).
     */
    fun speakingStopped() { if (speaking) speakingEnded() }

    /**
     * 사람이 말해서 안내를 끊었다: 울림 대기([quietMs]) 없이 바로 마이크를 켠다.
     * 기다리면 사람이 말을 시작한 앞부분이 잘린다. 스피커는 이미 멈췄고 사람 목소리가 마이크에 들어오는 중이다.
     */
    fun speakingInterrupted() { speaking = false; lastEnd.set(Long.MIN_VALUE / 2) }

    /** 지금부터 마이크를 켜도 되기까지 더 기다려야 하는 시간(ms). 0이면 바로 켜도 됨. */
    fun waitMs(): Long {
        if (speaking) return quietMs                                    // 아직 읽는 중이면 짧게 다시 확인하게 한다
        return (lastEnd.get() + quietMs - nowMs()).coerceIn(0, quietMs)
    }

    fun isSpeaking() = speaking
}
