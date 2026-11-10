"""짧은 대답("네" · "다 됐어요" · "아니요")은 이번 발화 전체가 대답일 때만 버튼 글자로 바꾼다 (P4 · P5 통합, P1, 2026-11-10).

P4가 넣은 대답 동의어를 "질문 안에 들어 있으면 붙이기"로 두면 "끝까지 시원한 걸로 하나 더요"에 결제가,
"아니 아이스로 주세요"에 취소가 붙어 엉뚱한 버튼을 고르거나 빠른 길을 놓친다.
"""
import json

import pytest

from app.agent import LlmGuideAgent
from app.llm import HeuristicFakeLlmClient
from app.models import GuideRequest
from app.ranker import answer_button, expand_query
from app.settings import Settings
from .conftest import make_req


@pytest.mark.parametrize("utt,button", [
    ("네", "확인"), ("네.", "확인"), ("네 맞아요", "확인"), ("좋아요", "확인"),
    ("다 됐어요", "결제"), ("끝이에요", "결제"),
    ("아니요", "취소"), ("그만", "취소"),
])
def test_whole_answer_maps_to_button(utt, button):
    assert answer_button(utt) == button


@pytest.mark.parametrize("utt", [
    "아메리카노 네 잔 주세요", "제가 좋아하는 카페라떼 주세요", "그래도 하나 더 주세요",
    "끝까지 시원한 걸로 하나 더요", "아니 아이스로 주세요", "그만 샷 추가해 주세요",
])
def test_answer_words_inside_other_words_add_nothing(utt):
    assert answer_button(utt) is None
    added = expand_query(utt, utt)[len(utt):].split()
    assert not {"확인", "결제", "취소"} & set(added)


def test_screen_change_request_does_not_reuse_previous_answer():
    """화면만 바뀐 요청(새 발화 없음)은 목표 끝이 "네"여도 확인을 붙이지 않는다 (다음 화면의 확인 버튼을 또 누르게 하지 않음)."""
    assert "확인" not in expand_query("아메리카노 주세요, 네", None).split()
    assert "확인" in expand_query("아메리카노 주세요, 네", "네").split()


def _agent():
    return LlmGuideAgent(HeuristicFakeLlmClient(), Settings(app_key="k"))


def _with_confirm_screen(snaps):
    """옵션 화면의 "담기"를 "확인"으로 바꾼 화면 (키오스크 팝업 · 옵션 화면에 흔한 형태)."""
    s = json.loads(json.dumps(snaps["s03_options"], ensure_ascii=False).replace('"담기"', '"확인"'))
    assert any(e["text"] == "확인" for e in s["elements"])
    return dict(snaps, s03_confirm=s)


async def test_cart_does_not_jump_to_payment_on_unrelated_kkeut(snaps):
    r = await _agent().next(GuideRequest.model_validate(make_req(snaps, "s04_cart", "끝까지 시원한 걸로 하나 더요")))
    a = r.response.action
    assert not (a.type.value == "TAP" and a.guideText == "결제하기"), "한 잔 더 달라는 말에 결제를 안내하면 안 된다"


async def test_yes_taps_confirm_but_four_cups_does_not(snaps):
    s = _with_confirm_screen(snaps)
    r = await _agent().next(GuideRequest.model_validate(make_req(s, "s03_confirm", "네")))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText == "확인"
    r = await _agent().next(GuideRequest.model_validate(make_req(s, "s03_confirm", "아이스로 네 잔 주세요")))
    assert r.response.action.guideText == "ICE" and r.meta["source"] == "fastpath"     # "확인"이 끼어들어 빠른 길을 놓치지 않음


async def test_done_answer_on_cart_points_to_payment(snaps):
    r = await _agent().next(GuideRequest.model_validate(make_req(snaps, "s04_cart", "다 됐어요")))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText == "결제하기"
