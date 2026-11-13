"""행동 후 검증 (expectation check): 방금 안내한 버튼을 눌렀다면 새 화면이 어떻게 보여야 하는지와 지금 화면을 비교한다.

아이디어의 출처: 화면 에이전트 연구들은 행동한 뒤 "기대한 화면"과 실제 화면을 비교해 어긋나면 복구한다
(EcoAgent, AAAI 2026 · GUI-Reflection, arXiv 2506.08012 · InfiGUIAgent, EACL 2026의 expectation-reflection).
우리 앱에 맞게 옮긴 것이다: 모델 · 학습이 아니라 "눌렀다면 이 글자가 보여야 한다"는 규칙 하나.

왜 필요한가
- 앱은 화면 번호가 바뀌면 "눌렀다"고 보고 서버에 다시 묻는다. 그런데 번호는 글자 인식이 흔들려도(가림 · 흔들림 · 다른 창) 바뀐다.
- 서버가 "눌렀다"고 믿고 다음 단계(옵션)로 가면, 사실은 안 눌린 어르신께 엉뚱한 안내가 나간다.
- 메뉴를 누르면 옵션 창 제목이나 장바구니에 그 메뉴 이름이 보인다. 새 화면 어디에도 이름이 없으면 눌렸는지 의심한다.
의심만 알려 주고(LLM이 판단), 막지는 않는다.
"""
from __future__ import annotations

import re
from typing import Optional, Tuple

from . import progress
from .jamo import dice, normalize
from .models import ActionType, GuideRequest
from .ranker import same_item


def pressed_but_not_visible(req: GuideRequest) -> Optional[str]:
    """직전 안내가 메뉴 '누르기'였고 화면이 바뀌었는데, 그 메뉴 이름이 새 화면 어디에도 안 보이면 그 이름. 아니면 None.

    메뉴(MENU 단계)만 본다: 옵션 · 담기 · 결제는 눌러도 화면에 이름이 남는다는 보장이 없다.
    화면에 이름이 있기만 하면(옵션 창 제목 · 장바구니 · 선택 목록 · 오타 한두 글자) 눌린 것으로 본다."""
    last = req.history[-1] if req.history else None
    if not last or last.action != ActionType.TAP or not last.targetText or last.screenId == req.snapshot.screenId:
        return None
    if progress.stage_of(last.targetText) != progress.MENU:
        return None
    name = last.targetText.strip()
    nn = normalize(name)
    if len(nn) < 2:
        return None
    for e in req.snapshot.elements:
        if nn in normalize(e.text) or same_item(e.text, name) or _has_similar_word(e.text, name):
            return None
    return name


# 글자 인식이 한두 글자를 틀리게 읽어도("아메리키노 옵션 선택") 같은 메뉴로 본다. 다른 메뉴 · 버튼은 이보다 훨씬 낮다 (agent.ITEM_SEEN_DICE 측정 참고)
SIMILAR_WORD = 0.6


def _has_similar_word(text: str, name: str) -> bool:
    return any(len(normalize(tok)) >= 2 and dice(tok, name) >= SIMILAR_WORD for tok in text.split())


_MARK = re.compile(r"[\(\[]\s*(?:ice|hot|아이스|핫|ce)\s*[\)\]]", re.I)     # "(ICE)" "(HOT)" 꾸밈 ("(CE)"는 ICE를 잘못 읽은 것)


_LEADS = tuple(normalize(w) for w in ("선택하신", "선택한", "고르신", "주문하신", "주문", "담긴", "선택"))


def lookalike_menu(req: GuideRequest) -> Optional[Tuple[str, str]]:
    """눌렀다던 메뉴 이름을 품은 더 긴 다른 메뉴가 화면에 있고 정확히 그 메뉴는 없으면 (눌렀다던 이름, 보이는 이름).
    예: "아메리카노"를 안내했는데 새 화면에는 "(ICE)헤이즐넛아메리카노"뿐 — 이름이 들어 있다고 같은 메뉴로 보면 안 된다
    (2026-11-13 실기기: 헤이즐넛아메리카노 옵션 창이 떴는데 서버는 "눌렀다"고 믿고 "화면에 안 보여요"라고만 했다).
    (ICE) · (HOT) 꾸밈은 떼고 비교한다. 메뉴 단계의 누르기 뒤 화면 번호가 바뀐 경우만."""
    last = req.history[-1] if req.history else None
    if not last or last.action != ActionType.TAP or not last.targetText or last.screenId == req.snapshot.screenId:
        return None
    if progress.stage_of(last.targetText) != progress.MENU:
        return None
    name = last.targetText.strip()
    nn = normalize(_MARK.sub("", name))
    if len(nn) < 3:
        return None
    other = None
    for e in req.snapshot.elements:
        nt = normalize(_MARK.sub("", e.text))
        for lead in _LEADS:                                   # "선택하신 아메리카노"는 같은 메뉴 (설명하는 앞말일 뿐)
            if nt.startswith(lead):
                nt = nt[len(lead):]
        if nt == nn:
            return None                                       # 정확히 그 메뉴가 보인다
        # 같은 말로 "끝나는" 더 긴 이름만 (헤이즐넛+아메리카노). "아메리카노 옵션 선택" · "아메리카노 1잔" 같은 제목 · 줄은 다른 메뉴가 아니다
        if nt.endswith(nn) and len(nt) >= len(nn) + 2 and other is None:
            other = e.text.strip()[:30]
    return (name, other) if other else None
