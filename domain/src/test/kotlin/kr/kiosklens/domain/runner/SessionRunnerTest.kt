package kr.kiosklens.domain.runner

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideRequest
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.SttEvent
import kr.kiosklens.core.port.Clock
import kr.kiosklens.core.port.GuideAgent
import kr.kiosklens.core.port.HighlightSurface
import kr.kiosklens.core.port.Logger
import kr.kiosklens.core.port.SnapshotSource
import kr.kiosklens.core.port.SpeechInput
import kr.kiosklens.core.port.SpeechOutput
import kr.kiosklens.domain.policy.TimeoutPolicy
import kr.kiosklens.domain.session.Phase
import kr.kiosklens.domain.session.Screens
import kr.kiosklens.domain.session.SessionStateMachine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRunnerTest {
    private class Src : SnapshotSource {
        val snaps = MutableSharedFlow<ScreenSnapshot>(replay = 1)
        val changes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val snapshots: Flow<ScreenSnapshot> = snaps
        override val screenChanges: Flow<String> = changes
    }

    private class Ear(var script: () -> Flow<SttEvent>) : SpeechInput {
        var stops = 0
        override fun start(): Flow<SttEvent> = script()
        override fun stop() { stops++ }
    }

    private class Mouth(val readMs: Long = 5) : SpeechOutput {
        val said = CopyOnWriteArrayList<String>()
        var stops = 0
        override suspend fun speak(text: String, interrupt: Boolean) { said += text; delay(readMs) }
        override fun stop() { stops++ }
    }

    private class Brain(var answer: (GuideRequest) -> Result<GuideResponse>) : GuideAgent {
        val requests = CopyOnWriteArrayList<GuideRequest>()
        override suspend fun next(request: GuideRequest): Result<GuideResponse> { requests += request; return answer(request) }
    }

    private class Hand : HighlightSurface {
        val targets = CopyOnWriteArrayList<HighlightTarget?>()
        override fun setTarget(target: HighlightTarget?) { targets += target }
    }

    private val quiet = object : Logger {
        override fun d(tag: String, message: String) {}
        override fun w(tag: String, message: String, error: Throwable?) {}
        override fun e(tag: String, message: String, error: Throwable?) {}
    }

    private lateinit var scope: CoroutineScope
    private val src = Src()
    private val ear = Ear { flow { emit(SttEvent.Partial("따뜻")); emit(SttEvent.Final("아메리카노 주세요")) } }
    private val mouth = Mouth()
    private val hand = Hand()
    private val brain = Brain { r ->
        val e = r.snapshot.elements.first { it.text == "아메리카노" }
        Result.success(GuideResponse("아메리카노 주세요", GuideAction.Tap(e.id, e.text)))
    }

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @After fun tearDown() { scope.cancel() }

    private fun runner(t: TimeoutPolicy = TimeoutPolicy()) =
        SessionRunner(scope, SessionStateMachine(t), src, ear, mouth, brain, hand, Clock { System.currentTimeMillis() }, quiet, newSessionId = { "sid-1" })

    private fun waitUntil(ms: Long = 3000, cond: () -> Boolean) = runBlocking {
        withTimeout(ms) { while (!cond()) delay(5) }
    }

    @Test fun `말하면 서버에 묻고 하이라이트와 안내를 실행한다`() {
        val r = runner(); r.start()
        src.snaps.tryEmit(Screens.menu)
        waitUntil { r.state.value.snapshot != null }
        r.pressStart()
        waitUntil { r.state.value.phase == Phase.WAIT_CHANGE }
        assertEquals("아메리카노 주세요", brain.requests.single().utterance)
        assertEquals("sid-1", brain.requests.single().sessionId)
        assertEquals("e4", hand.targets.filterNotNull().first().elementId)
        assertTrue(mouth.said.single().contains("아메리카노 버튼을 누르세요"))
    }

    @Test fun `화면이 바뀌면 같은 목표로 다시 묻는다`() {
        val r = runner(); r.start()
        src.snaps.tryEmit(Screens.menu)
        waitUntil { r.state.value.snapshot != null }
        r.pressStart()
        waitUntil { r.state.value.phase == Phase.WAIT_CHANGE }
        brain.answer = { Result.success(GuideResponse(it.goal ?: "", GuideAction.Tap("e2", "HOT"))) }
        src.snaps.tryEmit(Screens.options)
        waitUntil { brain.requests.size == 2 && r.state.value.phase == Phase.WAIT_CHANGE }
        val second = brain.requests[1]
        assertNull(second.utterance)
        assertEquals("아메리카노 주세요", second.goal)
        assertEquals(1, second.history.size)
    }

    @Test fun `서버 실패는 잠시만요 뒤 다시 묻고, 계속 실패하면 오류 안내로 끝난다`() {
        brain.answer = { Result.failure(RuntimeException("x")) }
        val r = runner(TimeoutPolicy(retryAfterFailureMs = 20)); r.start()
        src.snaps.tryEmit(Screens.menu)
        waitUntil { r.state.value.snapshot != null }
        r.pressStart()
        waitUntil { r.state.value.phase == Phase.IDLE && mouth.said.any { it.contains("연결") } }
        assertTrue(mouth.said.first().contains("잠시만"))                       // 한 번 실패로 바로 끝내지 않는다
    }

    @Test fun `에이전트가 예외를 던져도 앱이 죽지 않는다`() {
        brain.answer = { throw IllegalStateException("계약 위반") }
        val r = runner(TimeoutPolicy(retryAfterFailureMs = 20)); r.start()
        src.snaps.tryEmit(Screens.menu)
        waitUntil { r.state.value.snapshot != null }
        r.pressStart()
        waitUntil { mouth.said.isNotEmpty() }
        waitUntil { r.state.value.phase == Phase.IDLE }
    }

    @Test fun `종료 버튼은 듣기 말하기 하이라이트를 모두 멈춘다`() {
        val r = runner(); r.start()
        src.snaps.tryEmit(Screens.menu)
        waitUntil { r.state.value.snapshot != null }
        r.pressStart()
        waitUntil { r.state.value.phase == Phase.WAIT_CHANGE }
        r.pressStop()
        waitUntil { r.state.value.phase == Phase.IDLE }
        waitUntil { hand.targets.last() == null }
        assertTrue(ear.stops > 0 && mouth.stops > 0)
    }

    @Test fun `무음이면 시간 초과로 끝난다`() {
        ear.script = { flow { delay(60_000) } }                       // 아무것도 안 내고 계속 듣기
        val r = runner(TimeoutPolicy(listenSilenceMs = 60)); r.start()
        r.pressStart()
        waitUntil { r.state.value.phase == Phase.IDLE && mouth.said.isNotEmpty() }
        assertTrue(mouth.said.first().contains("듣지 못했"))
    }

    @Test fun `화면이 없으면 찾다가 안내하고 화면이 보이면 바로 묻는다`() {
        val r = runner(TimeoutPolicy(scanningMs = 60)); r.start()
        r.pressStart()
        waitUntil { r.state.value.phase == Phase.LOST }
        src.snaps.tryEmit(Screens.menu)
        waitUntil { r.state.value.phase == Phase.WAIT_CHANGE }
    }

    @Test fun `인식 결과 없이 끝난 플로우도 말없음으로 처리한다`() {
        ear.script = { flow { } }
        val r = runner(); r.start()
        r.pressStart()
        waitUntil { r.state.value.phase == Phase.IDLE && mouth.said.isNotEmpty() }
    }

    @Test fun `소리 출력이 예외를 던져도 흐름은 이어진다`() {
        val bad = object : SpeechOutput {
            override suspend fun speak(text: String, interrupt: Boolean) { throw IllegalStateException("tts") }
            override fun stop() {}
        }
        val r = SessionRunner(scope, SessionStateMachine(), src, ear, bad, brain, hand, Clock { 0 }, quiet, newSessionId = { "s" })
        r.start(); src.snaps.tryEmit(Screens.menu)
        waitUntil { r.state.value.snapshot != null }
        r.pressStart()
        waitUntil { r.state.value.phase == Phase.WAIT_CHANGE }
    }
}
