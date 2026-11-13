"""메뉴 설명 뒤 "그걸로 할게요"가 무엇인지 잊지 않는다 (2026-11-13 시뮬레이션: 라떼 설명 뒤 엉뚱한 바닐라라떼)."""
import pytest

from app import elder
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest, HistoryItem
from app.settings import Settings
from .conftest import make_req

EXPLAIN = "카페라떼는 에스프레소에 따뜻한 우유를 듬뿍 넣은 부드러운 커피예요. 이걸로 하실래요?"


def hist(text=EXPLAIN, action="ASK_USER"):
    return [HistoryItem(screenId="s1", action=action, targetText=text)]


@pytest.mark.parametrize("said", ["그럼 그걸로 할게요", "그걸로 주세요", "네", "좋아요", "그래요", "응 그걸로 해줘요", "이걸로요"])
def test_agreement_after_an_explanation_means_the_explained_item(said):
    assert elder.resolve_referent(said, hist()) == "카페라떼 주세요"


@pytest.mark.parametrize("said", ["아니요", "싫어요", "다른 걸로 할게요", "아메리카노 말고 다른 거", "바닐라라떼로 할게요", "아메리카노 주세요", "뭐예요"])
def test_refusal_or_a_different_item_is_left_alone(said):
    assert elder.resolve_referent(said, hist()) is None


def test_only_right_after_a_known_menu_explanation():
    assert elder.resolve_referent("네", hist("매장에서 드실래요, 포장하실래요?")) is None          # 다른 질문의 "네"는 건드리지 않는다
    assert elder.resolve_referent("네", hist(EXPLAIN, action="TAP")) is None
    assert elder.resolve_referent("네", []) is None
    assert elder.resolve_referent(None, hist()) is None
    assert elder.resolve_referent("네", hist("모르는메뉴는 맛있는 음료예요. 이걸로 하실래요?")) is None   # 사전에 없는 설명(LLM이 한 것)은 제외


def test_option_explanations_work_too():
    text = "핫은 따뜻하게 마시는 거예요. 이걸로 하실래요?"
    assert elder.resolve_referent("그걸로 할게요", hist(text)) == "핫 주세요"


async def test_the_goal_becomes_the_explained_item(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"매장에서 드실래요, 포장하실래요?"}'])
    ag = LlmGuideAgent(llm, Settings(app_key="k", fastpath=True, cache_size=0, hedge_after_s=0))
    body = make_req(snaps, "s01_dine", "그럼 그걸로 할게요", goal=None, history=[h.model_dump(mode="json") for h in hist()])
    r = await ag.next(GuideRequest.model_validate(body))
    assert r.response.goal == "카페라떼 주세요"                       # "그걸로"가 아니라 이름이 목표에 남는다
