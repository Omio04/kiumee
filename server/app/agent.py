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
import logging
import re
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Protocol

from . import privacy
from .cache import ResponseCache, request_key
from .jamo import dice, normalize
from . import elder, menu_info, progress, screen_guide
from .llm import LlmClient, LlmRateLimited, LlmTimeout, LlmUpstream
from .models import (MAX_GOAL, MAX_HISTORY, ActionType, ElementKind, GuideAction, GuideRequest, GuideResponse,
                     LlmKind, ScreenElement)
from .parser import ActionParser, ParseError
from .prompt import RETRY_NOTE, SYSTEM, build_user
from .ranker import JamoNgramRanker, answer_button, is_flow_button, same_item
from .settings import Settings
from .validator import MSG_ASK, MSG_DONE, MSG_NOT_DONE, MSG_NOT_SEEN, MSG_UNKNOWN, ActionValidator

log = logging.getLogger("kl")

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
    fix = elder.correction_of(req.utterance, req.goal)       # "아메리카노 말고 라떼로" → 이전 목표를 버리고 새 것만
    if fix:
        return fix[-MAX_GOAL:]
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


def _premature_done(req: GuideRequest, chosen: List[str]) -> bool:
    """LLM의 DONE이 너무 이른가 (2026-11-12 시험: 잘못 알아들은 말 "회 기간"에 "결제가 완료되었습니다"라고 답하고 세션이 끝났다).

    결제 진행 화면이면 앞의 규칙이 이미 DONE을 냈으니, 여기 오는 DONE은 LLM의 판단뿐이다. 이럴 때 이르다고 본다:
    · 고른 것(메뉴 · 표 등)이 하나도 없고
    · 세션 이력이 다 보인다 (앱은 최근 MAX_HISTORY개만 보내서, 꽉 차면 메뉴가 밀려났을 수 있다 → LLM을 믿음)
    · 아직 이 일로 되물은 적이 없다 (한 세션에 한 번만: 또 DONE이면 그대로 둬서 되묻기가 되풀이되지 않게)"""
    if chosen or len(req.history) >= MAX_HISTORY:
        return False
    return not any(h.action == ActionType.ASK_USER and h.targetText == MSG_NOT_DONE for h in req.history)


# 결제로 넘어가는 버튼 (결제하기 · 카드결제 · 계산하기 …)
_CHECKOUT_WORDS = tuple(normalize(w) for w in ("결제", "계산"))
# 목표 자체가 결제 · 마무리면(어르신이 직접 담아 둔 것을 결제, "다 됐어요") 막지 않는다
_PAY_WORDS = tuple(normalize(w) for w in (
    "결제", "계산", "카드", "현금", "페이", "지불", "됐어", "됐습니다", "됐네", "끝", "다했", "완료"))
# 메뉴 이름이 아닌 말 ("아메리카노 한잔 주세요"에서 "한잔" · "주세요")
_FILLER = frozenset(normalize(w) for w in (
    "주세요", "줘요", "줘", "한잔", "두잔", "세잔", "하나", "두개", "주문", "할게요", "할래요", "먹을게요", "마실게요",
    "부탁해요", "그리고", "이랑", "하고", "좀", "더"))
# 화면 글자가 원하는 메뉴와 이만큼 비슷하면 "보인다" (글자 인식 오타 "CE에리카노" 0.65 · "0CE계리카노" 0.50,
# 다른 메뉴 · 버튼은 0.31 이하, 2026-11-13 측정)
ITEM_SEEN_DICE = 0.45


def _goal_words(goal: str) -> List[str]:
    """목표에서 메뉴 이름일 수 있는 낱말 ("아메리카노 한잔 주세요" → ["아메리카노"])"""
    return [w for w in (normalize(t) for t in re.split(r"[\s,]+", goal or "")) if len(w) >= 2 and w not in _FILLER]


def _item_seen(elements: List[ScreenElement], goal: str) -> bool:
    """어르신이 말한 메뉴 이름이 화면 어딘가(메뉴판 · 장바구니 · 옵션 창 제목)에 보이는가. 오타를 너그럽게 본다."""
    words = _goal_words(goal)
    for e in elements:
        t = normalize(e.text)
        if not t or is_flow_button(e.text):
            continue
        if any(w in t or dice(e.text, w) >= ITEM_SEEN_DICE for w in words):
            return True
    return False


