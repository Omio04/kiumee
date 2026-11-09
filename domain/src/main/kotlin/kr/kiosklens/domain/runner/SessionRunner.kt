package kr.kiosklens.domain.runner

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.kiosklens.core.model.SttError
import kr.kiosklens.core.model.SttEvent
import kr.kiosklens.core.port.Clock
import kr.kiosklens.core.port.GuideAgent
import kr.kiosklens.core.port.HighlightSurface
import kr.kiosklens.core.port.Logger
import kr.kiosklens.core.port.SnapshotSource
import kr.kiosklens.core.port.SpeechInput
import kr.kiosklens.core.port.SpeechOutput
import kr.kiosklens.domain.session.SessionEffect
import kr.kiosklens.domain.session.SessionEvent
import kr.kiosklens.domain.session.SessionState
import kr.kiosklens.domain.session.SessionStateMachine

/**
 * 상태머신이 내린 할 일([SessionEffect])을 포트 호출로 실행하고, 결과를 이벤트로 돌려 넣는다.
 * 서버 호출([GuideAgent.next])은 이 클래스에서만 한다.
 *
 * 규칙
 * - 이벤트는 한 줄(Channel)로 모아서 **한 번에 하나씩, 들어온 순서대로** reduce 한다.
 * - 포트가 던진 예외는 여기서 잡아 이벤트(실패)로 바꾼다. 앱이 죽지 않는다.
 * - 로그에는 개수·시간·종류만 남긴다. 발화·화면 글자 원문은 남기지 않는다.
 */
class SessionRunner(
    private val scope: CoroutineScope,
    private val machine: SessionStateMachine,
    private val snapshots: SnapshotSource,
    private val speechIn: SpeechInput,
    private val speechOut: SpeechOutput,
    private val agent: GuideAgent,
    private val highlight: HighlightSurface,
    private val clock: Clock,
    private val logger: Logger,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private val events = Channel<SessionEvent>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var listenJob: Job? = null
    private var speakJob: Job? = null
    private var planJob: Job? = null
    private var timerJob: Job? = null
    private var loopJob: Job? = null
    private val sources = mutableListOf<Job>()

    /** 이벤트 처리와 화면 수신을 시작한다. 한 번만 부른다. */
    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch { for (e in events) handle(e) }
        sources += scope.launch { guarded("snapshots") { snapshots.snapshots.collect { send(SessionEvent.SnapshotReceived(it)) } } }
        sources += scope.launch { guarded("screenChanges") { snapshots.screenChanges.collect { send(SessionEvent.ScreenChanged(it)) } } }
    }

    fun pressStart() = send(SessionEvent.StartPressed(newSessionId()))
    fun pressStop() = send(SessionEvent.StopPressed)

    fun send(event: SessionEvent) {
        events.trySend(event)
    }

    /** 앱 종료 시. 진행 중인 일을 모두 멈춘다. */
    fun close() {
        listOf(listenJob, speakJob, planJob, timerJob, loopJob).forEach { it?.cancel() }
        sources.forEach { it.cancel() }
        events.close()
        runCatching { speechIn.stop() }
        runCatching { speechOut.stop() }
        runCatching { highlight.setTarget(null) }
    }

    private fun handle(event: SessionEvent) {
        val before = _state.value
        val t = machine.reduce(before, event)
        _state.value = t.next
        if (before.phase != t.next.phase) logger.d(TAG, "phase ${before.phase} -> ${t.next.phase} (${event::class.simpleName})")
        for (effect in t.effects) {
            try {
                execute(effect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "effect failed: ${effect::class.simpleName}", e)
            }
        }
    }

    private fun execute(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.StartListening -> {
                listenJob?.cancel()
                listenJob = scope.launch { listen(effect.ticket) }
            }
            SessionEffect.StopListening -> {
                listenJob?.cancel(); listenJob = null
                runCatching { speechIn.stop() }
            }
            is SessionEffect.Speak -> {
                speakJob?.cancel()
                speakJob = scope.launch { speak(effect.text, effect.ticket) }
            }
            SessionEffect.StopSpeaking -> {
                speakJob?.cancel(); speakJob = null
                runCatching { speechOut.stop() }
            }
            is SessionEffect.RequestGuide -> {
                planJob?.cancel()
                planJob = scope.launch { plan(effect) }
            }
            is SessionEffect.Highlight -> highlight.setTarget(effect.target)
            SessionEffect.ClearHighlight -> highlight.setTarget(null)
            is SessionEffect.StartTimer -> {
                timerJob?.cancel()
                timerJob = scope.launch { delay(effect.delayMs); send(SessionEvent.TimedOut(effect.ticket)) }
            }
            SessionEffect.CancelPending -> {
                timerJob?.cancel(); timerJob = null
                planJob?.cancel(); planJob = null
            }
        }
    }

    private suspend fun listen(ticket: Int) {
        var done = false
        try {
            speechIn.start().collect { ev ->
                if (done) return@collect
                when (ev) {
                    is SttEvent.Partial -> Unit
                    is SttEvent.Final -> { done = true; send(SessionEvent.SpeechRecognized(ticket, ev.text)) }
                    is SttEvent.Error -> { done = true; send(SessionEvent.SpeechFailed(ticket, ev.code)) }
                }
            }
            if (!done) send(SessionEvent.SpeechFailed(ticket, SttError.NO_SPEECH))     // 계약 위반 방어: 결과 없이 끝남
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "listen failed", e)
            if (!done) send(SessionEvent.SpeechFailed(ticket, SttError.BUSY))
        }
    }

    private suspend fun speak(text: String, ticket: Int) {
        try {
            speechOut.speak(text, interrupt = true)
        } catch (e: CancellationException) {
            throw e                                                            // 취소되면 SpeechFinished를 보내지 않는다
        } catch (e: Exception) {
            logger.e(TAG, "speak failed", e)                                   // 소리가 안 나도 흐름은 이어 간다
        }
        send(SessionEvent.SpeechFinished(ticket))
    }

    private suspend fun plan(effect: SessionEffect.RequestGuide) {
        val t0 = clock.nowMs()
        val result = try {
            agent.next(effect.request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)                                                  // 계약 위반 방어: 예외 대신 failure여야 함
        }
        logger.d("KL_PERF", "guide_request ms=${clock.nowMs() - t0} ok=${result.isSuccess}")
        result.fold(
            onSuccess = { send(SessionEvent.GuideReceived(effect.ticket, it)) },
            onFailure = { send(SessionEvent.GuideFailed(effect.ticket)) },
        )
    }

    private suspend fun guarded(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "$name collect failed", e)
        }
    }

    private companion object { const val TAG = "SessionRunner" }
}
