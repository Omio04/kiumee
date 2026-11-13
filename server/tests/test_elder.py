"""어르신 말투 처리 (군말 · 다시 말해줘 · 도움 요청 · 마음 바꿈 · 현금) — LLM 없이 즉시 답한다."""
import pytest

from app import elder
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.settings import Settings
from .conftest import make_req


def agent(llm=None, **kw):
    llm = llm or ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    return LlmGuideAgent(llm, Settings(**{"app_key": "k", "fastpath": True, "cache_size": 0, **kw})), llm


def ask(ag, snaps, screen, utt, **kw):
    return ag.next(GuideRequest.model_validate(make_req(snaps, screen, utt, **kw)))


# ---- 군말 ----
@pytest.mark.parametrize("raw,clean", [
    ("어... 그... 커피 있잖아... 아메리카노 그거요", "커피 아메리카노 그거요"),
    ("음 따뜻한 걸로", "따뜻한 걸로"),
    ("뭐더라 아메리카노 주세요", "아메리카노 주세요"),
    ("어떤 거예요", "어떤 거예요"),          # "어떤"의 "어"는 군말이 아니다
    ("저기요 라떼요", "저기요 라떼요"),       # "저기"는 군말이지만 "저기요"는 부르는 말
])
def test_filler_words_are_removed_without_eating_real_words(raw, clean):
    assert elder.clean_utterance(raw) == clean


def test_only_fillers_means_nothing_was_said():
    assert elder.clean_utterance("음...") is None
    assert elder.clean_utterance("어... 그...") is None
    assert elder.clean_utterance(None) is None


# ---- 판별 ----
@pytest.mark.parametrize("u", ["뭐라고요? 다시 말해줘요", "다시 한번 말해줘", "한 번 더요", "못 들었어요"])
def test_repeat_requests(u):
    assert elder.is_repeat(u)


@pytest.mark.parametrize("u", ["아메리카노 주세요", "따뜻한 걸로요", "아니요 괜찮아요", "카드로 할게요", "네"])
def test_normal_orders_are_never_treated_as_special(u):
    assert not (elder.is_repeat(u) or elder.is_help(u) or elder.is_stop(u))


@pytest.mark.parametrize("u", ["모르겠어요 직원 좀 불러줘요", "도와주세요", "너무 어려워서 못 하겠어요"])
def test_help_requests(u):
    assert elder.is_help(u)


@pytest.mark.parametrize("u", ["그만할래요", "안 할래요", "종료"])
def test_stop_requests(u):
    assert elder.is_stop(u)


def test_correction_replaces_only_what_was_negated():
    assert elder.correction_of("아니 아메리카노 말고 라떼로 할래요", "아메리카노 주세요") == "라떼로 할래요"
    assert elder.correction_of("샷 추가 말고 시럽", "아메리카노 주세요") is None       # 옵션만 바꾸는 말은 주문 목표를 지우지 않는다
    assert elder.correction_of("아메리카노 말고 다른 거", "아메리카노 주세요") is None   # 막연하면 LLM이
    assert elder.correction_of("라떼 말고 아메리카노", None) is None


# ---- 에이전트 ----
async def test_help_ends_the_guidance_and_points_to_staff_without_llm(snaps):
    ag, llm = agent()
    r = await ask(ag, snaps, "s03_options", "모르겠어요 직원 좀 불러줘요", goal="아메리카노")
    assert r.response.action.type.value == "DONE" and "직원" in r.response.action.guideText
    assert len(llm.calls) == 0 and r.response.goal == "아메리카노"


async def test_stop_ends_the_guidance(snaps):
    ag, llm = agent()
    r = await ask(ag, snaps, "s02_menu", "그만할래요")
    assert r.response.action.type.value == "DONE" and len(llm.calls) == 0


async def test_repeat_asks_the_same_question_again_and_keeps_the_goal(snaps):
    hist = [{"screenId": "s01_dine", "action": "ASK_USER", "targetText": "매장에서 드실래요, 포장하실래요?"}]
    ag, llm = agent()
    r = await ask(ag, snaps, "s01_dine", "뭐라고요? 다시 말해줘요", goal="아메리카노", history=hist)
    a = r.response.action
    assert a.type.value == "ASK_USER" and a.question == "매장에서 드실래요, 포장하실래요?"
    assert r.response.goal == "아메리카노" and len(llm.calls) == 0


async def test_repeat_points_at_the_same_button_again(snaps):
    hist = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]
    ag, _ = agent()
    r = await ask(ag, snaps, "s02_menu", "다시 한번 말해줘", goal="아메리카노", history=hist)
    a = r.response.action
    assert a.type.value == "TAP" and a.guideText == "아메리카노" and r.response.goal == "아메리카노"


async def test_repeat_with_no_history_greets(snaps):
    ag, _ = agent()
    r = await ask(ag, snaps, "s01_dine", "뭐라고요")
    assert r.response.action.type.value == "ASK_USER" and r.response.goal == ""


async def test_correction_does_not_tap_the_item_that_was_just_rejected(snaps):
    """"아메리카노 말고 라떼" 인데 목표에 아메리카노가 남아 빠른 길이 아메리카노를 고르던 문제."""
    ag, _ = agent()
    r = await ask(ag, snaps, "s02_menu", "아니 아메리카노 말고 카페라떼로 할래요", goal="아메리카노 주세요")
    a = r.response.action
    assert a.type.value == "TAP" and a.guideText == "카페라떼"
    assert "아메리카노" not in r.response.goal


async def test_filler_only_utterance_is_treated_as_silence(snaps):
    ag, llm = agent(ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"무엇을 드시고 싶으세요?"}']))
    r = await ask(ag, snaps, "s02_menu", "어... 음...")
    assert r.response.action.type.value == "ASK_USER" and r.response.goal == ""


async def test_cash_is_explained_when_the_screen_has_no_cash_button(snaps):
    ag, llm = agent()
    r = await ask(ag, snaps, "s05_payment", "현금으로 할게요", goal="아메리카노")
    a = r.response.action
    assert a.type.value == "ASK_USER" and "현금" in a.question and len(a.question) <= 80
    assert len(llm.calls) == 0 and "현금" not in r.response.goal       # 현금이 목표에 섞이지 않는다
