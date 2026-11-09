package kr.kiosklens.voice.fake

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kr.kiosklens.core.model.SttEvent
import kr.kiosklens.core.port.SpeechInput
import kr.kiosklens.core.port.SpeechOutput

/**
 * 미리 정한 문장을 Partial → Final 로 내보내는 가짜 (mock 플레이버 · 테스트용).
 * [utterances]를 start() 할 때마다 하나씩 쓴다. 다 쓰면 Error(NO_SPEECH).
 */
class FakeSpeechInput(
    utterances: List<String> = emptyList(),
    private val delayMs: Long = 0,
    private val errorOnEmpty: kr.kiosklens.core.model.SttError = kr.kiosklens.core.model.SttError.NO_SPEECH,
) : SpeechInput {
    private val queue = ArrayDeque(utterances)
    var starts = 0; private set
    var stops = 0; private set

    fun enqueue(text: String) { queue.addLast(text) }

    override fun start(): Flow<SttEvent> = flow {
        starts++
        if (delayMs > 0) delay(delayMs)
        val next = queue.removeFirstOrNull()
        if (next == null) { emit(SttEvent.Error(errorOnEmpty)); return@flow }
        if (next.length > 2) emit(SttEvent.Partial(next.take(next.length / 2)))
        emit(SttEvent.Final(next))
    }

    override fun stop() { stops++ }
}

/** 말한 문장을 기록하는 가짜. [readMs] 동안 읽는 척한다(취소하면 즉시 멈춤). */
class FakeSpeechOutput(private val readMs: Long = 0) : SpeechOutput {
    val said = CopyOnWriteArrayList<String>()
    @Volatile var speaking = false; private set
    @Volatile var stops = 0; private set

    override suspend fun speak(text: String, interrupt: Boolean) {
        said += text
        speaking = true
        try { if (readMs > 0) delay(readMs) } finally { speaking = false }
    }

    override fun stop() { stops++ }
}
