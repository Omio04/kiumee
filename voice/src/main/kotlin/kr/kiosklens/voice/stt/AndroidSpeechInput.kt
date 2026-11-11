package kr.kiosklens.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kr.kiosklens.core.model.SttError
import kr.kiosklens.core.model.SttEvent
import kr.kiosklens.core.port.Logger
import kr.kiosklens.core.port.SpeechInput
import kr.kiosklens.voice.EchoGuard

/**
 * 한국어(ko-KR) 음성 인식 (설계서 7.6).
 * - 부분 결과를 내보내고, 발화 뒤 1.2초 조용하면 끝낸다.
 * - 계약: `Final` 또는 `Error` **하나**를 내고 플로우 종료 / 수집을 취소하면 마이크 해제 / 실패는 예외가 아니라 `Error`.
 * - 실패(권한 제외)하면 1회 자동으로 다시 듣는다.
 * - [EchoGuard]가 안내 소리 직후 300ms는 기다리게 한다.
 * - 기기 안(온디바이스) 인식이 되면 그것을 쓴다(Android 12+). 안 되면 일반 인식 + 오프라인 선호.
 * - 온디바이스 인식기가 한국어 모델 없음(오류 12 · 13)을 내면 재시도 횟수를 쓰지 않고 일반 인식기로 바꾼다.
 *   이후 이 인스턴스는 일반 인식기만 쓴다 (결정 #3 · U7).
 * - SpeechRecognizer는 **메인 스레드**에서만 만들고 쓴다.
 * - 로그에는 인식된 글자를 남기지 않는다.
 */
class AndroidSpeechInput(
    private val context: Context,
    private val echo: EchoGuard,
    private val logger: Logger,
    private val silenceMs: Long = 1200,
) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: SpeechRecognizer? = null
    /** 온디바이스 인식기가 한국어 모델 없음(12 · 13)을 낸 적이 있으면, 이후로는 처음부터 일반 인식기를 쓴다. */
    @Volatile private var onDeviceUnusable = false

    override fun start(): Flow<SttEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            logger.w(TAG, "recognition service unavailable")
            trySend(SttEvent.Error(SttError.NETWORK)); close(); return@callbackFlow
        }
        // 안내 소리의 끝울림이 사라질 때까지 (최대 300ms 씩 몇 번만) 기다린다
        var guardTries = 0
        while (echo.waitMs() > 0 && guardTries++ < 20) delay(echo.waitMs().coerceAtLeast(20))

        var finished = false
        var retried = false
        var recognizer: SpeechRecognizer? = null
        var usingOnDevice = false

        fun finish(event: SttEvent) {
            if (finished) return
            finished = true
            trySend(event)
            close()
        }

        fun begin() {                                                        // 항상 메인 스레드에서 호출
            recognizer?.let { runCatching { it.cancel(); it.destroy() } }
            usingOnDevice = canUseOnDevice()
            val r = createRecognizer(usingOnDevice)
            recognizer = r; current = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!finished && !t.isNullOrBlank()) trySend(SttEvent.Partial(t))
                }
                override fun onResults(results: Bundle?) {
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val best = SttMapping.best(texts, results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES))
                    if (best == null) onError(SpeechRecognizer.ERROR_NO_MATCH) else finish(SttEvent.Final(best))
                }
                override fun onError(error: Int) {
                    val e = SttMapping.toSttError(error)
                    logger.w(TAG, "stt error=$error mapped=$e onDevice=$usingOnDevice retried=$retried")
                    if (finished) return
                    when (SttMapping.next(error, usingOnDevice, retried)) {
                        SttNext.FALL_BACK_TO_STANDARD -> {
                            onDeviceUnusable = true
                            logger.w(TAG, "ko-KR on-device model missing (error=$error), falling back to standard recognizer")
                            main.postDelayed({ if (!finished) begin() }, 250)
                        }
                        SttNext.RETRY -> {
                            retried = true
                            main.postDelayed({ if (!finished) begin() }, 250)
                        }
                        SttNext.FINISH -> finish(SttEvent.Error(e))
                    }
                }
            })
            r.startListening(intent())
        }

        main.post { if (!finished) runCatching { begin() }.onFailure { logger.e(TAG, "stt start failed", it); finish(SttEvent.Error(SttError.BUSY)) } }

        awaitClose {
            main.post {                                                      // 수집이 취소되면 마이크를 놓는다
                recognizer?.let { runCatching { it.cancel(); it.destroy() } }
                if (current === recognizer) current = null
            }
        }
    }

    override fun stop() {
        main.post { current?.let { runCatching { it.cancel(); it.destroy() } }; current = null }
    }

    /** 온디바이스 인식기를 쓸 수 있는가: Android 12+ · 서비스 있음 · 한국어 모델 없음을 겪은 적 없음. */
    private fun canUseOnDevice(): Boolean =
        !onDeviceUnusable && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun createRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
    }

    private companion object { const val TAG = "AndroidSpeechInput" }
}
