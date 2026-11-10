"""GuideAgent: 요청 하나를 받아 다음 행동(GuideResponse)을 돌려줍니다.

처리 순서 (LlmGuideAgent)
  1. 개인정보 정리            privacy.sanitize
  2. 로컬 규칙                "카드를 넣어 주세요" 같은 결제 진행 화면이면 LLM 없이 DONE
  3. 후보 축소                JamoNgramRanker (K=8)
  4. 빠른 길(선택)            글자가 거의 똑같고 1등이 뚜렷하면 LLM 없이 TAP
  5. LLM 판단                 JSON 강제 · 형식이 틀리면 1회만 다시 요청 (총 처리 예산 안에서만)
  6. 검증                     후보 밖 targetId → UNKNOWN

앞에는 응답 캐시가 있어 같은 화면 · 목표 · 이력의 재요청(앱의 1회 재시도)은 LLM 없이 바로 답합니다.

목표(goal)는 서버가 저장하지 않습니다. 앱이 직전 응답의 goal을 요청에 돌려주고, 서버는 거기에
이번 발화(utterance)를 붙여 새 goal을 만들어 응답에 담습니다 (계약 v1, docs/contract.md).
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Dict, List, Protocol

from . import privacy
from .cache import ResponseCache, request_key
from .jamo import normalize
from .llm import LlmClient
from .models import (MAX_GOAL, ActionType, ElementKind, GuideAction, GuideRequest, GuideResponse, LlmKind,
                     ScreenElement)
from .parser import ActionParser, ParseError
from .prompt import RETRY_NOTE, SYSTEM, build_user
from .ranker import JamoNgramRanker
from .settings import Settings
from .validator import MSG_ASK, MSG_DONE, MSG_UNKNOWN, ActionValidator

# 결제가 "진행 중"이라는 뜻의 화면 문구. 이 계획의 범위는 결제 수단 선택까지라서, 보이면 안내를 마칩니다.
_DONE_PATTERNS = [normalize(p) for p in (
    "카드를 넣", "카드를 투입", "카드 투입", "투입구", "카드를 삽입", "카드를 태그", "결제 진행", "결제 중", "승인 중",
    "결제가 완료", "결제 완료", "주문이 완료", "주문 완료", "주문번호",
)]


@dataclass
class GuideResult:
    response: GuideResponse
    meta: Dict[str, object] = field(default_factory=dict)    # 내용이 아닌 진단 값만 (개수·시간·경로)


class GuideAgent(Protocol):
    async def next(self, req: GuideRequest) -> GuideResult: ...


def _goal_of(req: GuideRequest) -> str:
    """목표 = 직전 목표(goal) + 이번에 새로 들은 말(utterance).

    - 첫 요청        goal 없음 + utterance  → utterance
    - 화면만 바뀜    goal + utterance 없음  → goal 그대로
    - 되물음에 대답  goal + utterance       → "goal, utterance"
    길이는 계약 최대치(200자)에 맞춰 뒤쪽(최근 말)을 남깁니다.
    """
    parts = [p.strip() for p in (req.goal, req.utterance) if p and p.strip()]
    return ", ".join(parts)[-MAX_GOAL:]


def _is_done_screen(elements: List[ScreenElement]) -> bool:
    for e in elements:
        t = normalize(e.text)
        if any(p in t for p in _DONE_PATTERNS):
            return True
    return False


class LlmGuideAgent:
    def __init__(self, llm: LlmClient, settings: Settings):
        self.llm = llm
        self.s = settings
        self.ranker = JamoNgramRanker(settings.candidate_k)
        self.parser = ActionParser()
        self.validator = ActionValidator()
        self.cache = ResponseCache(settings.cache_size, settings.cache_ttl_s)

    async def next(self, req: GuideRequest) -> GuideResult:
        t0 = time.perf_counter()
        meta: Dict[str, object] = {"llm_calls": 0, "retry": False, "rejected": False}

        req, dropped = privacy.sanitize(req)
        meta["dropped"] = dropped
        els = req.snapshot.elements
        goal = _goal_of(req)

        key = request_key(goal, req)
        cached = self.cache.get(key)
        if cached is not None:
            meta.update(source="cache", candidates=0)
            return self._finish(cached, meta, t0)

        # 2) 로컬 규칙: 결제 진행 화면
        if _is_done_screen(els):
            meta.update(source="rule", candidates=0)
            return self._finish(GuideResponse(goal=goal, action=GuideAction.done(MSG_DONE), source="rule"), meta, t0)  # 규칙은 이미 즉시라 캐시 불필요

        ranked = self.ranker.rank(els, goal)
        meta["candidates"] = len(ranked)
        if not ranked:
            meta["source"] = "fallback"
            return self._finish(GuideResponse(goal=goal, action=GuideAction.unknown(MSG_UNKNOWN), source="fallback"),
                                meta, t0)

        # 4) 빠른 길
        if self.s.fastpath and goal:
            top = ranked[0]
            second = ranked[1].match if len(ranked) > 1 else 0.0
            if top.match >= self.s.fast_min_score and top.match - second >= self.s.fast_margin:
                meta["source"] = "fastpath"
                return self._finish(GuideResponse(goal=goal, action=GuideAction.tap(top.element.id, top.element.text),
                                                  source="fastpath"), meta, t0)

        # 5) LLM
        candidates = [r.element for r in ranked]
        by_id = {c.id: c for c in candidates}
        context = [e.text for e in els if e.kind == ElementKind.TEXT][:6]
        user = build_user(goal, req.history, candidates, context)

        action = None
        deadline = t0 + self.s.total_budget_s
        for attempt in range(2):                       # 최초 1회 + 형식 오류 시 1회
            remaining = deadline - time.perf_counter()
            if attempt > 0 and remaining < self.s.min_retry_s:
                break                                  # 앱 타임아웃(6초) 안에 답하려면 재요청을 포기하고 UNKNOWN으로
            timeout = max(0.5, min(self.s.llm_timeout_s, remaining))
            raw = await self.llm.complete(SYSTEM, user if attempt == 0 else user + RETRY_NOTE, timeout)
            meta["llm_calls"] = int(meta["llm_calls"]) + 1
            try:
                action = self.parser.parse(raw)
                break
            except ParseError:
                meta["retry"] = True
        if action is None:
            meta["source"] = "fallback"
            return self._finish(GuideResponse(goal=goal, action=GuideAction.unknown(MSG_UNKNOWN), source="fallback"),
                                meta, t0)

        # 6) 검증 (+ LLM 형식 GUIDE… → 앱 계약 TAP…)
        resp = self.validator.validate(action, by_id, goal=goal)
        if action.action == LlmKind.GUIDE and resp.action.type == ActionType.UNKNOWN:
            meta["rejected"] = True                    # LLM이 후보에 없는 버튼을 말함
        meta["source"] = resp.source
        if resp.source == "llm":
            self.cache.put(key, resp)
        return self._finish(resp, meta, t0)

    @staticmethod
    def _finish(resp: GuideResponse, meta: Dict[str, object], t0: float) -> GuideResult:
        meta["total_ms"] = round((time.perf_counter() - t0) * 1000, 1)
        return GuideResult(resp, meta)


class FakeGuideAgent:
    """LLM 없이 항상 같은 규칙으로 답하는 가짜 에이전트. Android 쪽 개발과 계약 테스트에 씁니다.
    규칙: 결제 진행 화면→DONE / 후보 없음→UNKNOWN / 목표 없음→ASK_USER / 그 외 1등 후보→TAP."""

    def __init__(self, k: int = 8):
        self.ranker = JamoNgramRanker(k)

    async def next(self, req: GuideRequest) -> GuideResult:
        req, dropped = privacy.sanitize(req)
        goal = _goal_of(req)
        ranked = self.ranker.rank(req.snapshot.elements, goal)
        if _is_done_screen(req.snapshot.elements):
            r = GuideResponse(goal=goal, action=GuideAction.done(MSG_DONE), source="fake")
        elif not ranked:
            r = GuideResponse(goal=goal, action=GuideAction.unknown(MSG_UNKNOWN), source="fake")
        elif not goal:
            r = GuideResponse(goal=goal, action=GuideAction.ask_user(MSG_ASK), source="fake")
        else:
            e = ranked[0].element
            r = GuideResponse(goal=goal, action=GuideAction.tap(e.id, e.text), source="fake")
        return GuideResult(r, {"source": "fake", "candidates": len(ranked), "dropped": dropped, "llm_calls": 0,
                               "retry": False, "rejected": False, "total_ms": 0.0})
