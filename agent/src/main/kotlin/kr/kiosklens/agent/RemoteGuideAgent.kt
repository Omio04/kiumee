package kr.kiosklens.agent

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
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

    override suspend fun next(request: GuideRequest): Result<GuideResponse> {
        val sent = DtoMapper.sanitize(request)
        val body = DtoMapper.requestJson(sent).toByteArray(Charsets.UTF_8)
        var last: Throwable? = null
        for (attempt in 0..config.retries) {
            val t0 = nowMs()
            try {
                val resp = runInterruptible(Dispatchers.IO) { post(body) }
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

    private fun post(body: ByteArray): GuideResponse {
        val url = URL(config.baseUrl.trimEnd('/') + "/v1/guide/next")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = config.timeoutMs
            c.readTimeout = config.timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-App-Key", config.appKey)
            c.outputStream.use { it.write(body) }
            val status = c.responseCode
            if (status != 200) {
                val text = runCatching { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
                val wait = c.getHeaderField("Retry-After")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                throw GuideHttpException(status, DtoMapper.errorCode(text), wait)
            }
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return DtoMapper.parseResponse(text)
        } finally {
            c.disconnect()
        }
    }
}
