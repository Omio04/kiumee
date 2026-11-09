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
     * "화면 왼쪽 위 깜빡이는 아메리카노 버튼을 누르세요"
     * 설계서는 "{guideText}를"이지만, HOT · ICE 같은 글자는 을/를을 정하기 어려워 "버튼을"로 고정한다.
     */
    fun tap(rect: NormRect, guideText: String): String {
        val name = guideText.trim()
        val obj = if (name.endsWith("버튼")) "${name}을" else "$name 버튼을"
        return "${phraser.phrase(rect)} 깜빡이는 $obj 누르세요"
    }

    /** SCROLL (서버는 아직 안 보냄): "화면을 아래로 밀어서 딸기스무디 찾아 주세요" */
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
        SttError.PERMISSION -> "마이크를 쓸 수 있게 허용해 주세요."
        else -> "말씀을 알아듣지 못했어요. 다시 말하기 버튼을 눌러 주세요."
    }

    /** 서버 요청 실패 (재시도까지 끝난 뒤) */
    fun networkError(): String = "연결이 잠시 불안정해요. 다시 말하기 버튼을 눌러 주세요."

    /** TAP의 targetId가 화면에 없을 때 (계약 위반 방어용, 정상이면 안 쓰임) */
    fun notFound(): String = "버튼을 찾지 못했어요. 화면이 잘 보이게 비춰 주세요."
}
