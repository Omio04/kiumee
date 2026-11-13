"""진행 상황 요약 (플래너) — 이력에서 단계를 뽑아 '지금까지 / 다음 단계'를 만든다."""
import pytest

from app import progress
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest, HistoryItem
from app.prompt import build_user
from app.settings import Settings
from .conftest import make_req


def H(*items):
    return [HistoryItem(screenId=f"s{i}", action=a, targetText=t) for i, (a, t) in enumerate(items)]


TAP, ASK = "TAP", "ASK_USER"


@pytest.mark.parametrize("text,stage", [
    ("매장에서 먹고가기", progress.DINE), ("포장하기", progress.DINE), ("아메리카노", progress.MENU), ("카페라떼", progress.MENU),
    ("HOT", progress.OPTION), ("ICE", progress.OPTION), ("샷 추가", progress.OPTION), ("시럽 추가", progress.OPTION),
    ("담기", progress.ADD), ("결제하기", progress.CHECKOUT), ("신용카드", progress.PAY), ("카카오페이", progress.PAY),
    ("이전", None), ("취소", None), ("처음으로", None), ("메뉴 더 담기", None),
])
def test_stage_of_buttons(text, stage):
    assert progress.stage_of(text) == stage


def test_summary_lists_done_steps_and_what_comes_next():
    s = progress.summarize(H((TAP, "매장에서 먹고가기"), (TAP, "아메리카노"), (TAP, "HOT")))
    assert "주문 방법(매장에서 먹고가기)" in s and "메뉴(아메리카노)" in s and "옵션(HOT)" in s
    assert s.split("다음 단계:")[1].strip().startswith("옵션(더 필요하면) → 담기 → 결제하기 → 결제 수단")


def test_summary_after_payment_says_finished():
    s = progress.summarize(H((TAP, "결제하기"), (TAP, "신용카드")))
    assert "끝" in s.split("다음 단계:")[1]


def test_questions_and_cancel_are_not_steps_and_empty_history_is_none():
    assert progress.summarize([]) is None
    assert progress.summarize(H((ASK, "어떤 걸로 할까요?"))) is None
    assert progress.summarize(H((TAP, "취소"))) is None


def test_prompt_carries_the_progress_line_only_when_given():
    assert "[진행]" not in build_user("g", [], [], [])
    assert "[진행] 지금까지: 메뉴(아메리카노)" in build_user("g", [], [], [], progress="지금까지: 메뉴(아메리카노) / 다음 단계: 옵션")


async def test_agent_sends_progress_to_the_llm_and_can_turn_it_off(snaps):
    hist = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]

    async def run(**kw):
        llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"따뜻한 걸로 드릴까요?"}'])
        ag = LlmGuideAgent(llm, Settings(**{"app_key": "k", "fastpath": False, "cache_size": 0, **kw}))
        await ag.next(GuideRequest.model_validate(make_req(snaps, "s03_options", "아메리카노 주세요", history=hist)))
        return llm.calls[0]["user"]
    assert "[진행] 지금까지: 메뉴(아메리카노)" in await run()
    assert "[진행]" not in await run(progress_hint=False)
