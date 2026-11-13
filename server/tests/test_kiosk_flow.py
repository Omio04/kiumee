from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.settings import Settings


async def test_iced_americano_follows_the_practice_screens_without_questions():
    llm = ScriptedFakeLlmClient([])
    agent = LlmGuideAgent(llm, Settings(app_key="test"))
    goal = None
    screen = 0

    async def step(labels, action, targets, phrase):
        nonlocal goal, screen
        screen += 1
        elements = [
            {"id": str(i), "text": label, "kind": kind,
             "rect": {"left": .1, "right": .5, "top": i / 10, "bottom": i / 10 + .04}}
            for i, (label, kind) in enumerate(labels)
        ]
        req = GuideRequest.model_validate({
            "sessionId": "practice", "goal": goal,
            "utterance": None if goal else "아이스 아메리카노 하나 마시고 싶어",
            "snapshot": {"screenId": f"s{screen}", "frameId": screen, "timestampMs": 0,
                         "stableFrames": 2, "elements": elements},
        })
        result = (await agent.next(req)).response
        goal = result.goal
        assert result.action.type.value == action
        assert phrase in result.action.guideText
        assert (result.action.targetIds or ([result.action.targetId] if result.action.targetId else [])) == targets

    b, t = "BUTTON_LIKE", "TEXT"
    await step([("(HOT)아메리카노", b), ("(ICE)헤이즐넛아메리카노", b), ("(ICE)아메리카노", b)],
               "TAP", ["2"], "아이스 아메리카노")
    await step([("선택된 옵션", t), ("(ICE)아메리카노", t), ("연하게", b), ("주문담기", b)],
               "WAIT", ["2", "3"], "옵션")
    await step([("선택된 옵션", t), ("(ICE)아메리카노", t), ("추가로 선택할 옵션이 없습니다", t), ("장바구니 담기", b)],
               "TAP", ["3"], "장바구니 담기")
    await step([("(ICE)아메리카노", b), ("장바구니 조회", t), ("(ICE)아메리카노", t), ("2,000 원 결제하기", b)],
               "TAP", ["3"], "결제하기")
    await step([("주문 세부 내역", t), ("먹고가기", b), ("포장하기", b)], "WAIT", ["1", "2"], "포장하기")
    await step([("일회용품 필요여부", t), ("선택안함", b), ("선택완료", b)], "WAIT", ["1", "2"], "일회용품")
    await step([("결제수단 선택", t), ("카드결제", b), ("삼성페이/애플페이", t), ("앱카드", b)],
               "WAIT", ["1", "3"], "삼성페이는 카드결제")
    await step([("스탬프 적립", t), ("×", b), ("적립", t)], "WAIT", ["1"], "전화번호")
    await step([("카드 결제(간편 결제)", t), ("승인 요청", b)], "WAIT", ["1"], "단말기")
    await step([("승인 중", t)], "WAIT", [], "진행 중")
    await step([("영수증을 출력하시겠습니까?", t), ("출력", b), ("미출력", b)], "WAIT", ["1", "2"], "영수증")
    await step([("결제가 완료되었습니다", t), ("주문번호", t)], "DONE", [], "번호표")
    assert not llm.calls
