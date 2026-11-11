"""ResponseCache: 같은 화면 · 같은 목표 · 같은 이력이면 LLM을 다시 부르지 않고 직전 답을 돌려줍니다.

왜 필요한가
- 앱은 시간 초과나 502 뒤에 같은 요청을 1회 다시 보냅니다 (계약 3장). 서버가 첫 요청을 끝까지 처리했는데
  응답만 늦은 경우, 두 번째 요청은 캐시에서 바로 답할 수 있습니다 (p95 단축).
- 화면 전환 직후 같은 화면의 스냅샷이 연달아 올 때도 같은 이유로 LLM 호출이 줄어듭니다.

지킬 것
- 키는 내용의 해시입니다. 값은 메모리에만 두고 TTL(기본 30초) 뒤 사라지며, 로그에는 남기지 않습니다.
- 성공한 판단(llm · fastpath · rule)만 저장합니다. fallback · 오류는 저장하지 않습니다 (다음 요청에서 다시 시도).
- frameId · timestampMs · sessionId는 키에서 뺍니다 (같은 화면이면 같은 답이어야 함).
"""
from __future__ import annotations

import hashlib
import json
import time
from collections import OrderedDict
from typing import Callable, Optional, Tuple

from .models import GuideRequest, GuideResponse


def request_key(goal: str, req: GuideRequest) -> str:
    snap = req.snapshot
    payload = {
        "goal": goal,
        "screen": snap.screenId,
        "els": [(e.id, e.text, e.kind.value, round(e.rect.left, 3), round(e.rect.top, 3)) for e in snap.elements],
        "hist": [(h.screenId, h.action.value, h.targetText) for h in req.history],
    }
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()


class ResponseCache:
    def __init__(self, max_items: int = 256, ttl_s: float = 30.0, clock: Callable[[], float] = time.monotonic):
        self.max_items = max_items
        self.ttl_s = ttl_s
        self._clock = clock
        self._items: "OrderedDict[str, Tuple[float, GuideResponse]]" = OrderedDict()

    @property
    def enabled(self) -> bool:
        return self.max_items > 0 and self.ttl_s > 0

    def get(self, key: str) -> Optional[GuideResponse]:
        if not self.enabled:
            return None
        hit = self._items.get(key)
        if hit is None:
            return None
        at, resp = hit
        if self._clock() - at > self.ttl_s:
            del self._items[key]
            return None
        self._items.move_to_end(key)
        return resp

    def put(self, key: str, resp: GuideResponse) -> None:
        if not self.enabled:
            return
        self._items[key] = (self._clock(), resp)
        self._items.move_to_end(key)
        while len(self._items) > self.max_items:
            self._items.popitem(last=False)

    def __len__(self) -> int:
        return len(self._items)
