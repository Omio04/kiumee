"""ActionValidator: LLM이 한 말을 그대로 믿지 않고, 앱 계약 형식으로 바꿉니다.

- 후보 밖 targetId → UNKNOWN (설계: 화이트리스트). 화면에 없는 버튼을 안내하는 일을 막습니다.
- guideText는 항상 실제 버튼 글자로 되돌립니다 (LLM이 글자를 바꿔 말해도 화면과 일치).
- 메시지가 없거나 너무 길면 기본 문구로 바꿉니다.
- LLM 형식 → 앱 계약: GUIDE → TAP(targetId, guideText) / ASK_USER → question / DONE → guideText / UNKNOWN → reason
  / SCROLL → direction + guideText(찾는 대상 이름) / WAIT → guideText(그대로 읽을 문장)
- SCROLL의 guideText는 문장이 아닙니다. 앱이 "화면을 아래로 밀어서 {guideText} 찾아 주세요"로 읽습니다 (계약 v1 2장).
"""
from __future__ import annotations

from typing import Dict

from . import privacy
from .models import GuideAction, GuideResponse, LlmAction, LlmKind, ScreenElement, ScrollDir

MSG_UNKNOWN = "버튼을 찾지 못했어요. 화면이 잘 보이게 정면에서 비춰주세요."
MSG_ASK = "무엇을 드시고 싶으세요?"
MSG_DONE = "결제 수단을 고르셨어요. 직원 또는 화면 안내를 따라주세요."
# 아직 아무것도 고르지 않았는데 LLM이 '끝'이라고 할 때 대신 되묻는 말 (잘못 알아들은 말이 흔한 원인, 2026-11-12)
MSG_NOT_DONE = "잘 못 알아들었어요. 무엇을 도와드릴지 다시 말씀해 주세요."
# 고른 것 없이 결제 버튼을 안내하려 할 때 대신 하는 말 (원하는 메뉴가 화면에 안 보임, 2026-11-13)
MSG_NOT_SEEN = "찾으시는 것이 화면에서 안 보여요. 화면이 잘 보이게 비춰 주세요."
SCROLL_TARGET_DEFAULT = "원하시는 메뉴"    # 찾는 대상을 모를 때. 앱: "화면을 아래로 밀어서 원하시는 메뉴 찾아 주세요"
MSG_WAIT = "화면이 바뀔 때까지 잠시 기다려 주세요."
MAX_MESSAGE = 60
MAX_SCROLL_TARGET = 20


def _clean_message(m: str | None, default: str) -> str:
    m = (m or "").strip()
    if not m or len(m) > MAX_MESSAGE:
        return default
    return m


def _scroll_target(t: str | None) -> str:
    """SCROLL의 guideText = 찾는 대상 이름(짧은 말). 문장이 오면 앱이 읽는 문장 안에 문장이 또 들어가므로 기본값으로 바꿉니다."""
    t = (t or "").strip()
    looks_like_sentence = any(c in t for c in ".!?") or t.endswith(("요", "니다"))
    if not t or len(t) > MAX_SCROLL_TARGET or looks_like_sentence or privacy.is_sensitive(t):
        return SCROLL_TARGET_DEFAULT
    return t


class ActionValidator:
    def validate(self, a: LlmAction, candidates: Dict[str, ScreenElement], goal: str = "",
                 source: str = "llm") -> GuideResponse:
        if a.action == LlmKind.GUIDE:
            el = candidates.get(a.targetId or "")
            if el is None:
                return GuideResponse(goal=goal, action=GuideAction.unknown(MSG_UNKNOWN), source="fallback")
            return GuideResponse(goal=goal, action=GuideAction.tap(el.id, el.text), source=source)
        if a.action == LlmKind.ASK_USER:
            # 질문이 message가 아니라 guideText 칸에 들어오는 일이 있다 (Gemini, 2026-11-12) → 질문 문장이면 그걸 쓴다
            q = a.message or (a.guideText if (a.guideText or "").strip().endswith(("?", "요", "까")) else None)
            return GuideResponse(goal=goal, action=GuideAction.ask_user(_clean_message(q, MSG_ASK)), source=source)
        if a.action == LlmKind.DONE:
            return GuideResponse(goal=goal, action=GuideAction.done(_clean_message(a.message, MSG_DONE)), source=source)
        if a.action == LlmKind.SCROLL:
            try:
                d = ScrollDir((a.direction or "DOWN").strip().upper())
            except ValueError:
                d = ScrollDir.DOWN                      # 모르는 방향은 가장 흔한 아래로
            act = GuideAction(type="SCROLL", direction=d, guideText=_scroll_target(a.guideText))
            return GuideResponse(goal=goal, action=act, source=source)
        if a.action == LlmKind.WAIT:
            return GuideResponse(goal=goal, action=GuideAction(type="WAIT", guideText=_clean_message(a.message, MSG_WAIT)),
                                 source=source)
        return GuideResponse(goal=goal, action=GuideAction.unknown(_clean_message(a.message, MSG_UNKNOWN)), source=source)
