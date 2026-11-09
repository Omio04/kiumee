package kr.kiosklens.app.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kr.kiosklens.agent.FakeGuideAgent
import kr.kiosklens.core.port.Clock
import kr.kiosklens.core.port.FrameSink
import kr.kiosklens.domain.runner.SessionRunner
import kr.kiosklens.domain.session.SessionStateMachine
import kr.kiosklens.overlay.ViewOverlay
import kr.kiosklens.vision.fake.FakeScreenAnalyzer
import kr.kiosklens.vision.fake.SampleScreens
import kr.kiosklens.voice.fake.FakeSpeechInput
import kr.kiosklens.voice.fake.FakeSpeechOutput

/** mock 플레이버: 모든 포트에 Fake. 카메라 · 마이크 · 서버 없이 전체 흐름(상태머신 → 안내 → 하이라이트)을 본다. */
object GraphFactory {
    fun create(context: Context, scope: CoroutineScope, overlay: ViewOverlay): Graph {
        val logger = AndroidLogger()
        val screens = FakeScreenAnalyzer(SampleScreens.cafe)
        val said = listOf("아메리카노 주세요", "HOT", "결제할게요")
        val speechIn = FakeSpeechInput(said + said + said, delayMs = 800)
        var caption: (String) -> Unit = {}
        val speechOut = CaptionSpeechOutput(FakeSpeechOutput(readMs = 1500)) { caption(it) }
        val runner = SessionRunner(scope, SessionStateMachine(), screens, speechIn, speechOut, FakeGuideAgent(), overlay,
            Clock { System.currentTimeMillis() }, logger)
        screens.show(1)                                                 // 메뉴 화면에서 시작
        return object : Graph {
            override val runner = runner
            override val frameSink: FrameSink = screens
            override fun setCaptionListener(listener: (String) -> Unit) { caption = listener }
            override fun debugNextScreen() = screens.next()
            override fun close() = runner.close()
        }
    }
}
