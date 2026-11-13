import json
from pathlib import Path

import pytest
from app.agent import LlmGuideAgent
from app.kiosk_flow import kiosk_action
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.settings import Settings

STEPS = json.loads((Path(__file__).resolve().parents[2] /
                   "vision/testdata/kiosk/handheld-guidance.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("filename", ["handheld-guidance.json", "handheld-holdout-guidance.json"])
async def test_actual_mlkit_snapshots_keep_order_until_number_ticket(filename):
    llm = ScriptedFakeLlmClient([])
    agent = LlmGuideAgent(llm, Settings(app_key="test", help_mode=False))
    steps = json.loads((Path(__file__).resolve().parents[2] / "vision/testdata/kiosk" / filename).read_text(encoding="utf-8"))
    for step in steps:
        result = (await agent.next(GuideRequest.model_validate(step["request"]))).response
        assert result.model_dump(exclude_none=True) == step["response"], step["stage"]
        if step["stage"] != "done":
            assert result.action.type.value != "DONE"
    assert not llm.calls


@pytest.mark.parametrize("message", ["결제가 완료되지 않았습니다", "승인 거절", "결제 실패", "카드를 태그해 주세요"])
async def test_failure_or_card_instruction_never_claims_order_completion(message):
    req = GuideRequest.model_validate(STEPS[-1]["request"])
    title = req.snapshot.elements[0].model_copy(update={"text": message})
    # Keep the visible order number: a number alone does not prove successful payment.
    req = req.model_copy(update={"snapshot":req.snapshot.model_copy(update={"elements":[title,*req.snapshot.elements[1:]]})})
    llm = ScriptedFakeLlmClient([])
    response = (await LlmGuideAgent(llm, Settings(app_key="test")).next(req)).response
    assert response.action.type.value == "WAIT"
    assert not llm.calls


@pytest.mark.parametrize("goal", ["아이스 아메리카노, 포장할게요", "카페라떼 한 잔", "주문 도와주세요"])
async def test_payment_progress_does_not_depend_on_exact_drink_phrase(goal):
    llm = ScriptedFakeLlmClient([])
    agent = LlmGuideAgent(llm, Settings(app_key="test"))
    for step in STEPS:
        if step["stage"] not in {"card", "processing", "receipt", "done"}:
            continue
        request = GuideRequest.model_validate(step["request"]).model_copy(update={"goal":goal,"utterance":None})
        action = (await agent.next(request)).response.action
        assert action.type.value == ("DONE" if step["stage"] == "done" else "WAIT")
    assert not llm.calls


@pytest.mark.parametrize("label,expected", [
    ("(ICE)아메리카노", "TAP"), ("(1CE)아메리카노", "TAP"), ("(CE)아메리카노", "TAP"),
    ("(HOT)아메리카노", "UNKNOWN"), ("(ICE)꿀아메리카노", "UNKNOWN"),
    ("(1CE)바닐라아메리카노", "UNKNOWN"), ("아메리카노", "UNKNOWN"),
    ("(ICE)디카페인아메리카노", "UNKNOWN"),
])
def test_temperature_ocr_tolerance_never_changes_the_drink(label, expected):
    req = GuideRequest.model_validate(STEPS[0]["request"])
    element = req.snapshot.elements[0].model_copy(update={"text":label, "kind":"BUTTON_LIKE"})
    req = req.model_copy(update={"snapshot":req.snapshot.model_copy(update={"elements":[element]})})
    assert kiosk_action(req, "아이스 아메리카노").type.value == expected
