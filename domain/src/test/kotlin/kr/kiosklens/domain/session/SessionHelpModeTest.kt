package kr.kiosklens.domain.session

import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.model.SttError
import kr.kiosklens.domain.session.Screens.menu
import kr.kiosklens.domain.session.Screens.options
import kr.kiosklens.domain.session.SessionEffect.ClearHighlight
import kr.kiosklens.domain.session.SessionEffect.RequestGuide
import kr.kiosklens.domain.session.SessionEffect.Speak
import kr.kiosklens.domain.session.SessionEffect.StartListening
import kr.kiosklens.domain.session.SessionEffect.StartTimer
import kr.kiosklens.domain.session.SessionEffect.StopListening
import kr.kiosklens.domain.session.SessionEvent.GuideFailed
import kr.kiosklens.domain.session.SessionEvent.GuideReceived
import kr.kiosklens.domain.session.SessionEvent.ScreenChanged
import kr.kiosklens.domain.session.SessionEvent.SnapshotReceived
import kr.kiosklens.domain.session.SessionEvent.SpeechFailed
import kr.kiosklens.domain.session.SessionEvent.SpeechFinished
import kr.kiosklens.domain.session.SessionEvent.SpeechRecognized
import kr.kiosklens.domain.session.SessionEvent.StartPressed
import kr.kiosklens.domain.session.SessionEvent.TimedOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 도움 모드 (2026-11-13): 어르신이 물어볼 때만 그 순간의 화면을 보고 답하고, 버튼 없이 계속 듣는다.
 * 예전 방식(helpMode=false)은 SessionStateMachineTest가 그대로 보증한다.
 */
class SessionHelpModeTest {
    private class Driver(var state: SessionState = SessionState()) {
        private val machine = SessionStateMachine(helpMode = true)
        fun send(event: SessionEvent): Transition = machine.reduce(state, event).also { state = it.next }
        fun send(make: (ticket: Int) -> SessionEvent): Transition = send(make(state.ticket))
        val ticket get() = state.ticket
        val phase get() = state.phase
    }

    private val question = "이건 또 뭘 고르라는 거야?"

    /** 메뉴 화면이 보이는 상태에서 한 번 눌러 듣기 시작 */
    private fun listening(): Driver = Driver().apply {
        send(SnapshotReceived(menu))
        send(StartPressed("s"))
    }

    private fun asked(): Driver = listening().apply { send { SpeechRecognized(it, question) } }

    @Test fun `질문하면 그 순간의 화면으로 서버에 묻는다`() {
        val d = listening()
        val tr = d.send { SpeechRecognized(it, question) }
        assertEquals(Phase.PLANNING, d.phase)
        val req = (tr.effects.last() as RequestGuide).request
        assertEquals(question, req.utterance)
        assertEquals(menu, req.snapshot)
    }

