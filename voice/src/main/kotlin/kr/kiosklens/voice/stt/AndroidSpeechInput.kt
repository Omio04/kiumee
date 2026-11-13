package kr.kiosklens.voice.stt

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kr.kiosklens.core.model.SttError
import kr.kiosklens.core.model.SttEvent
import kr.kiosklens.core.port.Logger
import kr.kiosklens.core.port.SpeechInput
import kr.kiosklens.voice.EchoGuard

/**
 * 한국어(ko-KR) 음성 인식 (설계서 7.6).
 * - 부분 결과를 내보내고, 발화 뒤 1.2초 조용하면 끝낸다 (Google 인식기에서 이 값이 실제로 먹는지는 아래 [intent] 참고).
 * - 계약: `Final` 또는 `Error` **하나**를 내고 플로우 종료 / 수집을 취소하면 마이크 해제 / 실패는 예외가 아니라 `Error`.
 * - 실패(권한 제외)하면 1회 자동으로 다시 듣는다.
 * - [EchoGuard]가 안내 소리 직후 300ms는 기다리게 한다 (실제로 읽던 중이었을 때만. 조용할 때 누르면 바로 듣는다).
 * - 처음에는 **오프라인**으로 듣는다: 기기 안(온디바이스) 인식이 되면 그것(Android 12+), 안 되면 일반 인식 + 오프라인만.
 * - 오프라인 시도가 한국어 모델 없음(오류 12 · 13)을 내면 재시도 횟수를 쓰지 않고 **일반 인식기 + 온라인**으로 바꾼다.
 *   이후로는 처음부터 온라인 일반 인식기를 쓴다 (결정 #3 · U7: 폴백 때는 음성이 Google 서버로 갈 수 있음).
 *   `EXTRA_PREFER_OFFLINE`은 "오프라인 엔진만"이라는 뜻이라, 폴백에서 끄지 않으면 같은 오류가 또 난다.
 * - 2026-11-12: 오프라인이 헛걸음하는 동안 한 말이 버려져 **앱을 켠 뒤 첫 말 앞부분이 잘렸다**
 *   ("아메리카노 한잔" → "카노 한잔"; 에뮬레이터 실측 버튼 → 마이크 열림 0.70초, 미리 알면 0.36초).
 *   그래서 앱이 켜질 때 미리 알아 둔다:
 *   Android 13+는 인식기에 한국어 오프라인 모델이 설치돼 있는지 물어보고(`checkRecognitionSupport`),
 *   모든 버전에서 "모델 없음"을 겪으면 [SttMapping.OFFLINE_MISSING_REMEMBER_MS] 동안 기억한다 (앱을 다시 켜도 유지).
 * - SpeechRecognizer는 **메인 스레드**에서만 만들고 쓴다.
 * - 로그에는 인식된 글자를 남기지 않는다.
 */
