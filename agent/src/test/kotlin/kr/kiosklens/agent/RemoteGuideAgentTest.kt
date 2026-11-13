package kr.kiosklens.agent

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kr.kiosklens.agent.remote.DtoMapper
import kr.kiosklens.agent.remote.Json
import kr.kiosklens.core.model.ActionType
import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideRequest
import kr.kiosklens.core.model.HistoryItem
import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.model.UiElement
import kr.kiosklens.core.port.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class RemoteGuideAgentTest {
    private val quiet = object : Logger {
        override fun d(tag: String, message: String) {}
        override fun w(tag: String, message: String, error: Throwable?) {}
        override fun e(tag: String, message: String, error: Throwable?) {}
    }
    private val contractDir = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "testdata/contract") }.first { it.isDirectory }

    private fun contract(name: String) = File(contractDir, name).readText()

    /** testdata/contract/request_*.json → GuideRequest */
    private fun requestFrom(name: String): GuideRequest {
        val m = Json.parse(contract(name)) as Map<String, Any?>
        val s = m["snapshot"] as Map<String, Any?>
        return GuideRequest(
            sessionId = m["sessionId"] as String, utterance = m["utterance"] as String?, goal = m["goal"] as String?,
            snapshot = ScreenSnapshot(
                s["screenId"] as String, (s["frameId"] as Double).toLong(), (s["timestampMs"] as Double).toLong(),
                (s["elements"] as List<Map<String, Any?>>).map { e ->
                    val r = e["rect"] as Map<String, Double>
                    UiElement(e["id"] as String, e["text"] as String,
                        NormRect(r["left"]!!.toFloat(), r["top"]!!.toFloat(), r["right"]!!.toFloat(), r["bottom"]!!.toFloat()),
                        (e["confidence"] as Double).toFloat(), ElementKind.valueOf(e["kind"] as String))
                }, (s["stableFrames"] as Double).toInt()),
            history = (m["history"] as List<Map<String, Any?>>).map { HistoryItem(it["screenId"] as String, ActionType.valueOf(it["action"] as String), it["targetText"] as String?) },
        )
    }

    private class Stub(val handler: (Int, String, Map<String, String>) -> Triple<Int, String, Map<String, String>>) {
        val hits = AtomicInteger()
        val bodies = CopyOnWriteArrayList<String>()
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/guide/next") { ex ->
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val h = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
                bodies += body; headers += h
                val (code, text, extra) = handler(hits.incrementAndGet(), body, h)
                extra.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                ex.responseHeaders.add("Content-Type", "application/json")
                val b = text.toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        fun stop() = server.stop(0)
    }

    private val stubs = mutableListOf<Stub>()
    @After fun tearDown() { stubs.forEach { it.stop() } }
    private fun stub(h: (Int, String, Map<String, String>) -> Triple<Int, String, Map<String, String>>) = Stub(h).also { stubs += it }
    private fun agent(s: Stub, retries: Int = 1, timeoutMs: Int = 2000) =
        RemoteGuideAgent(AgentConfig(s.url + "/", "KEY123", timeoutMs, retries, 50), quiet)
    private fun ok(name: String) = Triple(200, contract(name), emptyMap<String, String>())

    // ---------- 왕복: 서버와 같은 예시 파일 ----------

    @Test fun `요청 JSON이 contract 예시와 의미가 같다`() {
        for (n in listOf("request_first.json", "request_after_screen_change.json")) {
            val req = requestFrom(n)
            val mine = Json.parse(DtoMapper.requestJson(req))
            val theirs = Json.parse(contract(n))
            assertEquals(n, theirs, mine)
        }
    }

    @Test fun `contract 응답 예시를 모두 읽는다`() {
        val expect = mapOf(
            "response_tap.json" to ActionType.TAP, "response_scroll.json" to ActionType.SCROLL, "response_wait.json" to ActionType.WAIT,
            "response_ask_user.json" to ActionType.ASK_USER, "response_done.json" to ActionType.DONE, "response_unknown.json" to ActionType.UNKNOWN,
        )
        for ((n, t) in expect) {
            val r = DtoMapper.parseResponse(contract(n))
            val got = when (r.action) { is GuideAction.Tap -> ActionType.TAP; is GuideAction.Scroll -> ActionType.SCROLL; is GuideAction.Wait -> ActionType.WAIT
                is GuideAction.AskUser -> ActionType.ASK_USER; is GuideAction.Done -> ActionType.DONE; is GuideAction.Unknown -> ActionType.UNKNOWN }
            assertEquals(n, t, got)
        }
        val tap = DtoMapper.parseResponse(contract("response_tap.json"))
        assertEquals("따뜻한 아메리카노 한 잔 주세요", tap.goal)
        assertEquals(GuideAction.Tap("e4", "아메리카노"), tap.action)
    }

    // ---------- 통신 ----------

    @Test fun `앱 키 헤더와 본문을 보내고 TAP을 받는다`() = runBlocking {
        val s = stub { _, _, _ -> ok("response_tap.json") }
        val r = agent(s).next(requestFrom("request_first.json"))
        assertEquals(GuideAction.Tap("e4", "아메리카노"), r.getOrThrow().action)
        assertEquals("KEY123", s.headers.single()["x-app-key"])
        assertTrue(s.headers.single()["content-type"]!!.startsWith("application/json"))
    }

    @Test fun `이미지나 모르는 최상위 필드는 보내지 않는다`() {
        val keys = (Json.parse(DtoMapper.requestJson(requestFrom("request_first.json"))) as Map<String, Any?>).keys
        assertEquals(setOf("sessionId", "utterance", "goal", "snapshot", "history"), keys)
    }

    @Test fun `사진은 켠 경우에만 image 필드로 간다`() {
        val withPhoto = Json.parse(DtoMapper.requestJson(requestFrom("request_first.json"), "QUJD/+=")) as Map<String, Any?>
        assertEquals("QUJD/+=", withPhoto["image"])
        val without = Json.parse(DtoMapper.requestJson(requestFrom("request_first.json"), null)) as Map<String, Any?>
        assertTrue("image" !in without.keys)
    }

    @Test fun `401은 다시 시도하지 않고 failure`() = runBlocking {
        val s = stub { _, _, _ -> Triple(401, contract("error_401.json"), emptyMap()) }
        val r = agent(s).next(requestFrom("request_first.json"))
        assertTrue(r.isFailure)
        val e = r.exceptionOrNull() as GuideHttpException
        assertEquals(401, e.status); assertEquals("unauthorized", e.code)
        assertEquals(1, s.hits.get())
    }

    @Test fun `400도 다시 시도하지 않는다`() = runBlocking {
        val s = stub { _, _, _ -> Triple(400, """{"error":{"code":"invalid_request","message":"x","requestId":"r"}}""", emptyMap()) }
        assertTrue(agent(s).next(requestFrom("request_first.json")).isFailure)
        assertEquals(1, s.hits.get())
    }

    @Test fun `502는 1회 다시 시도해서 성공한다`() = runBlocking {
        val s = stub { n, _, _ -> if (n == 1) Triple(502, """{"error":{"code":"llm_error","message":"x","requestId":"r"}}""", emptyMap()) else ok("response_tap.json") }
        assertTrue(agent(s).next(requestFrom("request_first.json")).isSuccess)
        assertEquals(2, s.hits.get())
    }

    @Test fun `계속 실패하면 재시도는 1회뿐이다`() = runBlocking {
        val s = stub { _, _, _ -> Triple(500, """{"error":{"code":"internal","message":"x","requestId":"r"}}""", emptyMap()) }
        assertTrue(agent(s).next(requestFrom("request_first.json")).isFailure)
        assertEquals(2, s.hits.get())
    }

    @Test fun `429는 Retry-After 뒤에 1회 다시 시도한다`() = runBlocking {
        val t0 = System.currentTimeMillis()
        val s = stub { n, _, _ -> if (n == 1) Triple(429, """{"error":{"code":"rate_limited","message":"x","requestId":"r"}}""", mapOf("Retry-After" to "0.05")) else ok("response_tap.json") }
        assertTrue(agent(s).next(requestFrom("request_first.json")).isSuccess)
        assertEquals(2, s.hits.get())
        assertTrue(System.currentTimeMillis() - t0 >= 40)
    }

    @Test fun `시간 초과는 예외 없이 failure`() = runBlocking {
        val s = stub { _, _, _ -> Thread.sleep(600); ok("response_tap.json") }
        val r = agent(s, retries = 0, timeoutMs = 150).next(requestFrom("request_first.json"))
        assertTrue(r.isFailure)
    }

    @Test fun `응답이 조금씩 늦게 와도 한 번의 시도는 전체 시간 제한 안에 끝난다 (계약 3장)`() = runBlocking {
        // 연결 · 읽기 타임아웃은 구간별이라, 글자가 조금씩 오면 그것만으로는 끊기지 않는다 → 전체 시간으로 끊어야 한다
        val body = contract("response_tap.json").toByteArray(Charsets.UTF_8)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/guide/next") { ex ->
                ex.requestBody.readBytes()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, 0)                                  // 길이 미정 (조금씩 보냄)
                runCatching { ex.responseBody.use { out -> for (b in body) { out.write(b.toInt()); out.flush(); Thread.sleep(30) } } }
            }
            start()
        }
        try {
            val t0 = System.currentTimeMillis()
            val r = RemoteGuideAgent(AgentConfig("http://127.0.0.1:${server.address.port}", "k", 500, 0, 10), quiet)
                .next(requestFrom("request_first.json"))
            val took = System.currentTimeMillis() - t0
            assertTrue("다 받기 전에 끊겨야 함", r.isFailure)
            assertTrue("전체 시간 제한(500ms)을 크게 넘으면 안 됨: ${took}ms", took < 1_500)
        } finally {
            server.stop(0)
        }
    }

    @Test fun `서버에 연결할 수 없어도 예외 없이 failure`() = runBlocking {
        val r = RemoteGuideAgent(AgentConfig("http://127.0.0.1:1", "k", 300, 1, 10), quiet).next(requestFrom("request_first.json"))
        assertTrue(r.isFailure)
    }

    @Test fun `깨진 응답은 failure`() = runBlocking {
        val s = stub { _, _, _ -> Triple(200, """{"goal":"x","action":{"type":"TAP"}}""", emptyMap()) }
        assertTrue(agent(s, retries = 0).next(requestFrom("request_first.json")).isFailure)
    }

    @Test fun `요청 화면에 없는 targetId는 UNKNOWN으로 바꾼다`() = runBlocking {
        val s = stub { _, _, _ -> Triple(200, """{"goal":"g","action":{"type":"TAP","targetId":"zzz","guideText":"없는 버튼"}}""", emptyMap()) }
        val a = agent(s).next(requestFrom("request_first.json")).getOrThrow().action
        assertTrue(a is GuideAction.Unknown)
    }

    @Test fun `모르는 필드는 무시한다`() {
        val r = DtoMapper.parseResponse("""{"goal":"g","source":"llm","extra":{"a":1},"action":{"type":"DONE","guideText":"끝","x":1}}""")
        assertEquals(GuideAction.Done("끝"), r.action)
    }

    // ---------- 보내기 전 정리 (길이 제한 3.6) ----------

    @Test fun `길이 제한을 맞춰서 보낸다`() {
        val base = requestFrom("request_first.json")
        val many = (1..400).map { UiElement("e$it", "버튼$it".padEnd(120, '가'), NormRect(0.1f, 0.1f, 0.2f, 0.2f), 0.9f, ElementKind.BUTTON_LIKE) } +
            UiElement("e1", "중복", NormRect(0.1f, 0.1f, 0.2f, 0.2f), 0.9f, ElementKind.TEXT) +
            UiElement("bad", "깨진좌표", NormRect(0.5f, 0.5f, 0.2f, 0.2f), 0.9f, ElementKind.TEXT) +
            UiElement("blank", "   ", NormRect(0.1f, 0.1f, 0.2f, 0.2f), 0.9f, ElementKind.TEXT)
        val hist = (1..9).map { HistoryItem("s", ActionType.TAP, "t$it") }
        val req = base.copy(utterance = "가".repeat(500), goal = "나".repeat(500), snapshot = base.snapshot.copy(elements = many), history = hist)
        val out = Json.parse(DtoMapper.requestJson(req)) as Map<String, Any?>
        assertEquals(200, (out["utterance"] as String).length)
        assertEquals(200, (out["goal"] as String).length)
        val els = (out["snapshot"] as Map<String, Any?>)["elements"] as List<Map<String, Any?>>
        assertEquals(300, els.size)
        assertTrue(els.all { (it["text"] as String).length <= 80 })
        assertEquals(els.size, els.map { it["id"] }.toSet().size)
        assertEquals(5, (out["history"] as List<*>).size)
        assertEquals("t9", ((out["history"] as List<Map<String, Any?>>).last())["targetText"])
    }

    @Test fun `빈 말은 null로 보낸다`() {
        val out = Json.parse(DtoMapper.requestJson(requestFrom("request_first.json").copy(utterance = "  ", goal = ""))) as Map<String, Any?>
        assertNull(out["utterance"]); assertNull(out["goal"])
    }

    @Test fun `특수문자가 들어간 글자도 JSON으로 안전하다`() {
        val base = requestFrom("request_first.json")
        val el = UiElement("e1", "따옴표\"와 \\ 역슬래시\n줄바꿈", NormRect(0.1f, 0.1f, 0.3f, 0.2f), 0.9f, ElementKind.BUTTON_LIKE)
        val out = Json.parse(DtoMapper.requestJson(base.copy(snapshot = base.snapshot.copy(elements = listOf(el))))) as Map<String, Any?>
        val got = (((out["snapshot"] as Map<String, Any?>)["elements"] as List<Map<String, Any?>>)[0]["text"]) as String
        assertEquals(el.text, got)
    }
}
