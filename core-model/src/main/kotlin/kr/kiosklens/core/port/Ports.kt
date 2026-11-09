package kr.kiosklens.core.port

import kotlinx.coroutines.flow.Flow
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.model.FrameMotion
import kr.kiosklens.core.model.GuideRequest
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.SttEvent

/*
 * 포트 인터페이스 (설계서 16.4 · 16.7).
 * 이 파일의 변경은 전원 승인 PR로만 한다. Fake와 계약 테스트도 함께 수정할 것.
 */

/** 카메라 쪽은 입력만 안다. (:vision, P2) */
interface FrameSink {
    /**
     * 계약: 논블로킹(내부 큐에 넣고 즉시 복귀), 예외를 던지지 않음,
     * 밀리면 오래된 프레임을 폐기(폐기한 프레임은 close).
     */
    fun submit(frame: CameraFrame)
}

/** 세션 쪽은 결과만 안다. (:vision, P2) */
interface SnapshotSource {
    /** 계약: stableFrames >= 2인 스냅샷만 발행, 수집이 느려도 막히지 않음(최신만 유지). */
    val snapshots: Flow<ScreenSnapshot>
    /** 새 screenId 발생 이벤트 */
    val screenChanges: Flow<String>
}

/** (:voice, P3) */
interface SpeechInput {
    /**
     * 계약: Final 또는 Error 하나를 발행한 뒤 플로우 종료, 취소 시 마이크 자원 해제,
     * 실패는 예외가 아니라 [SttEvent.Error].
     */
    fun start(): Flow<SttEvent>
    fun stop()
}

/** (:voice, P3) */
interface SpeechOutput {
    /**
     * 계약: 재생이 끝날 때 복귀, 코루틴 취소 시 즉시 중단,
     * interrupt=true면 직전 안내를 즉시 중단.
     */
    suspend fun speak(text: String, interrupt: Boolean = true)
    fun stop()
}

/** (:agent, P4) */
interface GuideAgent {
    /**
     * 계약: 예외 대신 Result.failure,
     * 성공 시 Tap.targetId는 요청 스냅샷에 존재하거나 액션이 Unknown.
     */
    suspend fun next(request: GuideRequest): Result<GuideResponse>
}

/** (:overlay, P5) */
interface HighlightSurface {
    /** 계약: 어느 스레드에서 호출해도 안전, null이면 숨김, 같은 값 재호출은 무해(멱등). */
    fun setTarget(target: HighlightTarget?)
}

/** (:overlay, P5) 선택: 트래킹 입력 */
interface MotionSink {
    fun onFrameMotion(motion: FrameMotion)
}

/** 시간 의존 주입. 타임아웃 테스트를 결정적으로 만든다. */
fun interface Clock {
    fun nowMs(): Long
}

/** 로그 주입. 요청 원문 · 개인정보는 남기지 않는다. 성능 구간은 tag = "KL_PERF". */
interface Logger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}
