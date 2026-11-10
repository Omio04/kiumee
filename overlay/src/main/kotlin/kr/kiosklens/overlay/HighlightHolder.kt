package kr.kiosklens.overlay

import java.util.concurrent.atomic.AtomicReference
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.port.HighlightSurface

/**
 * HighlightSurface 계약의 핵심(스레드 안전 · null=숨김 · 같은 값 재호출은 무해)을 한 곳에 둔 상태 보관소.
 * [ViewOverlay]가 이것을 쓰고, 값이 바뀔 때만 [onChanged]로 다시 그리게 한다.
 * 같은 값이 또 오면 아무 일도 하지 않으므로 깜빡임이 처음부터 다시 시작되지 않는다.
 *
 * [setTarget]은 한 번에 한 스레드만 들어오게 막는다. 막지 않으면 두 스레드가 거의 동시에 부를 때
 * "값 바꾸기"와 [onChanged] 알림의 순서가 뒤바뀌어, 상태는 B인데 화면에는 A가 그려질 수 있다.
 * 호출은 1초에 몇 번뿐이라 막아도 느려지지 않는다. ([onChanged] 안에서 다시 setTarget을 부르면 안 된다)
 */
class HighlightHolder(private val onChanged: (HighlightTarget?) -> Unit = {}) : HighlightSurface {
    private val ref = AtomicReference<HighlightTarget?>(null)
    val target: HighlightTarget? get() = ref.get()

    @Synchronized
    override fun setTarget(target: HighlightTarget?) {
        val old = ref.getAndSet(target)
        if (old != target) onChanged(target)
    }
}