def _premature_checkout(req: GuideRequest, goal: str, chosen: List[str], target_text: Optional[str]) -> bool:
    """고른 것 없이 결제로 가려 하는가 (2026-11-12 시험: 메뉴 이름을 못 읽은 화면에서 "아메리카노 한 잔"에 '결제하기'를
    안내했고, 이어서 "주문이 완료되었습니다"로 끝났다). 이럴 때만 막는다:
    · 안내할 버튼이 결제 버튼이고 · 고른 것이 없고 · 세션 이력이 다 보이고(MAX_HISTORY개 미만, 꽉 차면 메뉴가 밀려났을 수 있음)
    · 짧은 대답("다 됐어요" · "네")이 아니고 목표가 결제 · 마무리 자체도 아니며
    · 목표에 메뉴 이름이 있는데 화면 어디에도 안 보인다 (장바구니에 이미 담겨 있으면 결제가 맞다)"""
    if not any(w in normalize(target_text or "") for w in _CHECKOUT_WORDS):
        return False
    if chosen or len(req.history) >= MAX_HISTORY:
        return False
    if answer_button(req.utterance) is not None or any(w in normalize(goal or "") for w in _PAY_WORDS):
        return False
    return bool(_goal_words(goal)) and not _item_seen(req.snapshot.elements, goal)


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
        spoken = elder.clean_utterance(req.utterance)             # "어... 그... 아메리카노" → "아메리카노" (군말 제거)
        if spoken != req.utterance:
            req = req.model_copy(update={"utterance": spoken})
        els = req.snapshot.elements
        goal = _goal_of(req)
        if self.s.debug_prompts:                       # 개발용 진단 (개인정보 걸러낸 뒤의 내용 · 로컬 기록에만)
            log.info("KL_DEBUG 요청 screen=%s goal=%r utter=%r 이력=%s 화면=%s", req.snapshot.screenId, goal, req.utterance,
                     [(h.action.value, h.targetText, h.screenId) for h in req.history],
                     [e.text for e in sorted(els, key=lambda e: (round(e.rect.top, 2), e.rect.left))][:30])

        key = request_key(goal, req)
        cached = self.cache.get(key)
        if cached is not None:
            meta.update(source="cache", candidates=0)
            return self._finish(cached, meta, t0)

        # 1-1) 어르신 말투 (LLM 없이 즉시): 도움 요청 · 그만 · 다시 말해줘 · 화면에 없는 현금
        prev_goal = (req.goal or "").strip()
        early = self._elder_rule(req, els, prev_goal)
        if early is not None:
            meta.update(source="rule", candidates=0, elder=True)
            return self._finish(early, meta, t0)

        # 2) 로컬 규칙: 결제 진행 화면
        if _is_done_screen(els):
            meta.update(source="rule", candidates=0)
            return self._finish(GuideResponse(goal=goal, action=GuideAction.done(MSG_DONE), source="rule"), meta, t0)  # 규칙은 이미 즉시라 캐시 불필요

        # 2-1) 질문: "라떼가 뭐야?" → 사전에 있으면 LLM 없이 설명 (goal은 그대로: 질문이 목표에 섞이면 안 된다)
        asking = menu_info.is_info_question(req.utterance)
        if asking:
            text = menu_info.explain(req.utterance or "", req.history)
            if text:
                meta.update(source="rule", candidates=0, explained=True)
                return self._finish(GuideResponse(goal=prev_goal, action=GuideAction.ask_user(text), source="rule"), meta, t0)
        # 2-2) "뭘 눌러야 돼요?" → 지금 화면에서 고를 수 있는 것을 알려 준다
        if menu_info.is_screen_question(req.utterance):
            text = screen_guide.describe(els, changed=False)
            if text:
                meta.update(source="rule", candidates=0, described=True)
                return self._finish(GuideResponse(goal=prev_goal, action=GuideAction.ask_user(text), source="rule"), meta, t0)

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
        if self.s.fastpath and goal and not asking and answer_button(req.utterance) is None:
            top = ranked[0]
            second = ranked[1].match if len(ranked) > 1 else 0.0
            repeat = any(same_item(top.element.text, c) for c in chosen)
            # 방금 누른 옵션(HOT · ICE …)은 '고른 메뉴'에 안 들어가서(진행 · 옵션 버튼 제외) 선택이 바뀐 새 화면에서도
            # 같은 말("따뜻한 걸로")에 또 HOT을 고르는 무한 반복이 생겼다 (2026-11-13 시뮬레이션). 방금 누른 버튼이면 다음 단계는 LLM이.
            repeat = repeat or (pressed is not None and same_item(top.element.text, pressed))
            if not repeat and top.match >= self.s.fast_min_score and top.match - second >= self.s.fast_margin:
                meta["source"] = "fastpath"
                return self._finish(GuideResponse(goal=goal, action=GuideAction.tap(top.element.id, top.element.text),
                                                  source="fastpath"), meta, t0)

        # 5) LLM
        candidates = [r.element for r in ranked]
        by_id = {c.id: c for c in candidates}
        # 화면 전체 글자를 위→아래로: 바뀐 화면이 원하는 메뉴의 옵션 창인지 · 다른 메뉴인지 · 다른 분류인지 LLM이 판단한다
        # (예전엔 일반 글자 6개만 보여서 판단할 근거가 없었다, 2026-11-12 시험)
        context = [e.text for e in sorted(els, key=lambda e: (round(e.rect.top, 2), e.rect.left))][:30]
        new_screen = screen_guide.screen_changed(req)
        user = build_user(goal, req.history, candidates, context, pressed=pressed, chosen=chosen,
                          question=asking, new_screen=new_screen,
                          progress=progress.summarize(req.history) if self.s.progress_hint else None)

        action = None
        deadline = t0 + self.s.total_budget_s
        for attempt in range(2):                       # 최초 1회 + 형식 오류 시 1회
            remaining = deadline - time.perf_counter()
            if attempt > 0 and remaining < self.s.min_retry_s:
                break                                  # 앱 타임아웃(6초) 안에 답하려면 재요청을 포기하고 UNKNOWN으로
            timeout = max(0.5, min(self.s.llm_timeout_s, remaining))
            raw = await self._complete(user if attempt == 0 else user + RETRY_NOTE, timeout, list(by_id), meta)
            meta["llm_calls"] = int(meta["llm_calls"]) + 1
            if self.s.debug_prompts:
                log.info("KL_DEBUG LLM 원답=%s", raw)
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
        if action.action == LlmKind.EXPLAIN:                      # 설명은 목표를 바꾸지 않는다
            resp = resp.model_copy(update={"goal": prev_goal})
        elif resp.action.type == ActionType.UNKNOWN and not asking:
            # 새 화면인데 고를 버튼을 못 정함: "못 찾았어요"로 끝내지 않고 고를 수 있는 것을 알려 준다
            text = screen_guide.describe(els, changed=new_screen)
            if text:
                meta["described"] = True
                resp = GuideResponse(goal=goal, action=GuideAction.ask_user(text), source="rule")
        if action.action == LlmKind.GUIDE and resp.action.type == ActionType.UNKNOWN:
            meta["rejected"] = True                    # LLM이 후보에 없는 버튼을 말함
        # 7) 이른 '끝' 막기: 고른 것이 없는데 DONE이면 끝내지 않고 다시 묻는다 (앱은 묻고 나서 바로 듣는다)
        if resp.action.type == ActionType.DONE and _premature_done(req, chosen):
            meta["rejected"] = True
            resp = GuideResponse(goal=goal, action=GuideAction.ask_user(MSG_NOT_DONE), source="rule")
        # 8) 고른 것 없이 결제로 가지 않기: 원하는 메뉴가 안 보이면 '모름' → 앱이 말하고 화면을 다시 보며 기다렸다가 다시 묻는다
        if resp.action.type == ActionType.TAP and _premature_checkout(req, goal, chosen, resp.action.guideText):
            meta["rejected"] = True
            resp = GuideResponse(goal=goal, action=GuideAction.unknown(MSG_NOT_SEEN), source="rule")
        meta["source"] = resp.source
        if resp.source == "llm":
            self.cache.put(key, resp)
        return self._finish(resp, meta, t0)

    @staticmethod
    def _elder_rule(req: GuideRequest, els: List[ScreenElement], prev_goal: str) -> Optional[GuideResponse]:
        """목표를 바꾸지 않고 바로 답하는 말들. 해당 없으면 None."""
        u = req.utterance
        if elder.is_help(u):
            return GuideResponse(goal=prev_goal, action=GuideAction.done(elder.MSG_HELP), source="rule")
        if elder.is_stop(u):
            return GuideResponse(goal=prev_goal, action=GuideAction.done(elder.MSG_STOP), source="rule")
        if elder.is_repeat(u):
            kind, el = elder.repeat_action(req.history, els)
            if kind == "tap" and el is not None:
                return GuideResponse(goal=prev_goal, action=GuideAction.tap(el.id, el.text), source="rule")
            question = kind[4:] if kind.startswith("ask:") else elder.MSG_REPEAT_FIRST
            return GuideResponse(goal=prev_goal, action=GuideAction.ask_user(question[:80]), source="rule")
        if elder.cash_unavailable(u, els):
            return GuideResponse(goal=prev_goal, action=GuideAction.ask_user(elder.MSG_CASH), source="rule")
        return None

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

    def _finish(self, resp: GuideResponse, meta: Dict[str, object], t0: float) -> GuideResult:
        meta["total_ms"] = round((time.perf_counter() - t0) * 1000, 1)
        if self.s.debug_prompts:
            a = resp.action
            log.info("KL_DEBUG → %s %r (source=%s, %sms)", a.type.value, a.guideText or a.question or a.reason,
                     meta.get("source"), meta["total_ms"])
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
