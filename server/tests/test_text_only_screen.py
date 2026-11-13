"""창이 앞에 뜨면 인식이 모든 글자를 일반 글자(TEXT)로 낮춰 버튼이 0개가 된다 → 서버가 막히지 않아야 한다 (2026-11-13 실기기)."""
import copy

from app import screen_guide
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest, ScreenSnapshot
from app.ranker import candidate_pool
from app.settings import Settings
from .conftest import make_req


def demote(snaps, screen):
    """모든 요소를 BUTTON_LIKE → TEXT로 (가격은 그대로)."""
    s = copy.deepcopy(snaps[screen])
    for e in s["elements"]:
        if e["kind"] == "BUTTON_LIKE":
            e["kind"] = "TEXT"
    return s


def request(snaps, screen, **kw):
    body = make_req(snaps, screen, kw.pop("utterance", None), **kw)
    body["snapshot"] = demote(snaps, screen)
    return GuideRequest.model_validate(body)


def agent(llm):
    return LlmGuideAgent(llm, Settings(app_key="k", fastpath=True, cache_size=0, hedge_after_s=0))


def test_pool_falls_back_to_short_text_only_when_there_are_no_buttons(snaps):
    req = request(snaps, "s03_options")
    texts = {e.text for e in candidate_pool(req.snapshot.elements)}
    assert {"HOT", "ICE", "담기"} <= texts and "아메리카노 옵션 선택" not in texts      # 짧은 글자만, 긴 제목은 제외
    normal = ScreenSnapshot.model_validate(snaps["s03_options"])
    assert all(e.kind.value == "BUTTON_LIKE" for e in candidate_pool(normal.elements))   # 버튼이 있으면 그대로


async def test_agent_still_asks_the_llm_instead_of_giving_up(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"따뜻한 걸로 드릴까요, 차가운 걸로 드릴까요?"}'])
    hist = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]
    r = await agent(llm).next(request(snaps, "s03_options", goal="아메리카노", history=hist))
    assert r.response.action.type.value == "ASK_USER" and "버튼을 찾지" not in r.response.action.question
    assert len(llm.calls) == 1 and r.meta["candidates"] > 0


async def test_llm_can_pick_a_text_label_and_it_is_still_whitelisted(snaps):
    req = request(snaps, "s03_options", goal="아메리카노, 시원한 걸로요")
    ice = next(e for e in req.snapshot.elements if e.text == "ICE")
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"%s","guideText":"ICE"}' % ice.id])
    r = await agent(llm).next(req)
    assert r.response.action.type.value == "TAP" and r.response.action.targetId == ice.id


async def test_llm_cannot_pick_something_outside_the_pool(snaps):
    req = request(snaps, "s03_options", goal="아메리카노")
    title = next(e for e in req.snapshot.elements if "옵션 선택" in e.text)          # 긴 제목은 후보가 아님
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"%s","guideText":"x"}' % title.id])
    r = await agent(llm).next(req)
    assert r.response.action.type.value != "TAP"


def test_screen_description_works_on_text_only_screens(snaps):
    req = request(snaps, "s03_options")
    text = screen_guide.describe(req.snapshot.elements, changed=True)
    assert text and "HOT" in text and len(text) <= 80
