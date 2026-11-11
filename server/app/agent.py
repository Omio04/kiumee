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

import asyncio
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Protocol

from . import privacy
from .cache import ResponseCache, request_key
from .jamo import normalize
from .llm import LlmClient, LlmRateLimited, LlmTimeout, LlmUpstream
from .models import (MAX_GOAL, ActionType, ElementKind, GuideAction, GuideRequest, GuideResponse, LlmKind,
                     ScreenElement)
from .parser import ActionParser, ParseError
from .prompt import RETRY_NOTE, SYSTEM, build_user
from .ranker import JamoNgramRanker, answer_button, is_flow_button, same_item
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


def _pressed_text(req: GuideRequest) -> Optional[str]:
    """직전 안내가 '누르기'였고 그 뒤 화면이 바뀌었으면(= 어르신이 눌렀음) 그 버튼 글자.

    새 화면에도 같은 글자(담은 목록 · 옵션 창 제목 등)가 있으면 빠른 길이 또 고르는 바람에
    "아메리카노를 누르세요"가 되풀이됐다 (2026-11-11 시험). 흐름상 다음 단계는 LLM이 이력을 보고 판단한다.
    """
    last = req.history[-1] if req.history else None
    if last and last.action == ActionType.TAP and last.targetText and last.screenId != req.snapshot.screenId:
        return last.targetText
    return None


def _chosen_items(req: GuideRequest) -> List[str]:
    """이번 세션에서 이미 고른 메뉴: '누르기' 안내 뒤 화면이 바뀐(= 눌렀음) 버튼 중 진행 · 옵션 버튼을 뺀 것.

    직전 버튼만 보면, 주문담기를 누르고 메뉴 화면으로 돌아왔을 때 빠른 길이 아메리카노를 또 고른다.
    '다음' · '확인' · '담기'는 화면마다 또 눌러야 할 수 있어서 넣지 않는다 (2026-11-11 시험)."""
    out: List[str] = []
    for h in req.history:
        t = h.targetText
        if (h.action == ActionType.TAP and t and h.screenId != req.snapshot.screenId and not is_flow_button(t)
                and not any(same_item(t, o) for o in out)):
            out.append(t)
    return out


def _retryable(e: Optional[BaseException]) -> bool:
    """겹쳐 보내도 되는 실패인가: 시간 초과 · 혼잡 · 네트워크. 인증 오류와 사용량 제한(429)은 다시 보내 봐야 소용없다."""
    if isinstance(e, LlmRateLimited):
        return False
    return isinstance(e, LlmTimeout) or (isinstance(e, LlmUpstream) and e.kind != "auth")


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

        ranked = self.ranker.rank(els, goal, req.utterance)          # 짧은 대답("네" 등)은 이번 발화로만 판단
        meta["candidates"] = len(ranked)
        if not ranked:
            meta["source"] = "fallback"
            return self._finish(GuideResponse(goal=goal, action=GuideAction.unknown(MSG_UNKNOWN), source="fallback"),
                                meta, t0)

        # 4) 빠른 길 — 쓰지 않는 경우:
        #    · 이미 고른 메뉴 (화면이 바뀌었으니 다음 단계는 LLM이 이력으로 판단)
        #    · 짧은 대답("네" · "아니요" …): 무엇에 대한 대답인지는 직전 질문을 봐야 안다.
        #      "샷 추가하실래요?" → "아니요"를 '취소' 버튼으로 누르면 주문이 취소된다 (2026-11-12)
        pressed = _pressed_text(req)
        chosen = _chosen_items(req)
        if self.s.fastpath and goal and answer_button(req.utterance) is None:
            top = ranked[0]
            second = ranked[1].match if len(ranked) > 1 else 0.0
            repeat = any(same_item(top.element.text, c) for c in chosen)
            if not repeat and top.match >= self.s.fast_min_score and top.match - second >= self.s.fast_margin:
                meta["source"] = "fastpath"
                return self._finish(GuideResponse(goal=goal, action=GuideAction.tap(top.element.id, top.element.text),
                                                  source="fastpath"), meta, t0)

        # 5) LLM
        candidates = [r.element for r in ranked]
        by_id = {c.id: c for c in candidates}
        context = [e.text for e in els if e.kind == ElementKind.TEXT][:6]
        user = build_user(goal, req.history, candidates, context, pressed=pressed, chosen=chosen)

        action = None
        deadline = t0 + self.s.total_budget_s
        for attempt in range(2):                       # 최초 1회 + 형식 오류 시 1회
            remaining = deadline - time.perf_counter()
            if attempt > 0 and remaining < self.s.min_retry_s:
                break                                  # 앱 타임아웃(6초) 안에 답하려면 재요청을 포기하고 UNKNOWN으로
            timeout = max(0.5, min(self.s.llm_timeout_s, remaining))
            raw = await self._complete(user if attempt == 0 else user + RETRY_NOTE, timeout, list(by_id), meta)
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

    async def _complete(self, user: str, timeout: float, ids: List[str], meta: Dict[str, object]) -> str:
        """LLM 한 번 부르기 (버튼 번호는 후보 안에서만 고르게 형식 강제).

        겹쳐 보내기: hedge_after_s 안에 답이 없거나 그 전에 일시 오류(혼잡 503 · 시간 초과 등)로 실패하면
        같은 요청을 하나 더 보내고 먼저 온 답을 쓴다. Gemini 무료 한도는 보통 1초인데 가끔 4초를 넘겨서
        서버 시간 초과 → 앱 오류가 잦았다 (2026-11-12 평가: LLM 판단 12건 중 5건 시간 초과).
        사용량 제한(429) · 인증 오류는 다시 보내지 않는다."""
        call = lambda t: asyncio.ensure_future(self.llm.complete(SYSTEM, user, t, target_ids=ids))
        t0 = time.perf_counter()
        tasks = [call(timeout)]
        hedge = self.s.hedge_after_s
        try:
            if 0 < hedge < timeout:
                done, _ = await asyncio.wait(tasks, timeout=hedge)
                first_failed = bool(done) and _retryable(tasks[0].exception())
                left = timeout - (time.perf_counter() - t0)
                if (not done or first_failed) and left > 0.5:
                    tasks.append(call(left))
                    meta["hedged"] = True
            error: Optional[BaseException] = None
            for fut in asyncio.as_completed(tasks):
                try:
                    return await fut
                except (LlmTimeout, LlmUpstream) as e:          # 하나가 실패하면 남은 쪽을 기다린다
                    error = e
            raise error if error else LlmTimeout("llm timeout")
        finally:
            for t in tasks:
                if not t.done():
                    t.cancel()

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
        ranked = self.ranker.rank(req.snapshot.elements, goal, req.utterance)
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
