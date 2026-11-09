package kr.kiosklens.app.di

import kr.kiosklens.core.port.SpeechOutput

/** 읽어 주는 문장을 화면에도 크게 보여 주기 위해 SpeechOutput 을 감싼다(소리가 안 들리는 곳에서도 읽을 수 있게). */
class CaptionSpeechOutput(private val inner: SpeechOutput, private val onCaption: (String) -> Unit) : SpeechOutput {
    override suspend fun speak(text: String, interrupt: Boolean) { onCaption(text); inner.speak(text, interrupt) }
    override fun stop() = inner.stop()
}
