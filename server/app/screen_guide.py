"""화면 안내: 화면이 바뀌었을 때 / "뭘 할 수 있어?"라고 물었을 때, 지금 화면에서 고를 수 있는 것을 말로 알려 줍니다.

예전: 새 화면에서 목표와 맞는 버튼이 없으면 "버튼을 찾지 못했어요"로 끝나서, 어르신이 무엇을 해야 하는지 알 수 없었다.
지금: "화면이 바뀌었어요. HOT, ICE 중에서 고를 수 있어요. 어떤 걸로 할까요?" — 앱은 ASK_USER로 읽고 듣는다.
"""
from __future__ import annotations

from typing import List, Optional, Sequence

from .models import MAX_TEXT, ActionType, ElementKind, GuideRequest, ScreenElement
from .ranker import is_flow_button, same_item

MAX_LABELS = 4
MAX_LABEL_LEN = 10
_TAIL = " 중에서 고를 수 있어요. 어떤 걸로 할까요?"


def screen_changed(req: GuideRequest) -> bool:
    """직전 안내를 받은 화면과 지금 화면이 다른가 (= 어르신이 눌러서 화면이 넘어감). 첫 요청은 아님."""
    last = req.history[-1] if req.history else None
    return bool(last) and last.screenId != req.snapshot.screenId


def choices(elements: Sequence[ScreenElement]) -> List[str]:
    """고를 수 있는 버튼 글자 (읽는 순서, 중복 제거). 옵션 · 메뉴를 먼저, 취소 · 다음 같은 진행 버튼은 고를 게 없을 때만."""
    buttons = sorted((e for e in elements if e.kind == ElementKind.BUTTON_LIKE), key=lambda e: (round(e.rect.top, 2), e.rect.left))
    out: List[str] = []
    for pool in ([b for b in buttons if not is_flow_button(b.text)], buttons):
        for b in pool:
            t = b.text.strip()
            if t and len(t) <= 20 and not any(same_item(t, o) for o in out):
                out.append(t[:MAX_LABEL_LEN])
            if len(out) >= MAX_LABELS:
                break
        if out:
            break
    return out


def describe(elements: Sequence[ScreenElement], changed: bool) -> Optional[str]:
    """고를 수 있는 것을 알려 주는 한 문장 (ASK_USER 질문). 버튼이 하나도 없으면 None."""
    labels = choices(elements)
    head = "화면이 바뀌었어요. " if changed else "지금 화면에서는 "
    while labels:
        text = head + ", ".join(labels) + _TAIL
        if len(text) <= MAX_TEXT:
            return text
        labels = labels[:-1]
    return None
