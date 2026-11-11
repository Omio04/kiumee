package kr.kiosklens.agent

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kr.kiosklens.agent.remote.DtoMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideFailureTest {
    private fun http(status: Int) = GuideHttpException(status, null)

    @Test fun `상태 코드마다 알맞은 종류로 나뉜다`() {
        assertEquals(GuideFailureKind.BAD_REQUEST, http(400).guideFailureKind())
        assertEquals(GuideFailureKind.UNAUTHORIZED, http(401).guideFailureKind())
        assertEquals(GuideFailureKind.UNAUTHORIZED, http(403).guideFailureKind())
        assertEquals(GuideFailureKind.RATE_LIMITED, http(429).guideFailureKind())
        assertEquals(GuideFailureKind.SERVER_ERROR, http(500).guideFailureKind())
        assertEquals(GuideFailureKind.SERVER_ERROR, http(502).guideFailureKind())
        assertEquals(GuideFailureKind.TIMEOUT, http(504).guideFailureKind())
        assertEquals(GuideFailureKind.UNKNOWN, http(418).guideFailureKind())
    }

    @Test fun `네트워크 문제와 깨진 응답도 나뉜다`() {
        assertEquals(GuideFailureKind.TIMEOUT, SocketTimeoutException("x").guideFailureKind())
        assertEquals(GuideFailureKind.OFFLINE, UnknownHostException("x").guideFailureKind())
        assertEquals(GuideFailureKind.OFFLINE, ConnectException("x").guideFailureKind())
        assertEquals(GuideFailureKind.BAD_RESPONSE, DtoMapper.BadResponse("x").guideFailureKind())
        assertEquals(GuideFailureKind.UNKNOWN, IllegalStateException("x").guideFailureKind())
    }

    @Test fun `다시 해도 같은 실패만 다시 시도 불가로 표시한다`() {
        assertFalse(GuideFailureKind.BAD_REQUEST.retryable)
        assertFalse(GuideFailureKind.UNAUTHORIZED.retryable)
        assertTrue(GuideFailureKind.TIMEOUT.retryable)
        assertTrue(GuideFailureKind.RATE_LIMITED.retryable)
    }

    @Test fun `안내 문구는 짧고 전문용어와 상태 코드가 없다`() {
        val banned = listOf("서버", "네트워크", "오류", "에러", "코드", "HTTP", "API", "토큰", "401", "429", "502", "504", "잘못")
        for (k in GuideFailureKind.entries) {
            assertTrue("${k.name} 문구가 비었다", k.userMessage.isNotBlank())
            assertTrue("${k.name} 문구가 너무 길다", k.userMessage.length <= 40)
            for (w in banned) assertFalse("${k.name} 문구에 '$w'", k.userMessage.contains(w))
        }
    }

    @Test fun `예외에서 바로 문구를 얻는다`() {
        assertEquals(GuideFailureKind.RATE_LIMITED.userMessage, http(429).guideFailureMessage())
    }
}
