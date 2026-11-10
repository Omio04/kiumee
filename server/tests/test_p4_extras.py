"""P4 추가 기능 테스트: 총 처리 예산 · 응답 캐시 · SCROLL/WAIT · 구조화 출력(도구 호출 · responseSchema)."""
import json

import httpx
import pytest

from app.agent import LlmGuideAgent
from app.cache import ResponseCache
from app.llm import ACTION_SCHEMA, AnthropicLlmClient, GeminiLlmClient, LlmTimeout, ScriptedFakeLlmClient
from app.models import GuideRequest, LlmAction
from app.settings import Settings
from .conftest import make_req

GOOD = '{"action":"GUIDE","targetId":"%s","guideText":"x"}'


def S(**kw):
    return Settings(app_key="k", fastpath=False, **kw)


def first_button(snaps, screen):
    return next(e for e in snaps[screen]["elements"] if e["kind"] == "BUTTON_LIKE")


async def test_retry_skipped_when_budget_is_spent(snaps):
    """첫 응답이 형식 오류이고 예산이 거의 없으면 재요청하지 않고 UNKNOWN (앱 6초 타임아웃 보호)."""
    llm = ScriptedFakeLlmClient(["not json", "not json"])
    ag = LlmGuideAgent(llm, S(total_budget_s=0.0))
    r = await ag.next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "아무거나")))
    assert len(llm.calls) == 1
    assert r.response.action.type.value == "UNKNOWN"


async def test_attempt_timeout_never_exceeds_remaining_budget(snaps):
    llm = ScriptedFakeLlmClient(["bad", "bad"])
    ag = LlmGuideAgent(llm, S(total_budget_s=3.0, llm_timeout_s=4.0, min_retry_s=0.1))
    await ag.next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "아무거나")))
    assert all(c["timeout_s"] <= 3.0 for c in llm.calls)


async def test_retry_still_happens_with_budget(snaps):
    b = first_button(snaps, "s02_menu")
    llm = ScriptedFakeLlmClient(["bad", GOOD % b["id"]])
    ag = LlmGuideAgent(llm, S())
    r = await ag.next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "아무거나")))
    assert len(llm.calls) == 2 and r.response.action.type.value == "TAP"


async def test_same_request_hits_cache_without_llm(snaps):
    b = first_button(snaps, "s02_menu")
    llm = ScriptedFakeLlmClient([GOOD % b["id"]])
    ag = LlmGuideAgent(llm, S())
    body = make_req(snaps, "s02_menu", "아무거나")
    r1 = await ag.next(GuideRequest.model_validate(body))
    body2 = dict(body, sessionId="other", snapshot=dict(body["snapshot"], frameId=999, timestampMs=5))
    r2 = await ag.next(GuideRequest.model_validate(body2))
    assert len(llm.calls) == 1
    assert r2.meta["source"] == "cache" and r2.response.action == r1.response.action


async def test_cache_not_used_when_goal_or_screen_differs(snaps):
    b = first_button(snaps, "s02_menu")
    llm = ScriptedFakeLlmClient([GOOD % b["id"]])
    ag = LlmGuideAgent(llm, S())
    await ag.next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "아무거나")))
    await ag.next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "다른 말")))
    assert len(llm.calls) == 2


async def test_failures_are_not_cached(snaps):
    llm = ScriptedFakeLlmClient([LlmTimeout("t"), GOOD % first_button(snaps, "s02_menu")["id"]])
    ag = LlmGuideAgent(llm, S())
    req = GuideRequest.model_validate(make_req(snaps, "s02_menu", "아무거나"))
    with pytest.raises(LlmTimeout):
        await ag.next(req)
    r = await ag.next(req)
    assert r.response.action.type.value == "TAP"


