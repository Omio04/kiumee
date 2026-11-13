package kr.kiosklens.domain.session

import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideResponse
import org.junit.Assert.*
import org.junit.Test

class TouchChoiceTransitionTest {
    private val machine = SessionStateMachine()
    private fun asking(): SessionState {
        var state = SessionState()
        fun send(event: SessionEvent) { state = machine.reduce(state, event).next }
        send(SessionEvent.SnapshotReceived(Screens.menu))
        send(SessionEvent.StartPressed("order"))
        send(SessionEvent.SpeechRecognized(state.ticket, "아이스 아메리카노 한 잔"))
        send(SessionEvent.GuideReceived(state.ticket,
            GuideResponse("아이스 아메리카노 한 잔", GuideAction.AskUser("원하는 옵션을 골라 주세요."))))
        return state
    }

    @Test fun touchChoiceAdvancesWhileQuestionIsSpeakingOrListeningInEitherEventOrder() {
        for (listening in listOf(false, true)) for (snapshotFirst in listOf(false, true)) {
            var state = asking()
            if (listening) state = machine.reduce(state, SessionEvent.SpeechFinished(state.ticket)).next
            val oldTicket = state.ticket
            val events = listOf(SessionEvent.SnapshotReceived(Screens.options), SessionEvent.ScreenChanged(Screens.options.screenId))
                .let { if (snapshotFirst) it else it.reversed() }
            val effects = events.flatMap { event -> machine.reduce(state, event).also { state = it.next }.effects }
            val request = effects.filterIsInstance<SessionEffect.RequestGuide>().single().request
            assertEquals(Screens.options.screenId, request.snapshot.screenId)
            assertEquals("아이스 아메리카노 한 잔", request.goal)
            assertNull(request.utterance)
            assertEquals(Phase.PLANNING, state.phase)
            if (listening) assertTrue(effects.contains(SessionEffect.StopListening))
            // Delayed STT/TTS callbacks must not overwrite the new request.
            assertEquals(state, machine.reduce(state, SessionEvent.SpeechRecognized(oldTicket, "따뜻하게")).next)
            assertEquals(state, machine.reduce(state, SessionEvent.SpeechFinished(oldTicket)).next)
        }
    }

    @Test fun sameScreenDoesNotInterruptAnAnswer() {
        val asking = asking()
        val listening = machine.reduce(asking, SessionEvent.SpeechFinished(asking.ticket)).next
        assertTrue(machine.reduce(listening, SessionEvent.SnapshotReceived(Screens.menu)).effects.isEmpty())
    }

    @Test fun explicitOrderAmendmentIsNotInterruptedByCameraChanges() {
        val state = machine.reduce(asking(), SessionEvent.StartPressed("ignored")).next
        assertTrue(state.listeningToAdd)
        val transition = machine.reduce(state, SessionEvent.SnapshotReceived(Screens.options))
        assertEquals(Phase.LISTENING, transition.next.phase)
        assertTrue(transition.effects.isEmpty())
    }
}
