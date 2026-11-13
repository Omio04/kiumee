"""음식 설명 질문 · 새 화면 안내 (2026-11-13)."""
import httpx
import pytest

from app import menu_info, screen_guide
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.settings import Settings
from .conftest import make_req


def S(**kw):
    return Settings(**{"app_key": "k", "fastpath": True, **kw})


def run(agent, snaps, screen, utt, **kw):
    return agent.next(GuideRequest.model_validate(make_req(snaps, screen, utt, **kw)))


# ---- 질문 판별 ----
@pytest.mark.parametrize("q", ["카페라떼가 뭐야", "이거 뭐예요", "바닐라라떼는 무슨 맛이에요", "이건 어떤 음식이야",
                               "라떼랑 아메리카노 차이가 뭐예요", "샷 추가가 뭔가요", "콜드브루 설명해줘", "이거 달아요"])
def test_info_questions_detected(q):
    assert menu_info.is_info_question(q)


@pytest.mark.parametrize("q", ["아메리카노 한 잔 주세요", "따뜻한 걸로요", "샷 하나 더요", "네", "포장해 주세요", "아메리카노 말고 라떼"])
def test_orders_are_not_questions(q):
    assert not menu_info.is_info_question(q) and not menu_info.is_screen_question(q)


@pytest.mark.parametrize("q", ["뭘 눌러야 돼요", "뭐 할 수 있어요", "다음엔 어떻게 해요", "어떻게 주문해요"])
def test_screen_questions_detected(q):
    assert menu_info.is_screen_question(q) and not menu_info.is_info_question(q)


def test_all_menu_descriptions_fit_the_contract():
    for k, v in menu_info._MENU.items():
        assert len(menu_info._named((k, v)) + menu_info.FOLLOW_UP) <= menu_info.MAX_EXPLAIN, k


def test_explanation_starts_with_the_menu_name_so_that_it_can_be_chosen_later():
    assert menu_info.explain("카페라떼가 뭐야", []).startswith("카페라떼는 ")
    assert menu_info.explain("아메리카노가 뭐예요", []).startswith("아메리카노는 ")
    assert menu_info.explain("샷 추가가 뭔가요", []).startswith("샷 추가는 ") or menu_info.explain("샷 추가가 뭔가요", []).startswith("샷추가는 ")
    assert menu_info.explain("바닐라라떼가 뭐야", []).startswith("바닐라라떼는 ")


def test_longest_name_wins():
    assert "바닐라" in menu_info.explain("바닐라라떼가 뭐야", [])
    assert "깔끔" in menu_info.explain("아메리카노는 뭐야", [])


# ---- 에이전트 ----
async def test_known_menu_question_is_answered_locally_and_goal_is_untouched(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    r = await run(LlmGuideAgent(llm, S()), snaps, "s02_menu", "카페라떼가 뭐야", goal="아메리카노 주세요")
    a = r.response.action
    assert a.type.value == "ASK_USER" and "우유" in a.question and len(a.question) <= 80
    assert r.response.goal == "아메리카노 주세요"            # 질문이 목표에 섞이지 않는다
    assert len(llm.calls) == 0 and r.meta["source"] == "rule"


async def test_question_never_taps_even_when_menu_name_matches_exactly(snaps):
    r = await run(LlmGuideAgent(ScriptedFakeLlmClient(['{"action":"UNKNOWN"}']), S()), snaps, "s02_menu", "아메리카노가 뭐예요")
    assert r.response.action.type.value == "ASK_USER" and r.response.goal == ""


async def test_pointing_question_uses_last_tapped_button(snaps):
    hist = [{"screenId": "s02_menu", "action": "TAP", "targetText": "카페라떼"}]
    r = await run(LlmGuideAgent(ScriptedFakeLlmClient(['{"action":"UNKNOWN"}']), S()), snaps, "s03_options", "이거 뭐야", history=hist,
                  goal="라떼")
    assert "우유" in r.response.action.question


async def test_unknown_menu_question_goes_to_llm_as_explain(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"EXPLAIN","message":"달콤한 음료예요. 이걸로 하실래요?"}'])
    r = await run(LlmGuideAgent(llm, S()), snaps, "s02_menu", "모르는메뉴가 뭐야", goal="주문")
    assert "[질문]" in llm.calls[0]["user"]
    assert r.response.action.type.value == "ASK_USER" and r.response.goal == "주문"


async def test_screen_question_lists_choices_without_llm(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    r = await run(LlmGuideAgent(llm, S()), snaps, "s03_options", "뭘 눌러야 돼요", goal="아메리카노")
    q = r.response.action.question
    assert r.response.action.type.value == "ASK_USER" and "고를 수 있어요" in q and len(q) <= 80
    assert len(llm.calls) == 0 and r.response.goal == "아메리카노"


async def test_unknown_on_changed_screen_becomes_a_screen_description(snaps):
    hist = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN","message":"버튼을 찾지 못했어요."}'])
    r = await run(LlmGuideAgent(llm, S(fastpath=False)), snaps, "s03_options", None, history=hist, goal="아메리카노")
    q = r.response.action.question
    assert r.response.action.type.value == "ASK_USER" and q.startswith("옵션을 고르는 화면이에요") and "버튼을 찾지" not in q
    assert "[새 화면]" in llm.calls[0]["user"]


def test_describe_prefers_choices_over_flow_buttons_and_fits(snaps):
    from app.models import ScreenSnapshot
    snap = ScreenSnapshot.model_validate(snaps["s03_options"])
    text = screen_guide.describe(snap.elements, changed=True)
    assert len(text) <= 80 and "취소" not in text.split("중에서")[0]
    assert screen_guide.describe([], changed=True) is None


def test_explain_kind_is_in_the_forced_schema():
    from app.llm import ACTION_SCHEMA
    assert "EXPLAIN" in ACTION_SCHEMA["properties"]["action"]["enum"]
