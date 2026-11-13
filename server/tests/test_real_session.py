"""2026-11-13 실기기 사용 기록에서 찾은 결함 (깨진 글자 · 같은 글자의 버튼 여러 개 · 비슷한 다른 메뉴)."""
from app import expectation
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.settings import Settings


def el(i, text, kind="BUTTON_LIKE", top=None):
    t = top if top is not None else .05 * i + .01
    return {"id": f"e{i}", "text": text, "kind": kind, "rect": {"left": .1, "top": t, "right": .6, "bottom": t + .04}}


def req(elements, utterance=None, goal=None, history=None, sid="s9"):
    return GuideRequest.model_validate({"sessionId": "real", "utterance": utterance, "goal": goal, "history": history or [],
        "snapshot": {"screenId": sid, "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": elements}})


def agent(llm, **kw):
    return LlmGuideAgent(llm, Settings(**{"app_key": "k", "fastpath": True, "cache_size": 0, "hedge_after_s": 0, **kw}))


# ---- 같은 글자의 버튼이 여럿이면 빠른 길을 쓰지 않는다
async def test_duplicate_labels_are_not_fast_pathed():
    els = [el(1, "샷"), el(2, "선택"), el(3, "연하게"), el(4, "선택"), el(5, "초기화")]
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"어느 쪽 선택을 누르실까요?"}'])
    r = await agent(llm).next(req(els, utterance="선택", goal="선택"))
    assert r.meta.get("source") != "fastpath" and len(llm.calls) == 1


async def test_generic_labels_are_never_fast_pathed_even_when_unique():
    els = [el(1, "추가"), el(2, "담기")]
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"무엇을 추가할까요?"}'])
    r = await agent(llm).next(req(els, utterance="추가", goal="추가"))
    assert r.meta.get("source") != "fastpath"


async def test_unique_specific_labels_still_use_the_fast_path():
    els = [el(1, "아메리카노"), el(2, "4,500원", "PRICE"), el(3, "카페라떼"), el(4, "5,000원", "PRICE")]      # 가격이 있는 메뉴 화면 (빠른 길은 메뉴처럼 보이는 화면에서만)
    r = await agent(ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])).next(req(els, utterance="아메리카노 주세요", goal=None))
    assert r.meta["source"] == "fastpath" and r.response.action.guideText == "아메리카노"


# ---- 비슷하지만 다른 메뉴
PRESSED = [{"screenId": "m1", "action": "TAP", "targetText": "아메리카노"}]


def test_a_longer_menu_that_contains_the_name_is_not_the_same_menu():
    els = [el(1, "선택하신 상품의 옵션상품을 모두 선택해주세요", "TEXT"), el(2, "(ICE)헤이즐넛아메리카노", "TEXT"), el(3, "담기")]
    assert expectation.lookalike_menu(req(els, history=PRESSED)) == ("아메리카노", "(ICE)헤이즐넛아메리카노")


def test_the_exact_menu_with_an_ice_hot_marker_is_the_same_menu():
    for title in ("(ICE)아메리카노", "(HOT)아메리카노", "(CE)아메리카노", "아메리카노"):
        assert expectation.lookalike_menu(req([el(1, title, "TEXT")], history=PRESSED)) is None, title


def test_exact_menu_wins_when_both_are_visible_and_other_cases_are_ignored():
    both = [el(1, "(ICE)아메리카노", "TEXT"), el(2, "(ICE)헤이즐넛아메리카노", "TEXT")]
    assert expectation.lookalike_menu(req(both, history=PRESSED)) is None
    assert expectation.lookalike_menu(req(both[1:], history=PRESSED, sid="m1")) is None            # 같은 화면
    assert expectation.lookalike_menu(req(both[1:], history=[])) is None
    hot = [{"screenId": "m1", "action": "TAP", "targetText": "HOT"}]
    assert expectation.lookalike_menu(req(both[1:], history=hot)) is None                          # 메뉴 단계가 아니다


async def test_iced_americano_guide_closes_the_wrong_menu_without_a_question():
    els = [el(1, "선택하신 상품의 옵션상품을 모두 선택해주세요", "TEXT"), el(2, "(ICE)헤이즐넛아메리카노", "TEXT"), el(3, "닫기"), el(4, "담기")]
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"e3","guideText":"닫기"}'])
    r = await agent(llm, fastpath=False).next(req(els, goal="아이스 아메리카노", history=PRESSED))
    assert not llm.calls
    assert r.response.action.type.value == "TAP" and r.response.action.targetId == "e3"


def test_titles_and_cart_lines_that_merely_contain_the_name_are_not_other_menus():
    for text in ("아메리카노 옵션 선택", "아메리카노 1잔", "아메리카노 2,000원", "선택하신 아메리카노"):
        assert expectation.lookalike_menu(req([el(1, text, "TEXT")], history=PRESSED)) is None, text
