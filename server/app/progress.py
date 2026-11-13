"""진행 상황 추적 (플래너): 지금까지 한 단계와 다음 단계를 한 줄로 요약해 LLM에 알려 준다.

아이디어의 출처: 1인칭(egocentric) 영상 기반 선제 안내 연구들 (ProAssist, EMNLP 2025 · arXiv 2506.05904 등)은
안내를 "지금 장면"만이 아니라 **과제 계획과 진행 상태**(어디까지 했고 다음이 무엇인가)에 묶는다.
우리 앱에 맞게 옮긴 것이다: 모델 · 학습을 가져온 것이 아니라 "계획 + 진행 상태를 프롬프트에 명시"라는 설계만 쓴다.

왜 필요한가
- LLM은 이력을 줄글로 읽고 매번 다시 추론한다 → 이른 '끝', 같은 버튼 반복, 옵션 건너뛰기가 생긴다.
- 서버가 이력에서 단계를 먼저 뽑아 "다음 단계" 후보를 주면 LLM이 할 일이 판단에서 확인으로 줄어든다.
- 키오스크마다 화면은 달라도 주문 흐름(방법 → 메뉴 → 옵션 → 담기 → 결제하기 → 결제 수단)은 같다 (계획서 2장 범위).
"""
from __future__ import annotations

from typing import List, Optional, Sequence

from .jamo import normalize
from .models import ActionType, HistoryItem

# 주문 흐름의 단계 (순서가 계획)
DINE, MENU, OPTION, ADD, CHECKOUT, PAY = "주문 방법", "메뉴", "옵션", "담기", "결제하기", "결제 수단"
PLAN = (DINE, MENU, OPTION, ADD, CHECKOUT, PAY)

_DINE = tuple(normalize(w) for w in ("매장", "포장", "먹고가기", "가져가기"))
_OPTION = ("hot", "ice", "핫", "아이스", "샷", "시럽", "사이즈", "휘핑", "라지", "레귤러", "연하게", "진하게")
_ADD = tuple(normalize(w) for w in ("담기", "주문담기", "장바구니담기", "추가하기"))
_CHECKOUT = tuple(normalize(w) for w in ("결제하기", "주문하기", "계산하기", "장바구니보기"))
_PAY = tuple(normalize(w) for w in ("카드", "페이", "현금", "쿠폰", "간편결제"))
_NAV_ONLY = tuple(normalize(w) for w in ("이전", "취소", "처음", "닫기", "뒤로", "초기화", "더담기"))


def stage_of(text: str) -> Optional[str]:
    """누른 버튼 글자가 주문 흐름의 어느 단계인가. 되돌리기 · 취소 같은 버튼은 None (단계가 아님)."""
    n = normalize(text)
    if not n or any(w in n for w in _NAV_ONLY):
        return None
    if any(w in n for w in _DINE):
        return DINE
    if any(w in n for w in _PAY):
        return PAY
    if any(w in n for w in _CHECKOUT):
        return CHECKOUT
    if any(w in n for w in _ADD):
        return ADD
    if n in _OPTION or any(n == normalize(w) or n.endswith(("추가", "선택")) for w in _OPTION):
        return OPTION
    return MENU


def summarize(history: Sequence[HistoryItem]) -> Optional[str]:
    """이력에서 "지금까지: 주문 방법(매장) → 메뉴(아메리카노) → 옵션(HOT) / 다음 단계: 담기 → 결제하기 → 결제 수단".
    이력이 없으면 None. 이력은 앱이 최근 5개만 보내므로 앞 단계가 밀려났을 수 있다 → '지금까지'는 보이는 범위."""
    done: List[str] = []
    seen = set()
    for h in history:
        if h.action != ActionType.TAP or not h.targetText:
            continue
        st = stage_of(h.targetText)
        if st is None:
            continue
        label = f"{st}({h.targetText.strip()[:12]})"
        if label not in seen:
            seen.add(label)
            done.append((st, label))
    if not done:
        return None
    last_stage = done[-1][0]
    # 옵션은 여러 번 고를 수 있어 '다음'에서 빼지 않는다: 마지막으로 한 단계 다음부터 보여 준다
    idx = PLAN.index(last_stage)
    remaining = list(PLAN[idx + 1:]) if last_stage != OPTION else [OPTION + "(더 필요하면)"] + list(PLAN[idx + 1:])
    now = " → ".join(label for _, label in done[-5:])
    nxt = " → ".join(remaining) if remaining else "끝 (결제 수단까지 고름)"
    return f"지금까지: {now} / 다음 단계: {nxt}"
