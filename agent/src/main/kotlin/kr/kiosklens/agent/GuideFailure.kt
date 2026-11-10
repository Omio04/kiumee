package kr.kiosklens.agent

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kr.kiosklens.agent.remote.DtoMapper

/**
 * [RemoteGuideAgent]가 돌려준 실패를 사용자에게 읽어 줄 종류로 나눈다 (5일 계획 3일차: 400 · 401 · 429 · 502 · 504 → 사용자 안내).
 *
 * 문구 규칙 (설계서 10장): 사용자를 탓하지 않고, 전문용어(서버 · 네트워크 · 오류 코드)를 쓰지 않고, 한 문장으로 다음 행동을 알려 준다.
 * `:domain`은 `:agent`를 모르므로(어댑터 규칙) 이 분류는 `:app`이 `GuideAgent`를 감쌀 때 쓰거나, 계약을 바꾸는 PR로 옮긴다.
 */
enum class GuideFailureKind(val userMessage: String, val retryable: Boolean) {
    /** 400: 앱이 보낸 요청이 서버 규칙에 안 맞음 (앱 버그). 다시 해도 같다. */
    BAD_REQUEST("지금은 안내를 할 수 없어요. 앱을 다시 켜 주세요.", false),

    /** 401 · 403: 앱 키가 맞지 않음. 다시 해도 같다. */
    UNAUTHORIZED("안내 준비가 덜 됐어요. 도움이 필요하면 직원에게 말씀해 주세요.", false),

    /** 429: 요청이 몰림. 잠시 뒤 다시. */
    RATE_LIMITED("지금 안내가 많이 몰렸어요. 잠시 뒤에 다시 말씀해 주세요.", true),

    /** 500 · 502: 서버나 AI 쪽 문제. */
    SERVER_ERROR("안내에 문제가 생겼어요. 잠시 뒤에 다시 말씀해 주세요.", true),

    /** 504 또는 앱 쪽 시간 초과: 답이 늦음. */
    TIMEOUT("답이 늦어지고 있어요. 다시 한 번 말씀해 주세요.", true),

    /** 인터넷 연결 없음. */
    OFFLINE("인터넷 연결이 약해요. 연결을 확인해 주세요.", true),

    /** 서버가 이해할 수 없는 답을 줌. */
    BAD_RESPONSE("안내를 이해하지 못했어요. 한 번만 더 말씀해 주세요.", true),

    UNKNOWN("지금은 안내가 어려워요. 잠시 뒤에 다시 말씀해 주세요.", true),
}

/** 실패 원인 → 종류. 로그나 화면에는 [GuideFailureKind]만 쓰고 예외 메시지는 쓰지 않는다. */
fun Throwable.guideFailureKind(): GuideFailureKind = when (this) {
    is GuideHttpException -> when {
        status == 400 -> GuideFailureKind.BAD_REQUEST
        status == 401 || status == 403 -> GuideFailureKind.UNAUTHORIZED
        status == 429 -> GuideFailureKind.RATE_LIMITED
        status == 504 || status == 408 -> GuideFailureKind.TIMEOUT
        status in 500..599 -> GuideFailureKind.SERVER_ERROR
        else -> GuideFailureKind.UNKNOWN
    }
    is SocketTimeoutException -> GuideFailureKind.TIMEOUT
    is UnknownHostException, is ConnectException -> GuideFailureKind.OFFLINE
    is DtoMapper.BadResponse -> GuideFailureKind.BAD_RESPONSE
    else -> GuideFailureKind.UNKNOWN
}

/** 읽어 줄 안내 문구. */
fun Throwable.guideFailureMessage(): String = guideFailureKind().userMessage
