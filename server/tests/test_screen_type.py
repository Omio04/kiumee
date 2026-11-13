"""화면 종류 판별: 지금 화면이 무엇을 하는 화면인지 글자로 정하고, LLM과 어르신께 알려 준다."""
import copy

import pytest

from app import screen_guide, screen_type
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest, ScreenSnapshot
from app.screen_type import ScreenType as T
from app.settings import Settings
from .conftest import make_req


def snap(snaps, name):
    return ScreenSnapshot.model_validate(snaps[name])


@pytest.mark.parametrize("name,expected", [
    ("s01_dine", T.DINE), ("s02_menu", T.MENU), ("s03_options", T.OPTIONS), ("s04_cart", T.ORDER_REVIEW),
    ("s05_payment", T.PAYMENT), ("s06_card", T.CARD), ("s07_loyalty", T.LOYALTY),
])
def test_fixture_screens_are_recognised(snaps, name, expected):
    assert screen_type.classify(snap(snaps, name).elements) == expected


def element(i, text, kind="TEXT"):
    return {"id": f"e{i}", "text": text, "kind": kind, "rect": {"left": .1, "top": .1 * i % .9 + .01, "right": .5, "bottom": .1 * i % .9 + .08}}


def classify(*items):
    s = ScreenSnapshot.model_validate({"screenId": "x", "frameId": 1, "timestampMs": 0, "stableFrames": 2,
                                        "elements": [element(i, t, k) for i, (t, k) in enumerate(items, 1)]})
    return screen_type.classify(s.elements)


def test_other_kiosk_wordings():
    B, P, X = "BUTTON_LIKE", "PRICE", "TEXT"
    assert classify(("포인트 적립", X), ("휴대폰 번호", X), ("적립 안 함", B)) == T.LOYALTY
    assert classify(("일회용품 필요 여부", X), ("필요 없음", B), ("필요", B)) == T.DISPOSABLE
    assert classify(("옵션 상품을 모두 선택해주세요", X), ("연하게", B), ("선택완료", B)) == T.OPTIONS
    assert classify(("주문 세부 내역", X), ("합계", X), ("결제하기", B)) == T.ORDER_REVIEW
    assert classify(("신용카드", B), ("카카오페이", B), ("네이버페이", B)) == T.PAYMENT
    assert classify(("결제가 완료되었습니다", X), ("영수증을 출력", B)) == T.COMPLETE
    assert classify(("화면을 터치하세요", X)) == T.START
    assert classify(("불고기 버거", B), ("4,500원", P), ("치즈 버거", B), ("5,000원", P)) == T.MENU


def test_unknown_when_nothing_matches():
    assert classify(("안녕하세요", "TEXT"), ("반갑습니다", "TEXT")) == T.UNKNOWN
    assert screen_type.classify([]) == T.UNKNOWN


def test_points_to_use_on_a_payment_or_menu_screen_is_not_a_loyalty_screen():
    P = "PRICE"
    # 가격이 여러 개 보이는 화면의 '적립' 문구(예: 메뉴판 광고 · 결제 화면의 '적립 예정')는 적립 창이 아니다
    assert classify(("아메리카노", "BUTTON_LIKE"), ("4,500원", P), ("라떼", "BUTTON_LIKE"), ("5,000원", P), ("포인트 적립 이벤트", "TEXT")) != T.LOYALTY


def test_skip_button_prefers_close_marks(snaps):
    s = snap(snaps, "s07_loyalty")
    assert screen_type.skip_button(s.elements).text == "×"
    assert screen_type.skip_button(snap(snaps, "s03_options").elements) is None


def test_labels_read_naturally(snaps):
    assert screen_type.label(T.OPTIONS) == "옵션을 고르는 화면"
    assert screen_type.label(T.UNKNOWN) is None
    text = screen_guide.describe(snap(snaps, "s03_options").elements, changed=True, label=screen_type.label(T.OPTIONS))
    assert text.startswith("옵션을 고르는 화면이에요. ") and len(text) <= 80
    assert all(len(screen_guide.describe(snap(snaps, n).elements, True, screen_type.label(screen_type.classify(snap(snaps, n).elements))) or "") <= 80
               for n in snaps)


def agent(llm):
    return LlmGuideAgent(llm, Settings(app_key="k", fastpath=True, cache_size=0, hedge_after_s=0))


async def test_loyalty_screen_is_skipped_by_pointing_at_the_close_mark_without_llm(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    body = make_req(snaps, "s07_loyalty", None, goal="아메리카노")
    r = await agent(llm).next(GuideRequest.model_validate(body))
    a = r.response.action
    assert a.type.value == "TAP" and a.guideText == "×" and r.response.goal == "아메리카노"
    assert len(llm.calls) == 0 and r.meta["screen"] == "LOYALTY"


async def test_loyalty_screen_without_a_visible_close_mark_asks_to_show_the_screen_again(snaps):
    body = make_req(snaps, "s07_loyalty", None, goal="아메리카노")
    body["snapshot"]["elements"] = [e for e in body["snapshot"]["elements"] if e["text"] != "×"] + [
        {"id": "e9", "text": "스탬프 적립", "kind": "TEXT", "rect": {"left": .1, "top": .5, "right": .5, "bottom": .6}},
        {"id": "e8", "text": "포인트 적립", "kind": "TEXT", "rect": {"left": .1, "top": .6, "right": .5, "bottom": .7}}]
    r = await agent(ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])).next(GuideRequest.model_validate(body))
    a = r.response.action
    assert a.type.value == "UNKNOWN" and "닫기" in a.reason and len(a.reason) <= 80      # 보이지 않는 ×를 지어내지 않는다


async def test_person_who_asks_for_points_is_not_skipped(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"휴대폰 번호를 직접 눌러 주세요."}'])
    body = make_req(snaps, "s07_loyalty", "적립할게요", goal="아메리카노")
    r = await agent(llm).next(GuideRequest.model_validate(body))
    assert r.response.action.type.value == "ASK_USER" and len(llm.calls) == 1


async def test_llm_prompt_names_the_screen_and_new_screen_description_leads_with_it(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN","message":"버튼을 찾지 못했어요."}'])
    hist = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]
    body = make_req(snaps, "s03_options", None, goal="모르는것", history=hist)
    body["snapshot"]["screenId"] = "s03_options#1"
    r = await agent(llm).next(GuideRequest.model_validate(body))
    assert "[화면 종류] 옵션을 고르는 화면" in llm.calls[0]["user"]
    assert r.response.action.question.startswith("옵션을 고르는 화면이에요. ")