def test_cache_ttl_and_size():
    t = [0.0]
    c = ResponseCache(max_items=2, ttl_s=10, clock=lambda: t[0])
    from app.models import GuideAction, GuideResponse
    resp = GuideResponse(goal="g", action=GuideAction.done("끝"))
    c.put("a", resp); c.put("b", resp); c.put("c", resp)
    assert c.get("a") is None and c.get("b") is not None       # 가장 오래된 것부터 밀려남
    t[0] = 11
    assert c.get("b") is None                                  # TTL 지남
    assert not ResponseCache(0, 10).enabled


async def test_scroll_and_wait_reach_the_app_contract(snaps):
    ag = LlmGuideAgent(ScriptedFakeLlmClient(['{"action":"SCROLL","direction":"down","message":"아래로 밀어 보세요."}']), S())
    r = await ag.next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "아무거나")))
    a = r.response.action
    assert a.type.value == "SCROLL" and a.direction.value == "DOWN" and a.guideText == "아래로 밀어 보세요."
    ag = LlmGuideAgent(ScriptedFakeLlmClient(['{"action":"WAIT"}']), S())
    r = await ag.next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "아무거나")))
    assert r.response.action.type.value == "WAIT" and r.response.action.guideText


async def test_scroll_with_bad_direction_defaults_to_down(snaps):
    ag = LlmGuideAgent(ScriptedFakeLlmClient(['{"action":"SCROLL","direction":"sideways"}']), S())
    r = await ag.next(GuideRequest.model_validate(make_req(snaps, "s02_menu", "아무거나")))
    assert r.response.action.direction.value == "DOWN"


def test_llm_action_accepts_direction():
    assert LlmAction.model_validate({"action": "SCROLL", "direction": "UP"}).direction == "UP"


async def test_anthropic_forces_tool_use_and_unwraps_it():
    seen = {}

    def h(req):
        seen["b"] = json.loads(req.content)
        return httpx.Response(200, json={"content": [{"type": "tool_use", "name": "decide", "id": "t",
                                                      "input": {"action": "GUIDE", "targetId": "e1", "guideText": "포장하기"}}]})
    c = AnthropicLlmClient("sk", "m", http=httpx.AsyncClient(transport=httpx.MockTransport(h)))
    out = await c.complete("s", "u", 4)
    assert json.loads(out)["targetId"] == "e1"
    assert seen["b"]["tool_choice"] == {"type": "tool", "name": "decide"}
    assert seen["b"]["tools"][0]["input_schema"] == ACTION_SCHEMA


async def test_gemini_sends_response_schema_with_upper_case_types():
    seen = {}

    def h(req):
        seen["b"] = json.loads(req.content)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": '{"action":"DONE"}'}]}}]})
    c = GeminiLlmClient("g", "m", http=httpx.AsyncClient(transport=httpx.MockTransport(h)))
    assert await c.complete("s", "u", 4) == '{"action":"DONE"}'
    schema = seen["b"]["generationConfig"]["responseSchema"]
    assert schema["type"] == "OBJECT" and schema["properties"]["action"]["type"] == "STRING"
    assert "GUIDE" in schema["properties"]["action"]["enum"] and schema["required"] == ["action"]


async def test_api_returns_cached_answer_on_client_retry(snaps):
    """앱의 재시도처럼 같은 요청이 두 번 오면 LLM은 한 번만 불린다 (HTTP 통과)."""
    from app.main import create_app
    b = first_button(snaps, "s02_menu")
    llm = ScriptedFakeLlmClient([GOOD % b["id"]])
    s = S()
    app = create_app(s, LlmGuideAgent(llm, s))
    body = make_req(snaps, "s02_menu", "아무거나")
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
        r1 = await c.post("/v1/guide/next", json=body, headers={"X-App-Key": "k"})
        r2 = await c.post("/v1/guide/next", json=body, headers={"X-App-Key": "k"})
    assert r1.status_code == r2.status_code == 200 and r1.json()["action"] == r2.json()["action"]
    assert len(llm.calls) == 1
