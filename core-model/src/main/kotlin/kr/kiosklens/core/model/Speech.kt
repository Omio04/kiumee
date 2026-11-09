package kr.kiosklens.core.model

enum class SttError { NO_SPEECH, NETWORK, BUSY, PERMISSION }

sealed interface SttEvent {
    data class Partial(val text: String) : SttEvent
    data class Final(val text: String) : SttEvent
    data class Error(val code: SttError) : SttEvent
}