    @Test fun `답을 다 읽으면 버튼 없이 다시 듣는다`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("메뉴를 고르는 화면이에요. 또 도움이 필요하세요?"))) }
        assertEquals(Phase.ASKING, d.phase)
        val tr = d.send { SpeechFinished(it) }
        assertEquals(Phase.LISTENING, d.phase)
        assertTrue(tr.effects.any { it is StartListening })
    }

    @Test fun `버튼을 가리킨 안내도 다 읽으면 기다리지 않고 다시 듣는다`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.Tap("e4", "아메리카노"))) }
        assertEquals(Phase.GUIDING, d.phase)
        val tr = d.send { SpeechFinished(it) }
        assertEquals(Phase.LISTENING, d.phase)                                  // 예전에는 WAIT_CHANGE
        assertTrue(tr.effects.any { it is StartListening })
        assertTrue(tr.effects.any { it is StartTimer && it.delayMs == 8_000L })
    }

    @Test fun `화면이 바뀌어도 서버에 먼저 묻지 않고 낡은 테두리만 지운다`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.Tap("e4", "아메리카노"))) }
        d.send { SpeechFinished(it) }
        val tr = d.send(SnapshotReceived(options))                              // 어르신이 눌러서 새 화면
        assertEquals(Phase.LISTENING, d.phase)
        assertTrue(tr.effects.none { it is RequestGuide })
        assertEquals(listOf(ClearHighlight), tr.effects)
        assertNull(d.state.guide)
        assertEquals(options, d.state.snapshot)                                  // 다음 질문은 이 새 화면으로
    }

    @Test fun `화면 전환 알림도 마찬가지`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.Tap("e4", "아메리카노"))) }
        d.send { SpeechFinished(it) }
        val tr = d.send(ScreenChanged("s03_options"))
        assertTrue(tr.effects.none { it is RequestGuide })
    }

    @Test fun `서버에 묻는 사이 화면이 바뀌어도 다시 묻지 않는다`() {
        val d = asked()
        val tr = d.send(SnapshotReceived(options))
        assertEquals(Phase.PLANNING, d.phase)
        assertTrue(tr.effects.none { it is RequestGuide })
    }

    @Test fun `질문 뒤 다음 질문도 같은 방식으로 이어진다`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("대답이에요. 또 도움이 필요하세요?"))) }
        d.send { SpeechFinished(it) }
        d.send(SnapshotReceived(options))
        val tr = d.send { SpeechRecognized(it, "카드는 어디에 대야 해?") }
        assertEquals(Phase.PLANNING, d.phase)
        assertEquals(options, (tr.effects.last() as RequestGuide).request.snapshot)
    }

    @Test fun `말이 없으면 조용히 다시 듣는다`() {
        val d = listening()
        val tr = d.send { TimedOut(it) }
        assertEquals(Phase.LISTENING, d.phase)
        assertEquals(listOf(StopListening), tr.effects.take(1))
        assertTrue(tr.effects.any { it is StartListening })
        assertTrue(tr.effects.none { it is Speak })                              // 아무 말도 하지 않는다
        assertEquals(1, d.state.silentRounds)
    }

    @Test fun `인식기가 잠깐 못 알아들어도 계속 듣는다`() {
        val d = listening()
        for (e in listOf(SttError.NO_SPEECH, SttError.BUSY, SttError.NETWORK)) {
            val tr = d.send { SpeechFailed(it, e) }
            assertEquals(Phase.LISTENING, d.phase)
            assertTrue(tr.effects.any { it is StartListening })
        }
    }

    @Test fun `마이크 권한이 없으면 오류로 끝낸다`() {
        val d = listening()
        d.send { SpeechFailed(it, SttError.PERMISSION) }
        assertEquals(Phase.ERROR, d.phase)
    }

    @Test fun `한참 조용하면 쉰다`() {
        val d = listening()
        repeat(11) { d.send { TimedOut(it) } }                 // 12번째에 쉰다 (MAX_SILENT_ROUNDS = 12)
        assertEquals(Phase.LISTENING, d.phase)
        d.send { TimedOut(it) }
        assertEquals(Phase.IDLE, d.phase)
    }

    @Test fun `말을 들으면 조용했던 횟수는 0으로`() {
        val d = listening()
        repeat(3) { d.send { TimedOut(it) } }
        assertEquals(3, d.state.silentRounds)
        d.send { SpeechRecognized(it, question) }
        assertEquals(0, d.state.silentRounds)
    }

    @Test fun `서버가 실패하면 같은 말로 잠깐 뒤 다시 묻는 흐름은 그대로`() {
        val d = asked()
        d.send { GuideFailed(it) }
        assertEquals(Phase.GUIDING, d.phase)
        d.send { SpeechFinished(it) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)                                 // 못 답한 질문이 남았으니 듣지 않고 다시 묻는다
        val tr = d.send { TimedOut(it) }
        assertEquals(Phase.PLANNING, d.phase)
        assertEquals(question, (tr.effects.last() as RequestGuide).request.utterance)
    }

    @Test fun `끝내기 답이면 끝낸다`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.Done("알겠어요. 또 필요하시면 말씀해 주세요."))) }
        assertEquals(Phase.DONE, d.phase)
        d.send { SpeechFinished(it) }
        assertEquals(Phase.IDLE, d.phase)
    }

    @Test fun `읽는 중 말하기를 누르거나 말하면 끊고 듣는다 (말 끊기)`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("길게 설명해요. 또 도움이 필요하세요?"))) }
        assertEquals(Phase.ASKING, d.phase)
        val tr = d.send(StartPressed("s2"))                                      // 말 끊기는 이 이벤트로 들어온다
        assertEquals(Phase.LISTENING, d.phase)
        assertTrue(tr.effects.contains(SessionEffect.StopSpeaking))
        assertNotNull(tr.effects.firstOrNull { it is StartListening })
    }

    @Test fun `예전 방식은 그대로다`() {
        val old = SessionStateMachine()
        var s = SessionState()
        s = old.reduce(s, SnapshotReceived(menu)).next
        s = old.reduce(s, StartPressed("s")).next
        s = old.reduce(s, SpeechRecognized(s.ticket, "아메리카노 주세요")).next
        s = old.reduce(s, GuideReceived(s.ticket, GuideResponse("g", GuideAction.Tap("e4", "아메리카노")))).next
        s = old.reduce(s, SpeechFinished(s.ticket)).next
        assertEquals(Phase.WAIT_CHANGE, s.phase)
        assertFalse(old.reduce(s, SnapshotReceived(options)).effects.none { it is RequestGuide })
    }
}
