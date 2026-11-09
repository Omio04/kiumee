"""간단한 메모리 속도 제한 (토큰 버킷). 서버가 1대일 때만 정확합니다.
여러 대로 늘리면 Redis 같은 공유 저장소나 클라우드 앞단의 제한 기능으로 바꾸세요."""
from __future__ import annotations

import time
from typing import Callable, Dict, Tuple


class RateLimiter:
    def __init__(self, per_minute: int, clock: Callable[[], float] = time.monotonic):
        self.capacity = max(1, per_minute)
        self.rate = self.capacity / 60.0
        self.clock = clock
        self._state: Dict[str, Tuple[float, float]] = {}   # key -> (tokens, last)

    def allow(self, key: str) -> Tuple[bool, int]:
        """(허용 여부, 거절이면 기다려야 할 초)."""
        now = self.clock()
        tokens, last = self._state.get(key, (float(self.capacity), now))
        tokens = min(self.capacity, tokens + (now - last) * self.rate)
        if tokens >= 1.0:
            self._state[key] = (tokens - 1.0, now)
            return True, 0
        self._state[key] = (tokens, now)
        wait = int((1.0 - tokens) / self.rate) + 1
        if len(self._state) > 10000:          # 메모리 보호
            self._state.clear()
        return False, wait
