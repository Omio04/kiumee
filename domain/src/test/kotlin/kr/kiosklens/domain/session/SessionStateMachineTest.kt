package kr.kiosklens.domain.session

import kr.kiosklens.core.model.ActionType
import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideRequest
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.model.HistoryItem
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.SttError
import kr.kiosklens.domain.session.Screens.menu
import kr.kiosklens.domain.session.Screens.options
import kr.kiosklens.domain.session.SessionEffect.CancelPending
import kr.kiosklens.domain.session.SessionEffect.ClearHighlight
import kr.kiosklens.domain.session.SessionEffect.Highlight
import kr.kiosklens.domain.session.SessionEffect.RequestGuide
import kr.kiosklens.domain.session.SessionEffect.Speak
import kr.kiosklens.domain.session.SessionEffect.StartListening
import kr.kiosklens.domain.session.SessionEffect.StartTimer
import kr.kiosklens.domain.session.SessionEffect.StopListening
import kr.kiosklens.domain.session.SessionEffect.StopSpeaking
import kr.kiosklens.domain.session.SessionEvent.GuideFailed
import kr.kiosklens.domain.session.SessionEvent.GuideReceived
import kr.kiosklens.domain.session.SessionEvent.ScreenChanged
import kr.kiosklens.domain.session.SessionEvent.SnapshotReceived
import kr.kiosklens.domain.session.SessionEvent.SpeechFailed
import kr.kiosklens.domain.session.SessionEvent.SpeechFinished
import kr.kiosklens.domain.session.SessionEvent.SpeechRecognized
import kr.kiosklens.domain.session.SessionEvent.StartPressed
import kr.kiosklens.domain.session.SessionEvent.StopPressed
import kr.kiosklens.domain.session.SessionEvent.TimedOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    /** 이벤트를 차례로 넣어 보는 도우미. 번호표 이벤트는 지금 번호로 만든다. */
    private class Driver(var state: SessionState = SessionState()) {
        private val machine = SessionStateMachine()

        fun send(event: SessionEvent): Transition = machine.reduce(state, event).also { state = it.next }
        fun send(make: (ticket: Int) -> SessionEvent): Transition = send(make(state.ticket))
        val ticket get() = state.ticket
        val phase get() = state.phase
    }

    private val goal = "따뜻한 아메리카노 한 잔 주세요"
    private val guideAmericano = "화면 왼쪽 위 노란색 칸을 눌러 주세요. 적힌 글자는 아메리카노"

    /** 메뉴 화면이 보이는 상태에서 말하고, 서버에 묻는 데까지 */
    private fun planningOnMenu(): Driver = Driver().apply {
        send(SnapshotReceived(menu))
        send(StartPressed("sess-1"))
        send { SpeechRecognized(it, goal) }
    }

    /** 아메리카노 안내를 다 읽고 화면 전환을 기다리는 데까지 */
    private fun waitingOnMenu(): Driver = planningOnMenu().apply {
        send { GuideReceived(it, GuideResponse(goal, GuideAction.Tap("e4", "아메리카노"))) }
        send { SpeechFinished(it) }
    }

    // ---------------------------------------------------------------- ① 정상 경로

    @Test fun `말하기부터 안내 하이라이트 완료까지 정상 경로`() {
        val d = Driver()
        d.send(SnapshotReceived(menu))                                     // 화면은 이미 보이는 중

        var tr = d.send(StartPressed("sess-1"))
        assertEquals(Phase.LISTENING, d.phase)
        assertEquals(listOf(StopSpeaking, ClearHighlight, StartListening(d.ticket), StartTimer(20_000, d.ticket)), tr.effects)

        tr = d.send { SpeechRecognized(it, goal) }                         // 말 인식 → 화면이 있으니 바로 묻기
        assertEquals(Phase.PLANNING, d.phase)
        assertEquals(listOf(CancelPending, StartTimer(1_500, d.ticket), RequestGuide(GuideRequest("sess-1", goal, null, menu, emptyList()), d.ticket)), tr.effects)

        tr = d.send { GuideReceived(it, GuideResponse(goal, GuideAction.Tap("e4", "아메리카노"))) }
        assertEquals(Phase.GUIDING, d.phase)                               // 하이라이트 + 안내 문장
        assertEquals(listOf(Highlight(HighlightTarget("e4", menu.findElement("e4")!!.rect)), Speak(guideAmericano, d.ticket)), tr.effects)

        tr = d.send { SpeechFinished(it) }                                 // 다 읽음 → 15초 기다리기
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        assertEquals(listOf(StartTimer(15_000, d.ticket)), tr.effects)

        tr = d.send(ScreenChanged("s03_options"))                          // 어르신이 누름 → 새 화면 찾기
        assertEquals(Phase.SCANNING, d.phase)
        assertEquals(listOf(StopSpeaking, ClearHighlight, CancelPending, StartTimer(10_000, d.ticket)), tr.effects)

        tr = d.send(SnapshotReceived(options))                             // 새 화면 → 다시 묻기 (목표 · 이력 이어감)
        assertEquals(Phase.PLANNING, d.phase)
        val expected = GuideRequest("sess-1", null, goal, options, listOf(HistoryItem("s02_menu", ActionType.TAP, "아메리카노")))
        assertEquals(listOf(CancelPending, StartTimer(1_500, d.ticket), RequestGuide(expected, d.ticket)), tr.effects)

        val bye = "결제 수단을 고르셨어요. 직원 또는 화면 안내를 따라주세요."
        tr = d.send { GuideReceived(it, GuideResponse(goal, GuideAction.Done(bye))) }
        assertEquals(Phase.DONE, d.phase)
        assertEquals(listOf(ClearHighlight, Speak(bye, d.ticket)), tr.effects)

        tr = d.send { SpeechFinished(it) }                                 // 마무리 안내 끝 → 세션 종료
        assertEquals(Phase.IDLE, d.phase)
        assertEquals(listOf(ClearHighlight), tr.effects)
        assertNull(d.state.sessionId)
        assertNull(d.state.goal)
        assertTrue(d.state.history.isEmpty())
    }

    // ---------------------------------------------------------------- ② 화면 없이 말함

    @Test fun `화면이 안 보일 때 말하면 SCANNING에서 기다렸다가 화면이 오면 묻는다`() {
        val d = Driver()
        d.send(StartPressed("sess-1"))
        var tr = d.send { SpeechRecognized(it, "아메리카노") }
        assertEquals(Phase.SCANNING, d.phase)
        assertEquals(listOf(CancelPending, StartTimer(10_000, d.ticket)), tr.effects)

        tr = d.send(SnapshotReceived(menu))
        assertEquals(Phase.PLANNING, d.phase)
        assertEquals("아메리카노", (tr.effects.last() as RequestGuide).request.utterance)
    }

    // ---------------------------------------------------------------- ③ 종료 버튼

    @Test fun `종료 버튼은 어떤 단계에서든 IDLE로 가고 진행 중인 일을 모두 정리한다`() {
        val stops = listOf(StopListening, StopSpeaking, ClearHighlight, CancelPending)
        val scenarios = mapOf(
            Phase.LISTENING to Driver().apply { send(SnapshotReceived(menu)); send(StartPressed("s")) },
            Phase.PLANNING to planningOnMenu(),
            Phase.GUIDING to planningOnMenu().apply { send { GuideReceived(it, GuideResponse(goal, GuideAction.Tap("e4", "아메리카노"))) } },
            Phase.WAIT_CHANGE to waitingOnMenu(),
        )
        for ((phase, d) in scenarios) {
            assertEquals(phase, d.phase)
            val tr = d.send(StopPressed)
            assertEquals("$phase → IDLE", Phase.IDLE, d.phase)
            assertEquals(stops, tr.effects)
            assertNull(d.state.sessionId)
            assertEquals("화면은 세션과 상관없이 유지", menu, d.state.snapshot)
        }
    }

    // ---------------------------------------------------------------- ④ 번호표

    @Test fun `종료 뒤에 늦게 온 서버 응답은 번호표가 달라 무시된다`() {
        val d = planningOnMenu()
        val oldTicket = d.ticket
        d.send(StopPressed)
        val before = d.state
        val tr = d.send(GuideReceived(oldTicket, GuideResponse(goal, GuideAction.Tap("e4", "아메리카노"))))
        assertEquals(before, d.state)
        assertTrue(tr.effects.isEmpty())
    }

    // ---------------------------------------------------------------- ⑤ 되묻기

    @Test fun `되물을 때는 질문을 다 읽은 뒤에야 듣기 시작하고 대답은 목표에 이어진다`() {
        val d = planningOnMenu()
        val question = "따뜻한 것과 차가운 것 중 어떤 걸로 드릴까요?"
        var tr = d.send { GuideReceived(it, GuideResponse("커피 한 잔", GuideAction.AskUser(question))) }
        assertEquals(Phase.ASKING, d.phase)
        assertEquals(listOf(ClearHighlight, Speak(question, d.ticket)), tr.effects)
        assertFalse("질문을 읽는 동안 듣기 시작 금지 (에코 방지)", tr.effects.any { it is StartListening })

        tr = d.send { SpeechFinished(it) }
        assertEquals(Phase.LISTENING, d.phase)
        assertEquals(listOf(StartListening(d.ticket), StartTimer(20_000, d.ticket)), tr.effects)

        tr = d.send { SpeechRecognized(it, "따뜻한 거요") }
        val request = (tr.effects.last() as RequestGuide).request
        assertEquals("따뜻한 거요", request.utterance)
        assertEquals("커피 한 잔", request.goal)
        assertEquals(HistoryItem("s02_menu", ActionType.ASK_USER, question), request.history.last())
    }

    // ---------------------------------------------------------------- ⑥ 시간 초과

    @Test fun `듣는 중 8초 무음이면 안내하고 IDLE`() {
        val d = Driver().apply { send(StartPressed("s")) }
        val tr = d.send { TimedOut(it) }
        assertEquals(Phase.IDLE, d.phase)
        assertEquals(Speak("말씀을 듣지 못했어요. 다시 말하기 버튼을 눌러 주세요.", d.ticket), tr.effects.last())
    }

    @Test fun `10초 동안 화면을 못 찾으면 LOST 안내 뒤 다시 찾는다`() {
        val d = Driver().apply { send(StartPressed("s")); send { SpeechRecognized(it, "아메리카노") } }
        var tr = d.send { TimedOut(it) }
        assertEquals(Phase.LOST, d.phase)
        assertEquals(listOf(Speak("키오스크 화면이 잘 보이게 비춰 주세요.", d.ticket)), tr.effects)

        tr = d.send { SpeechFinished(it) }
        assertEquals(Phase.SCANNING, d.phase)
        assertEquals(listOf(StartTimer(10_000, d.ticket)), tr.effects)
        assertEquals("발화는 아직 기다리는 중", "아메리카노", d.state.pendingUtterance)
    }

    @Test fun `15초 동안 화면이 안 바뀌면 같은 안내를 다시 읽는다`() {
        val d = waitingOnMenu()
        val tr = d.send { TimedOut(it) }
        assertEquals(Phase.GUIDING, d.phase)
        assertEquals(listOf(Speak(guideAmericano, d.ticket)), tr.effects)
    }

    // ---------------------------------------------------------------- ⑦ UNKNOWN

    @Test fun `UNKNOWN이면 이유를 읽고 15초 뒤 최신 화면으로 다시 묻는다`() {
        val d = planningOnMenu()
        val reason = "버튼을 찾지 못했어요. 화면이 잘 보이게 정면에서 비춰주세요."
        var tr = d.send { GuideReceived(it, GuideResponse(goal, GuideAction.Unknown(reason))) }
        assertEquals(Phase.GUIDING, d.phase)
        assertEquals(listOf(ClearHighlight, Speak(reason, d.ticket)), tr.effects)

        d.send { SpeechFinished(it) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)

        tr = d.send { TimedOut(it) }
        assertEquals(Phase.PLANNING, d.phase)
        val request = (tr.effects.last() as RequestGuide).request
        assertNull("새 발화 없이 다시 묻기", request.utterance)
        assertEquals(goal, request.goal)
    }

    // ---------------------------------------------------------------- ⑧ 하이라이트 따라가기

    @Test fun `같은 화면에서 버튼이 움직이면 하이라이트가 따라가고 그대로면 아무것도 안 한다`() {
        val d = waitingOnMenu()
        val moved = NormRect(0.07f, 0.27f, 0.32f, 0.42f)
        val shaken = menu.copy(frameId = 11, elements = menu.elements.map { if (it.id == "e4") it.copy(rect = moved) else it })

        var tr = d.send(SnapshotReceived(shaken))
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        assertEquals(listOf(Highlight(HighlightTarget("e4", moved))), tr.effects)

        tr = d.send(SnapshotReceived(shaken.copy(frameId = 12)))
        assertTrue("위치가 같으면 다시 보내지 않음", tr.effects.isEmpty())
    }

    @Test fun `목표가 가려지면 주변이 움직인 만큼 하이라이트를 옮기고 단서가 없으면 숨겼다가 다시 보이면 그린다`() {
        val d = waitingOnMenu()
        val start = d.state.guide!!.rect!!
        // 손가락이 아메리카노를 가리고 휴대폰이 위로 움직임 (다른 요소는 0.125 위로)
        val up = menu.copy(frameId = 11, elements = menu.elements.filter { it.id != "e4" }
            .map { it.copy(rect = NormRect(it.rect.left, it.rect.top - 0.125f, it.rect.right, it.rect.bottom - 0.125f)) })
        var tr = d.send(SnapshotReceived(up))
        val moved = (tr.effects.single() as Highlight).target
        assertEquals("e4", moved.elementId)
        assertEquals(start.top - 0.125f, moved.rect.top, 1e-4f)
        // 단서가 없음 (요소 1개뿐) → 틀린 자리를 가리키지 않게 숨김
        tr = d.send(SnapshotReceived(up.copy(frameId = 12, elements = up.elements.take(1))))
        assertEquals(listOf(ClearHighlight), tr.effects)
        // 다시 보이면 다시 그림
        tr = d.send(SnapshotReceived(menu.copy(frameId = 13)))
        assertEquals(listOf(Highlight(HighlightTarget("e4", menu.findElement("e4")!!.rect))), tr.effects)
    }

    @Test fun `다른 화면의 스냅샷이 오면 화면 전환으로 보고 바로 다시 묻는다`() {
        val d = waitingOnMenu()
        val tr = d.send(SnapshotReceived(options))
        assertEquals(Phase.PLANNING, d.phase)
        assertEquals(listOf(StopSpeaking, ClearHighlight, CancelPending, StartTimer(1_500, d.ticket)), tr.effects.dropLast(1))
        assertEquals(options, (tr.effects.last() as RequestGuide).request.snapshot)
    }

    // ---------------------------------------------------------------- ⑨ 묻는 사이 화면이 바뀜

    @Test fun `서버 응답을 기다리는 사이 화면이 바뀌면 그 응답은 버리고 새 화면으로 다시 묻는다`() {
        val d = planningOnMenu()
        val oldTicket = d.ticket
        val tr = d.send(SnapshotReceived(options))
        assertEquals(Phase.PLANNING, d.phase)
        val request = (tr.effects.last() as RequestGuide).request
        assertEquals(options, request.snapshot)
        assertEquals("아직 답을 못 받은 발화는 다시 보냄", goal, request.utterance)

        val stale = d.send(GuideReceived(oldTicket, GuideResponse(goal, GuideAction.Tap("e4", "아메리카노"))))
        assertTrue("옛 화면에 대한 응답은 무시", stale.effects.isEmpty())
        assertEquals(Phase.PLANNING, d.phase)
    }

    // ---------------------------------------------------------------- ⑩ 오류

    @Test fun `서버 요청이 실패하면 잠시만요 뒤 같은 내용으로 다시 묻고, 세 번 연달아 실패하면 오류 안내 뒤 IDLE`() {
        val d = planningOnMenu()
        var tr = d.send { GuideFailed(it) }                               // 무료 LLM이 늦거나 혼잡함 (2026-11-12)
        assertEquals(Phase.GUIDING, d.phase)
        assertEquals(listOf(Speak("답이 조금 늦어지고 있어요. 잠시만 기다려 주세요.", d.ticket)), tr.effects)
        tr = d.send { SpeechFinished(it) }
        assertEquals(listOf(StartTimer(3_000, d.ticket)), tr.effects)     // 15초가 아니라 3초 뒤
        tr = d.send { TimedOut(it) }
        assertEquals(Phase.PLANNING, d.phase)
        assertEquals("들은 말을 그대로 다시 보낸다", goal, (tr.effects.last() as RequestGuide).request.utterance)

        d.send { GuideFailed(it) }; d.send { SpeechFinished(it) }; d.send { TimedOut(it) }   // 두 번째 실패 → 또 다시 묻기
        assertEquals(Phase.PLANNING, d.phase)
        tr = d.send { GuideFailed(it) }                                   // 세 번째 연달아 실패 → 끝
        assertEquals(Phase.ERROR, d.phase)
        assertEquals(Speak("연결이 잠시 불안정해요. 다시 말하기 버튼을 눌러 주세요.", d.ticket), tr.effects.last())
        tr = d.send { SpeechFinished(it) }
        assertEquals(Phase.IDLE, d.phase)
        assertEquals(listOf(ClearHighlight), tr.effects)
    }

    @Test fun `답을 받으면 실패 횟수는 처음부터 다시 센다`() {
        val d = planningOnMenu()
        d.send { GuideFailed(it) }; d.send { SpeechFinished(it) }; d.send { TimedOut(it) }
        d.send { GuideFailed(it) }; d.send { SpeechFinished(it) }; d.send { TimedOut(it) }
        d.send { GuideReceived(it, GuideResponse(goal, GuideAction.Tap("e4", "아메리카노"))) }
        assertEquals(0, d.state.guideFailures)
    }

    @Test fun `음성 인식 실패 - 말이 없으면 IDLE, 권한 문제면 ERROR`() {
        val silent = Driver().apply { send(StartPressed("s")) }
        silent.send { SpeechFailed(it, SttError.NO_SPEECH) }
        assertEquals(Phase.IDLE, silent.phase)

        val denied = Driver().apply { send(StartPressed("s")) }
        val tr = denied.send { SpeechFailed(it, SttError.PERMISSION) }
        assertEquals(Phase.ERROR, denied.phase)
        assertEquals(Speak("마이크를 쓸 수 없어요. 휴대폰 설정에서 이 앱과 음성 인식 서비스의 마이크 허용을 확인해 주세요.", denied.ticket), tr.effects.last())
    }

    // ---------------------------------------------------------------- ⑪ 이력

    @Test fun `이력은 최근 5개만 보낸다`() {
        val d = planningOnMenu()
        repeat(7) {                                                       // 되묻기를 7번 반복
            d.send { GuideReceived(it, GuideResponse(goal, GuideAction.AskUser("질문 $it"))) }
            d.send { SpeechFinished(it) }
            d.send { SpeechRecognized(it, "대답") }
        }
        assertEquals(GuideRequest.MAX_HISTORY, d.state.history.size)
        assertEquals(Phase.PLANNING, d.phase)
    }

    // ---------------------------------------------------------------- 그 밖

    // ---------------------------------------------------------------- ⑫ 안내 중 말하기 (2026-11-11 시험: 처음 한 번만 말할 수 있었다)

    @Test fun `안내 중 말하기는 읽던 안내를 끊고 듣는다 - 같은 세션, 목표와 이력은 그대로`() {
        val d = waitingOnMenu()
        val before = d.state
        val tr = d.send(StartPressed("sess-2"))
        assertEquals(Phase.LISTENING, d.phase)
        assertEquals(listOf(StopSpeaking, CancelPending, ClearHighlight, StartListening(d.ticket), StartTimer(20_000, d.ticket)), tr.effects)
        assertEquals("같은 세션", "sess-1", d.state.sessionId)
        assertEquals(before.goal, d.state.goal)
        assertEquals(before.history, d.state.history)

        val sent = d.send { SpeechRecognized(it, "따뜻한 걸로요") }        // 들은 말은 utterance로 → 서버가 목표에 붙인다
        assertEquals(Phase.PLANNING, d.phase)
        val req = (sent.effects.last() as RequestGuide).request
        assertEquals("따뜻한 걸로요", req.utterance)
        assertEquals(goal, req.goal)
        assertEquals(before.history, req.history)
    }

    @Test fun `안내 중 말하기에서 못 들으면 세션을 끝내지 않고 하던 안내로 돌아간다`() {
        val silent = waitingOnMenu().apply { send(StartPressed("x")) }
        val tr = silent.send { TimedOut(it) }                              // 8초 동안 말이 없음
        assertEquals(Phase.PLANNING, silent.phase)                         // 최신 화면으로 다시 묻기 (IDLE 아님)
        assertEquals("sess-1", silent.state.sessionId)
        assertEquals(StopListening, tr.effects.first())

        val failed = waitingOnMenu().apply { send(StartPressed("x")) }
        failed.send { SpeechFailed(it, SttError.NETWORK) }
        assertEquals(Phase.PLANNING, failed.phase)

        // 처음 말하기는 예전처럼: 말이 없으면 세션을 끝낸다
        val first = Driver().apply { send(StartPressed("s")) }
        first.send { TimedOut(it) }
        assertEquals(Phase.IDLE, first.phase)
    }

    @Test fun `질문에 대답하다 인식이 실패해도 세션을 끝내지 않고 기다렸다가 다시 묻는다`() {
        val d = planningOnMenu().apply {
            send { GuideReceived(it, GuideResponse(goal, GuideAction.AskUser("따뜻한 걸로 드릴까요, 차가운 걸로 드릴까요?"))) }
            send { SpeechFinished(it) }                                    // 질문을 다 읽음 → 듣기
        }
        assertEquals(Phase.LISTENING, d.phase)
        val tr = d.send { SpeechFailed(it, SttError.NETWORK) }             // 2026-11-12 시험: 인식 오류 11번 → 세션이 끝났다
        assertEquals(Phase.GUIDING, d.phase)
        assertEquals("sess-1", d.state.sessionId)
        assertEquals(Speak("말씀을 잘 못 들었어요. 대답하시려면 '말하기'를 누르고 말씀해 주세요.", d.ticket), tr.effects.last())
        d.send { SpeechFinished(it) }
        d.send { TimedOut(it) }
        assertEquals(Phase.PLANNING, d.phase)                               // 최신 화면으로 다시 묻기 → 같은 질문이 다시 나온다

        val silent = planningOnMenu().apply {                               // 말이 없을 때(무음 8초)도 같다
            send { GuideReceived(it, GuideResponse(goal, GuideAction.AskUser("포장하실 건가요?"))) }
            send { SpeechFinished(it) }
            send { TimedOut(it) }
        }
        assertEquals(Phase.GUIDING, silent.phase)
    }

    @Test fun `서버가 답하기 전에 또 말하면 앞의 말과 이어 붙인다`() {
        val d = planningOnMenu()                                           // 첫 말을 서버에 묻는 중
        d.send(StartPressed("x"))
        val tr = d.send { SpeechRecognized(it, "아이스로요") }
        assertEquals("$goal, 아이스로요", (tr.effects.last() as RequestGuide).request.utterance)
    }

    @Test fun `듣는 중 말하기는 무시하고, 마무리 안내 중 말하기는 새 세션을 연다`() {
        val listening = Driver().apply { send(StartPressed("s")) }
        val before = listening.state
        assertTrue(listening.send(StartPressed("s2")).effects.isEmpty())
        assertEquals(before, listening.state)

        val done = planningOnMenu().apply { send { GuideReceived(it, GuideResponse(goal, GuideAction.Done("다 됐어요"))) } }
        assertEquals(Phase.DONE, done.phase)
        done.send(StartPressed("sess-2"))
        assertEquals(Phase.LISTENING, done.phase)
        assertEquals("sess-2", done.state.sessionId)
        assertNull(done.state.goal)
    }

    // ---------------------------------------------------------------- ⑬ 놓친 화면 전환

    @Test fun `화면 번호가 그대로여도 안내한 버튼이 사라지고 새 글자가 여럿이면 다시 묻는다`() {
        // 메뉴 위에 옵션 창이 떠서 뒤 글자가 그대로 읽히는 바람에 화면 번호가 안 바뀐 경우 (2026-11-11 시험: 4분 동안 같은 안내)
        val popup = menu.copy(frameId = 11, elements = menu.elements.filter { it.id != "e4" } + listOf(
            Screens.el("p1", "옵션을 선택해 주세요", 0.2f, 0.3f, 0.8f, 0.35f, ElementKind.TEXT),
            Screens.el("p2", "취소", 0.2f, 0.7f, 0.45f, 0.75f),
            Screens.el("p3", "주문담기", 0.55f, 0.7f, 0.8f, 0.75f),
        ))
        val d = waitingOnMenu().apply { send(SnapshotReceived(popup)) }
        assertEquals(Phase.WAIT_CHANGE, d.phase)
        d.send { TimedOut(it) }
        assertEquals(Phase.PLANNING, d.phase)

        // 손가락이 버튼을 가린 것뿐이면(처음 보는 글자 없음) 다시 읽기만 한다
        val covered = menu.copy(frameId = 12, elements = menu.elements.filter { it.id != "e4" })
        val f = waitingOnMenu().apply { send(SnapshotReceived(covered)) }
        f.send { TimedOut(it) }
        assertEquals(Phase.GUIDING, f.phase)
    }

    // ---------------------------------------------------------------- 서버 답을 기다리는 동안

    @Test fun `서버 답이 1_5초 넘게 늦으면 잠시만요를 읽고 곧 올 답은 그대로 받는다`() {
        val d = planningOnMenu()
        val ticket = d.ticket
        val wait = d.send { TimedOut(it) }
        assertEquals(Phase.PLANNING, d.phase)
        assertEquals(ticket, d.ticket)                                    // 번호표를 올리지 않는다 = 늦게 온 답도 유효
        assertEquals(listOf(Speak("잠시만요. 찾고 있어요.", ticket)), wait.effects)
        val tr = d.send { GuideReceived(it, GuideResponse("g", GuideAction.Tap("e4", "아메리카노"))) }
        assertEquals(Phase.GUIDING, d.phase)
        assertTrue(tr.effects.any { it is Highlight })
    }

    @Test fun `서버가 먼저 답하면 잠시만요 타이머는 지난 일로 무시된다`() {
        val d = planningOnMenu()
        val staleTicket = d.ticket
        d.send { GuideReceived(it, GuideResponse("g", GuideAction.Tap("e4", "아메리카노"))) }
        val late = d.send(TimedOut(staleTicket))
        assertEquals(Phase.GUIDING, d.phase)
        assertTrue(late.effects.isEmpty())
    }

    @Test fun `잠시만요를 다 읽어도 서버 기다림은 끝나지 않는다`() {
        val d = planningOnMenu()
        d.send { TimedOut(it) }
        val tr = d.send { SpeechFinished(it) }
        assertEquals(Phase.PLANNING, d.phase)
        assertTrue(tr.effects.isEmpty())
    }
}
