"""어르신 몫인 선택을 AI가 대신 고르지 않기 · 동의 버튼 설명 · 설명 문장 전달 (2026-11-14 실기기).

실기기: 목표에 선호가 없는데 "예약번호 입력"을 골라 누르라 하고, "동의합니다"를 설명 없이 누르라고 했다.
원인: 기본 안내 경로는 AI가 쓴 설명(message)을 응답에 싣지 않았고, 어르신이 정하지 않은 선택을 막는 일반 규칙이 없었다.

설계: "어르신이 말했는가"는 낱말 목록으로 추측하지 않고 모델이 응답에 직접 적는 userChoice(named · delegated · not_stated)를 따른다.
서버는 결정적인 부분(선택지가 둘 이상인 화면 · 진행/동의 버튼 제외)만 확인한다.
"""
import json

import pytest

from app.agent import LlmGuideAgent, _consent_note, _is_consent
from app.llm import ACTION_SCHEMA, ScriptedFakeLlmClient, _gemini_schema
from app.models import GuideAction, GuideRequest, GuideResponse, LlmAction
from app.settings import Settings


def S(**kw):
    return Settings(app_key="k", fastpath=False, **kw)


def el(i, text, top, kind="BUTTON_LIKE"):
    return {"id": i, "text": text, "kind": kind, "confidence": 0.95,
            "rect": {"left": 0.1, "top": top, "right": 0.9, "bottom": top + 0.08}}


def req(goal, elements, utterance=None):
    return GuideRequest.model_validate({
        "sessionId": "s", "utterance": utterance, "goal": goal,
        "snapshot": {"screenId": "s1", "frameId": 1, "timestampMs": 1, "stableFrames": 3, "elements": elements},
        "history": [],
    })


METHOD_SCREEN = [
    el("e0", "예약 확인 방법을 선택해 주세요", 0.05, "TEXT"),
    el("e1", "예약번호 입력", 0.2), el("e2", "여권 스캔", 0.4), el("e3", "QR 바코드", 0.6),
]


def pick(target_id, text, user_choice, message="예약 확인 방법을 고르는 화면이에요."):
    return json.dumps({"screenAsks": "방법", "action": "GUIDE", "targetId": target_id, "guideText": text,
                       "message": message, "userChoice": user_choice}, ensure_ascii=False)


async def test_choice_the_model_says_it_picked_alone_becomes_a_question():
    """모델 스스로 not_stated(어르신이 말한 적 없는데 내가 골랐다)라고 적은 선택은 가리키지 않고 선택지를 읽어 주며 묻는다."""
    ag = LlmGuideAgent(ScriptedFakeLlmClient([pick("e1", "예약번호 입력", "not_stated")]), S())
    r = await ag.next(req("제주항공 선택했어 이제 뭐야 돼", METHOD_SCREEN))
    assert r.response.action.type.value == "ASK_USER"
    assert r.meta.get("unasked_choice") is True
    assert "여권" in r.response.action.question


@pytest.mark.parametrize("user_choice", ["named", "delegated"])
async def test_named_or_delegated_choice_is_pointed_out(user_choice):
    ag = LlmGuideAgent(ScriptedFakeLlmClient([pick("e2", "여권 스캔", user_choice)]), S())
    r = await ag.next(req("여권 스캔으로 하고 싶은데 나는", METHOD_SCREEN, utterance="여권 스캔으로 하고 싶은데 나는"))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText == "여권 스캔"
    assert r.meta.get("unasked_choice") is None


async def test_missing_field_does_not_block_guidance():
    """필드를 안 적은 응답(예전 모델 · 다른 제공자)은 막지 않는다: 근거 없이 가리키기를 거부하지 않는다."""
    raw = json.dumps({"action": "GUIDE", "targetId": "e1", "guideText": "예약번호 입력"}, ensure_ascii=False)
    ag = LlmGuideAgent(ScriptedFakeLlmClient([raw]), S())
    r = await ag.next(req("아무거나", METHOD_SCREEN))
    assert r.response.action.type.value == "TAP"


async def test_flow_buttons_are_never_blocked():
    """다음 · 확인 같은 진행 버튼은 누구에게나 같은 버튼이라 모델이 not_stated라 적어도 막지 않는다."""
    screen = [el("e1", "다음", 0.2), el("e2", "이전", 0.4), el("e3", "처음으로", 0.6)]
    ag = LlmGuideAgent(ScriptedFakeLlmClient([pick("e1", "다음", "not_stated")]), S())
    r = await ag.next(req("이제 뭐 해", screen))
    assert r.response.action.type.value == "TAP"


async def test_explanation_sentence_reaches_the_app():
    """AI가 GUIDE와 함께 쓴 설명 한 문장이 응답의 say로 간다 (앱이 먼저 읽는다)."""
    ag = LlmGuideAgent(ScriptedFakeLlmClient([pick("e2", "여권 스캔", "named", "예약을 확인하는 방법을 고르는 화면이에요.")]), S())
    r = await ag.next(req("여권 스캔으로 할래", METHOD_SCREEN, utterance="여권 스캔으로 할래"))
    assert r.response.say == "예약을 확인하는 방법을 고르는 화면이에요."


def test_consent_button_always_has_an_explanation():
    tap = GuideResponse(goal="g", action=GuideAction.tap("e1", "동의합니다"), source="llm")
    assert _consent_note(tap).say
    refuse = GuideResponse(goal="g", action=GuideAction.tap("e2", "동의하지 않음"), source="llm")
    assert _consent_note(refuse).say is None
    explained = tap.model_copy(update={"say": "비행기에 못 가져가는 물건 안내예요."})
    assert _consent_note(explained).say == "비행기에 못 가져가는 물건 안내예요."


def test_consent_detection():
    assert _is_consent("동의합니다") and _is_consent("약관 동의")
    assert not _is_consent("동의하지 않음") and not _is_consent("다음")


def test_user_choice_is_in_every_provider_schema_and_parsed_strictly():
    assert ACTION_SCHEMA["properties"]["userChoice"]["enum"] == ["named", "delegated", "not_stated"]
    assert "userChoice" in _gemini_schema()["required"]                       # Gemini는 모든 칸을 채우게 한다
    assert LlmAction.model_validate({"action": "GUIDE", "userChoice": "NOT_STATED"}).userChoice == "not_stated"
    assert LlmAction.model_validate({"action": "GUIDE", "userChoice": "아무말"}).userChoice is None   # 모르는 값은 없는 것으로


async def test_consent_is_explained_in_help_mode_too():
    """도움 모드(물어볼 때만 답함)에서도 동의 버튼을 설명 없이 가리키지 않는다: 모든 응답이 지나는 공통 출구에서 보장한다."""
    consent = [el("e0", "기내 반입 제한 물품 안내", 0.05, "TEXT"), el("e2", "동의하지 않음", 0.7), el("e3", "동의합니다", 0.8)]
    raw = json.dumps({"screenAsks": "동의", "action": "GUIDE", "targetId": "e3", "guideText": "동의합니다", "userChoice": "named"}, ensure_ascii=False)
    ag = LlmGuideAgent(ScriptedFakeLlmClient([raw]), S(help_mode=True))
    r = await ag.next(req("뭐 해야 돼", consent, utterance="뭐 해야 돼"))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText == "동의합니다"
    assert r.response.say                                  # 설명이 붙는다
