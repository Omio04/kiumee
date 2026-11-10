"""ActionValidator: LLM이 한 말을 그대로 믿지 않고, 앱 계약 형식으로 바꿉니다.

- 후보 밖 targetId → UNKNOWN (설계: 화이트리스트). 화면에 없는 버튼을 안내하는 일을 막습니다.
- guideText는 항상 실제 버튼 글자로 되돌립니다 (LLM이 글자를 바꿔 말해도 화면과 일치).
- 메시지가 없거나 너무 길면 기본 문구로 바꿉니다.
- LLM 형식 → 앱 계약: GUIDE → TAP(targetId, guideText) / ASK_USER → question / DONE → guideText / UNKNOWN → reason
"""
from __future__ import annotations

from typing import Dict

from .models import GuideAction, GuideResponse, LlmAction, LlmKind, ScreenElement, ScrollDir

MSG_UNKNOWN = "버튼을 찾지 못했어요. 화면이 잘 보이게 정면에서 비춰주세요."
MSG_ASK = "무엇을 드시고 싶으세요?"
MSG_DONE = "결제 수단을 고르셨어요. 직원 또는 화면 안내를 따라주세요."
MSG_SCROLL = "화면을 위아래로 밀어서 찾아보세요."
MSG_WAIT = "화면이 바뀔 때까지 잠시 기다려 주세요."
MAX_MESSAGE = 60


def _clean_message(m: str | None, default: str) -> str:
    m = (m or "").strip()
    if not m or len(m) > MAX_MESSAGE:
        return default
    return m


class ActionValidator:
    def validate(self, a: LlmAction, candidates: Dict[str, ScreenElement], goal: str = "",
                 source: str = "llm") -> GuideResponse:
        if a.action == LlmKind.GUIDE:
            el = candidates.get(a.targetId or "")
            if el is None:
                return GuideResponse(goal=goal, action=GuideAction.unknown(MSG_UNKNOWN), source="fallback")
            return GuideResponse(goal=goal, action=GuideAction.tap(el.id, el.text), source=source)
        if a.action == LlmKind.ASK_USER:
            return GuideResponse(goal=goal, action=GuideAction.ask_user(_clean_message(a.message, MSG_ASK)), source=source)
        if a.action == LlmKind.DONE:
            return GuideResponse(goal=goal, action=GuideAction.done(_clean_message(a.message, MSG_DONE)), source=source)
        if a.action == LlmKind.SCROLL:
            try:
                d = ScrollDir((a.direction or "DOWN").strip().upper())
            except ValueError:
                d = ScrollDir.DOWN                      # 모르는 방향은 가장 흔한 아래로
            act = GuideAction(type="SCROLL", direction=d, guideText=_clean_message(a.message, MSG_SCROLL))
            return GuideResponse(goal=goal, action=act, source=source)
        if a.action == LlmKind.WAIT:
            return GuideResponse(goal=goal, action=GuideAction(type="WAIT", guideText=_clean_message(a.message, MSG_WAIT)),
                                 source=source)
        return GuideResponse(goal=goal, action=GuideAction.unknown(_clean_message(a.message, MSG_UNKNOWN)), source=source)
