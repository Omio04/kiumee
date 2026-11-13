"""새 화면에서 "잠시만 기다려 주세요"로 멈추지 않는다 (2026-11-13 실기기: 눌러서 화면이 바뀌었는데 기다리라는 말만 나왔다)."""
import copy

from app.agent import LlmGuideAgent
from app.llm import ACTION_SCHEMA, ScriptedFakeLlmClient
from app.models import GuideRequest
from app.prompt import SYSTEM
from app.settings import Settings
from .conftest import make_req


def agent(llm):
    return LlmGuideAgent(llm, Settings(app_key="k", fastpath=False, cache_size=0, hedge_after_s=0))


def test_the_model_can_no_longer_choose_wait():
    assert "WAIT" not in ACTION_SCHEMA["properties"]["action"]["enum"]
    assert "GUIDE|ASK_USER|DONE|UNKNOWN|SCROLL|EXPLAIN" in SYSTEM and "|WAIT" not in SYSTEM
    assert "WAIT 또는" not in SYSTEM                      # [기대와 다름] 규칙이 기다리라고 하지 않는다


async def test_a_wait_answer_becomes_a_description_of_the_new_screen(snaps):
    hist = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]
    body = make_req(snaps, "s03_options", None, goal="아메리카노", history=hist)
    body["snapshot"]["screenId"] = "s03_options#1"
    llm = ScriptedFakeLlmClient(['{"action":"WAIT","message":"화면이 바뀌는 중이에요. 잠시만 기다려 주세요."}'])
    r = await agent(llm).next(GuideRequest.model_validate(body))
    a = r.response.action
    assert a.type.value == "ASK_USER" and a.question.startswith("옵션을 고르는 화면이에요. ")
    assert "기다려" not in a.question and len(a.question) <= 80


async def test_a_wait_answer_with_nothing_to_describe_asks_to_show_the_screen_again(snaps):
    body = make_req(snaps, "s03_options", "아무거나", goal="아메리카노")
    body["snapshot"]["elements"] = []                         # 읽힌 글자가 하나도 없는 화면
    llm = ScriptedFakeLlmClient(['{"action":"WAIT"}'])
    r = await agent(llm).next(GuideRequest.model_validate(body))
    assert r.response.action.type.value == "UNKNOWN" and "기다" not in r.response.action.reason


async def test_talk_that_makes_the_app_sound_like_it_is_doing_something_is_blocked(snaps):
    """앱은 읽고 안내만 한다. 키오스크는 어르신이 손으로 누른다 — "바뀌는 중이에요" 같은 말은 나가지 않는다."""
    from app.agent import _waiting_talk
    from app.models import GuideAction, GuideResponse
    for bad in ("화면이 바뀌는 중이에요. 잠시만 기다려 주세요.", "지금 처리 중이에요.", "화면이 로딩되고 있어요."):
        assert _waiting_talk(GuideResponse(goal="", action=GuideAction.ask_user(bad)))
    assert not _waiting_talk(GuideResponse(goal="", action=GuideAction.ask_user("옵션을 고르는 화면이에요. HOT, ICE 중에서 고를 수 있어요.")))

    hist = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]
    body = make_req(snaps, "s03_options", None, goal="아메리카노", history=hist)
    body["snapshot"]["screenId"] = "s03_options#1"
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"화면이 바뀌는 중이에요. 잠시만 기다려 주세요."}'])
    r = await agent(llm).next(GuideRequest.model_validate(body))
    q = r.response.action.question
    assert q.startswith("옵션을 고르는 화면이에요. ") and "바뀌는 중" not in q and "기다" not in q


def test_system_prompt_states_the_apps_role():
    assert "키오스크는 어르신이 직접 손으로 누릅니다" in SYSTEM and "바뀌는 중이에요" in SYSTEM   # 금지 예시로 들어 있다
