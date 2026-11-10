package kr.kiosklens.overlay

import java.util.concurrent.atomic.AtomicReference
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.port.HighlightSurface

/**
 * HighlightSurface 계약의 핵심(스레드 안전 · null=숨김 · 같은 값 재호출은 무해)을 한 곳에 둔 상태 보관소.
 * [ViewOverlay]가 이것을 쓰고, 값이 바뀔 때만 [onChanged]로 다시 그리게 한다.
 * 같은 값이 또 오면 아무 일도 하지 않으므로 깜빡임이 처음부터 다시 시작되지 않는다.
 */
class HighlightHolder(private val onChanged: (HighlightTarget?) -> Unit = {}) : HighlightSurface {
    private val ref = AtomicReference<HighlightTarget?>(null)
    val target: HighlightTarget? get() = ref.get()

    override fun setTarget(target: HighlightTarget?) {
        val old = ref.getAndSet(target)
        if (old != target) onChanged(target)
    }
}
