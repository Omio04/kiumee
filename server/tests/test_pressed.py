"""방금 누른 버튼 (P1, 2026-11-11): '누르기' 안내 뒤 화면이 바뀌었으면 어르신이 이미 누른 것이다.
새 화면에도 같은 글자(담은 목록 · 옵션 창 제목 등)가 있을 때 빠른 길이 또 고르면 "아메리카노를 누르세요"가 되풀이된다."""
from app.agent import LlmGuideAgent, _chosen_items, _pressed_text
from app.llm import HeuristicFakeLlmClient, ScriptedFakeLlmClient
from app.models import GuideRequest
from app.prompt import CHOSEN_PREFIX, PRESSED_PREFIX, SCREEN_PREFIX, build_user
from app.ranker import is_flow_button
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
    assert CHOSEN_PREFIX not in build_user("g", [], [], [])
    assert (CHOSEN_PREFIX + "아메리카노, 카페라떼") in build_user("g", [], [], [], chosen=["아메리카노", "카페라떼"])


# ---- 이미 고른 메뉴 (2026-11-12): 직전 버튼만 보면 주문담기 뒤 메뉴 화면에서 아메리카노를 또 고른다 ----

ADDED = [{"screenId": "s1", "action": "TAP", "targetText": "아메리카노"},     # 앱의 화면 번호는 들를 때마다 새로 붙는다
         {"screenId": "s2", "action": "TAP", "targetText": "담기"}]


async def test_menu_chosen_two_steps_ago_is_not_fastpathed_again(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    r = await LlmGuideAgent(llm, Settings(app_key="k")).next(_req(snaps, ADDED))     # 담기 → 다시 메뉴 화면
    assert r.meta["source"] != "fastpath"
    assert CHOSEN_PREFIX + "아메리카노" in llm.calls[0]["user"]
    assert PRESSED_PREFIX + "담기" in llm.calls[0]["user"]


def test_progress_and_option_buttons_are_not_chosen_menus(snaps):
    for t in ("다음", "확인", "주문담기", "담기", "결제하기", "HOT", "ice", "샷 추가", "사이즈 선택"):
        assert is_flow_button(t), t
    for t in ("아메리카노", "(ICE)아메리카노", "아이스 아메리카노", "핫초코", "카페라떼"):
        assert not is_flow_button(t), t
    hist = [{"screenId": "s1", "action": "TAP", "targetText": "다음"}, *ADDED]
    assert _chosen_items(_req(snaps, hist)) == ["아메리카노"]                 # '다음' · '담기'는 화면마다 또 누를 수 있다


async def test_short_answer_never_takes_the_fastpath(snaps):
    """'샷 추가하실래요?' → '아니요'를 '취소' 버튼으로 누르면 주문이 취소된다. 질문을 아는 LLM이 판단한다."""
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    hist = [{"screenId": "s03_options", "action": "ASK_USER", "targetText": "샷이나 시럽을 추가하실래요?"}]
    req = GuideRequest.model_validate(make_req(snaps, "s03_options", utterance="아니요", goal="아메리카노 주세요", history=hist))
    r = await LlmGuideAgent(llm, Settings(app_key="k")).next(req)
    assert r.meta["source"] != "fastpath" and len(llm.calls) == 1
    assert "샷이나 시럽을 추가하실래요?" in llm.calls[0]["user"]                # 무엇에 대한 대답인지 LLM이 본다


async def test_llm_sees_the_whole_screen_in_reading_order(snaps):
    """바뀐 화면이 원하는 메뉴의 옵션 창인지 · 다른 메뉴인지 · 다른 분류인지 판단하려면 화면 전체 글자가 필요하다
    (예전엔 일반 글자 6개만 보여 줬다, 2026-11-12 시험)."""
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    req = GuideRequest.model_validate(make_req(snaps, "s03_options", goal="아메리카노 주세요", history=TAPPED))
    await LlmGuideAgent(llm, Settings(app_key="k")).next(req)
    line = next(x for x in llm.calls[0]["user"].splitlines() if x.startswith(SCREEN_PREFIX))
    assert line.index("아메리카노 옵션 선택") < line.index("HOT") < line.index("담기")    # 제목부터 위→아래
    assert "정말 그 버튼이 눌렸는지 확인" in llm.calls[0]["user"]                        # 눌렀다고 단정하지 않는다


async def test_llm_gets_candidate_ids_to_restrict_its_answer(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    await LlmGuideAgent(llm, Settings(app_key="k", fastpath=False)).next(_req(snaps, []))
    ids = llm.calls[0]["target_ids"]
    assert ids and all(i in llm.calls[0]["user"] for i in ids)
