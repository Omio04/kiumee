"""안 보이는 버튼: 어르신이 하려는 일의 버튼이 화면에 없으면 지어내지 말고 카메라를 조정하게 한다.

2026-11-13 실기기: "그대로 담을게"라고 4번 말했지만 카메라에는 화면 윗부분만 보여 "담기" 버튼이 없었다(노트북 키보드 글자 `Prt Sc`까지 읽혔다).
서버는 그 사실을 말하지 못하고 엉뚱한 글자("선택된 옵션", "선택")를 눌러 보라고 했다. 담기 · 결제하기는 보통 화면 맨 아래에 있어서
폰이 가까우면 잘린다. 말하는 일과 화면의 버튼이 안 맞으면 "버튼이 안 보여요, 폰을 뒤로 빼서 아래까지 비춰 주세요"가 정답이다.
"""
from __future__ import annotations

import re
from typing import Optional, Sequence

from .jamo import dice, normalize
from .models import ScreenElement

# (말 패턴, 필요한 버튼 낱말, 어르신께 말할 이름). 담기 · 결제는 화면 맨 아래에 있는 경우가 많다.
_INTENTS = (
    (re.compile(r"담(?:을|아|기|고|는다|자)|주문\s*담"), ("담기", "주문담기", "장바구니담기", "선택완료"), "담기"),
    (re.compile(r"결제(?:할|하|해|를|$|\s)|계산"), ("결제하기", "결제", "계산하기", "주문하기"), "결제하기"),
)
SIMILAR = 0.6


def _visible(elements: Sequence[ScreenElement], words: Sequence[str]) -> bool:
    for e in elements:
        t = normalize(e.text)
        if not t:
            continue
        for w in words:
            nw = normalize(w)
            if nw in t or (len(t) >= 3 and dice(t, nw) >= SIMILAR):    # 글자 인식이 한두 글자 틀려도 ("주문답기")
                return True
    return False


def missing_button(utterance: Optional[str], elements: Sequence[ScreenElement]) -> Optional[str]:
    """이번 말이 가리키는 일의 버튼이 화면에 전혀 안 보이면 그 버튼 이름. 보이거나 해당 말이 아니면 None."""
    if not utterance or not elements:
        return None
    for pattern, words, name in _INTENTS:
        if pattern.search(utterance) and not _visible(elements, words):
            return name
    return None


def message(name: str) -> str:
    return f"{name} 버튼이 지금 안 보여요. 폰을 조금 뒤로 빼서 화면 아래까지 보이게 비춰 주세요."
