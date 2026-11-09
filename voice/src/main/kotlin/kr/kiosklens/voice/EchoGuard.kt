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

    /** 지금부터 마이크를 켜도 되기까지 더 기다려야 하는 시간(ms). 0이면 바로 켜도 됨. */
    fun waitMs(): Long {
        if (speaking) return quietMs                                    // 아직 읽는 중이면 짧게 다시 확인하게 한다
        return (lastEnd.get() + quietMs - nowMs()).coerceIn(0, quietMs)
    }

    fun isSpeaking() = speaking
}
