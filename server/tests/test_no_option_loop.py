"""방금 누른 옵션을 같은 말로 또 안내하는 무한 반복 방지 (2026-11-13 가상 노인 시뮬레이션에서 발견)."""
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.settings import Settings
from .conftest import make_req


def agent(llm):
    return LlmGuideAgent(llm, Settings(app_key="k", fastpath=True, cache_size=0, hedge_after_s=0))


def button_id(snaps, screen, text):
    return next(e["id"] for e in snaps[screen]["elements"] if e["text"] == text)


async def test_pressed_option_is_not_announced_again_by_the_fast_path(snaps):
    """HOT을 눌러 선택이 바뀐 새 화면(번호만 다름): 같은 목표("따뜻한 걸로요")로 빠른 길이 또 HOT을 고르면 안 된다."""
    body = make_req(snaps, "s03_options", None, goal="아메리카노, 따뜻한 걸로요",
                    history=[{"screenId": "s03_options#1", "action": "TAP", "targetText": "HOT"}])
    body["snapshot"]["screenId"] = "s03_options#2"
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"샷이나 시럽을 추가하실래요?"}'])
    r = await agent(llm).next(GuideRequest.model_validate(body))
    assert len(llm.calls) == 1                                  # 빠른 길이 아니라 LLM이 다음 단계를 정했다
    assert r.response.action.type.value == "ASK_USER"
    assert "[방금 안내한 버튼: HOT" in llm.calls[0]["user"].replace("방금 안내한 버튼: ", "[방금 안내한 버튼: ", 1)


async def test_fast_path_still_works_for_the_first_time(snaps):
    body = make_req(snaps, "s03_options", "따뜻한 걸로요", goal="아메리카노")
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    r = await agent(llm).next(GuideRequest.model_validate(body))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText == "HOT"
    assert len(llm.calls) == 0


async def test_a_button_already_guided_in_this_session_is_not_fast_pathed_again(snaps):
    """샷 추가를 묻는 질문에 "아니요 괜찮아요"라고 해도 목표에 남은 "따뜻한 걸로"가 HOT을 또 고르게 하면 안 된다."""
    body = make_req(snaps, "s03_options", "아니요 괜찮아요", goal="아메리카노, 따뜻한 걸로요",
                    history=[{"screenId": "s03_options#1", "action": "TAP", "targetText": "HOT"},
                             {"screenId": "s03_options#2", "action": "ASK_USER", "targetText": "샷이나 시럽을 추가하실래요?"}])
    body["snapshot"]["screenId"] = "s03_options#2"
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"%s","guideText":"담기"}' % button_id(snaps, "s03_options", "담기")])
    r = await agent(llm).next(GuideRequest.model_validate(body))
    assert len(llm.calls) == 1 and r.response.action.guideText == "담기"
