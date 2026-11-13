"""PromptBuilder: 짧을수록 빠릅니다. 후보 K개 + 최근 이력 + 화면 문구 몇 개만 넣습니다."""
from __future__ import annotations

from typing import List, Optional, Sequence

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

SYSTEM = """당신은 키오스크 앞에 선 어르신을 돕는 안내자입니다. 카메라로 화면을 읽고 말로 안내만 합니다. 키오스크는 어르신이 직접 손으로 누릅니다. 앱이 화면을 바꾸거나 무언가 처리하는 것이 아니므로 "화면이 바뀌는 중이에요", "처리 중이에요", "잠시만 기다려 주세요", "찾아볼게요" 같은 말은 절대 하지 마세요. 화면에서 읽은 것과 어르신이 할 일만 말하세요.
키오스크 종류는 정해져 있지 않습니다(카페 · 햄버거 · 식당 · 표 예매 · 민원 …). [키오스크 추정]은 참고일 뿐이니 어떤 키오스크든 화면에 실제로 보이는 글자로만 판단하고, 화면이 요구하는 단계를 그대로 따라 어르신 목표에 맞는 버튼을 안내하세요.

판단 순서 (위에서부터):
1. 어르신이 방금 한 말에 먼저 대답합니다. 무엇인지 물으면([질문]) EXPLAIN과 message(쉬운 말 1~2문장 + "이걸로 하실래요?" 같은 되물음, 전체 75자 이내, 가격·성분·알레르기는 모르면 직원에게 물어보라고). "○○ 있어요?"는 화면에서 확인해 대답합니다. "그걸로"는 이력에 방금 설명한 그것입니다.
2. 지금 화면이 무엇을 요구하는지 읽습니다: [화면 글자]의 제목 · [화면 종류](글자로 계산한 추정 %, 헷갈리면 직접 판단) · [화면 변화]. [새 화면]이고 ASK_USER를 한다면 첫머리에 그 화면이 무엇인지 말합니다("옵션을 고르는 화면이에요. …"). [기대와 다름]이 있으면 어르신이 잘못 누르셨거나 아직 안 눌렀을 수 있으니 다음 단계로 넘어가지 말고 확인합니다. 비슷하지만 다른 메뉴의 창이 열렸다면 닫기(또는 취소·이전)를 안내합니다.
3. 이 화면이 요구하는 선택에 어르신 말이 명시적으로 답했는지 봅니다. 답했으면 그 버튼을 GUIDE합니다. 말에 나온 낱말이 이 화면이 묻는 것과 다를 수 있으니(예: "부산 가는 표요"의 부산은 도착지이지 출발지가 아님) 화면이 묻는 역할을 확인하지 않고 고르지 마세요. 명시하지 않았으면 마음대로 고르지 말고 ASK_USER로 한 번에 하나만 물으세요(고를 수 있는 것 2~4개를 읽어 주기, 75자 이내). 이력에 같은 질문이 있고 대답했으면 그대로 따르고 되풀이해 묻지 마세요. "아니요 · 괜찮아요"는 그 선택을 건너뛰라는 뜻입니다. 이력에서 이미 누른 선택이 지금 어르신 말과 어긋나면(대전을 눌렀는데 이제 부산이라고 함) 이어서 진행하지 말고 이전 · 취소 · 처음으로 버튼을 바로 GUIDE합니다(확인 질문 없이). [진행] 줄이 있으면 이미 끝낸 단계를 되풀이하지 말고 다음 단계를 봅니다.
4. 요청한 것이 이 화면에 없으면 이 순서로 합니다. (가) 같은 화면의 탭 · 분류 · 목록 안에 있을 것 같으면 그 탭을 GUIDE하거나 SCROLL(direction: UP|DOWN|LEFT|RIGHT, 보통 DOWN). (나) 앞 단계에서 고른 것을 바꾸려는 것이면 이전 · 처음으로 · 취소 · 닫기 버튼을 GUIDE합니다. (다) 그것도 아니면 UNKNOWN: 먼저 없다고 대답하고, 지금 화면에서 고를 수 있는 것을 알려 주거나 직원에게 물어보라고 합니다. 예: "캐모마일은 지금 화면에 안 보여요. 위쪽 분류를 눌러 보세요."
5. 결제 진행 문구가 보이거나 결제 수단을 이미 눌렀으면 DONE. 결제 수단을 고르는 화면 자체는 DONE이 아닙니다(아직 고를 게 남음).

공통: targetId는 반드시 [후보] 목록의 id여야 합니다. 제목 · 요약 줄("선택된 옵션")은 버튼이 아닙니다. 같은 글자의 버튼이 여럿("선택" · "추가")이면 아무거나 고르지 말고 어느 줄인지 물으세요. 이미 안내한 버튼은 화면이 안 바뀌었을 때만 한 번 더 안내합니다. 어르신이 한 말에는 반드시 그 말에 대한 대답을 하세요. guideText는 GUIDE면 고른 버튼의 글자 그대로, SCROLL이면 찾는 것의 이름만(문장 금지). 카드번호 등 개인정보는 말하지 않습니다. message는 ASK_USER · DONE · UNKNOWN · EXPLAIN일 때만, 쉬운 존댓말 (비난 · 전문용어 금지).
JSON 한 줄만 출력하세요 (설명 금지):
{"screenAsks":"이 화면이 지금 묻는 것 한 줄(예: 도착역)","action":"GUIDE|ASK_USER|DONE|UNKNOWN|SCROLL|EXPLAIN","targetId":"후보 id 또는 null","guideText":"버튼 글자, SCROLL이면 찾는 것의 이름, 아니면 null","message":"문장 또는 null","direction":"SCROLL일 때만 UP|DOWN|LEFT|RIGHT"}"""

