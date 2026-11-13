package kr.kiosklens.domain.policy

import kr.kiosklens.core.model.NormRect
import kr.kiosklens.core.model.ScrollDir
import kr.kiosklens.core.model.SttError

/**
 * 어르신께 읽어 드리는 문장을 한 곳에 모은다 (변경 이유: 말투).
 * 규칙 (설계서 10장): 쉬운 존댓말 · 비난 · 전문용어 금지. 3일차에 P3와 다듬는다.
 *
 * 서버가 준 문장(ASK_USER의 question, DONE의 guideText, UNKNOWN의 reason)은 그대로 읽는다.
 */
class GuideTextComposer(private val phraser: PositionPhraser = PositionPhraser()) {

    /**
     * "화면 왼쪽 위 노란색 칸을 눌러 주세요. 적힌 글자는 아메리카노"
     * (2026-11-13) 예전 "깜빡이는 ○○ 버튼을 누르세요"는 어르신께 "깜빡이는", "버튼"이 와닿지 않았다. 눈에 보이는 **노란색 칸**을 말하고,
     * 맞는 칸인지 확인할 수 있게 적힌 글자를 덧붙인다. 글자 뒤에 조사(을/를 · 이라고/라고)를 붙이지 않아서 HOT · ICE · 어떤 이름이 와도 맞는 말이다.
     */
    fun tap(rect: NormRect, guideText: String): String {
        val name = guideText.trim()
        return "${phraser.phrase(rect)} 노란색 칸을 눌러 주세요. 적힌 글자는 $name"
    }

    /** SCROLL: "화면을 아래로 밀어서 딸기스무디 찾아 주세요". guideText는 찾는 대상 이름 (문장 아님, contract.md 3.2) */
    fun scroll(direction: ScrollDir, guideText: String): String {
        val where = when (direction) {
            ScrollDir.UP -> "위로"
            ScrollDir.DOWN -> "아래로"
            ScrollDir.LEFT -> "왼쪽으로"
            ScrollDir.RIGHT -> "오른쪽으로"
        }
        return "화면을 $where 밀어서 ${guideText.trim()} 찾아 주세요"
    }

    /** LOST: 10초 동안 키오스크 화면을 못 찾았을 때 */
    fun lost(): String = "키오스크 화면이 잘 보이게 비춰 주세요."

    /** LISTENING에서 말을 듣지 못했을 때 (무음 8초 · NO_SPEECH) */
    fun silence(): String = "말씀을 듣지 못했어요. 다시 말하기 버튼을 눌러 주세요."

    /** 음성 인식 오류 (NO_SPEECH 제외) */
    fun speechError(error: SttError): String = when (error) {
        SttError.PERMISSION -> "마이크를 쓸 수 없어요. 휴대폰 설정에서 이 앱과 음성 인식 서비스의 마이크 허용을 확인해 주세요."   // 앱 권한이 있어도 음성 인식 서비스(Google) 쪽 권한이 꺼져 있으면 같은 오류가 난다 (2026-11-13 실기기)
        else -> "말씀을 알아듣지 못했어요. 다시 말하기 버튼을 눌러 주세요."
    }

    /** 서버 요청 실패 (재시도까지 끝난 뒤) */
    fun networkError(): String = "연결이 잠시 불안정해요. 다시 말하기 버튼을 눌러 주세요."

    /** 진행 중에 질문의 대답을 못 들었을 때 (세션은 이어가고 잠시 뒤 다시 묻는다) */
    fun notHeard(): String = "말씀을 잘 못 들었어요. 대답하시려면 '말하기'를 누르고 말씀해 주세요."

    /** 서버 답을 기다리는 중 ([TimeoutPolicy.thinkingMs] 넘게) */
    fun thinking(): String = "잠시만요. 찾고 있어요."

    /** 서버 요청이 실패해서 잠시 뒤 다시 물을 때 (세션은 이어간다) */
    fun retrying(): String = "답이 조금 늦어지고 있어요. 잠시만 기다려 주세요."

    /** TAP의 targetId가 화면에 없을 때 (계약 위반 방어용, 정상이면 안 쓰임) */
    fun notFound(): String = "버튼을 찾지 못했어요. 화면이 잘 보이게 비춰 주세요."
}