class AndroidSpeechInput(
    private val context: Context,
    private val echo: EchoGuard,
    private val logger: Logger,
    private val silenceMs: Long = 1200,
    /** 말이 시작된 뒤 이 시간이 지나면 녹음을 끊고 지금까지 들은 것으로 답한다 (시끄러운 곳에서 끝없이 듣는 것 방지) */
    private val maxSpeechMs: Long = 8000,
) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: SpeechRecognizer? = null
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /**
     * 한국어 오프라인 인식을 못 쓰는 줄 알면 처음부터 온라인 일반 인식기를 쓴다.
     * 앞서 겪은 "모델 없음"(12 · 13) 기억으로 시작하고, Android 13+는 켜질 때 인식기에 물어서 고친다.
     */
    @Volatile private var offlineUnavailable =
        SttMapping.offlineMissingRemembered(prefs.getLong(KEY_OFFLINE_MISSING_UNTIL, 0L), System.currentTimeMillis())

    init {
        if (Build.VERSION.SDK_INT >= 33) main.post { checkOfflineKorean() }
    }

    private val _level = MutableStateFlow(0f)
    /** 듣는 동안의 목소리 크기 0~1 (마이크 버튼 둘레 파동용). 듣지 않을 때는 0. 포트(`SpeechInput`) 밖의 화면용 정보 */
    val level: StateFlow<Float> = _level.asStateFlow()

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
        var usingOffline = true
        var usingOnDevice = false
        var lastPartial = ""
        var capRunnable = Runnable {}

        fun finish(event: SttEvent) {
            if (finished) return
            finished = true
            main.removeCallbacks(capRunnable)
            _level.value = 0f
            trySend(event)
            close()
        }

        capRunnable = Runnable {                                          // 녹음 상한: 멈추라고 하고, 결과가 안 오면 마지막 부분 결과로 마친다
            if (finished) return@Runnable
            runCatching { recognizer?.stopListening() }
            main.postDelayed({
                if (!finished) finish(if (lastPartial.isNotBlank()) SttEvent.Final(lastPartial) else SttEvent.Error(SttError.NO_SPEECH))
            }, 1500)
        }

        fun begin() {                                                        // 항상 메인 스레드에서 호출
            recognizer?.let { runCatching { it.cancel(); it.destroy() } }
            usingOffline = !offlineUnavailable
            usingOnDevice = usingOffline && canUseOnDevice()
            val r = createRecognizer(usingOnDevice)
            recognizer = r; current = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() { main.removeCallbacks(capRunnable); main.postDelayed(capRunnable, maxSpeechMs) }
                override fun onRmsChanged(rmsdB: Float) { if (!finished) _level.value = SttMapping.level(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!finished && !t.isNullOrBlank()) { lastPartial = t; trySend(SttEvent.Partial(t)) }
                }
                override fun onResults(results: Bundle?) {
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val best = SttMapping.best(texts, results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES))
                    if (best == null) onError(SpeechRecognizer.ERROR_NO_MATCH) else finish(SttEvent.Final(best))
                }
                override fun onError(error: Int) {
                    val e = SttMapping.toSttError(error)
                    logger.w(TAG, "stt error=$error mapped=$e onDevice=$usingOnDevice offline=$usingOffline retried=$retried")
                    if (finished) return
                    when (SttMapping.next(error, usingOffline, retried)) {
                        SttNext.FALL_BACK_TO_STANDARD -> {
                            rememberOfflineKorean(installed = false)
                            logger.w(TAG, "ko-KR offline model missing (error=$error, onDevice=$usingOnDevice), falling back to online standard recognizer")
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
            r.startListening(intent(preferOffline = usingOffline))
        }

        main.post { if (!finished) runCatching { begin() }.onFailure { logger.e(TAG, "stt start failed", it); finish(SttEvent.Error(SttError.BUSY)) } }

        awaitClose {
            _level.value = 0f
            main.removeCallbacks(capRunnable)
            main.post {                                                      // 수집이 취소되면 마이크를 놓는다
                recognizer?.let { runCatching { it.cancel(); it.destroy() } }
                if (current === recognizer) current = null
            }
        }
    }

    override fun stop() {
        _level.value = 0f
        main.post { current?.let { runCatching { it.cancel(); it.destroy() } }; current = null }
    }

    /**
     * Android 13+: 처음 듣기 전에, 먼저 쓸 인식기(온디바이스 또는 일반)에 한국어 오프라인 모델이 설치돼 있는지 묻는다.
     * 없으면 첫 말부터 온라인으로 듣고, 있으면(그사이 내려받음) 다시 오프라인을 쓴다. 묻지 못하면 기존 기억대로 둔다.
     * 메인 스레드에서 호출.
     */
    @TargetApi(33)
    private fun checkOfflineKorean() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        val onDevice = canUseOnDevice()
        val probe = runCatching { createRecognizer(onDevice) }.getOrNull() ?: return
        val release = Runnable { runCatching { probe.destroy() } }
        runCatching {
            probe.checkRecognitionSupport(intent(preferOffline = true), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val installed = SttMapping.hasKorean(support.installedOnDeviceLanguages)
                    rememberOfflineKorean(installed)
                    logger.d(TAG, "offline ko installed=$installed onDevice=$onDevice (installed ${support.installedOnDeviceLanguages.size} languages)")
                    main.post(release)
                }
                override fun onError(error: Int) {
                    logger.w(TAG, "offline ko check failed error=$error onDevice=$onDevice, keeping remembered=$offlineUnavailable")
                    main.post(release)
                }
            })
        }.onFailure { logger.w(TAG, "offline ko check unavailable: $it"); main.post(release) }
    }

    /** 한국어 오프라인 모델이 있는지를 이 인스턴스와 저장소(앱을 다시 켜도 유지)에 적어 둔다 */
    private fun rememberOfflineKorean(installed: Boolean) {
        offlineUnavailable = !installed
        prefs.edit().apply {
            if (installed) remove(KEY_OFFLINE_MISSING_UNTIL)
            else putLong(KEY_OFFLINE_MISSING_UNTIL, System.currentTimeMillis() + SttMapping.OFFLINE_MISSING_REMEMBER_MS)
        }.apply()
    }

    /** 온디바이스 인식기를 쓸 수 있는가: Android 12+ · 서비스 있음. */
    private fun canUseOnDevice(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun createRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    /** [preferOffline]: true면 오프라인 엔진만 쓴다 (Android 문서). 폴백(온라인)일 때만 false. */
    private fun intent(preferOffline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // 조용함 길이 (P3 확인 필요, 2026-11-13 에뮬레이터 logcat): Google 인식기는 COMPLETE_SILENCE를 Int로 읽어서
        // 이 Long 값은 버린다("expected Integer but value was a java.lang.Long") → 실제로는 인식기 기본 끝 판정이 쓰인다.
        // Int로 바꿔 보았을 때 결과가 오지 않은 적이 있지만 다른 변경과 겹쳐 원인을 가르지 못해 그대로 둔다.
        // 바꾸려면 실제 폰에서 따로 여러 번 시험할 것
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
    }

    private companion object {
        const val TAG = "AndroidSpeechInput"
        const val PREFS = "kl_voice"
        /** "한국어 오프라인 모델 없음" 기억이 유효한 마지막 시각 (epoch ms) */
        const val KEY_OFFLINE_MISSING_UNTIL = "offline_ko_missing_until"
    }
}
