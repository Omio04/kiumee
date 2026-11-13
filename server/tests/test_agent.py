"""계약 테스트: 같은 시나리오를 FakeGuideAgent와 LlmGuideAgent(가짜 LLM) 양쪽에 돌려 응답 규약을 확인."""
import pytest
from app.agent import FakeGuideAgent, LlmGuideAgent
from app.llm import HeuristicFakeLlmClient, ScriptedFakeLlmClient, LlmTimeout
from app.models import ActionType, GuideRequest
from app.settings import Settings
from tests.conftest import make_req

S = Settings(app_key="k", llm_provider="fake")
S_NOFAST = Settings(app_key="k", llm_provider="fake", fastpath=False)


def agents():
    return {"fake": FakeGuideAgent(), "llm": LlmGuideAgent(HeuristicFakeLlmClient(), S)}


@pytest.mark.parametrize("name", ["fake", "llm"])
@pytest.mark.parametrize("screen,goal", [("s01_dine", "포장해 주세요"), ("s02_menu", "아메리카노 주세요"),
                                          ("s03_options", "따뜻한 걸로요"), ("s04_cart", "결제"), ("s05_payment", "카드로요")])
async def test_contract_guide(snaps, name, screen, goal):
    r = (await agents()[name].next(GuideRequest.model_validate(make_req(snaps, screen, goal)))).response
    assert r.action.type == ActionType.TAP and r.goal == goal
    ids = {e["id"]: e for e in snaps[screen]["elements"]}
    a = r.action
    assert a.targetId in ids and ids[a.targetId]["kind"] == "BUTTON_LIKE" and a.guideText == ids[a.targetId]["text"]


@pytest.mark.parametrize("name", ["fake", "llm"])
async def test_card_screen_waits_for_approval_instead_of_ending_session(snaps, name):
    r = (await agents()[name].next(GuideRequest.model_validate(make_req(snaps, "s06_card", "")))).response
    assert r.action.type == ActionType.WAIT and r.action.guideText and r.action.targetId is None


@pytest.mark.parametrize("name", ["fake", "llm"])
async def test_contract_no_buttons_unknown(name):
    snap = {"screenId": "x", "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": []}
    req = {"sessionId": "s", "utterance": "뭐", "snapshot": snap}
    r = (await agents()[name].next(GuideRequest.model_validate(req))).response
    assert r.action.type == ActionType.UNKNOWN and r.action.reason


async def test_llm_unknown_button_rejected(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"ghost","guideText":"유령","message":null}'])
    a = LlmGuideAgent(llm, S_NOFAST)
    res = await a.next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "뭔가 주세요")))
    assert res.response.action.type == ActionType.UNKNOWN and res.meta["rejected"] and len(llm.calls) == 1


async def test_llm_bad_format_retried_once_then_ok(snaps):
    llm = ScriptedFakeLlmClient(["죄송합니다", '{"action":"GUIDE","targetId":"e3","guideText":"x"}'])
    res = await LlmGuideAgent(llm, S_NOFAST).next(GuideRequest.model_validate(make_req(snaps, "s01_dine", "포장")))
    assert res.response.action.targetId == "e3" and len(llm.calls) == 2 and res.meta["retry"]
    assert "형식이 틀렸" in llm.calls[1]["user"]


async def test_llm_bad_format_twice_unknown_no_third_call(snaps):
    llm = ScriptedFakeLlmClient(["엉뚱", "또 엉뚱"])
    res = await LlmGuideAgent(llm, S_NOFAST).next(GuideRequest.model_validate(make_req(snaps, "s01_dine", "포장")))
    assert res.response.action.type == ActionType.UNKNOWN and len(llm.calls) == 2


async def test_fastpath_skips_llm(snaps):
    llm = ScriptedFakeLlmClient(["X"])
    res = await LlmGuideAgent(llm, S).next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "아메리카노 주세요")))
    assert res.response.source == "fastpath" and llm.calls == []


async def test_fastpath_off_calls_llm(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"e4","guideText":"아메리카노"}'])
    res = await LlmGuideAgent(llm, S_NOFAST).next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "아메리카노 주세요")))
    assert res.response.source == "llm" and len(llm.calls) == 1


async def test_timeout_propagates(snaps):
    a = LlmGuideAgent(ScriptedFakeLlmClient([LlmTimeout("t")]), S_NOFAST)
    with pytest.raises(LlmTimeout):
        await a.next(GuideRequest.model_validate(make_req(snaps, "s01_dine", "포장")))


async def test_prompt_has_only_k_candidates_and_no_card_digits(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN","message":"모르겠어요"}'])
    s = Settings(app_key="k", llm_provider="fake", fastpath=False, candidate_k=3)
    await LlmGuideAgent(llm, s).next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "카드번호 1234567812345678 뭐")))
    user = llm.calls[0]["user"]
    assert user.split("[후보]")[1].strip().count("\n") + 1 == 3 and "12345678" not in user


async def test_echoed_goal_used_when_no_new_utterance(snaps):
    """화면만 바뀐 재요청: 앱이 돌려준 goal로 목표를 이어 감 (서버는 상태 저장 안 함)."""
    h = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]
    res = await LlmGuideAgent(HeuristicFakeLlmClient(), S).next(
        GuideRequest.model_validate(make_req(snaps, "s03_options", None, h, goal="아이스 주세요")))
    assert res.response.action.guideText == "ICE" and res.response.goal == "아이스 주세요"


@pytest.mark.parametrize("name", ["fake", "llm"])
async def test_answer_to_question_extends_goal(snaps, name):
    """되물음에 대답: 직전 goal + 새 발화 → 새 goal, 대답으로 버튼을 고름."""
    h = [{"screenId": "s03_options", "action": "ASK_USER", "targetText": "따뜻한 것과 차가운 것 중 어떤 걸로 드릴까요?"}]
    req = make_req(snaps, "s03_options", "따뜻한 거요", h, goal="커피 한 잔 주세요")
    r = (await agents()[name].next(GuideRequest.model_validate(req))).response
    assert r.goal == "커피 한 잔 주세요, 따뜻한 거요" and r.action.guideText == "HOT"


async def test_goal_is_capped_to_contract_length(snaps):
    req = make_req(snaps, "s01_dine", "포장이요", goal="가" * 200)
    r = (await FakeGuideAgent().next(GuideRequest.model_validate(req))).response
    assert len(r.goal) == 200 and r.goal.endswith("포장이요")


async def test_history_is_rendered_in_prompt(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN","message":"모르겠어요"}'])
    h = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"},
         {"screenId": "s03_options", "action": "ASK_USER", "targetText": "따뜻한 걸로 드릴까요?"}]
    await LlmGuideAgent(llm, S_NOFAST).next(GuideRequest.model_validate(make_req(snaps, "s03_options", "네", h, goal="커피")))
    user = llm.calls[0]["user"]
    assert "누르기 안내: 아메리카노" in user and "되물음: 따뜻한 걸로 드릴까요?" in user and "목표(어르신 말): 커피, 네" in user
