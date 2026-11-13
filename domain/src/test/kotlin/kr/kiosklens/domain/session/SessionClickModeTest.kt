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
import kr.kiosklens.domain.session.SessionEffect.StopSpeaking
import kr.kiosklens.domain.session.SessionEvent.GuideReceived
import kr.kiosklens.domain.session.SessionEvent.SnapshotReceived
import kr.kiosklens.domain.session.SessionEvent.SpeechFailed
import kr.kiosklens.domain.session.SessionEvent.SpeechFinished
import kr.kiosklens.domain.session.SessionEvent.SpeechRecognized
import kr.kiosklens.domain.session.SessionEvent.StartPressed
import kr.kiosklens.domain.session.SessionEvent.TimedOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 도움 모드 · 버튼 방식 (기본, 2026-11-13): 어르신이 질문할 때만 답하고, 답이 끝나면 **저절로 듣지 않고** "말하기" 버튼을 기다린다.
 * 앱이 말하는 도중 끼어들지 않고, 주변 소음을 말로 오인하는 일도 없다. 계속 듣기(handsFree)는 SessionHelpModeTest.
 */
class SessionClickModeTest {
    private class Driver(var state: SessionState = SessionState()) {
        private val machine = SessionStateMachine(helpMode = true, handsFree = false)
        fun send(event: SessionEvent): Transition = machine.reduce(state, event).also { state = it.next }
        fun send(make: (ticket: Int) -> SessionEvent): Transition = send(make(state.ticket))
        val phase get() = state.phase
    }

    private val question = "이건 또 뭘 고르라는 거야?"
    private fun asked(): Driver = Driver().apply {
        send(SnapshotReceived(menu)); send(StartPressed("s")); send { SpeechRecognized(it, question) }
    }

