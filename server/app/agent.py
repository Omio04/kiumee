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
from . import elder, expectation, help_mode, hidden_button, kiosk, menu_info, progress, screen_guide, screen_type
from .screen_memory import ScreenMemory
from .llm import LlmClient, LlmRateLimited, LlmTimeout, LlmUpstream
from .models import (MAX_GOAL, MAX_HISTORY, ActionType, Box, ElementKind, GuideAction, GuideRequest, GuideResponse,
                     LlmKind, ScreenElement)
from .parser import ActionParser, ParseError
from .prompt import RETRY_NOTE, SYSTEM, build_user
from .ranker import JamoNgramRanker, answer_button, is_flow_button, same_item
from .settings import Settings
from .validator import _clean_message, MSG_ASK, MSG_DONE, MSG_NOT_DONE, MSG_NOT_SEEN, MSG_UNKNOWN, ActionValidator

log = logging.getLogger("kl")

MSG_LOYALTY_NO_CLOSE = "적립 화면이에요. 닫기(×)가 잘 안 보여요. 화면이 잘 보이게 비춰 주세요."

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


# 앱은 읽고 안내만 한다 (키오스크는 어르신이 손으로 누른다). 앱이 무언가 하고 있는 듯한 말은 어르신을 혼란스럽게 한다 (2026-11-13 실기기)
_WAITING_TALK = tuple(normalize(w) for w in ("바뀌는 중", "바뀌고 있", "바꾸는 중", "처리 중", "로딩", "기다려", "기다리", "기다릴"))


def _generic_unknown(resp: GuideResponse) -> bool:
    """LLM이 이유를 말하지 않은(또는 후보 밖 버튼을 말해 서버가 바꾼) 기본 문구의 UNKNOWN. 구체적인 대답은 해당 없음."""
    if resp.action.type != ActionType.UNKNOWN:
        return False
    reason = normalize(resp.action.reason or "")
    # "버튼을 찾지 못했어요. 화면이 잘 보이게 비춰 주세요" 같은 상투적인 말은 어르신 질문에 대한 대답이 아니다.
    # "화면에 캐모마일 메뉴가 보이지 않아요"처럼 무엇이 없는지 말한 대답은 남긴다.
    return not reason or any(normalize(w) in reason for w in ("버튼을 찾", "화면이 잘 보이게", "정면에서 비춰", "찾지 못했"))


def _photo_box(action, has_image: bool, candidates: dict) -> Optional[tuple]:
    """AI가 사진에서 직접 가리킨 칸: (Box, 칸 글자). 글자 목록(후보)에 없는 칸일 때만, 사진이 있고 박스가 말이 될 때만."""
    if not has_image:
        return None
    if action.targetId and action.targetId in candidates:
        return None                                                          # 후보에 있으면 글자 목록의 칸을 쓴다 (더 정확)
    b, text = action.box, (action.boxText or action.guideText or "").strip()
    if not b or len(b) != 4 or not text:
        return None
    top, left, bottom, right = (max(0, min(1000, int(v))) / 1000.0 for v in b)
    w, h = right - left, bottom - top
    if w < 0.02 or h < 0.01 or w * h > 0.35:                                 # 점 같거나 화면 반을 덮는 박스는 믿지 않는다
        return None
    return Box(left=left, top=top, right=right, bottom=bottom), text[:40]


def _waiting_talk_text(text: str) -> bool:
    return any(w in normalize(text) for w in _WAITING_TALK)


def _waiting_talk(resp: GuideResponse) -> bool:
    a = resp.action
    text = normalize(" ".join(t for t in (a.question, a.reason, a.guideText) if t))
    return any(w in text for w in _WAITING_TALK)


_APP_CLAIMS = tuple(normalize(w) for w in ("찾아볼게", "내려 찾", "내려볼게", "밀어볼게", "눌러드릴게", "눌러 드릴게", "넘어갈게"))


