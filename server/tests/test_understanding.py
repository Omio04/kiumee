"""화면 이해: 종류 확률 · 키오스크 추정 · 화면 변화 기억. 어떤 키오스크든 동작해야 한다 (브랜드는 참고일 뿐)."""
import copy

import pytest

from app import kiosk, screen_guide, screen_type
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest, ScreenSnapshot
from app.screen_memory import ScreenMemory
from app.screen_type import ScreenType as T
from app.settings import Settings
from .conftest import make_req


def els(snaps, name):
    return ScreenSnapshot.model_validate(snaps[name]).elements


def snapshot(*items):
    return ScreenSnapshot.model_validate({"screenId": "x", "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": [
        {"id": f"e{i}", "text": t, "kind": k, "rect": {"left": .1, "top": .04 * i + .01, "right": .5, "bottom": .04 * i + .035}}
        for i, (t, k) in enumerate(items, 1)]}).elements


# ---- 확률
def test_probabilities_sum_to_one_and_top_is_the_rule_choice(snaps):
    for name in snaps:
        p = screen_type.probabilities(els(snaps, name))
        assert abs(sum(x for _, x in p) - 1) < 0.05
        assert p[0][0] == screen_type.classify(els(snaps, name)) or p[0][0] == T.UNKNOWN


def test_clear_screens_are_confident_and_weak_ones_leave_room_for_unknown(snaps):
    assert screen_type.probabilities(els(snaps, "s03_options"))[0][1] >= 0.8
    weak = screen_type.probabilities(snapshot(("옵션", "TEXT")))
    assert weak[0][1] < 0.8                                   # 단서가 하나뿐이면 확신이 낮다


def test_ambiguous_screen_shows_two_candidates(snaps):
    line = screen_type.describe_probabilities(screen_type.probabilities(els(snaps, "s02_menu")))
    assert "메뉴를 고르는 화면" in line and "%" in line


def test_nothing_to_say_when_nothing_matches():
    assert screen_type.describe_probabilities(screen_type.probabilities(snapshot(("안녕하세요", "TEXT")))) is None


# ---- 키오스크 추정
def test_brand_is_found_even_with_ocr_noise():
    k = kiosk.identify(snapshot(("메가엠지씨커피", "TEXT"), ("신메뉴", "BUTTON_LIKE")))
    assert k.name == "메가커피" and k.category == "카페" and k.confidence >= 0.8
    noisy = kiosk.identify(snapshot(("메가엠지씨커피", "TEXT")))
    assert noisy.name == "메가커피"
    typo = kiosk.identify(snapshot(("메가앰지시커피", "TEXT")))
    assert typo.name == "메가커피"                              # 글자 인식이 틀려도


def test_unknown_brand_still_gets_a_category_and_nothing_is_required():
    k = kiosk.identify(snapshot(("커피", "BUTTON_LIKE"), ("라떼", "BUTTON_LIKE"), ("디저트", "BUTTON_LIKE")))
    assert k.name is None and k.category == "카페" and k.text() == "카페 키오스크"
    assert kiosk.identify(snapshot(("안녕하세요", "TEXT"))).text() is None
    assert kiosk.identify([]).text() is None


def test_other_kinds_of_kiosks_are_recognised_by_words():
    assert kiosk.identify(snapshot(("승차권 예매", "TEXT"), ("출발역", "BUTTON_LIKE"), ("도착역", "BUTTON_LIKE"))).category == "표 · 예매"
    assert kiosk.identify(snapshot(("불고기버거", "BUTTON_LIKE"), ("세트", "BUTTON_LIKE"), ("콜라", "BUTTON_LIKE"))).category == "햄버거"


# ---- 화면 기억
class Clock:
    t = 0.0

    def __call__(self):
        return self.t


def test_change_between_screens_is_described_with_what_appeared(snaps):
    mem = ScreenMemory()
    assert mem.observe("s", "s1", T.MENU, els(snaps, "s02_menu")) is None              # 처음: 변화 없음
    assert mem.observe("s", "s1", T.MENU, els(snaps, "s02_menu")) is None              # 같은 화면: 변화 없음
    ch = mem.observe("s", "s2", T.OPTIONS, els(snaps, "s03_options"))
    assert ch.from_type == T.MENU and ch.to_type == T.OPTIONS and ch.type_changed
    assert {"HOT", "ICE"} & set(ch.appeared) and ch.vanished                           # 새로 보이는 것과 사라진 것


