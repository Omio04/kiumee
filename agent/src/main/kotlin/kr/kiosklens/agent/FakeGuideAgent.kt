package kr.kiosklens.agent

import kr.kiosklens.core.model.ElementKind
import kr.kiosklens.core.model.GuideAction
import kr.kiosklens.core.model.GuideRequest
import kr.kiosklens.core.model.GuideResponse
import kr.kiosklens.core.port.GuideAgent

/**
 * 서버 없이 쓰는 가짜 (mock 플레이버 · 테스트용). 규칙은 항상 같다.
 * 말이 없으면 되묻기 / 누를 만한 글자가 없으면 UNKNOWN / 그 밖에는 목표와 글자가 가장 겹치는 BUTTON_LIKE로 TAP.
 */
class FakeGuideAgent : GuideAgent {
    val requests = mutableListOf<GuideRequest>()

    override suspend fun next(request: GuideRequest): Result<GuideResponse> {
        requests += request
        val goal = request.utterance?.takeIf { it.isNotBlank() } ?: request.goal.orEmpty()
        if (goal.isBlank()) return Result.success(GuideResponse("", GuideAction.AskUser("무엇을 도와드릴까요?")))
        val buttons = request.snapshot.elements.filter { it.kind == ElementKind.BUTTON_LIKE }
        if (buttons.isEmpty()) {
            return Result.success(GuideResponse(goal, GuideAction.Unknown("버튼을 찾지 못했어요. 화면이 잘 보이게 비춰 주세요.")))
        }
        val best = buttons.maxByOrNull { e -> overlap(e.text, goal) }!!
        return Result.success(GuideResponse(goal, GuideAction.Tap(best.id, best.text)))
    }

    private fun overlap(a: String, b: String): Int = a.count { it.isLetterOrDigit() && b.contains(it) }
}