def _scrub_claims(resp: GuideResponse) -> GuideResponse:
    """UNKNOWN 문장에서 앱이 직접 하는 듯한 부분만 뺀다 ("…안 보여요. 아래로 더 내려 찾아볼게요" → "…안 보여요. 화면을 밀어서 찾아 보시겠어요?").
    구체적인 앞 문장(어르신 질문에 대한 대답)은 남긴다 (2026-11-13: 이걸 통째로 지워 질문에 답을 못 했다)."""
    a = resp.action
    if a.type != ActionType.UNKNOWN or not a.reason:
        return resp
    sentences = [s for s in re.split(r"(?<=[.?!])\s+", a.reason) if s.strip()]
    clean = [s for s in sentences if not any(w in normalize(s) for w in _APP_CLAIMS)]
    if len(clean) == len(sentences) or not clean:
        return resp
    text = (" ".join(clean) + " 화면을 밀어서 찾아 보시겠어요?")[:80]
    return GuideResponse(goal=resp.goal, action=GuideAction.unknown(text[:60]), source=resp.source)


MSG_STUCK = "같은 말씀을 계속 못 알아듣고 있어요. 직원에게 도움을 요청해 보세요."


def _repeat_guard(req: GuideRequest, resp: GuideResponse) -> GuideResponse:
    """같은 말을 또 하지 않는다 (2026-11-13: 어르신이 말해도 똑같은 안내만 되풀이했다).
    직전과 같은 질문이면 한 번은 다른 말로, 두 번째부터는 직원 도움을 안내하고 끝낸다."""
    a = resp.action
    if a.type != ActionType.ASK_USER or not req.history:
        return resp
    same = 0
    for h in reversed(req.history):
        if h.action == ActionType.ASK_USER and h.targetText == a.question:
            same += 1
        else:
            break
    if same == 0:
        return resp
    if same == 1 and req.utterance:
        return resp                                            # 새로 말씀하셨으면 질문을 그대로 둔다 ("아직 잘 모르겠어요"는 그 말을 무시한 것처럼 들린다)
    if same >= 3:                                              # 세 번 물어도 진전이 없으면 직원 도움 (음성 인식 오류가 한두 번 겹칠 수 있다)
        return GuideResponse(goal=resp.goal, action=GuideAction.done(MSG_STUCK), source="rule")
    return GuideResponse(goal=resp.goal, source="rule", action=GuideAction.ask_user(
        "아직 잘 모르겠어요. 하고 싶은 것을 짧게 한 번 더 말씀해 주세요."))


# 옵션 줄마다 반복되는 뜻 없는 버튼 글자: 빠른 길로 고르면 엉뚱한 줄의 버튼을 안내한다
_GENERIC_LABELS = frozenset(normalize(w) for w in ("선택", "추가", "선택안함", "+", "-"))


def _done_is_justified(req: GuideRequest, stype) -> bool:
    """안내를 끝내도 되는가: 결제 진행/완료 화면이거나, 이번 세션에서 결제 수단 버튼을 이미 눌렀다."""
    if stype in (screen_type.ScreenType.CARD, screen_type.ScreenType.COMPLETE):
        return True
    return any(h.action == ActionType.TAP and h.targetText and progress.stage_of(h.targetText) == progress.PAY for h in req.history)