def test_sessions_are_separate_and_memory_expires_and_is_bounded(snaps):
    clock = Clock()
    mem = ScreenMemory(ttl_s=10, max_sessions=2, clock=clock)
    mem.observe("a", "1", T.MENU, els(snaps, "s02_menu"))
    assert mem.observe("b", "2", T.OPTIONS, els(snaps, "s03_options")) is None          # 다른 세션은 서로 모른다
    clock.t = 11
    assert mem.observe("a", "3", T.OPTIONS, els(snaps, "s03_options")) is None          # TTL 지남
    mem.observe("c", "4", T.MENU, els(snaps, "s02_menu"))
    mem.observe("d", "5", T.MENU, els(snaps, "s02_menu"))
    assert len(mem) <= 2


def test_memory_keeps_only_short_labels_never_prices_or_long_text(snaps):
    mem = ScreenMemory()
    long = "아주 긴 문장입니다 이것은 기억하지 않습니다 그리고 숫자 123456 도 있습니다"
    mem.observe("s", "1", T.UNKNOWN, snapshot((long, "TEXT"), ("4,500원", "PRICE"), ("확인", "BUTTON_LIKE")))
    kept = mem._items["s"].labels
    assert kept == ["확인"]


def test_describe_leads_with_how_it_changed(snaps):
    mem = ScreenMemory()
    mem.observe("s", "1", T.MENU, els(snaps, "s02_menu"))
    ch = mem.observe("s", "2", T.OPTIONS, els(snaps, "s03_options"))
    text = screen_guide.describe(els(snaps, "s03_options"), True, screen_type.label(T.OPTIONS), change=ch)
    assert text.startswith("옵션을 고르는 화면으로 바뀌었어요. ") and len(text) <= 80
    menu = screen_guide.describe(els(snaps, "s02_menu"), True, screen_type.label(T.MENU), change=ch)
    assert menu.startswith("메뉴를 고르는 화면으로 바뀌었어요.") and len(menu) <= 80


# ---- 에이전트: 어떤 키오스크든
def agent(llm):
    return LlmGuideAgent(llm, Settings(app_key="k", fastpath=False, cache_size=0, hedge_after_s=0))


async def test_prompt_carries_probability_kiosk_and_change_lines(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"따뜻한 걸로 드릴까요?"}'])
    ag = agent(llm)
    first = make_req(snaps, "s02_menu", "아메리카노 주세요", session="sess-1")
    first["snapshot"]["screenId"] = "m1"
    await ag.next(GuideRequest.model_validate(first))
    second = make_req(snaps, "s03_options", None, goal="아메리카노", session="sess-1",
                      history=[{"screenId": "m1", "action": "TAP", "targetText": "아메리카노"}])
    second["snapshot"]["screenId"] = "m2"
    await ag.next(GuideRequest.model_validate(second))
    user = llm.calls[-1]["user"]
    assert "[화면 종류] 옵션을 고르는 화면" in user and "%" in user
    assert "[화면 변화] 메뉴를 고르는 화면 → 옵션을 고르는 화면" in user and "새로 보임" in user


async def test_the_brand_never_changes_the_decision(snaps):
    """같은 화면에 가게 이름이 있든 없든 같은 판단: 브랜드는 참고일 뿐이다."""
    async def run(with_brand):
        llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
        body = make_req(snaps, "s07_loyalty", None, goal="아메리카노", session=f"b{with_brand}")
        if with_brand:
            body["snapshot"]["elements"].append({"id": "eb", "text": "메가엠지씨커피", "kind": "TEXT",
                                                 "rect": {"left": .1, "top": .02, "right": .5, "bottom": .06}})
        r = await agent(llm).next(GuideRequest.model_validate(body))
        return r.response.action.type.value, r.response.action.guideText
    assert await run(False) == await run(True) == ("TAP", "×")


async def test_a_kiosk_that_is_not_an_order_kiosk_still_gets_an_answer(snaps):
    """표 예매 같은 다른 종류: 모르는 화면이어도 LLM이 판단하고 서버는 막지 않는다."""
    body = make_req(snaps, "s01_dine", "서울에서 부산 가는 표요", goal=None)
    body["snapshot"]["elements"] = [
        {"id": "a", "text": "출발역을 선택해 주세요", "kind": "TEXT", "rect": {"left": .1, "top": .05, "right": .9, "bottom": .1}},
        {"id": "b", "text": "서울", "kind": "BUTTON_LIKE", "rect": {"left": .1, "top": .2, "right": .4, "bottom": .3}},
        {"id": "c", "text": "부산", "kind": "BUTTON_LIKE", "rect": {"left": .5, "top": .2, "right": .9, "bottom": .3}}]
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"b","guideText":"서울"}'])
    r = await agent(llm).next(GuideRequest.model_validate(body))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText == "서울"
    assert "[키오스크 추정(참고)] 표 · 예매 키오스크" in llm.calls[0]["user"] or "[키오스크 추정" not in llm.calls[0]["user"]
    assert "키오스크 종류는 정해져 있지 않습니다" in llm.calls[0]["system"]
