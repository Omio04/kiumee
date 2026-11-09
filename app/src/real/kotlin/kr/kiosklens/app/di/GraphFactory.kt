package kr.kiosklens.app.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kr.kiosklens.agent.AgentConfig
import kr.kiosklens.agent.RemoteGuideAgent
import kr.kiosklens.app.BuildConfig
import kr.kiosklens.core.port.Clock
import kr.kiosklens.core.port.FrameSink
import kr.kiosklens.domain.runner.SessionRunner
import kr.kiosklens.domain.session.SessionStateMachine
import kr.kiosklens.overlay.ViewOverlay
import kr.kiosklens.vision.VisionScreenAnalyzer
import kr.kiosklens.vision.ocr.MlKitOcrEngine
import kr.kiosklens.vision.pipeline.ImageProxyThumbnail
import kr.kiosklens.voice.EchoGuard
import kr.kiosklens.voice.stt.AndroidSpeechInput
import kr.kiosklens.voice.tts.AndroidSpeechOutput

/** real 플레이버: 포트마다 실구현을 끼운다. (로직 없음 — 끼우기만) */
object GraphFactory {
    fun create(context: Context, scope: CoroutineScope, overlay: ViewOverlay): Graph {
        val app = context.applicationContext
        val logger = AndroidLogger()
        val clock = Clock { System.currentTimeMillis() }
        val echo = EchoGuard()

        val ocr = MlKitOcrEngine()
        val vision = VisionScreenAnalyzer(scope, ocr, clock, logger, thumbnailer = ImageProxyThumbnail::of)
        val tts = AndroidSpeechOutput(app, echo, logger)
        var caption: (String) -> Unit = {}
        val speechOut = CaptionSpeechOutput(tts) { caption(it) }
        val speechIn = AndroidSpeechInput(app, echo, logger)
        val agent = RemoteGuideAgent(AgentConfig(BuildConfig.SERVER_URL, BuildConfig.APP_KEY), logger)

        val runner = SessionRunner(scope, SessionStateMachine(), vision, speechIn, speechOut, agent, overlay, clock, logger)
        return object : Graph {
            override val runner = runner
            override val frameSink: FrameSink = vision
            override fun setCaptionListener(listener: (String) -> Unit) { caption = listener }
            override fun close() { runner.close(); tts.shutdown(); ocr.close() }
        }
    }
}
