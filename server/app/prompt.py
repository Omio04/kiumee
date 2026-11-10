"""PromptBuilder: 짧을수록 빠릅니다. 후보 K개 + 최근 이력 + 화면 문구 몇 개만 넣습니다."""
from __future__ import annotations

from typing import List, Sequence

from .models import ActionType, HistoryItem, ScreenElement

# 이력 한 줄을 LLM이 읽기 쉬운 말로 (앱 계약의 ActionType → 한국어)
_ACTION_KO = {
    ActionType.TAP: "누르기 안내",
    ActionType.SCROLL: "밀기 안내",
    ActionType.WAIT: "기다림 안내",
    ActionType.ASK_USER: "되물음",
    ActionType.DONE: "안내 끝",
    ActionType.UNKNOWN: "버튼 못 찾음",
}

SYSTEM = """키오스크 안내 도우미의 판단기입니다. 화면 버튼 후보 중에서 어르신이 다음에 눌러야 할 버튼 하나를 고릅니다.
규칙:
- targetId는 반드시 [후보] 목록에 있는 id여야 합니다. 맞는 버튼이 없으면 action은 UNKNOWN.
- 흐름: 메뉴 → 옵션 → 장바구니 → 결제 수단 선택. 결제 수단을 이미 눌렀거나 결제 진행 문구가 보이면 DONE.
- 원하는 버튼이 후보에 없지만 목록·메뉴가 더 있을 것 같으면 SCROLL(direction: UP|DOWN|LEFT|RIGHT, 보통 DOWN). 화면이 로딩 중이거나 바뀌는 중이면 WAIT.
- 어르신이 무엇을 원하는지 모르겠으면 ASK_USER와 40자 이내의 짧은 질문.
- 지난 안내와 같은 버튼을 또 고를 때는 화면이 바뀌지 않았다는 뜻이니, 그대로 한 번 더 고르세요.
- guideText는 GUIDE면 고른 버튼의 글자 그대로, SCROLL이면 찾는 메뉴 이름만 (예: 딸기 스무디. 문장 금지). 카드번호 등 개인정보는 말하지 않습니다.
- message는 ASK_USER, DONE, UNKNOWN, WAIT일 때만, 쉬운 존댓말 한 문장 (비난·전문용어 금지).
JSON 한 줄만 출력하세요 (설명 금지):
{"action":"GUIDE|ASK_USER|DONE|UNKNOWN|SCROLL|WAIT","targetId":"후보 id 또는 null","guideText":"버튼 글자, SCROLL이면 찾는 메뉴 이름, 아니면 null","message":"문장 또는 null","direction":"SCROLL일 때만 UP|DOWN|LEFT|RIGHT"}"""

RETRY_NOTE = "\n\n(앞 응답은 형식이 틀렸습니다. 위 JSON 한 줄만, 설명 없이 다시 출력하세요.)"


def build_user(goal: str, history: Sequence[HistoryItem], candidates: Sequence[ScreenElement],
               context_texts: Sequence[str]) -> str:
    lines: List[str] = [f"목표(어르신 말): {goal.strip() or '(없음)'}"]
    hist = list(history)[-5:]
    if hist:
        lines.append("이력 (오래된 것부터):")
        for h in hist:
            text = (h.targetText or "")[:80]
            lines.append(f"- {_ACTION_KO[h.action]}: {text}" if text else f"- {_ACTION_KO[h.action]}")
    if context_texts:
        lines.append("화면 문구: " + " | ".join(t[:30] for t in context_texts[:6]))
    lines.append("[후보]")
    for c in candidates:
        lines.append(f"{c.id}|{c.text}")
    return "\n".join(lines)
