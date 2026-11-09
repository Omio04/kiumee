package kr.kiosklens.overlay

import java.util.concurrent.CopyOnWriteArrayList
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.port.HighlightSurface
import kr.kiosklens.core.port.Logger

/** 받은 대상을 기록(+로그)하는 가짜 (mock 플레이버 · 테스트용). */
class FakeOverlay(private val logger: Logger? = null) : HighlightSurface {
    private val holder = HighlightHolder { t ->
        history += t
        logger?.d("FakeOverlay", if (t == null) "hide" else "show ${t.elementId}")
    }
    val history = CopyOnWriteArrayList<HighlightTarget?>()
    val current: HighlightTarget? get() = holder.target
    override fun setTarget(target: HighlightTarget?) = holder.setTarget(target)
}
