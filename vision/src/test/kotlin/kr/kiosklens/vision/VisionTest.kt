package kr.kiosklens.vision

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.kiosklens.core.model.CameraFrame
import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.port.Clock
import kr.kiosklens.core.port.FrameSink
import kr.kiosklens.core.port.Logger
import kr.kiosklens.core.port.SnapshotSource
import kr.kiosklens.vision.classify.ButtonLikeClassifier
import kr.kiosklens.vision.classify.ClassifierChain
import kr.kiosklens.vision.classify.PriceClassifier
import kr.kiosklens.vision.fake.FakeScreenAnalyzer
import kr.kiosklens.vision.fake.SampleScreens
import kr.kiosklens.vision.filter.CardNumberFilter
import kr.kiosklens.vision.filter.LowConfidenceFilter
import kr.kiosklens.vision.filter.TinyAreaFilter
import kr.kiosklens.vision.ocr.OcrBlock
import kr.kiosklens.vision.ocr.OcrEngine
import kr.kiosklens.vision.pipeline.ElementBuilder
import kr.kiosklens.vision.pipeline.FrameSampler
import kr.kiosklens.vision.pipeline.ScreenDiffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TestFrame(override val frameId: Long, var closed: AtomicInteger = AtomicInteger()) : CameraFrame {
    override val timestampMs = frameId * 100
    override val rotationDegrees = 0
    override val width = 1080
    override val height = 1920
    override val image: Any = Any()
    override fun close() { closed.incrementAndGet() }
}

internal fun blk(text: String, l: Float, t: Float, r: Float, b: Float, c: Float = 0.9f) = OcrBlock(text, NormRect(l, t, r, b), c)

internal val quiet = object : Logger {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String, error: Throwable?) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
}

/** 메뉴 화면을 OCR이 읽은 모양 */
internal fun menuBlocks(extra: List<OcrBlock> = emptyList()) = listOf(
    blk("커피", 0.02f, 0.12f, 0.2f, 0.18f), blk("음료", 0.22f, 0.12f, 0.4f, 0.18f),
    blk("아메리카노", 0.05f, 0.25f, 0.3f, 0.4f), blk("4,500원", 0.05f, 0.41f, 0.3f, 0.45f),
    blk("장바구니 보기", 0.6f, 0.9f, 0.95f, 0.97f), blk("원하시는 메뉴를 선택해 주세요", 0.1f, 0.05f, 0.9f, 0.1f),
) + extra

internal fun optionBlocks() = listOf(
    blk("HOT", 0.1f, 0.25f, 0.4f, 0.38f), blk("ICE", 0.55f, 0.25f, 0.85f, 0.38f),
    blk("담기", 0.55f, 0.88f, 0.95f, 0.96f), blk("아메리카노 옵션", 0.2f, 0.08f, 0.8f, 0.15f),
)

/** 계약 테스트 (README): Fake 와 실구현이 둘 다 이 클래스를 상속해서 통과해야 한다. */
abstract class VisionContractSpec {
    protected abstract fun create(): Pair<FrameSink, SnapshotSource>
    /** 화면 하나가 충분히 안정되어 스냅샷이 나오게 만든다 */
    protected abstract fun feedStableScreen(sink: FrameSink, source: SnapshotSource)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun cancel() { scope.cancel() }

    @Test fun `submit은 막히지 않고 이상한 프레임도 예외가 없다`() {
        val (sink, _) = create()
        val bad = object : CameraFrame {
            override val frameId = -1L; override val timestampMs = -5L; override val rotationDegrees = 777
            override val width = 0; override val height = 0; override val image: Any = "not an image"
            override fun close() { throw IllegalStateException("close도 실패") }
        }
        val t0 = System.nanoTime()
        sink.submit(bad); sink.submit(TestFrame(1))
        assertTrue("즉시 복귀", (System.nanoTime() - t0) / 1_000_000 < 200)
    }

    @Test fun `스냅샷은 stableFrames 2 이상만 나온다`() = runBlocking {
        val (sink, src) = create()
        val got = java.util.concurrent.CopyOnWriteArrayList<ScreenSnapshot>()
        val job = scope.launch { src.snapshots.collect { got += it } }
        feedStableScreen(sink, src)
        withTimeout(3000) { while (got.isEmpty()) delay(10) }
        assertTrue(got.all { it.stableFrames >= 2 })
        job.cancel()
    }

