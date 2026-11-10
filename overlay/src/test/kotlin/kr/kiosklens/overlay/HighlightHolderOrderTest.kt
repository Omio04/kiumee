package kr.kiosklens.overlay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kr.kiosklens.core.model.HighlightTarget
import kr.kiosklens.core.model.NormRect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 동시 호출 때 "값 바꾸기 → 다시 그리라고 알리기" 순서가 뒤바뀌지 않는지, 일부러 끼어들게 만들어 매번 확인한다.
 *
 * 계약 테스트의 "여러 스레드에서 동시에 부르기"는 이 순간을 거의 못 잡는다
 * (2026-11-10 확인: `@Synchronized`를 뺀 코드로 15번 돌려 15번 모두 통과). 이 테스트는 빼면 매번 실패한다.
 */
class HighlightHolderOrderTest {
    private fun t(id: String) = HighlightTarget(id, NormRect(0.1f, 0.2f, 0.3f, 0.3f))

    @Test(timeout = 5_000)
    fun `A를 알리는 도중에 B가 들어와도 마지막으로 알린 값은 B다`() {
        val a = t("a"); val b = t("b")
        val inA = CountDownLatch(1); val releaseA = CountDownLatch(1)
        val notified = AtomicReference<HighlightTarget?>(null)
        val holder = HighlightHolder { v ->
            if (v == a) { inA.countDown(); releaseA.await() }   // A를 알리는 도중에 멈춘다
            notified.set(v)
        }
        val t1 = Thread { holder.setTarget(a) }.apply { start() }
        inA.await()
        val t2 = Thread { holder.setTarget(b) }.apply { start() }
        // 잠금이 있으면 t2는 기다리고(BLOCKED), 없으면 B를 먼저 알리고 끝난다(TERMINATED)
        while (t2.state != Thread.State.BLOCKED && t2.state != Thread.State.TERMINATED) Thread.sleep(1)
        releaseA.countDown()
        t1.join(); t2.join()
        assertEquals(b, holder.target)
        assertEquals("상태는 B인데 화면에는 A가 그려진다", b, notified.get())
    }
}
