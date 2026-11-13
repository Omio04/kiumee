"""화면 기억: 같은 세션의 직전 화면을 짧게 기억해서 "무엇에서 무엇으로 어떻게 바뀌었는지"를 알려 준다.

왜 서버가 기억하나
- 앱은 화면 번호가 바뀌면 서버에 묻지만, 요청에는 지금 화면만 있다. 이전 화면이 무엇이었는지는 이력(history)의 글자 몇 개뿐이라
  "메뉴 화면에서 옵션 화면으로 바뀌었어요"처럼 변화를 설명할 수 없었다.
- 공용 계약은 바꾸지 않는다 (요청에 이전 화면을 더 싣지 않는다). 서버 안에서만, 짧게 기억한다.

개인정보
- 기억하는 것은 화면 종류와 짧은 글자(개인정보 마스킹이 끝난 뒤의 12자 이하 버튼 글자 몇 개)뿐이다. 발화 · 이미지 · 숫자열은 기억하지 않는다.
- 메모리에만 두고 TTL(10분) 뒤 사라지며, 로그에는 남기지 않는다. 세션 수에도 상한이 있다.
"""
from __future__ import annotations

import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Sequence, Tuple

from .jamo import normalize
from .models import ScreenElement
from .screen_type import ScreenType

MAX_LABELS = 24
MAX_LABEL_LEN = 12


@dataclass(frozen=True)
class Change:
    """직전 화면 → 지금 화면."""
    from_type: ScreenType
    to_type: ScreenType
    appeared: List[str] = field(default_factory=list)     # 새로 보이는 글자 (읽는 순서, 최대 3)
    vanished: List[str] = field(default_factory=list)     # 사라진 글자 (최대 3)

    @property
    def type_changed(self) -> bool:
        return self.from_type != self.to_type and ScreenType.UNKNOWN not in (self.from_type, self.to_type)


@dataclass
class _Seen:
    screen_id: str
    stype: ScreenType
    labels: List[str]
    at: float


def short_labels(elements: Sequence[ScreenElement]) -> List[str]:
    """읽는 순서로 짧은 글자 몇 개 (가격 · 긴 문장 · 숫자 많은 글자 제외)."""
    out: List[str] = []
    for e in sorted(elements, key=lambda e: (round(e.rect.top, 2), e.rect.left)):
        t = e.text.strip()
        n = normalize(t)
        if 1 <= len(n) <= MAX_LABEL_LEN and sum(c.isdigit() for c in t) < 3 and e.kind.value != "PRICE" and t not in out:
            out.append(t)
        if len(out) >= MAX_LABELS:
            break
    return out


class ScreenMemory:
    def __init__(self, ttl_s: float = 600.0, max_sessions: int = 200, clock: Callable[[], float] = time.monotonic):
        self.ttl_s = ttl_s
        self.max_sessions = max_sessions
        self._clock = clock
        self._items: "OrderedDict[str, _Seen]" = OrderedDict()

    def observe(self, session_id: str, screen_id: str, stype: ScreenType, elements: Sequence[ScreenElement]) -> Optional[Change]:
        """지금 화면을 기억하고, 직전과 화면 번호가 다르면 변화를 돌려준다 (같은 화면이거나 처음이면 None)."""
        now = self._clock()
        labels = short_labels(elements)
        prev = self._items.get(session_id)
        if prev is not None and now - prev.at > self.ttl_s:
            prev = None
        self._items[session_id] = _Seen(screen_id, stype, labels, now)
        self._items.move_to_end(session_id)
        while len(self._items) > self.max_sessions:
            self._items.popitem(last=False)
        if prev is None or prev.screen_id == screen_id:
            return None
        before, after = {normalize(x) for x in prev.labels}, {normalize(x) for x in labels}
        appeared = [x for x in labels if normalize(x) not in before][:3]
        vanished = [x for x in prev.labels if normalize(x) not in after][:3]
        return Change(prev.stype, stype, appeared, vanished)

    def __len__(self) -> int:
        return len(self._items)