    @Test fun `받는 쪽이 느려도 막히지 않는다`() = runBlocking {
        val (sink, src) = create()
        val job = scope.launch { src.snapshots.collect { delay(10_000) } }              // 아주 느린 수집
        val t0 = System.currentTimeMillis()
        repeat(5) { feedStableScreen(sink, src) }
        assertTrue(System.currentTimeMillis() - t0 < 3000)
        job.cancel()
    }
}

class FakeScreenAnalyzerContractTest : VisionContractSpec() {
    override fun create(): Pair<FrameSink, SnapshotSource> = FakeScreenAnalyzer(SampleScreens.cafe).let { it to it }
    override fun feedStableScreen(sink: FrameSink, source: SnapshotSource) { (source as FakeScreenAnalyzer).show(1) }
}

class VisionScreenAnalyzerContractTest : VisionContractSpec() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var n = 0L
    override fun create(): Pair<FrameSink, SnapshotSource> {
        val ocr = object : OcrEngine { override suspend fun recognize(frame: CameraFrame) = menuBlocks() }
        val a = VisionScreenAnalyzer(scope, ocr, Clock { System.currentTimeMillis() }, quiet, sampler = FrameSampler(fps = 1000))
        return a to a
    }
    override fun feedStableScreen(sink: FrameSink, source: SnapshotSource) {
        repeat(4) { sink.submit(TestFrame(++n)); Thread.sleep(15) }
    }
}

class VisionPartsTest {
    // ---- 보안 필터 ----
    @Test fun `카드 번호와 유효기간은 요소가 되지 않는다`() {
        val f = CardNumberFilter()
        assertFalse(f.keep(blk("1234 5678 9012 3456", 0.1f, 0.1f, 0.5f, 0.15f)))
        assertFalse(f.keep(blk("1234567890123456", 0.1f, 0.1f, 0.5f, 0.15f)))
        assertFalse(f.keep(blk("1234-5678-9012-3456", 0.1f, 0.1f, 0.5f, 0.15f)))
        assertFalse(f.keep(blk("12/27", 0.1f, 0.1f, 0.5f, 0.15f)))
        assertTrue(f.keep(blk("4,500원", 0.1f, 0.1f, 0.5f, 0.15f)))
        assertTrue(f.keep(blk("아메리카노", 0.1f, 0.1f, 0.5f, 0.15f)))
        assertTrue(f.keep(blk("주문번호 105", 0.1f, 0.1f, 0.5f, 0.15f)))
        assertTrue(f.keep(blk("10:30", 0.1f, 0.1f, 0.5f, 0.15f)))
    }

    @Test fun `낮은 신뢰도와 아주 작은 글자는 거른다`() {
        assertFalse(LowConfidenceFilter(0.5f).keep(blk("흐림", 0.1f, 0.1f, 0.3f, 0.15f, c = 0.2f)))
        assertFalse(TinyAreaFilter().keep(blk("점", 0.1f, 0.1f, 0.101f, 0.102f)))
        assertFalse(TinyAreaFilter().keep(blk("전체", 0f, 0f, 1f, 1f)))
        assertTrue(TinyAreaFilter().keep(blk("버튼", 0.1f, 0.1f, 0.3f, 0.15f)))
    }

    // ---- 분류 ----
    @Test fun `가격과 버튼과 안내문을 구분한다`() {
        val c = ClassifierChain.default()
        fun k(t: String) = c.classify(blk(t, 0.1f, 0.1f, 0.4f, 0.15f))
        assertEquals(ElementKind.PRICE, k("4,500원")); assertEquals(ElementKind.PRICE, k("12,000원")); assertEquals(ElementKind.PRICE, k("₩4500"))
        assertEquals(ElementKind.BUTTON_LIKE, k("아메리카노")); assertEquals(ElementKind.BUTTON_LIKE, k("HOT")); assertEquals(ElementKind.BUTTON_LIKE, k("장바구니 보기"))
        assertEquals(ElementKind.BUTTON_LIKE, k("담기")); assertEquals(ElementKind.BUTTON_LIKE, k("불고기버거 세트"))
        assertEquals(ElementKind.TEXT, k("원하시는 메뉴를 선택해 주세요")); assertEquals(ElementKind.TEXT, k("결제 수단을 선택하세요"))
        assertEquals(ElementKind.TEXT, k("국내산 한우 패티를 사용하여 만든 프리미엄 버거"))
    }

    // ---- 요소 ID ----
    @Test fun `같은 버튼은 프레임이 바뀌어도 같은 id`() {
        val b = ElementBuilder(listOf(), ClassifierChain.default())
        val a = b.build(menuBlocks()).associate { it.text to it.id }
        val shaken = menuBlocks().map { it.copy(rect = NormRect(it.rect.left + 0.003f, it.rect.top + 0.002f, it.rect.right + 0.003f, it.rect.bottom + 0.002f)) }
        val c = b.build(shaken).associate { it.text to it.id }
        assertEquals(a, c)
    }