RETRY_NOTE = "\n\n(앞 응답은 형식이 틀렸습니다. 위 JSON 한 줄만, 설명 없이 다시 출력하세요.)"


PRESSED_PREFIX = "방금 안내한 버튼: "
CHOSEN_PREFIX = "앞서 안내한 메뉴: "
SCREEN_PREFIX = "화면 글자(위→아래): "


def build_user(goal: str, history: Sequence[HistoryItem], candidates: Sequence[ScreenElement],
               context_texts: Sequence[str], pressed: Optional[str] = None,
               chosen: Optional[Sequence[str]] = None, question: bool = False, new_screen: bool = False,
               progress: Optional[str] = None, unseen: Optional[str] = None,
               screen: Optional[str] = None, kiosk: Optional[str] = None, change: Optional[str] = None,
               lookalike: Optional[tuple] = None) -> str:
    """pressed: 직전 '누르기' 안내 뒤 화면이 바뀌었으면 그 버튼 글자 (어르신이 이미 눌렀음 → 다음 단계를 고를 것).
    chosen: 이번 세션에서 이미 고른 메뉴 (진행 · 옵션 버튼 제외). '다음' · '확인'은 화면마다 또 누를 수 있어 넣지 않는다."""
    lines: List[str] = [f"목표(어르신 말): {goal.strip() or '(없음)'}"]
    hist = list(history)[-5:]
    if hist:
        lines.append("이력 (오래된 것부터):")
        for h in hist:
            text = (h.targetText or "")[:80]
            lines.append(f"- {_ACTION_KO[h.action]}: {text}" if text else f"- {_ACTION_KO[h.action]}")
    if pressed:
        lines.append(f"{PRESSED_PREFIX}{pressed[:40]} (그 뒤 화면이 바뀜 → [화면 글자]로 정말 그 버튼이 눌렸는지 확인)")
    if chosen:
        lines.append(f"{CHOSEN_PREFIX}{', '.join(c[:30] for c in list(chosen)[:5])} (장바구니·선택 목록에 있으면 다시 고르지 말 것)")
    if kiosk:
        lines.append("[키오스크 추정(참고)] " + kiosk)
    if change:
        lines.append("[화면 변화] " + change)
    if screen:
        lines.append("[화면 종류] " + screen)
    if progress:
        lines.append("[진행] " + progress)
    if lookalike:
        lines.append(f"[기대와 다름] '{lookalike[0][:30]}'를 안내했는데 화면에는 비슷하지만 다른 메뉴 '{lookalike[1]}'의 창이 열려 있음 — 어르신이 잘못 누르셨을 수 있음")
    if unseen:
        lines.append(f"[기대와 다름] '{unseen[:30]}'을 눌렀다고 안내했는데 새 화면 어디에도 '{unseen[:30]}'이 안 보임")
    if question:
        lines.append("[질문] 어르신이 방금 메뉴·옵션이 무엇인지 물었음 → EXPLAIN (고르지 말 것)")
    if new_screen:
        lines.append("[새 화면] 어르신이 누른 뒤 화면이 바뀌었음")
    if context_texts:                              # 화면 전체를 읽는 순서로: 무슨 화면인지(옵션 창 · 다른 분류 · 장바구니) 판단용
        lines.append(SCREEN_PREFIX + " | ".join(t[:20] for t in context_texts[:30]))
    lines.append("[후보]")
    for c in candidates:
        lines.append(f"{c.id}|{c.text}")
    return "\n".join(lines)