def _change_line(change) -> Optional[str]:
    """LLM에 줄 한 줄: "메뉴를 고르는 화면 → 옵션을 고르는 화면 (새로 보임: HOT, ICE / 사라짐: 커피)". 변화가 없으면 None."""
    if change is None or not (change.type_changed or change.appeared):
        return None
    name = lambda t: screen_type.label(t) or "알 수 없는 화면"
    head = f"{name(change.from_type)} → {name(change.to_type)}"
    bits = []
    if change.appeared:
        bits.append("새로 보임: " + ", ".join(change.appeared))
    if change.vanished:
        bits.append("사라짐: " + ", ".join(change.vanished))
    return head + (f" ({' / '.join(bits)})" if bits else "")


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
        self.memory = ScreenMemory()                 # 직전 화면 (무엇에서 무엇으로 바뀌었는지)

    async def next(self, req: GuideRequest) -> GuideResult:
        t0 = time.perf_counter()
        meta: Dict[str, object] = {"llm_calls": 0, "retry": False, "rejected": False}

        req, dropped = privacy.sanitize(req)
        meta["dropped"] = dropped
        spoken = elder.clean_utterance(req.utterance)             # "어... 그... 아메리카노" → "아메리카노" (군말 제거)
        if spoken != req.utterance:
            req = req.model_copy(update={"utterance": spoken})
        resolved = elder.resolve_referent(req.utterance, req.history)   # 설명 뒤 "그걸로 할게요" → "카페라떼 주세요"
        if resolved:
            req = req.model_copy(update={"utterance": resolved})
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
        help_on = self.s.help_mode and bool(req.utterance)
        early = self._elder_rule(req, els, prev_goal, help_on)
        if early is not None:
            meta.update(source="rule", candidates=0, elder=True)
            return self._finish(early, meta, t0)

        # 2) 로컬 규칙: 결제 진행 화면
        if _is_done_screen(els) and not help_on:
            meta.update(source="rule", candidates=0)
            return self._finish(GuideResponse(goal=goal, action=GuideAction.done(MSG_DONE), source="rule"), meta, t0)  # 규칙은 이미 즉시라 캐시 불필요

        # 2-0) 화면 종류: 글자로 이 화면이 무슨 화면인지 먼저 정한다 (LLM 추측에 맡기지 않는다)
        stype = screen_type.classify(els)
        meta["screen"] = stype.value
        probs = screen_type.probabilities(els)                  # 이 종류일 확률 (글자 단서의 상대 비율)
        meta["screen_p"] = probs[0][1] if probs else 0.0
        kiosk_info = kiosk.identify(els)
        kiosk_hint = kiosk_info.text()                           # 참고만: 어떤 규칙도 가게 이름에 의존하지 않는다
        # 주문형 키오스크일 때만 쓰는 장치(진행 단계 · 눌렀는지 확인): 기차표 같은 다른 키오스크에서는 "다음 단계: 담기"처럼 틀린 말을 하게 된다
        ordering = screen_type.is_ordering(stype, kiosk_info.category)
        meta["ordering"] = ordering
        change = self.memory.observe(req.sessionId, req.snapshot.screenId, stype, els)
        if change is not None:
            meta["changed_from"] = change.from_type.value
        if help_on:                                              # 도움 모드: 질문에 그 순간의 화면을 보고 답한다
            return await self._answer_question(req, els, goal, prev_goal, stype, probs, kiosk_hint, change, meta, t0)
        if stype == screen_type.ScreenType.LOYALTY and "적립" not in (req.utterance or ""):
            # 적립은 건너뛴다 (P2 인수 문서: 어르신이 요청하지 않았으면). 실제로 보이는 닫기(×)가 있을 때만 안내하고,
            # 없으면 지어내지 않고 다시 비춰 달라고 한다.
            close = screen_type.skip_button(els)
            meta.update(source="rule", candidates=0)
            if close is not None:
                return self._finish(GuideResponse(goal=goal, action=GuideAction.tap(close.id, close.text), source="rule"), meta, t0)
            return self._finish(GuideResponse(goal=goal, action=GuideAction.unknown(MSG_LOYALTY_NO_CLOSE), source="rule"), meta, t0)

        # 2-0b) 하려는 일의 버튼(담기 · 결제하기)이 화면에 아예 안 보이면 지어내지 않고 카메라를 조정하게 한다
        hidden = hidden_button.missing_button(req.utterance, els) if stype not in (screen_type.ScreenType.LOYALTY,) else None
        if hidden:
            meta.update(source="rule", candidates=0, hidden=hidden)
            return self._finish(GuideResponse(goal=goal, action=GuideAction.unknown(hidden_button.message(hidden)), source="rule"), meta, t0)

        # 2-1) 질문: "라떼가 뭐야?" → 사전에 있으면 LLM 없이 설명 (goal은 그대로: 질문이 목표에 섞이면 안 된다)
        asking = menu_info.is_info_question(req.utterance)
        if asking:
            text = menu_info.explain(req.utterance or "", req.history)
            if text:
                meta.update(source="rule", candidates=0, explained=True)
                return self._finish(GuideResponse(goal=prev_goal, action=GuideAction.ask_user(text), source="rule"), meta, t0)
        # 2-2) "뭘 눌러야 돼요?" → 지금 화면에서 고를 수 있는 것을 알려 준다
        if menu_info.is_screen_question(req.utterance):
            text = screen_guide.describe(els, changed=False, label=screen_type.label(stype), change=change)
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
        if self.s.fastpath and goal and not asking and answer_button(req.utterance) is None and stype in screen_type.FASTPATH_TYPES:
            top = ranked[0]
            second = ranked[1].match if len(ranked) > 1 else 0.0
            repeat = any(same_item(top.element.text, c) for c in chosen)
            # 방금 누른 옵션(HOT · ICE …)은 '고른 메뉴'에 안 들어가서(진행 · 옵션 버튼 제외) 선택이 바뀐 새 화면에서도
            # 같은 말("따뜻한 걸로")에 또 HOT을 고르는 무한 반복이 생겼다 (2026-11-13 시뮬레이션). 방금 누른 버튼이면 다음 단계는 LLM이.
            repeat = repeat or (pressed is not None and same_item(top.element.text, pressed))
            # 같은 글자의 버튼이 여럿이면(옵션 줄마다 "선택" · "추가") 어느 것인지 서버가 정할 수 없다 (2026-11-13 실기기: "결제할래"에 "선택").
            # 이번 세션에서 이미 안내한 버튼은 빠른 길로 다시 고르지 않는다: 목표에 "따뜻한 걸로"가 남아 있어서, 샷 추가를 묻는
            # 질문에 "아니요 괜찮아요"라고 해도 HOT을 또 안내하던 반복 (2026-11-13 시뮬레이션 15). 다음 단계는 LLM이 이력을 보고 정한다.
            repeat = repeat or any(h.action == ActionType.TAP and h.targetText and same_item(top.element.text, h.targetText)
                                   for h in req.history)
            same_text = sum(1 for r in ranked if normalize(r.element.text) == normalize(top.element.text))
            repeat = repeat or same_text > 1 or normalize(top.element.text) in _GENERIC_LABELS
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
        unseen = expectation.pressed_but_not_visible(req) if (self.s.expectation_check and ordering) else None
        lookalike = expectation.lookalike_menu(req) if (self.s.expectation_check and ordering) else None
        if lookalike:
            meta["lookalike"] = True
        if unseen:
            meta["unseen"] = True
        user = build_user(goal, req.history, candidates, context, pressed=pressed, chosen=chosen,
                          question=asking, new_screen=new_screen,
                          progress=progress.summarize(req.history) if (self.s.progress_hint and ordering) else None,
                          unseen=unseen, screen=screen_type.describe_probabilities(probs) or screen_type.label(stype),
                          kiosk=kiosk_hint, change=_change_line(change), lookalike=lookalike)

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
        elif (resp.action.type == ActionType.WAIT or _waiting_talk(resp) or _generic_unknown(resp)) and not asking:
            # 새 화면인데 고를 버튼을 못 정함: "못 찾았어요"로 끝내지 않고 고를 수 있는 것을 알려 준다
            text = screen_guide.describe(els, changed=new_screen, label=screen_type.label(stype), change=change)
            if text:
                meta["described"] = True
                resp = GuideResponse(goal=goal, action=GuideAction.ask_user(text), source="rule")
            elif resp.action.type == ActionType.WAIT or _waiting_talk(resp):    # 설명할 버튼도 없으면 기다리라 하지 않고 다시 비춰 달라고
                resp = GuideResponse(goal=goal, action=GuideAction.unknown(MSG_UNKNOWN), source="fallback")
        if action.action == LlmKind.GUIDE and resp.action.type == ActionType.UNKNOWN:
            meta["rejected"] = True                    # LLM이 후보에 없는 버튼을 말함
        # 6-1) 종료(DONE)는 끝났다는 증거가 있을 때만: 카드 결제/완료 화면이거나 결제 수단을 이미 눌렀을 때.
        #      증거 없이 AI가 DONE이라 하면(기차표 시뮬레이션 T2: 도착역을 고르자마자 "다음 화면에서 안내를 따라 주세요") 끝내지 않고 화면을 설명한다.
        if (resp.action.type == ActionType.DONE and action.action == LlmKind.DONE and not _done_is_justified(req, stype)
                and not _premature_done(req, chosen)):      # 아무것도 안 고른 경우는 아래 7)의 기존 문구("잘 못 알아들었어요")
            meta["rejected"] = True
            text = screen_guide.describe(els, changed=new_screen, label=screen_type.label(stype), change=change)
            resp = GuideResponse(goal=goal, action=GuideAction.ask_user(text or MSG_ASK), source="rule")
        # 7) 이른 '끝' 막기: 고른 것이 없는데 DONE이면 끝내지 않고 다시 묻는다 (앱은 묻고 나서 바로 듣는다)
        if resp.action.type == ActionType.DONE and _premature_done(req, chosen):
            meta["rejected"] = True
            resp = GuideResponse(goal=goal, action=GuideAction.ask_user(MSG_NOT_DONE), source="rule")
        # 8) 고른 것 없이 결제로 가지 않기: 원하는 메뉴가 안 보이면 '모름' → 앱이 말하고 화면을 다시 보며 기다렸다가 다시 묻는다
        if resp.action.type == ActionType.TAP and _premature_checkout(req, goal, chosen, resp.action.guideText):
            meta["rejected"] = True
            resp = GuideResponse(goal=goal, action=GuideAction.unknown(MSG_NOT_SEEN), source="rule")
        resp = _repeat_guard(req, _scrub_claims(resp))
        meta["source"] = resp.source
        if resp.source == "llm":
            self.cache.put(key, resp)
        return self._finish(resp, meta, t0)

    @staticmethod
    def _elder_rule(req: GuideRequest, els: List[ScreenElement], prev_goal: str, help_on: bool = False) -> Optional[GuideResponse]:
        """목표를 바꾸지 않고 바로 답하는 말들. 해당 없으면 None."""
        u = req.utterance
        if help_on and help_mode.is_decline(u, req.history):
            return GuideResponse(goal=prev_goal, action=GuideAction.done(help_mode.MSG_BYE), source="rule")
        if help_on and u and "직원" not in u and elder.is_help(u):
            return None                                          # "도와줘요" · "어떻게 해야 돼"는 끝내는 말이 아니라 질문이다
        if elder.is_help(u):
            return GuideResponse(goal=prev_goal, action=GuideAction.done(elder.MSG_HELP), source="rule")
        if elder.is_stop(u):
            return GuideResponse(goal=prev_goal, action=GuideAction.done(elder.MSG_STOP), source="rule")
        if elder.is_preface(u):
            return GuideResponse(goal=prev_goal, action=GuideAction.ask_user(elder.MSG_LISTENING), source="rule")
        if elder.is_repeat(u):
            kind, el = elder.repeat_action(req.history, els)
            if kind == "tap" and el is not None:
                return GuideResponse(goal=prev_goal, action=GuideAction.tap(el.id, el.text), source="rule")
            question = kind[4:] if kind.startswith("ask:") else elder.MSG_REPEAT_FIRST
            return GuideResponse(goal=prev_goal, action=GuideAction.ask_user(question[:80]), source="rule")
        if elder.cash_unavailable(u, els):
            return GuideResponse(goal=prev_goal, action=GuideAction.ask_user(elder.MSG_CASH), source="rule")
        return None

    async def _answer_question(self, req, els, goal, prev_goal, stype, probs, kiosk_hint, change, meta, t0):
        """도움 모드: 어르신 말을 질문으로 보고, 그 순간의 화면 전체를 보여 주고 AI가 답하게 한다. 답 뒤에 "또 도움이 필요하세요?"를 붙인다."""
        candidates = help_mode.candidates_for_help(els)
        by_id = {c.id: c for c in candidates}
        user = help_mode.build_user(req.utterance or "", req.history, els, candidates,
                                    screen=screen_type.describe_probabilities(probs) or screen_type.label(stype),
                                    kiosk=kiosk_hint, change=_change_line(change))
        meta["help"] = True
        meta["candidates"] = len(candidates)
        if self.s.debug_prompts:
            log.info("KL_DEBUG 사진=%s", f"{len(req.image) // 1024}KB" if req.image else "없음")        # 크기만 (내용은 남기지 않는다)
        action = None
        deadline = t0 + self.s.total_budget_s
        for attempt in range(2):
            remaining = deadline - time.perf_counter()
            if attempt > 0 and remaining < self.s.min_retry_s:
                break
            raw = await self._complete(user if attempt == 0 else user + RETRY_NOTE, max(0.5, min(self.s.llm_timeout_s, remaining)),
                                       list(by_id), meta, system=help_mode.HELP_SYSTEM, image=req.image)
            meta["llm_calls"] = int(meta["llm_calls"]) + 1
            if self.s.debug_prompts:
                log.info("KL_DEBUG 도움 원답=%s", raw)
            try:
                action = self.parser.parse(raw)
                break
            except ParseError:
                meta["retry"] = True
        label = screen_type.label(stype)
        fallback = f"{label}이에요. 어떤 점이 궁금하세요?" if label else "무엇이 궁금하세요?"
        if action is None:
            meta["source"] = "fallback"
            return self._finish(GuideResponse(goal=prev_goal, action=GuideAction.ask_user(fallback), source="fallback"), meta, t0)
        photo = _photo_box(action, bool(req.image), by_id) if action.action == LlmKind.GUIDE else None
        if photo is not None:                                                # 글자 목록에 없지만 사진에서 찾은 칸: 박스로 가리킨다
            box, name = photo
            say = _clean_message(action.message, "", 80)
            if say and (_waiting_talk_text(say) or any(w in normalize(say) for w in _APP_CLAIMS)):
                say = ""
            resp = GuideResponse(goal=prev_goal, action=GuideAction.tap("photo", name), say=say or None, box=box, source="help")
            meta["source"] = "help"
            meta["photo_box"] = True
            return self._finish(resp, meta, t0)
        resp = self.validator.validate(action, by_id, goal=prev_goal)       # 후보 밖 버튼은 안내하지 않는다 (화이트리스트)
        a = resp.action
        say = ""
        if a.type == ActionType.TAP:                                          # 버튼을 가리킨다 + 앞에 읽을 이유 · 방법 한 문장(say)
            say = _clean_message(action.message, "", 80)
            if say and (_waiting_talk_text(say) or any(w in normalize(say) for w in _APP_CLAIMS)):
                say = ""                                                      # 앱이 뭔가 하는 듯한 말은 읽지 않는다
        if a.type == ActionType.TAP:
            resp = resp.model_copy(update={"say": say or None})
        elif a.type == ActionType.DONE:
            resp = GuideResponse(goal=prev_goal, action=GuideAction.done(help_mode.MSG_BYE), source=resp.source)
        else:                                                                 # 대답(ASK_USER · EXPLAIN · UNKNOWN · SCROLL) → 말하고 계속 듣는다
            text = a.question or a.reason or fallback
            if a.type == ActionType.SCROLL and not (a.question or a.reason):    # 버튼 이름만 읽으면 알아들을 수 없다 — 문장으로 만든다 (2026-11-13 실기기)
                way = {"UP": "위로", "DOWN": "아래로"}.get(str(getattr(a.direction, "value", a.direction) or "").upper(), "")
                name = (a.guideText or "").strip()
                text = f"화면을 {way + ' ' if way else ''}밀어서 '{name}' 칸을 찾아 보세요." if name else fallback
            if _waiting_talk(resp) or any(w in normalize(text) for w in _APP_CLAIMS):
                text = fallback                                               # 앱이 뭔가 하는 듯한 말은 하지 않는다
            resp = GuideResponse(goal=prev_goal, action=GuideAction.ask_user(help_mode.with_follow_up(text)), source=resp.source)
        meta["source"] = "help"
        return self._finish(resp, meta, t0)

    async def _complete(self, user: str, timeout: float, ids: List[str], meta: Dict[str, object], system: str = SYSTEM, image: Optional[str] = None) -> str:
        """LLM 한 번 부르기 (버튼 번호는 후보 안에서만 고르게 형식 강제).

        겹쳐 보내기: hedge_after_s 안에 답이 없거나 그 전에 일시 오류(혼잡 503 · 시간 초과 등)로 실패하면
        같은 요청을 하나 더 보내고 먼저 온 답을 쓴다. Gemini 무료 한도는 보통 1초인데 가끔 4초를 넘겨서
        서버 시간 초과 → 앱 오류가 잦았다 (2026-11-12 평가: LLM 판단 12건 중 5건 시간 초과).
        사용량 제한(429) · 인증 오류는 다시 보내지 않는다."""
        call = lambda t: asyncio.ensure_future(self.llm.complete(system, user, t, target_ids=ids, **({"image": image} if image else {})))
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