    @Test fun `다른 자리의 같은 글자는 다른 id이고 충돌은 번호가 붙는다`() {
        val b = ElementBuilder(listOf(), ClassifierChain.default())
        val two = b.build(listOf(blk("추가", 0.1f, 0.2f, 0.3f, 0.25f), blk("추가", 0.6f, 0.7f, 0.8f, 0.75f)))
        assertEquals(2, two.map { it.id }.toSet().size)
        val same = b.build(listOf(blk("추가", 0.1f, 0.2f, 0.3f, 0.25f), blk("추가", 0.11f, 0.2f, 0.3f, 0.25f)))
        assertTrue(same.any { it.id.endsWith("_2") })
    }

    @Test fun `길이와 개수 제한을 지킨다`() {
        val b = ElementBuilder(listOf(), ClassifierChain.default())
        val many = (0 until 400).map { blk("항목$it", (it % 20) / 20f, (it / 20) / 25f, (it % 20) / 20f + 0.04f, (it / 20) / 25f + 0.03f) }
        assertEquals(300, b.build(many).size)
        assertTrue(b.build(listOf(blk("가".repeat(200), 0.1f, 0.1f, 0.5f, 0.2f))).single().text.length <= 80)
        assertTrue(b.build(listOf(blk("나쁨", 0.5f, 0.5f, 0.2f, 0.2f))).isEmpty())
    }

    // ---- 화면 전환 ----
    private fun els(blocks: List<OcrBlock>) = ElementBuilder(listOf(), ClassifierChain.default()).build(blocks)

    @Test fun `처음 화면은 바로 안정 2`() {
        val d = ScreenDiffer()
        val r = d.update(els(menuBlocks()))
        assertTrue(r.isNewScreen); assertEquals(2, r.stableFrames)
        assertEquals(3, d.update(els(menuBlocks())).stableFrames)
    }

    @Test fun `한 프레임만 다르게 읽혀도 새 화면이 아니다`() {
        val d = ScreenDiffer(); val m = els(menuBlocks())
        val id = d.update(m).screenId
        d.update(m)
        val glitch = d.update(els(optionBlocks()))
        assertFalse(glitch.isNewScreen); assertEquals(id, glitch.screenId)
        val back = d.update(m)
        assertEquals(id, back.screenId); assertFalse(back.isNewScreen)
    }

    @Test fun `두 프레임 연속 다르면 새 화면`() {
        val d = ScreenDiffer(); val m = els(menuBlocks())
        val id = d.update(m).screenId
        d.update(m)
        d.update(els(optionBlocks()))
        val r = d.update(els(optionBlocks()))
        assertTrue(r.isNewScreen); assertNotEquals(id, r.screenId)
    }

    @Test fun `요소가 3개 미만이면 판단에서 제외`() {
        val d = ScreenDiffer(); d.update(els(menuBlocks()))
        val r = d.update(els(listOf(blk("손", 0.1f, 0.1f, 0.2f, 0.2f))))
        assertNull(r.screenId)
        assertFalse(d.update(els(menuBlocks())).isNewScreen)
    }

    @Test fun `OCR 노이즈가 조금 있어도 같은 화면`() {
        val d = ScreenDiffer(); d.update(els(menuBlocks()))
        val noisy = els(menuBlocks().filterNot { it.text == "음료" } + blk("잡음", 0.8f, 0.5f, 0.9f, 0.55f))
        assertFalse(d.update(noisy).isNewScreen)
    }

    // ---- 샘플링 ----
    @Test fun `초당 3장 이하로 줄이고 똑같은 프레임은 건너뛴다`() {
        val s = FrameSampler(fps = 3)
        val img = ByteArray(64 * 36) { 100 }
        assertEquals(FrameSampler.Decision.RUN_OCR, s.decide(0, img))
        assertEquals(FrameSampler.Decision.DROP, s.decide(100, img))
        assertEquals(FrameSampler.Decision.SAME_AS_LAST, s.decide(400, img.copyOf()))
        val changed = ByteArray(64 * 36) { 200.toByte() }
        assertEquals(FrameSampler.Decision.RUN_OCR, s.decide(800, changed))
        assertEquals(FrameSampler.Decision.RUN_OCR, FrameSampler().decide(0, null))
    }
}

class VisionAnalyzerFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun end() { scope.cancel() }

    @Test fun `화면을 읽어 스냅샷과 화면 전환을 내보내고 프레임을 모두 닫는다`() = runBlocking {
        var current = menuBlocks()
        val ocr = object : OcrEngine { override suspend fun recognize(frame: CameraFrame) = current }
        val a = VisionScreenAnalyzer(scope, ocr, Clock { System.currentTimeMillis() }, quiet, sampler = FrameSampler(fps = 1000))
        val snaps = java.util.concurrent.CopyOnWriteArrayList<ScreenSnapshot>()
        val changes = java.util.concurrent.CopyOnWriteArrayList<String>()
        scope.launch { a.snapshots.collect { snaps += it } }
        scope.launch { a.screenChanges.collect { changes += it } }
        delay(50)
        val frames = ArrayList<TestFrame>()
        fun feed(n: Int) = repeat(n) { val f = TestFrame(frames.size + 1L); frames += f; a.submit(f); Thread.sleep(25) }
        feed(3); withTimeout(3000) { while (snaps.isEmpty()) delay(10) }
        val first = snaps.last()
        assertTrue(first.elements.any { it.text == "아메리카노" && it.kind == ElementKind.BUTTON_LIKE })
        assertTrue(first.elements.any { it.kind == ElementKind.PRICE })
        current = optionBlocks(); feed(4)
        withTimeout(3000) { while (changes.size < 2) delay(10) }
        withTimeout(3000) { while (snaps.last().screenId == first.screenId) delay(10) }
        delay(200)
        assertTrue("모든 프레임은 정확히 한 번 닫힌다: " + frames.map { it.closed.get() }, frames.all { it.closed.get() == 1 })
    }

    @Test fun `OCR이 예외를 던져도 계속 동작하고 프레임을 닫는다`() = runBlocking {
        var fail = true
        val ocr = object : OcrEngine { override suspend fun recognize(frame: CameraFrame): List<OcrBlock> { if (fail) throw RuntimeException("mlkit"); return menuBlocks() } }
        val a = VisionScreenAnalyzer(scope, ocr, Clock { System.currentTimeMillis() }, quiet, sampler = FrameSampler(fps = 1000))
        val f1 = TestFrame(1); a.submit(f1); delay(100)
        assertEquals(1, f1.closed.get())
        fail = false
        val got = java.util.concurrent.CopyOnWriteArrayList<ScreenSnapshot>()
        scope.launch { a.snapshots.collect { got += it } }
        repeat(3) { a.submit(TestFrame(10L + it)); Thread.sleep(25) }
        withTimeout(3000) { while (got.isEmpty()) delay(10) }
    }

    @Test fun `처리가 밀리면 오래된 프레임을 버리고 닫는다`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ocr = object : OcrEngine { override suspend fun recognize(frame: CameraFrame): List<OcrBlock> { gate.await(); return menuBlocks() } }
        val a = VisionScreenAnalyzer(scope, ocr, Clock { System.currentTimeMillis() }, quiet, sampler = FrameSampler(fps = 1000))
        val fs = (1..6).map { TestFrame(it.toLong()) }
        fs.forEach { a.submit(it); Thread.sleep(5) }
        delay(100)
        val closedWhileBlocked = fs.count { it.closed.get() == 1 }
        assertTrue("밀린 프레임이 닫혀야 함: $closedWhileBlocked", closedWhileBlocked >= 4)
        gate.complete(Unit); delay(200)
        assertTrue(fs.all { it.closed.get() == 1 })
    }

    @Test fun `카드 번호는 스냅샷 어디에도 없다`() = runBlocking {
        val ocr = object : OcrEngine { override suspend fun recognize(frame: CameraFrame) = menuBlocks(listOf(blk("1234 5678 9012 3456", 0.1f, 0.6f, 0.6f, 0.65f), blk("12/27", 0.7f, 0.6f, 0.8f, 0.65f))) }
        val a = VisionScreenAnalyzer(scope, ocr, Clock { System.currentTimeMillis() }, quiet, sampler = FrameSampler(fps = 1000))
        val got = java.util.concurrent.CopyOnWriteArrayList<ScreenSnapshot>()
        scope.launch { a.snapshots.collect { got += it } }
        repeat(3) { a.submit(TestFrame(it + 1L)); Thread.sleep(25) }
        withTimeout(3000) { while (got.isEmpty()) delay(10) }
        assertTrue(got.flatMap { it.elements }.none { it.text.contains("1234") || it.text == "12/27" })
    }
}

private fun assertTrue(msg: String, c: Boolean) = org.junit.Assert.assertTrue(msg, c)