    @Test fun `대답을 다 읽어도 저절로 듣지 않고 버튼을 기다린다`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("메뉴를 고르는 화면이에요. 또 도움이 필요하세요?"))) }
        val tr = d.send { SpeechFinished(it) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        assertTrue(tr.effects.none { it is StartListening })
        assertTrue(tr.effects.isEmpty())                                           // 타이머도 없다: 아무 일도 일어나지 않는다
    }

    @Test fun `버튼 안내를 다 읽어도 기다린다 (15초 뒤 다시 읽지 않는다)`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.Tap("e4", "아메리카노"))) }
        val tr = d.send { SpeechFinished(it) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        assertTrue(tr.effects.isEmpty())
        val later = d.send { TimedOut(it) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        assertTrue(later.effects.none { it is Speak || it is RequestGuide })
    }

    @Test fun `버튼을 누르면 다시 듣고 이어서 질문하면 그 순간의 새 화면으로 묻는다`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("대답이에요. 또 도움이 필요하세요?"))) }
        d.send { SpeechFinished(it) }
        d.send(SnapshotReceived(options))                                          // 그 사이 어르신이 눌러서 화면이 바뀜
        val press = d.send(StartPressed("s2"))
        assertEquals(Phase.LISTENING, d.phase)
        assertTrue(press.effects.any { it is StartListening })
        val tr = d.send { SpeechRecognized(it, "카드는 어디에 대야 해?") }
        assertEquals(options, (tr.effects.last() as RequestGuide).request.snapshot)
    }

    @Test fun `말이 없으면 한 마디 안내하고 대기로 돌아간다 (세션은 그대로)`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("대답이에요. 또 도움이 필요하세요?"))) }
        d.send { SpeechFinished(it) }
        d.send(StartPressed("s2"))
        val tr = d.send { TimedOut(it) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        assertTrue(tr.effects.any { it is Speak && it.text.contains("말하기 버튼") })
        assertTrue(tr.effects.none { it is StartListening })                      // 다시 듣지 않는다
        assertEquals(d.state.sessionId, "s")                                       // 세션은 끝나지 않았다
    }

    @Test fun `인식이 실패해도 대기로 돌아가고 권한 문제만 오류`() {
        for (e in listOf(SttError.NO_SPEECH, SttError.BUSY, SttError.NETWORK)) {
            val d = asked().apply { send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("x. 또 도움이 필요하세요?"))) }; send { SpeechFinished(it) }; send(StartPressed("s2")) }
            val tr = d.send { SpeechFailed(it, e) }
            assertEquals(e.name, Phase.WAIT_CHANGE, d.phase)
            assertTrue(tr.effects.none { it is StartListening })
        }
        val d = asked().apply { send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("x. 또 도움이 필요하세요?"))) }; send { SpeechFinished(it) }; send(StartPressed("s2")) }
        d.send { SpeechFailed(it, SttError.PERMISSION) }
        assertEquals(Phase.ERROR, d.phase)
    }

    @Test fun `화면이 바뀌어도 서버에 먼저 묻지 않고 노란 칸은 다음 질문까지 그대로 둔다`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.Tap("e4", "아메리카노"))) }
        d.send { SpeechFinished(it) }
        val tr = d.send(SnapshotReceived(options))
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        assertTrue(tr.effects.none { it is ClearHighlight || it is RequestGuide })
        assertTrue(d.state.guide != null)
        val press = d.send(StartPressed("s2"))                                     // 다음 질문을 누르면 그때 지운다
        assertTrue(press.effects.any { it is ClearHighlight })
    }

    @Test fun `앱이 말하는 중에는 말하기를 눌러도 끼어들 수 없다`() {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("길게 설명해요. 또 도움이 필요하세요?"))) }
        assertEquals(Phase.ASKING, d.phase)
        val tr = d.send(StartPressed("s2"))
        assertEquals(Phase.ASKING, d.phase)
        assertTrue(tr.effects.none { it is StopSpeaking || it is StartListening })
        d.send { SpeechFinished(it) }
        d.send(StartPressed("s3"))
        assertEquals(Phase.LISTENING, d.phase)                                     // 다 읽은 뒤에는 누를 수 있다
    }

    @Test fun `버튼을 가리킬 때 이유를 먼저 읽고 노란색 칸을 말한다`() {
        val d = asked()
        val tr = d.send { GuideReceived(it, GuideResponse("", GuideAction.Tap("e4", "아메리카노"), say = "메뉴를 먼저 골라야 넘어가요.")) }
        val spoken = tr.effects.filterIsInstance<Speak>().first().text
        assertTrue(spoken, spoken.startsWith("메뉴를 먼저 골라야 넘어가요. ") && spoken.contains("노란색 칸을 눌러 주세요"))
    }

    @Test fun `글자 목록에 없는 칸도 AI가 사진에서 찾은 박스로 노란 칸을 친다`() {
        val d = asked()
        val box = kr.kiosklens.core.model.NormRect(0.5f, 0.85f, 0.9f, 0.95f)
        val tr = d.send { GuideReceived(it, GuideResponse("", GuideAction.Tap("photo", "주문담기"), say = "안 골라도 돼요.", box = box)) }
        val hl = tr.effects.filterIsInstance<SessionEffect.Highlight>().single().target
        assertTrue(hl.rect.left <= box.left && hl.rect.right >= box.right && hl.rect.top <= box.top && hl.rect.bottom >= box.bottom)
        assertTrue(hl.rect.width < box.width + 0.1f)                                // 박스를 거의 그대로 (두 배로 키우지 않는다)
        assertTrue(tr.effects.filterIsInstance<Speak>().first().text.contains("주문담기"))
    }

    // ---- 칠 칸이 아직 없다는 알림(seek): 찾는 이름을 기억했다가 글자가 읽히는 순간 노란 칸을 친다 (2026-11-13 실기기: 주문담기가 글자 목록에 없었다)
    private fun askedSeek(): Driver {
        val d = asked()
        d.send { GuideReceived(it, GuideResponse("", GuideAction.AskUser("'주문담기' 칸이 아직 안 읽혀요. 크게 나오게 비춰 주세요."), seek = "주문담기")) }
        return d
    }

    private val menuWithoutButton = menu
    private val menuWithGarbled = menu.copy(screenId = "s02_menu_b", frameId = 11, elements = menu.elements + Screens.el("n9", "주문달기", 0.55f, 0.9f, 0.95f, 0.97f))

    @Test fun `찾는 칸이 읽히면 다시 묻지 않아도 노란 칸을 치고 알려 준다`() {
        val d = askedSeek()
        assertEquals("주문담기", d.state.seek)
        val done = d.send { SpeechFinished(it) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        assertTrue(done.effects.any { it is SessionEffect.StartTimer })                 // 무한정 기다리지 않는다
        val none = d.send(SnapshotReceived(menuWithoutButton))                           // 아직 안 읽힘
        assertTrue(none.effects.isEmpty() && d.state.seek == "주문담기")
        val found = d.send(SnapshotReceived(menuWithGarbled))                            // 깨진 글자로 읽힘
        assertEquals(Phase.GUIDING, d.phase)
        assertEquals("n9", found.effects.filterIsInstance<SessionEffect.Highlight>().single().target.elementId)
        val spoken = found.effects.filterIsInstance<Speak>().single().text
        assertTrue(spoken, spoken.contains("노란색 칸을 눌러 주세요") && spoken.contains("주문담기"))   // 말은 AI가 읽은 정확한 이름
        assertNull(d.state.seek)
    }

    @Test fun `새 질문을 누르면 찾던 것을 비운다`() {
        val d = askedSeek(); d.send { SpeechFinished(it) }
        d.send(StartPressed("s2"))
        assertNull(d.state.seek)
    }

    @Test fun `시간이 지나도 안 읽히면 포기한다`() {
        val d = askedSeek(); d.send { SpeechFinished(it) }
        d.send { TimedOut(it) }
        assertNull(d.state.seek)
        val tr = d.send(SnapshotReceived(menuWithGarbled))                              // 그 뒤에 읽혀도 갑자기 말하지 않는다
        assertTrue(tr.effects.none { it is Speak })
    }
}
