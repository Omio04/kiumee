package kr.kiosklens.domain.session

import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.model.ScrollDir
import kr.kiosklens.domain.policy.GuideTextComposer
import kr.kiosklens.domain.session.Screens.menu
import kr.kiosklens.domain.session.Screens.options
import kr.kiosklens.domain.session.SessionEffect.RequestGuide
import kr.kiosklens.domain.session.SessionEffect.Speak
import kr.kiosklens.domain.session.SessionEffect.StartTimer
import kr.kiosklens.domain.session.SessionEffect.StopSpeaking
import kr.kiosklens.domain.session.SessionEvent.AimTrouble
import kr.kiosklens.domain.session.SessionEvent.GuideReceived
import kr.kiosklens.domain.session.SessionEvent.SnapshotReceived
import kr.kiosklens.domain.session.SessionEvent.SpeechFinished
import kr.kiosklens.domain.session.SessionEvent.SpeechRecognized
import kr.kiosklens.domain.session.SessionEvent.StartPressed
import kr.kiosklens.domain.session.SessionEvent.TimedOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 어떻게 비추면 되는지 알려 주기 (2026-11-13 P5: 반사 · 손떨림 · 너무 가까움에 어르신 손을 움직이게) */
class AimTroubleTest {
    private class Driver(var state: SessionState = SessionState()) {
        private val machine = SessionStateMachine()
        fun send(event: SessionEvent): Transition = machine.reduce(state, event).also { state = it.next }
        fun send(make: (ticket: Int) -> SessionEvent): Transition = send(make(state.ticket))
        val ticket get() = state.ticket
        val phase get() = state.phase
    }

    private val texts = GuideTextComposer()
    private val goal = "따뜻한 아메리카노 한 잔 주세요"
    private val guideAmericano = "화면 왼쪽 위 깜빡이는 아메리카노 버튼을 누르세요"

    private fun planningOnMenu() = Driver().apply {
        send(SnapshotReceived(menu))
        send(StartPressed("sess-1"))
        send { SpeechRecognized(it, goal) }
    }

    /** 아메리카노 안내를 다 읽고 누르기를 기다리는 중 */
    private fun waitingOnMenu() = planningOnMenu().apply {
        send { GuideReceived(it, GuideResponse(goal, GuideAction.Tap("e4", "아메리카노"))) }
        send { SpeechFinished(it) }
    }

    @Test fun `기다리는 중 버튼이 안 보이면 뒤로 빼 달라고 하고, 하던 안내는 그대로 다시 기다린다`() {
        val d = waitingOnMenu()
        val guide = d.state.guide
        assertEquals(Phase.WAIT_CHANGE, d.phase)

        val tr = d.send(AimTrouble(AimIssue.TARGET_HIDDEN))
        assertEquals(Phase.GUIDING, d.phase)
        assertEquals(listOf(Speak(texts.moveBack(), d.ticket)), tr.effects)        // 하이라이트는 건드리지 않는다
        assertEquals("안내 · 하이라이트 위치 · 다시 읽을 문장 그대로", guide, d.state.guide)

        val back = d.send { SpeechFinished(it) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        assertEquals(listOf(StartTimer(15_000, d.ticket)), back.effects)

        val again = d.send { TimedOut(it) }                                           // 15초 뒤에는 원래 안내를 다시 읽는다
        assertEquals(listOf(Speak(guideAmericano, d.ticket)), again.effects)
    }

    @Test fun `기다리는 중 글자를 못 읽으면 화면 전체가 보이게 비춰 달라고 한다`() {
        val d = waitingOnMenu()
        val tr = d.send(AimTrouble(AimIssue.NO_TEXT))
        assertEquals(Phase.GUIDING, d.phase)
        assertEquals(listOf(Speak(texts.aimAtKiosk(), d.ticket)), tr.effects)
    }

    @Test fun `알려 주는 사이 화면이 바뀌면 바로 새 화면으로 묻는다`() {
        val d = waitingOnMenu()
        d.send(AimTrouble(AimIssue.TARGET_HIDDEN))
        val tr = d.send(SnapshotReceived(options))
        assertEquals(Phase.PLANNING, d.phase)
        assertTrue(StopSpeaking in tr.effects)
        assertTrue(tr.effects.any { it is RequestGuide && it.request.snapshot == options })
    }

    @Test fun `화면을 찾는 중 글자를 못 읽으면 10초를 기다리지 않고 바로 알려 주고, 보이면 바로 묻는다`() {
        val d = Driver()
        d.send(StartPressed("sess-1"))
        d.send { SpeechRecognized(it, goal) }
        assertEquals("화면이 아직 없어서 찾는 중", Phase.SCANNING, d.phase)

        val tr = d.send(AimTrouble(AimIssue.NO_TEXT))
        assertEquals(Phase.LOST, d.phase)
        assertEquals(listOf(Speak(texts.aimAtKiosk(), d.ticket)), tr.effects)

        val seen = d.send(SnapshotReceived(menu))
        assertEquals(Phase.PLANNING, d.phase)
        assertTrue(StopSpeaking in seen.effects)
    }

    @Test fun `화면을 찾는 중에는 버튼 안 보임을 알리지 않는다 (안내 중인 버튼이 없다)`() {
        val d = Driver()
        d.send(StartPressed("sess-1"))
        d.send { SpeechRecognized(it, goal) }
        val before = d.state
        val tr = d.send(AimTrouble(AimIssue.TARGET_HIDDEN))
        assertEquals(before, d.state)
        assertTrue(tr.effects.isEmpty())
    }

    @Test fun `하이라이트 없는 안내(밀어서 찾기)를 기다릴 때는 버튼 안 보임은 무시하고 글자 못 읽음만 알린다`() {
        val d = planningOnMenu()
        d.send { GuideReceived(it, GuideResponse(goal, GuideAction.Scroll(ScrollDir.DOWN, "딸기스무디"))) }
        d.send { SpeechFinished(it) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)

        assertTrue(d.send(AimTrouble(AimIssue.TARGET_HIDDEN)).effects.isEmpty())
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        val tr = d.send(AimTrouble(AimIssue.NO_TEXT))
        assertEquals(listOf(Speak(texts.aimAtKiosk(), d.ticket)), tr.effects)
    }

    @Test fun `듣는 중 · 안내를 읽는 중 · 서버에 묻는 중 · 대기 중에는 말을 끊지 않는다`() {
        val idle = Driver().apply { send(SnapshotReceived(menu)) }
        val listening = Driver().apply { send(SnapshotReceived(menu)); send(StartPressed("sess-1")) }
        val planning = planningOnMenu()
        val guiding = planningOnMenu().apply { send { GuideReceived(it, GuideResponse(goal, GuideAction.Tap("e4", "아메리카노"))) } }
        val asking = planningOnMenu().apply { send { GuideReceived(it, GuideResponse(goal, GuideAction.AskUser("따뜻한 거요, 차가운 거요?"))) } }
        for ((name, d) in listOf("대기" to idle, "듣는 중" to listening, "묻는 중" to planning, "안내 읽는 중" to guiding, "되묻는 중" to asking)) {
            for (issue in AimIssue.entries) {
                val before = d.state
                val tr = d.send(AimTrouble(issue))
                assertEquals("$name · $issue", before, d.state)
                assertTrue("$name · $issue", tr.effects.isEmpty())
            }
        }
    }
}
