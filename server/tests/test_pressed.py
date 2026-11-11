"""방금 누른 버튼 (P1, 2026-11-11): '누르기' 안내 뒤 화면이 바뀌었으면 어르신이 이미 누른 것이다.
새 화면에도 같은 글자(담은 목록 · 옵션 창 제목 등)가 있을 때 빠른 길이 또 고르면 "아메리카노를 누르세요"가 되풀이된다."""
from app.agent import LlmGuideAgent, _pressed_text
from app.llm import HeuristicFakeLlmClient, ScriptedFakeLlmClient
from app.models import GuideRequest
from app.prompt import PRESSED_PREFIX, build_user
from app.settings import Settings
from .conftest import make_req

TAPPED = [{"screenId": "s01_before", "action": "TAP", "targetText": "아메리카노"}]


def _req(snaps, history, screen="s02_menu"):
    return GuideRequest.model_validate(make_req(snaps, screen, utterance=None, goal="아메리카노 주세요", history=history))


def test_pressed_only_when_the_screen_changed_after_a_tap(snaps):
    assert _pressed_text(_req(snaps, TAPPED)) == "아메리카노"
    same = [dict(TAPPED[0], screenId="s02_menu")]                          # 화면이 그대로면 아직 안 누른 것
    assert _pressed_text(_req(snaps, same)) is None
    assert _pressed_text(_req(snaps, [])) is None


async def test_screen_changed_after_tap_does_not_fastpath_the_same_button_again(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    r = await LlmGuideAgent(llm, Settings(app_key="k")).next(_req(snaps, TAPPED))
    assert r.meta["source"] != "fastpath"
    assert PRESSED_PREFIX + "아메리카노" in llm.calls[0]["user"]           # LLM에게 이미 눌렀다고 알린다


async def test_same_screen_still_repeats_the_same_button(snaps):
    same = [dict(TAPPED[0], screenId="s02_menu")]
    r = await LlmGuideAgent(HeuristicFakeLlmClient(), Settings(app_key="k")).next(_req(snaps, same))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText == "아메리카노"


async def test_fake_llm_skips_the_pressed_button(snaps):
    r = await LlmGuideAgent(HeuristicFakeLlmClient(), Settings(app_key="k", fastpath=False)).next(_req(snaps, TAPPED))
    assert r.response.action.guideText != "아메리카노"


def test_decorated_name_of_the_pressed_item_counts_as_the_same_item():
    """옵션 창 제목 "(ICE)아메리카노" · 담은 목록처럼 꾸밈말이 붙어도 방금 누른 메뉴다 (2026-11-11 시험)."""
    from app.ranker import same_item
    assert same_item("(ICE)아메리카노", "아메리카노")
    assert same_item("아메리카노", "아메리카노")
    assert not same_item("주문담기", "아메리카노")
    assert not same_item("카페라떼", "아메리카노")
    assert not same_item("", "아메리카노")


def test_prompt_mentions_pressed_button_only_when_given():
    assert PRESSED_PREFIX not in build_user("g", [], [], [])
    assert (PRESSED_PREFIX + "아메리카노") in build_user("g", [], [], [], pressed="아메리카노")
