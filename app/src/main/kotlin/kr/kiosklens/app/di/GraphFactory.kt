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
import kr.kiosklens.vision.createVisionScreenAnalyzer
import kotlinx.coroutines.launch
import kr.kiosklens.voice.EchoGuard
import kr.kiosklens.voice.barge.BargeInMonitor
import kr.kiosklens.voice.stt.AndroidSpeechInput
import kr.kiosklens.voice.tts.AndroidSpeechOutput

/**
 * 포트마다 실구현을 끼운다 (로직 없음 — 끼우기만, 설계서 16.8).
 * 2일차 결정: Fake · mock 플레이버 없이 처음부터 실구현으로 간다 (docs/decisions.md 5장).
 */
object GraphFactory {
    fun create(context: Context, scope: CoroutineScope, overlay: ViewOverlay): Graph {
        val app = context.applicationContext
        val logger = AndroidLogger()
        val clock = Clock { System.currentTimeMillis() }
        val echo = EchoGuard()                                                   // TTS와 STT가 같은 인스턴스를 공유 (에코 방지)

        val vision = createVisionScreenAnalyzer(logger = logger)                 // P2: ML Kit 한국어 OCR + 화면 추적 (자체 작업 스레드)
        val tts = AndroidSpeechOutput(app, echo, logger, bargeIn = if (BuildConfig.BARGE_IN) BargeInMonitor(app, logger) else null)
        var caption: (String) -> Unit = {}
        val speechOut = CaptionSpeechOutput(tts) { caption(it) }
        val speechIn = AndroidSpeechInput(app, echo, logger)
        val agent = RemoteGuideAgent(AgentConfig(BuildConfig.SERVER_URL, BuildConfig.APP_KEY), logger)

        val runner = SessionRunner(scope, SessionStateMachine(), vision, speechIn, speechOut, agent, overlay, clock, logger)
        // 읽는 중 사람이 말하면 "말하기" 버튼을 누른 것과 같게: 읽던 안내를 끊고 듣는다 (상태머신은 그대로)
        scope.launch { tts.bargeIns.collect { runner.pressStart() } }
        return object : Graph {
            override val runner = runner
            override val frameSink: FrameSink = vision
            override val snapshots = vision.snapshots
            override val scanStatus = vision.status
            override val voiceLevel = speechIn.level
            override fun setCaptionListener(listener: (String) -> Unit) { caption = listener }
            override fun close() { runner.close(); tts.shutdown(); vision.close() }
        }
    }
}
