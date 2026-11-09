package kr.kiosklens.voice.stt

import kr.kiosklens.core.model.SttError

/** android.speech.SpeechRecognizer 의 오류 번호를 [SttError]로. (번호는 Android 문서의 ERROR_* 값) */
object SttMapping {
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
