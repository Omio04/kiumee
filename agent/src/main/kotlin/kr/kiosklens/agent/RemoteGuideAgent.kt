package kr.kiosklens.agent

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.kiosklens.agent.remote.DtoMapper
import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideRequest
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.port.GuideAgent
import kr.kiosklens.core.port.Logger

/** 서버 주소와 앱 키. LLM 키는 절대 여기 없다 (서버에만). */
data class AgentConfig(
    /** 예: "https://kiosk-lens.example.com" (끝의 / 는 있어도 없어도 됨) */
    val baseUrl: String,
    val appKey: String,
    val timeoutMs: Int = 6_000,
    /** 첫 시도 뒤 다시 시도하는 횟수 (계약: 1회) */
    val retries: Int = 1,
    /** 429의 Retry-After를 기다리는 최대 시간 */
    val maxRetryWaitMs: Long = 5_000,
)

/** 서버가 준 상태 코드. 앱은 이 코드로 안내 문구를 고른다 (message는 개발자용). */
class GuideHttpException(val status: Int, val code: String?, val retryAfterMs: Long? = null) : IOException("HTTP $status ${code.orEmpty()}")

/**
 * POST /v1/guide/next (계약 v1, docs/contract.md 3장).
 * - 예외 대신 [Result.failure]
 * - 400 · 401은 다시 시도하지 않는다. 429는 Retry-After만큼 기다렸다 1회. 502 · 504 · 500 · 네트워크 오류는 바로 1회.
 * - 성공해도 TAP의 targetId가 요청 화면에 없으면 [GuideAction.Unknown]으로 바꾼다.
 * - 로그에는 상태 코드와 시간만 남긴다 (발화 · 화면 글자 금지).
 */
class RemoteGuideAgent(
    private val config: AgentConfig,
    private val logger: Logger,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : GuideAgent {

    /** 네트워크 읽기 전용. 시간이 다 된 읽기는 여기서 혼자 끝나게 두고 기다리지 않는다 ([postWithin]). */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun next(request: GuideRequest): Result<GuideResponse> {
        val sent = DtoMapper.sanitize(request)
        val body = DtoMapper.requestJson(sent).toByteArray(Charsets.UTF_8)
        var last: Throwable? = null
        for (attempt in 0..config.retries) {
            val t0 = nowMs()
            try {
                val resp = postWithin(body)
                logger.d("KL_PERF", "agent attempt=$attempt status=200 ms=${nowMs() - t0}")
                return Result.success(guard(resp, sent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GuideHttpException) {
                logger.w("KL_PERF", "agent attempt=$attempt status=${e.status} code=${e.code} ms=${nowMs() - t0}")
                last = e
                if (e.status == 400 || e.status == 401 || e.status == 403) break        // 다시 해도 같다
                if (e.status == 429) e.retryAfterMs?.let { delay(it.coerceAtMost(config.maxRetryWaitMs)) }
            } catch (e: DtoMapper.BadResponse) {
                logger.w("KL_PERF", "agent attempt=$attempt bad_response ms=${nowMs() - t0}")
                last = e
            } catch (e: IOException) {                                                   // 시간 초과 · 연결 실패
                logger.w("KL_PERF", "agent attempt=$attempt io=${e.javaClass.simpleName} ms=${nowMs() - t0}")
                last = e
            } catch (e: Exception) {
                logger.e("KL_PERF", "agent attempt=$attempt unexpected", e)
                last = e
                break
            }
        }
        return Result.failure(last ?: IOException("unknown"))
    }

    /** 모르는 버튼을 가리키면 안 된다: 요청 화면에 없는 targetId는 UNKNOWN으로. */
    private fun guard(resp: GuideResponse, sent: GuideRequest): GuideResponse {
        val a = resp.action
        if (a is GuideAction.Tap && sent.snapshot.findElement(a.targetId) == null) {
            return resp.copy(action = GuideAction.Unknown("버튼을 찾지 못했어요. 화면이 잘 보이게 비춰 주세요."))
        }
        return resp
    }

    /**
     * 한 번의 시도는 **전체** [AgentConfig.timeoutMs] 안에 끝난다 (계약 3장: 앱 → 서버 6초).
     * connect · read 타임아웃은 구간별이라 응답이 조금씩 늦게 오면 그보다 길어질 수 있고,
     * 읽는 중인 연결을 끊는 것(disconnect)도 JVM에서는 남은 응답을 다 읽느라 멈출 수 있다.
     * 그래서 읽기는 [io]에서 따로 돌리고, 시간이 다 되면 기다리지 않고 바로 시간 초과로 돌아온다.
     * 남은 읽기와 연결 끊기는 [io]에서 혼자 끝난다.
     */
    private suspend fun postWithin(body: ByteArray): GuideResponse {
        val c = open()                                                      // 아직 네트워크를 쓰지 않음
        val call = io.async { exchange(c, body) }
        try {
            return withTimeoutOrNull(config.timeoutMs.toLong()) { call.await() }
                ?: throw SocketTimeoutException("전체 ${config.timeoutMs}ms 초과")
        } finally {
            if (!call.isCompleted) call.cancel()
            io.launch { runCatching { c.disconnect() } }                    // 끊기가 오래 걸려도 부른 쪽은 기다리지 않음
        }
    }

    private fun open(): HttpURLConnection {
        val c = URL(config.baseUrl.trimEnd('/') + "/v1/guide/next").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = config.timeoutMs
        c.readTimeout = config.timeoutMs
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("X-App-Key", config.appKey)
        return c
    }

    /** 요청을 쓰고 응답을 읽는다. 연결은 [postWithin]이 끊는다. */
    private fun exchange(c: HttpURLConnection, body: ByteArray): GuideResponse {
        c.outputStream.use { it.write(body) }
        val status = c.responseCode
        if (status != 200) {
            val text = runCatching { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
            val wait = c.getHeaderField("Retry-After")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            throw GuideHttpException(status, DtoMapper.errorCode(text), wait)
        }
        val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        return DtoMapper.parseResponse(text)
    }
}
