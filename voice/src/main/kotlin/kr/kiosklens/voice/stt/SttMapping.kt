package kr.kiosklens.voice.stt

import kr.kiosklens.core.model.SttError

/** 인식 오류 뒤에 할 일. [SttMapping.next]가 고른다. */
enum class SttNext { FALL_BACK_TO_STANDARD, RETRY, FINISH }

/** android.speech.SpeechRecognizer 의 오류 번호를 [SttError]로. (번호는 Android 문서의 ERROR_* 값) */
object SttMapping {
    /**
     * 음성 인식기의 소리 크기(`onRmsChanged`, 대략 -2 ~ 10 dB)를 0~1로. 마이크 버튼 둘레 파동의 크기에 쓴다.
     * 조용하면 0 근처, 보통 말소리는 0.3~0.8.
     */
    fun level(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /**
     * 12 · 13(언어 미지원 · 한국어 모델 없음)도 [SttError.NETWORK]로 둔다.
     * [SttError]에는 "모델 없음" 값이 없고(추가는 :core-model 전원 승인 PR), NO_SPEECH로 바꾸면
     * "말을 안 하셨어요" 흐름(IDLE)으로 가서 더 틀린 안내가 된다. 대신 [next]가 일반 인식기로 넘겨서
     * 이 오류가 어르신께 닿지 않게 한다 (decisions.md U7 · 결정 #3).
     */
    fun toSttError(code: Int): SttError = when (code) {
        ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> SttError.NO_SPEECH
        ERROR_NETWORK_TIMEOUT, ERROR_NETWORK, ERROR_SERVER, ERROR_SERVER_DISCONNECTED,
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> SttError.NETWORK
        ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS, ERROR_CLIENT -> SttError.BUSY
        ERROR_INSUFFICIENT_PERMISSIONS -> SttError.PERMISSION
        else -> SttError.NO_SPEECH                                            // 마이크 오류 등: 다시 말하게 한다
    }

    /** 실패했을 때 한 번 자동으로 다시 듣는가 (설계: 실패 시 1회 자동 재시도). 권한 문제는 다시 해도 같다. */
    fun shouldRetry(error: SttError, alreadyRetried: Boolean): Boolean =
        !alreadyRetried && error != SttError.PERMISSION

    /** 기기에 한국어 인식 모델이 없거나 언어를 지원하지 않는 오류인가 (12 · 13). */
    fun isLanguageUnavailable(code: Int): Boolean =
        code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    /**
     * 인식 오류 뒤에 할 일 (결정 #3: 온디바이스 우선, 한국어 모델이 없으면 일반 인식기).
     * - [offline] = 이번 시도가 오프라인이었는가 (온디바이스 인식기, 또는 일반 인식기 + `EXTRA_PREFER_OFFLINE`).
     * - 오프라인 시도에서 12 · 13 → 일반 인식기 + 온라인으로 바꿔 다시 듣는다. 듣기 전에 바로 나는 오류라
     *   어르신의 말을 잃지 않으므로 1회 자동 재시도는 쓰지 않고 남겨 둔다.
     * - 그 밖에는 [shouldRetry] 규칙대로.
     */
    fun next(code: Int, offline: Boolean, retried: Boolean): SttNext = when {
        offline && isLanguageUnavailable(code) -> SttNext.FALL_BACK_TO_STANDARD
        shouldRetry(toSttError(code), retried) -> SttNext.RETRY
        else -> SttNext.FINISH
    }

    /** 인식 후보 중 가장 믿을 만한 것 (신뢰도가 있으면 그것으로, 없으면 첫 번째). 빈 문장은 제외. */
    fun best(texts: List<String>, confidences: FloatArray?): String? {
        var bestI = -1; var bestC = -1f
        texts.forEachIndexed { i, t ->
            if (t.isBlank()) return@forEachIndexed
            val c = confidences?.getOrNull(i)?.takeIf { it >= 0f } ?: (1f - i * 0.01f)
            if (c > bestC) { bestC = c; bestI = i }
        }
        return texts.getOrNull(bestI)?.trim()
    }
}
