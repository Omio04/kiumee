package kr.kiosklens.voice.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kr.kiosklens.core.port.Logger
import kr.kiosklens.core.port.SpeechOutput
import kr.kiosklens.voice.EchoGuard

/**
 * 한국어 음성 안내 (설계서 7.6): 속도 0.85, 새 안내가 오면 이전 안내를 끊는다.
 * 계약: `speak()`는 재생이 **끝날 때** 돌아온다 / 코루틴이 취소되면 즉시 멈춘다 / `interrupt=true`면 직전 안내를 끊는다.
 * TextToSpeech 준비는 비동기라서 처음 `speak()`는 준비될 때까지(최대 5초) 기다린다.
 * 긴 문장은 문장 단위로 나눠 읽는다(엔진 길이 제한 · 중간 멈춤 대비).
 */
class AndroidSpeechOutput(
    context: Context,
    private val echo: EchoGuard,
    private val logger: Logger,
    private val rate: Float = 0.85f,
) : SpeechOutput {
    private val ready = CompletableDeferred<Boolean>()
    private val waiting = ConcurrentHashMap<String, () -> Unit>()
    private val ids = AtomicInteger()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke() } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke() } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { waiting.remove(it)?.invoke() } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { waiting.remove(it)?.invoke() } }
        })
    }

    override suspend fun speak(text: String, interrupt: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val ok = withTimeoutOrNull(5_000) { ready.await() } ?: false
        if (!ok) { logger.w(TAG, "tts not ready"); return }                      // 소리를 못 내도 흐름은 이어 간다
        tts.language = Locale.KOREA
        tts.setSpeechRate(rate)
        echo.speakingStarted()
        try {
            val parts = SpeechSplit.split(clean)
            parts.forEachIndexed { i, part ->
                val mode = if (i == 0 && interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                playOne(part, mode)
            }
        } finally {
            echo.speakingEnded()
        }
    }

    private suspend fun playOne(part: String, mode: Int) = suspendCancellableCoroutine<Unit> { cont ->
        val id = "kl-" + ids.incrementAndGet()
        waiting[id] = { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { waiting.remove(id); tts.stop() }            // 취소 → 즉시 멈춤
        val r = tts.speak(part, mode, Bundle(), id)
        if (r != TextToSpeech.SUCCESS) { waiting.remove(id); if (cont.isActive) cont.resume(Unit) }
    }

    override fun stop() {
        tts.stop()
        waiting.values.toList().forEach { it() }
        waiting.clear()
        echo.speakingEnded()
    }

    fun shutdown() { tts.stop(); tts.shutdown() }

    companion object {
        private const val TAG = "AndroidSpeechOutput"
    }
}
