package kr.kiosklens.vision

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement
import kr.kiosklens.core.port.Clock
import kr.kiosklens.core.port.FrameSink
import kr.kiosklens.core.port.Logger
import kr.kiosklens.core.port.SnapshotSource
import kr.kiosklens.vision.classify.ClassifierChain
import kr.kiosklens.vision.classify.ElementClassifier
import kr.kiosklens.vision.filter.BlankFilter
import kr.kiosklens.vision.filter.CardNumberFilter
import kr.kiosklens.vision.filter.LowConfidenceFilter
import kr.kiosklens.vision.filter.TextFilter
import kr.kiosklens.vision.filter.TinyAreaFilter
import kr.kiosklens.vision.ocr.OcrEngine
import kr.kiosklens.vision.pipeline.ElementBuilder
import kr.kiosklens.vision.pipeline.FrameSampler
import kr.kiosklens.vision.pipeline.ScreenDiffer

/**
 * 카메라 프레임 → 글자 읽기 → [ScreenSnapshot]. (:vision 의 두 포트를 한 클래스가 구현)
 *
 * 계약
 * - [submit]은 즉시 돌아오고 예외를 던지지 않는다. 처리가 밀리면 **오래된 프레임을 버리고 close()** 한다.
 * - 모든 프레임은 정확히 한 번 close() 된다 (안 닫으면 카메라가 멈춘다).
 * - [snapshots]는 `stableFrames >= 2` 만 발행하고, 받는 쪽이 느려도 막히지 않는다(최신만 남김).
 * - 원본 글자는 로그에 남기지 않는다.
 */
class VisionScreenAnalyzer(
    private val scope: CoroutineScope,
    private val ocr: OcrEngine,
    private val clock: Clock,
    private val logger: Logger,
    filters: List<TextFilter> = defaultFilters(),
    classifier: ElementClassifier = ClassifierChain.default(),
    private val sampler: FrameSampler = FrameSampler(),
    private val differ: ScreenDiffer = ScreenDiffer(),
    /** 64×36 흑백 축소본. 없으면 시간 간격만으로 샘플링한다. :app 이 ImageProxy 의 Y 평면에서 만들어 넣는다. */
    private val thumbnailer: (CameraFrame) -> ByteArray? = { null },
) : FrameSink, SnapshotSource {

    private val builder = ElementBuilder(filters, classifier)
    private val latest = AtomicReference<CameraFrame?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val snapFlow = MutableSharedFlow<ScreenSnapshot>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val changeFlow = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val snapshots: Flow<ScreenSnapshot> = snapFlow
    override val screenChanges: Flow<String> = changeFlow

    private var lastElements: List<UiElement> = emptyList()
    private var lastFrame: CameraFrame? = null

    init {
        scope.launch {
            try {
                for (u in wake) {
                    val f = latest.getAndSet(null) ?: continue
                    process(f)
                }
            } finally {
                latest.getAndSet(null)?.safeClose()
            }
        }
    }

    override fun submit(frame: CameraFrame) {
        try {
            val old = latest.getAndSet(frame)
            old?.safeClose()                                    // 밀린 프레임은 버린다
            wake.trySend(Unit)
        } catch (e: Throwable) {
            frame.safeClose()
        }
    }

    private suspend fun process(frame: CameraFrame) {
        val t0 = clock.nowMs()
        try {
            val thumb = runCatching { thumbnailer(frame) }.getOrNull()
            when (sampler.decide(t0, thumb)) {
                FrameSampler.Decision.DROP -> return
                FrameSampler.Decision.SAME_AS_LAST -> {
                    val r = differ.repeat()
                    val id = r.screenId
                    if (id != null && r.stableFrames == MIN_STABLE && lastElements.isNotEmpty()) publish(frame, id, r.stableFrames, lastElements)
                    return
                }
                FrameSampler.Decision.RUN_OCR -> Unit
            }
            val blocks = ocr.recognize(frame)
            val elements = builder.build(blocks)
            val r = differ.update(elements)
            val id = r.screenId ?: return                       // 요소가 너무 적음: 화면이 아니거나 가려짐
            if (r.isNewScreen) changeFlow.tryEmit(id)
            lastElements = elements
            if (r.stableFrames >= MIN_STABLE) publish(frame, id, r.stableFrames, elements)
            logger.d("KL_PERF", "vision ocr_ms=${clock.nowMs() - t0} elements=${elements.size} stable=${r.stableFrames}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("KL_PERF", "vision failed", e)             // 한 프레임 실패는 건너뛴다
        } finally {
            frame.safeClose()
        }
    }

    private fun publish(frame: CameraFrame, id: String, stable: Int, elements: List<UiElement>) {
        snapFlow.tryEmit(ScreenSnapshot(id, frame.frameId, frame.timestampMs, elements, stable))
    }

    private fun CameraFrame.safeClose() { try { close() } catch (_: Throwable) {} }

    companion object {
        const val MIN_STABLE = 2
        fun defaultFilters(): List<TextFilter> = listOf(BlankFilter(), CardNumberFilter(), LowConfidenceFilter(), TinyAreaFilter())
    }
}
