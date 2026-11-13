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

from . import lexicon, som, privacy
from .cache import ResponseCache, request_key
from .jamo import dice, normalize
from . import elder, expectation, help_mode, hidden_button, kiosk, menu_info, progress, screen_guide, screen_type
from .screen_memory import ScreenMemory
from .kiosk_flow import kiosk_action
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


_AFTER_WORDS = ("그다음", "그 다음", "뒤에", "후에", "준비되면", "끝나면", "고르면", "넣으면", "대면", "한 뒤", "하신 뒤", "한 후", "한 다음", "마치면")   # 지금이 아니라 나중에 누르는 칸
_PRESS_RE = re.compile(r"눌러|누르|누를|터치|선택해")
_FUZZY_MIN = 0.65            # 깨진 글자(주문담기 → 주문달기 0.71)도 맞추되, 다른 칸(0.1 이하)은 맞추지 않는다
_FUZZY_MARGIN = 0.15         # 두 번째로 비슷한 칸과 이만큼 차이 나야 믿는다


_LATER_RE = re.compile(r"(?:신|은|한|른|간|난)\s?(?:뒤|후)|다음에|누르면\s*안|누르지\s*마|안\s*돼|안\s*됩니다")


def _is_later_or_negated(sentence: str) -> bool:
    """지금 누를 칸이 아니다: "다 고르면 · 고르신 뒤에 · 준비되면"(나중) 또는 "누르면 안 돼요"(부정)."""
    return any(w in sentence for w in _AFTER_WORDS) or bool(_LATER_RE.search(sentence))


_POINT_WORDS = re.compile(r"눌러|누르|누를|터치|탭|칸|버튼|메뉴")


def _spoken_targets(message: str) -> List[str]:
    """AI의 말에서 따옴표로 가리킨 버튼 이름들. 버튼을 가리키는 문장(누르 · 탭 · 칸 · 버튼 · 메뉴)에 있는 것만:
    "화면에 '모두 선택해주세요'라고 나와요"처럼 화면 문구를 옮긴 것은 버튼이 아니다."""
    out: List[str] = []
    for sent in re.split(r"(?<=[.!?])\s+", message or ""):
        if _POINT_WORDS.search(sent):
            out += [q.strip() for q in re.findall(r"['\"‘“]([^'\"’”]{2,20})['\"’”]", sent) if q.strip()]
    return out


def _press_sentence(message: str) -> Optional[str]:
    for sent in re.split(r"(?<=[.!?])\s+", message or ""):
        if _PRESS_RE.search(sent):
            return sent
    return None


def _named_phrases(sentence: str) -> List[str]:
    """누르라는 문장에서 버튼 이름 후보: 따옴표로 묶은 것, "<이름>을/를 눌러"의 이름."""
    quoted = re.findall(r"['\"‘“]([^'\"’”]{2,20})['\"’”]", sentence)
    before_verb = re.findall(r"([가-힣A-Za-z0-9]{2,12})\s*(?:버튼|칸|메뉴)?\s*[을를]?\s*(?:눌러|누르|누를|터치)", sentence)
    return [q for q in quoted + before_verb if q.strip()]


def _fuzzy_named_candidate(sentence: str, candidates: dict):
    """AI가 사진에서 읽은 정확한 이름("주문담기")과 글자 읽기가 깨뜨린 후보("주문달기")를 자모 유사도로 맞춘다."""
    scored = {}
    for ph in _named_phrases(sentence):
        np_ = normalize(ph)
        if len(np_) < 2:
            continue
        for c in candidates.values():
            nt = normalize(c.text)
            if len(nt) < 2:
                continue
            sc = 1.0 if np_ == nt else dice(np_, nt)
            if sc > scored.get(c.id, (0.0, None))[0]:
                scored[c.id] = (sc, c)
    if not scored:
        return None
    ranked = sorted(scored.values(), key=lambda x: -x[0])
    best = ranked[0]
    second = ranked[1][0] if len(ranked) > 1 else 0.0
    return best[1] if best[0] >= _FUZZY_MIN and best[0] - second >= _FUZZY_MARGIN else None


def _pressed_candidate(message: str, candidates: dict):
    """AI가 말로 "~을 누르세요"라고 했는데 칸을 안 가리킨 경우, 그 칸을 찾는다 (말과 표시를 맞춘다).
    누르라는 문장에 "고르면 · 준비되면 · ~한 뒤" 같은 나중 말이 있으면(= 지금 누를 칸이 아니면) 하지 않는다.
    칸을 찾는 순서: ① 글자가 정확히 "<글자>을 누르" ② 깨진 글자까지 자모 유사도로 ③ 누르라는 말 바로 앞에서 가장 가까이 말한 칸."""
    m = (message or "")
    sent = _press_sentence(m)
    if not m or sent is None or _is_later_or_negated(sent):
        return None
    flat = m.replace(" ", "")
    hits = [c for c in candidates.values()
            if c.text.strip() and re.search(re.escape(c.text.strip().replace(" ", "")) + r"['\"‘’“”]?[을를이가는은]?(?:버튼|칸|메뉴)?[을를]?(?:눌|누르|터치)", flat)]
    if len(hits) == 1:
        return hits[0]
    if hits:
        return None                                                       # 둘 이상이 직접 눌러라는 말을 받았다 → 모호
    fuzzy = _fuzzy_named_candidate(sent, candidates)
    if fuzzy is not None:
        return fuzzy
    # "'푸드' 탭에 있을 것 같아요. 그곳을 눌러 볼까요?"처럼 칸 이름과 "누르" 사이에 다른 말이 있는 경우:
    # 누르라는 말 바로 앞에서 가장 가까이 말한 칸(2글자 이상)을 가리킨다. 40자보다 멀면 믿지 않는다.
    verb = _PRESS_RE.search(m)
    if not verb:
        return None
    best, best_end = None, -1
    for c in candidates.values():
        t = c.text.strip()
        if len(t) < 2:
            continue
        for mt in re.finditer(re.escape(t), m[:verb.start()]):
            if mt.end() > best_end:
                best, best_end = c, mt.end()
    return best if best is not None and verb.start() - best_end <= 40 else None


_NEW_KIOSK_OVERLAP = 0.12     # 직전 화면과 글자 겹침이 이보다 낮으면 다른 키오스크로 본다 (같은 키오스크 안 탭 이동은 머리글 · 메뉴 글자가 남아 높다)
_MIN_LABELS = 4               # 비교할 글자가 이보다 적으면(글자 읽기가 거의 안 된 화면) 판단하지 않는다
_PRESS_SENTENCE = re.compile(r"눌러|누르|누를|터치")
_REPEAT_NOTE = ("\n[주의] 어르신이 방금 안내받은 '{t}' 칸은 이미 눌렀을 가능성이 큽니다(화면이 바뀌었을 수 있음). "
                "같은 칸을 또 가리키지 마세요. 사진과 글자를 다시 보고 **지금 화면에서** 어르신 질문에 맞는 다음 칸을 고르세요.")


def _repeated_target(action, candidates: dict, history) -> Optional[str]:
    """방금 안내한 칸(최근 두 이력 안의 마지막 TAP)을 또 가리키려는가. 그렇다면 그 칸의 글자를 돌려준다 (어르신이 이미 눌렀을 가능성)."""
    if action.action != LlmKind.GUIDE or not action.targetId or action.targetId not in candidates:
        return None
    cur = candidates[action.targetId].text.replace(" ", "").strip().lower()
    for h in reversed(list(history)[-2:]):
        if h.action == ActionType.TAP and (h.targetText or "").replace(" ", "").strip().lower() == cur and cur:
            return candidates[action.targetId].text.strip()
    return None


def _reason_only(say: str) -> str:
    """앱이 "노란색 칸을 눌러 주세요. 적힌 글자는 ○○"를 따로 읽으므로, AI가 한 말에서 "~을 눌러 주세요" 문장은 뺀다 (같은 지시를 두 번 읽지 않게)."""
    parts = re.split(r"(?<=[.!?])\s+", (say or "").strip())
    kept = [p for p in parts if p and not _PRESS_SENTENCE.search(p)]
    return " ".join(kept).strip()


_NOT_A_BUTTON = {"화면", "버튼", "아래", "위쪽", "아래쪽", "오른쪽", "왼쪽", "하단", "상단", "그곳", "여기", "이것", "그것"}


MISSING_ID = "MISSING"       # 도움 모드: 누를 칸이 글자 목록에 없을 때 AI가 고르는 값 (형식이 목록 안 번호만 허용하면 아무 칸이나 억지로 고른다)
_UNDO_BUTTONS = {"처음으로", "처음", "초기화", "취소", "전체취소", "주문취소", "삭제", "전체삭제", "이전", "뒤로", "뒤로가기", "홈"}
_UNDO_INTENT = re.compile(r"취소|처음|다시|잘못|빼|지우|초기|돌아|그만|이전|뒤로|삭제|안 할|안할|없던")
_CONDITIONAL_PICK = re.compile(r"(?:이|라)면\s*(?:노란|이\s*칸|그\s*칸|여기)")
_NAME_AGREE = 0.5            # AI가 쓴 버튼 이름과 고른 칸의 글자가 이보다 덜 비슷하면 고른 칸을 믿지 않는다 (깨진 글자 주문담기↔주문달기 0.71은 통과)


def _clean_name(text: str) -> str:
    """버튼 이름에서 가격(4,500원)을 뗀다: 글자 읽기는 "결제하기"와 "4,500원"을 따로 읽기도 한다."""
    return re.sub(r"[\d,\.]+\s*원?", "", text or "").strip()


def _name_score(name: str, text: str) -> float:
    """이름과 칸 글자가 얼마나 같은가 (앱 NameMatcher.score 와 같은 규칙): 같음 1.0 · 품음 0.95 · 그 밖은 자모 유사도."""
    n, t = normalize(_clean_name(name)), normalize(text)
    if n and n == t:
        return 1.0                                      # 한 글자 버튼(티 · 예 · 네)도 같으면 같은 칸이다 (2026-11-13: '티'를 0점으로 버렸다)
    if len(n) < 2 or len(t) < 2:
        return 0.0
    if len(n) >= 3 and n in t:
        return 0.95
    return dice(n, t)


def _seek_name(sentence: str) -> Optional[str]:
    """누르라는 문장에서 찾는 버튼 이름 하나. 가격(2,000원)은 뗀다: 글자 읽기는 "결제하기"와 "2,000원"을 따로 읽기도 한다."""
    for ph in _named_phrases(sentence):
        name = _clean_name(ph)
        if len(normalize(name)) >= 2 and name not in _NOT_A_BUTTON:
            return name[:30]
    return None


def _seek_text(text: str, name: str) -> str:
    """누르라는 문장은 빼고(칠 칸이 없으니 "누르세요"라고 말하지 않는다), 그 칸이 안 읽힌다는 말로 바꾼다. 80자 안.
    앞 문장은 온전한 문장만 남긴다(중간에 자르지 않는다). 묻는 문장은 뺀다: 뒤에 "비춰 주세요"가 오면 어색하다."""
    tail = f"'{name}' 칸이 아직 안 읽혀요. 크게 나오게 비춰 주세요. 보이면 노란색으로 알려 드릴게요."
    head = ""
    for p in re.split(r"(?<=[.!?])\s+", (text or "").strip()):
        if not p or _PRESS_SENTENCE.search(p) or p.rstrip().endswith("?"):
            continue
        if len(head) + len(p) + len(tail) + 2 > 80:
            break
        head = (head + " " + p).strip()
    return (head + " " + tail).strip()


def _photo_box(action, has_image: bool, candidates: dict, size: Optional[tuple] = None) -> Optional[tuple]:
    """AI가 사진에서 직접 가리킨 칸: (Box, 칸 글자). 글자 목록(후보)에 없는 칸일 때만, 사진이 있고 박스가 말이 될 때만.
    좌표: 사진 크기(size)를 알면 픽셀 [왼쪽, 위, 오른쪽, 아래] (질문에 크기를 적어 준다). 모르면 예전 형식 0~1000 [위, 왼쪽, 아래, 오른쪽]."""
    if not has_image:
        return None
    if action.targetId and action.targetId in candidates:
        return None                                                          # 후보에 있으면 글자 목록의 칸을 쓴다 (더 정확)
    text = (action.boxText or action.guideText or "").strip()
    box = _box_from(action.box, size) if text else None
    return (box, text[:40]) if box is not None else None


def _box_from(b, size: Optional[tuple] = None) -> Optional[Box]:
    """AI가 사진에서 준 좌표 → 0~1 칸. 사진 크기(size)를 알면 픽셀 [왼쪽, 위, 오른쪽, 아래], 모르면 예전 0~1000 [위, 왼쪽, 아래, 오른쪽].
    점 같거나 화면 반을 덮는 박스는 믿지 않는다 (None)."""
    if not b or len(b) != 4:
        return None
    try:
        vals = [int(v) for v in b]
    except (TypeError, ValueError):
        return None
    if size:
        w0, h0 = size
        l, t, r, bt = vals
        left, right = sorted((max(0.0, min(1.0, l / w0)), max(0.0, min(1.0, r / w0))))
        top, bottom = sorted((max(0.0, min(1.0, t / h0)), max(0.0, min(1.0, bt / h0))))
    else:
        top, left, bottom, right = (max(0, min(1000, v)) / 1000.0 for v in vals)
    w, h = right - left, bottom - top
    if w < 0.02 or h < 0.01 or w * h > 0.35:
        return None
    return Box(left=left, top=top, right=right, bottom=bottom)


_BUTTON_BOX_SLACK = 0.01     # 글자 칸 가운데가 버튼 상자 밖으로 이만큼까지는 봐준다 (0~1)


def _button_box(action, el: Optional[ScreenElement], size: Optional[tuple]) -> Optional[Box]:
    """노란 칸을 버튼 전체에 그리기 (2026-11-13 요청: "버튼을 다 감싸듯이"). 글자 읽기는 글자 상자만 주므로,
    AI가 사진에서 본 그 버튼의 전체 상자를 같이 보낸다. 단, 상자가 그 버튼 글자 칸의 가운데를 품을 때만:
    사진과 글자 읽기는 다른 순간이라 휴대폰이 움직였으면 어긋나고, AI 상자가 빗나갈 수도 있다 (웹캠 사진 평가).
    글자 칸까지 품게 넓혀서 돌려준다. 앱은 이 칸을 글자 칸에 붙여 따라간다 (domain HighlightArea.follow)."""
    box = _box_from(action.box, size)
    if box is None or el is None:
        return None
    r, s = el.rect, _BUTTON_BOX_SLACK
    cx, cy = (r.left + r.right) / 2, (r.top + r.bottom) / 2
    if not (box.left - s <= cx <= box.right + s and box.top - s <= cy <= box.bottom + s):
        return None
    return Box(left=min(box.left, r.left), top=min(box.top, r.top), right=max(box.right, r.right), bottom=max(box.bottom, r.bottom))


def _photo_pick(action, by_id: dict, els, size: Optional[tuple], meta: Dict[str, object]):
    """사진을 볼 때 AI가 고른 칸을 AI가 말한 버튼 이름으로 맞춘다 (기본 안내, 2026-11-13). (고친 action, 새로 찾은 글자 칸 또는 None)
    ① 고른 칸의 글자가 말한 이름과 다르면 칸을 버린다: 결제하기라고 말하면서 번호는 '전체'를 고르는 일 (도움 모드와 같은 규칙)
    ② 칸이 없으면(MISSING · 버림) 글자 읽기 전체에서 그 이름의 칸을 다시 찾는다: 후보 30개 밖이거나 깨진 글자(결재하기)여도
       글자 칸이 사진 박스보다 정확하다. 같은 이름이 여럿이면 AI 박스와 가장 가까운 칸, 박스도 없으면 고르지 않는다."""
    named = _clean_name(action.guideText or action.boxText or "")
    chosen = by_id.get(action.targetId or "")
    if chosen is not None:
        if not named or _name_score(named, chosen.text) >= _NAME_AGREE:
            return action, None
        meta["target_mismatch"] = True
        action = action.model_copy(update={"targetId": None})
    if len(normalize(named)) < 1:
        return action, None
    # 가격 · 긴 안내 문장("결제하기 전에 주문 내역을 확인하세요")은 버튼이 아니다
    pool = [e for e in els if e.kind != ElementKind.PRICE and len(normalize(e.text)) <= 2 * len(normalize(named)) + 4]
    scored = [(e, _name_score(named, e.text)) for e in pool]
    top = max((s for _, s in scored), default=0.0)
    if top < 0.65:
        return action, None
    same = [e for e, s in scored if s == top]
    if len(same) > 1:
        box = _photo_box(action, True, by_id, size=size)
        if box is None:
            return action, None
        cx, cy = (box[0].left + box[0].right) / 2, (box[0].top + box[0].bottom) / 2
        same.sort(key=lambda e: abs((e.rect.left + e.rect.right) / 2 - cx) + abs((e.rect.top + e.rect.bottom) / 2 - cy))
    meta["name_found"] = True
    return action.model_copy(update={"targetId": same[0].id}), same[0]      # 이름은 AI가 사진에서 읽은 그대로 둔다 (_photo_name)


def _photo_name(said: str, text: str, els) -> Optional[str]:
    """글자 읽기가 한두 글자 틀린 버튼 이름(가드결제 · 주문달기)은 AI가 사진에서 읽은 이름(카드결제 · 주문담기)으로 말한다.
    좁게만: 4글자 이상 · 길이가 같고 다른 글자가 4글자에 1개 이하 · 그 이름의 버튼이 화면에 따로 없을 때.
    '매장하기'를 고르고 '포장하기'라고 말하면 다른 버튼일 수 있다(둘 다 화면에 있음) → 바꾸지 않는다."""
    name = _clean_name(said)
    a, b = re.sub(r"\s+", "", name), re.sub(r"\s+", "", text or "")
    if len(a) < 4 or a == b or len(a) != len(b) or sum(x != y for x, y in zip(a, b)) > len(a) // 4:
        return None
    if any(normalize(e.text) == normalize(name) for e in els):
        return None
    return name[:40]


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


# 사진을 AI에 보내지 않는 화면: 전화번호를 누르는 적립 화면 · 카드번호가 뜨는 카드 결제 화면
_PRIVATE_SCREENS = (screen_type.ScreenType.LOYALTY, screen_type.ScreenType.CARD)


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
                     [e.text for e in sorted(els, key=lambda e: (round(e.rect.top, 2), e.rect.left))][:80])   # 잘라 보면 아래쪽 버튼이 안 읽힌 것처럼 보인다 (2026-11-13 오진)

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

        if not help_on:                                            # 연습 키오스크 주문 흐름 규칙(main): 도움 모드는 AI가 그 순간의 화면을 보고 답한다
            practice_action = kiosk_action(req, goal)
            if practice_action is not None:
                meta.update(source="rule", candidates=0)
                return self._finish(GuideResponse(goal=goal, action=practice_action, source="rule"), meta, t0)

        # 2) 로컬 규칙: 결제 진행 화면
        if _is_done_screen(els) and not help_on:
            meta.update(source="rule", candidates=0)
            return self._finish(GuideResponse(goal=goal, action=GuideAction.done(MSG_DONE), source="rule"), meta, t0)  # 규칙은 이미 즉시라 캐시 불필요

        # 2-0) 화면 종류: 글자로 이 화면이 무슨 화면인지 먼저 정한다 (LLM 추측에 맡기지 않는다)
        stype = screen_type.classify(els)
        meta["screen"] = stype.value
        if req.image and stype in _PRIVATE_SCREENS:
            # 적립(전화번호 입력) · 카드 결제 화면의 사진에는 번호가 찍힐 수 있다. 앱은 그 글자를 먼저 지워 보내서(1차 방어선)
            # 위 privacy는 지운 글자가 없다고 보고 사진을 넘긴다 → 화면 종류로 사진을 버린다 (도움 모드 · 기본 안내 모두, 2026-11-13)
            req = req.model_copy(update={"image": None})
            meta["photo_dropped"] = stype.value
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
            if change is not None and change.compared >= _MIN_LABELS and change.overlap < _NEW_KIOSK_OVERLAP and req.history:
                # 직전 화면과 글자가 거의 안 겹친다 = 다른 키오스크로 옮겨 갔다 → 옛 대화(좌석 · 푸드 …)가 답을 끌고 가지 않게 비운다 (2026-11-13 실기기)
                req = req.model_copy(update={"history": []})
                meta["history_reset"] = True
            return await self._answer_question(req, els, goal, prev_goal, stype, probs, kiosk_hint, change, meta, t0)
        if stype == screen_type.ScreenType.LOYALTY and "적립" not in (req.utterance or ""):
            # 적립은 건너뛴다 (P2 인수 문서: 어르신이 요청하지 않았으면). 실제로 보이는 닫기(×)가 있을 때만 안내하고,
            # 없으면 지어내지 않고 다시 비춰 달라고 한다.
            close = screen_type.skip_button(els)
            meta.update(source="rule", candidates=0)
            if close is not None:
                return self._finish(GuideResponse(goal=goal, action=GuideAction.tap(close.id, close.text), source="rule"), meta, t0)
            return self._finish(GuideResponse(goal=goal, action=GuideAction.unknown(MSG_LOYALTY_NO_CLOSE), source="rule"), meta, t0)

        # 사진을 보고 판단하는가 (기본 안내, 사진이 붙은 요청만). 개인정보 글자를 지웠으면 privacy가 사진도 버려서 False
        use_photo = self.s.photo_guide and bool(req.image)
        photo_size = som.image_size(req.image) if use_photo else None      # 질문에 적고, 박스를 픽셀로 받는다
        meta["photo"] = use_photo
        # 2-0b) 하려는 일의 버튼(담기 · 결제하기)이 화면에 아예 안 보이면 지어내지 않고 카메라를 조정하게 한다.
        #       사진이 있으면 건너뛴다: 글자 읽기가 놓친 버튼도 AI가 사진에서 본다
        hidden = (hidden_button.missing_button(req.utterance, els)
                  if stype not in (screen_type.ScreenType.LOYALTY,) and not use_photo else None)
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
        if not ranked and not use_photo:                             # 사진이 있으면 글자를 하나도 못 읽어도 AI가 사진에서 찾는다
            meta["source"] = "fallback"
            return self._finish(GuideResponse(goal=goal, action=GuideAction.unknown(MSG_UNKNOWN), source="fallback"),
                                meta, t0)

        # 4) 빠른 길 — 쓰지 않는 경우:
        #    · 이미 고른 메뉴 (화면이 바뀌었으니 다음 단계는 LLM이 이력으로 판단)
        #    · 짧은 대답("네" · "아니요" …): 무엇에 대한 대답인지는 직전 질문을 봐야 안다.
        #      "샷 추가하실래요?" → "아니요"를 '취소' 버튼으로 누르면 주문이 취소된다 (2026-11-12)
        pressed = _pressed_text(req)
        chosen = _chosen_items(req)
        # 사진을 볼 때는 빠른 길을 쓰지 않는다: 빠른 길은 글자만 보고 골라서 버튼 전체 상자(노란 칸)도 모르고,
        # 선택 표시(✓) · 장바구니처럼 사진에만 보이는 것도 못 본다. 대신 그 경우 AI를 한 번 더 부른다(약 1.2초)
        if (self.s.fastpath and ranked and goal and not use_photo and not asking and answer_button(req.utterance) is None
                and stype in screen_type.FASTPATH_TYPES):
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
        if use_photo:
            # 사진을 볼 때는 후보를 넓힌다: 목표와 글자가 비슷한 8개만 주면 '커피' 탭 · '결제하기' 같은 진행 버튼이 빠져서
            # 사진에서 맞는 버튼을 보고도 고르지 못했다 (2026-11-13 평가). 넓힌 쪽은 도움 모드와 같다(위 탭 · 아래 진행 버튼 먼저)
            known = {c.id for c in candidates}
            candidates = (candidates + [e for e in help_mode.candidates_for_help(els, utterance=goal) if e.id not in known])[:30]
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
                          kiosk=kiosk_hint, change=_change_line(change), lookalike=lookalike, photo=use_photo,
                          photo_size=photo_size)

        action = None
        deadline = t0 + self.s.total_budget_s
        # 사진을 볼 때는 '목록에 없음(MISSING)'도 고를 수 있게 한다: 형식이 목록 안 번호만 허용하면, 사진에서 결제하기를 보고도
        # 글자 읽기가 놓친 그 버튼 대신 아무 칸(전체 · 연하게)이나 고른다 (2026-11-13 평가, 도움 모드와 같은 문제)
        ids = list(by_id) + ([MISSING_ID] if use_photo else [])
        for attempt in range(2):                       # 최초 1회 + 형식 오류 시 1회
            remaining = deadline - time.perf_counter()
            if attempt > 0 and remaining < self.s.min_retry_s:
                break                                  # 앱 타임아웃(6초) 안에 답하려면 재요청을 포기하고 UNKNOWN으로
            timeout = max(0.5, min(self.s.llm_timeout_s, remaining))
            raw = await self._complete(user if attempt == 0 else user + RETRY_NOTE, timeout, ids, meta,
                                       image=req.image if use_photo else None)
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

        # 5-1) 사진에서 찾은 칸: 글자 목록(후보)에 없지만 AI가 사진에서 가리킨 버튼은 사진 위치로 안내한다.
        #      앱은 응답의 box로 노란 칸을 친다 (domain: 스냅샷에 없는 targetId + box → 사진 칸). 캐시하지 않는다(폰이 움직이면 위치가 바뀜)
        if use_photo and action.action == LlmKind.GUIDE:
            action, found = _photo_pick(action, by_id, els, photo_size, meta)
            if found is not None:                     # 이름이 맞는 글자 칸이 있으면 그 칸 (사진 박스보다 정확하다)
                by_id = {**by_id, found.id: found}
            photo = _photo_box(action, True, by_id, size=photo_size)
            if photo is not None:
                box, name = photo
                meta.update(source="photo", photo_box=True)
                resp = _repeat_guard(req, _scrub_claims(GuideResponse(goal=goal, action=GuideAction.tap("photo", name),
                                                                      box=box, source="photo")))
                return self._finish(resp, meta, t0)

        # 6) 검증 (+ LLM 형식 GUIDE… → 앱 계약 TAP…)
        resp = self.validator.validate(action, by_id, goal=goal)
        if use_photo and resp.action.type == ActionType.TAP and action.action == LlmKind.GUIDE:
            fixed = _photo_name(action.guideText or action.boxText or "", resp.action.guideText or "", els)
            if fixed:                                  # 깨진 글자 대신 사진에서 읽은 이름으로 말한다 (칸은 글자 읽기 칸 그대로)
                resp = resp.model_copy(update={"action": resp.action.model_copy(update={"guideText": fixed})})
                meta["name_fixed"] = True
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
        #    사진이 있으면 건너뛴다: 글자만 보던 AI가 결제를 지어내서 생긴 규칙이라, 장바구니를 사진으로 볼 때는 맞는 결제까지 막는다
        if (resp.action.type == ActionType.TAP and not use_photo
                and _premature_checkout(req, goal, chosen, resp.action.guideText)):
            meta["rejected"] = True
            resp = GuideResponse(goal=goal, action=GuideAction.unknown(MSG_NOT_SEEN), source="rule")
        resp = _repeat_guard(req, _scrub_claims(resp))
        # 9) 노란 칸을 버튼 전체에: 글자 칸으로 고른 버튼도 AI가 사진에서 본 버튼 전체 상자를 같이 보낸다 (앱이 글자에 붙여 따라간다)
        if use_photo and resp.box is None and resp.action.type == ActionType.TAP:
            btn = _button_box(action, by_id.get(resp.action.targetId or ""), photo_size)
            if btn is not None:
                resp = resp.model_copy(update={"box": btn})
                meta["button_box"] = True
        meta["source"] = resp.source
        if resp.source == "llm":
            self.cache.put(key, resp)                  # 키에 글자 위치가 들어 있어서 같은 위치일 때만 상자째 다시 쓴다
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
        candidates = help_mode.candidates_for_help(els, utterance=req.utterance or "")
        image = req.image
        if self.s.lexicon_fix:                                              # OCR 오타(읍션 · 주문달기)를 공통 낱말로 바로잡아 보인다
            els = [e.model_copy(update={"text": lexicon.correct(e.text)}) for e in els]
            candidates = [c.model_copy(update={"text": lexicon.correct(c.text)}) for c in candidates]
        if self.s.som_marks and candidates:                                 # 번호를 그린 사진: AI는 번호만 고르고 칸은 OCR 칸을 쓴다
            alias = som.short_labels([c.id for c in candidates])
            candidates = [c.model_copy(update={"id": a}) for a, c in zip(alias, candidates)]
            meta["_alias"] = alias
            if image:
                image = som.draw_marks(image, {c.id: c for c in candidates}) or image
        by_id = {c.id: c for c in candidates}
        user = help_mode.build_user(req.utterance or "", req.history, els, candidates,
                                    screen=screen_type.describe_probabilities(probs) or screen_type.label(stype),
                                    kiosk=kiosk_hint, change=_change_line(change))
        size = som.image_size(req.image) if image else None
        if size:                                                            # 박스는 이 사진의 픽셀 좌표로 받는다
            user += f"\n사진 크기: {size[0]}×{size[1]} 픽셀 (box는 [왼쪽, 위, 오른쪽, 아래] 픽셀 좌표)"
        meta["help"] = True
        meta["candidates"] = len(candidates)
        if self.s.debug_prompts:
            log.info("KL_DEBUG 사진=%s", f"{len(req.image) // 1024}KB" if req.image else "없음")        # 크기만 (내용은 남기지 않는다)
        action = None
        deadline = t0 + self.s.total_budget_s
        repeat_retry = False
        for attempt in range(2):
            remaining = deadline - time.perf_counter()
            if attempt > 0 and remaining < self.s.min_retry_s:
                break
            raw = await self._complete(user + (RETRY_NOTE if attempt > 0 and not repeat_retry else ""), max(0.5, min(self.s.llm_timeout_s, remaining)),
                                       list(by_id) + [MISSING_ID], meta, system=help_mode.HELP_SYSTEM, image=image)
            meta["llm_calls"] = int(meta["llm_calls"]) + 1
            if self.s.debug_prompts:
                log.info("KL_DEBUG 도움 원답=%s", raw)
            try:
                action = self.parser.parse(raw)
            except ParseError:
                meta["retry"] = True
                continue
            again = _repeated_target(action, by_id, req.history)
            if again and attempt == 0 and deadline - time.perf_counter() >= self.s.min_retry_s:
                meta["retry"] = True                                           # 방금 안내한 칸을 또 가리킴 → 한 번 더 묻는다 (2026-11-13 실기기: 푸드를 누른 뒤에도 푸드)
                user = user + _REPEAT_NOTE.format(t=again)
                repeat_retry = True
                action = None
                continue
            if again:                                                          # 다시 물어도 같다 → 칸 없이 말로만 (이미 누른 칸을 또 칠하지 않는다)
                action = action.model_copy(update={"action": LlmKind.ASK_USER, "targetId": None})
            break
        label = screen_type.label(stype)
        fallback = f"{label}이에요. 어떤 점이 궁금하세요?" if label else "무엇이 궁금하세요?"
        if action is None:
            meta["source"] = "fallback"
            return self._finish(GuideResponse(goal=prev_goal, action=GuideAction.ask_user(fallback), source="fallback"), meta, t0)
        named = (action.guideText or action.boxText or "").strip() if action.action == LlmKind.GUIDE else ""
        chosen = by_id.get(action.targetId or "")
        spoken = _spoken_targets(action.message or "") if action.action == LlmKind.GUIDE else []
        if chosen is not None and spoken and all(_name_score(q, chosen.text) < _NAME_AGREE for q in spoken):
            # 말은 "'커피' 탭에 있어요"인데 칸은 '장바구니 조회'를 골랐다 (2026-11-13 실기기) → 말한 칸을 믿는다
            alt = max(by_id.values(), key=lambda c: _name_score(spoken[0], c.text))
            if _name_score(spoken[0], alt.text) >= 0.65:
                action = action.model_copy(update={"targetId": alt.id, "guideText": alt.text})
            else:
                action = action.model_copy(update={"targetId": None, "guideText": spoken[0]})
            chosen = by_id.get(action.targetId or "")
            named = (action.guideText or "").strip()
            meta["spoken_mismatch"] = True
        if chosen is not None and named and _name_score(named, chosen.text) < _NAME_AGREE:
            # 형식이 "목록 안의 번호"를 강제해서, 목록에 없는 버튼(결제하기)을 말하면서 번호는 아무 칸(마카롱 · 상품)이나 고른다 (2026-11-13 실기기).
            # 말한 이름과 고른 칸이 다르면 칸을 믿지 않는다 → 아래에서 사진 위치나 찾기(seek)로 넘어간다
            action = action.model_copy(update={"targetId": None})
            meta["target_mismatch"] = True
        chosen = by_id.get(action.targetId or "")
        if chosen is not None and normalize(chosen.text) in {normalize(w) for w in _UNDO_BUTTONS} and not _UNDO_INTENT.search(req.utterance or ""):
            # 되돌리는 버튼(처음으로 · 취소 · 초기화)은 어르신이 되돌리길 원할 때만 가리킨다: 진짜 버튼이 안 보일 때 대신 고르면 주문이 날아간다 (2026-11-13 실기기)
            meta["unsafe_target"] = True
            action = action.model_copy(update={"action": LlmKind.ASK_USER, "targetId": None, "guideText": None,
                                               "message": _reason_only(action.message or "") or None})
            named = ""
        photo = (_photo_box(action, bool(req.image), by_id, size=som.image_size(req.image))
                 if action.action == LlmKind.GUIDE else None)
        if photo is not None:                                                # 글자 목록에 없지만 사진에서 찾은 칸: 박스로 가리킨다
            box, name = photo
            say = _clean_message(action.message, "", 80)
            if say and (_waiting_talk_text(say) or any(w in normalize(say) for w in _APP_CLAIMS)):
                say = ""
            resp = GuideResponse(goal=prev_goal, action=GuideAction.tap("photo", name), say=say or None, box=box, source="help")
            meta["source"] = "help"
            meta["photo_box"] = True
            return self._finish(resp, meta, t0)
        seek_name = _clean_name(named)[:30] if action.action == LlmKind.GUIDE and action.targetId not in by_id else ""
        if len(normalize(seek_name)) >= 2 and seek_name not in _NOT_A_BUTTON:
            # 누를 칸은 정했는데 글자 목록에도 사진 위치에도 없다 → "누르세요"라고 하지 않고 찾는다(앱이 읽히는 순간 노란 칸을 친다)
            body = _clean_message(action.message, "", 80)
            return await self._found_or_seek(req, els, seek_name, body, deadline, prev_goal, meta, t0)
        resp = self.validator.validate(action, by_id, goal=prev_goal)       # 후보 밖 버튼은 안내하지 않는다 (화이트리스트)
        a = resp.action
        say = ""
        if a.type == ActionType.TAP:                                          # 버튼을 가리킨다 + 앞에 읽을 이유 · 방법 한 문장(say)
            say = _reason_only(_clean_message(action.message, "", 80))          # 이유만 (누르라는 문장은 앱이 따로 읽는다)
            if say and (_waiting_talk_text(say) or any(w in normalize(say) for w in _APP_CLAIMS)):
                say = ""                                                      # 앱이 뭔가 하는 듯한 말은 읽지 않는다
        if a.type == ActionType.TAP and _CONDITIONAL_PICK.search(action.message or "") and normalize(a.guideText or "") not in normalize(req.utterance or ""):
            # "드실지 포장하실지 골라 주세요. 포장이면 노란 칸을…" — 어르신이 정할 선택을 예로 골라 칠하지 않는다 (2026-11-13 실기기)
            meta["conditional_pick"] = True
            resp = GuideResponse(goal=prev_goal, action=GuideAction.ask_user(help_mode.with_follow_up(_reason_only(_clean_message(action.message, "", 80)) or fallback)), source=resp.source)
        elif a.type == ActionType.TAP:
            resp = resp.model_copy(update={"say": say or None})
        elif a.type == ActionType.DONE:
            resp = GuideResponse(goal=prev_goal, action=GuideAction.done(help_mode.MSG_BYE), source=resp.source)
        elif a.type == ActionType.ASK_USER and (pressed := _pressed_candidate(a.question or "", by_id)) is not None:
            # "× 를 누르세요"라고 말하면서 칸은 안 가리켰다 → 말한 그 칸을 노란색으로 가리킨다 (어르신이 어디인지 알 수 있게)
            say = _clean_message(a.question, "", 80)
            resp = GuideResponse(goal=prev_goal, action=GuideAction.tap(pressed.id, pressed.text), say=say or None, source=resp.source)
        else:                                                                 # 대답(ASK_USER · EXPLAIN · UNKNOWN · SCROLL) → 말하고 계속 듣는다
            text = a.question or a.reason or fallback
            if a.type == ActionType.SCROLL and not (a.question or a.reason):    # 버튼 이름만 읽으면 알아들을 수 없다 — 문장으로 만든다 (2026-11-13 실기기)
                way = {"UP": "위로", "DOWN": "아래로"}.get(str(getattr(a.direction, "value", a.direction) or "").upper(), "")
                name = (a.guideText or "").strip()
                text = f"화면을 {way + ' ' if way else ''}밀어서 '{name}' 칸을 찾아 보세요." if name else fallback
            if _waiting_talk(resp) or any(w in normalize(text) for w in _APP_CLAIMS):
                text = fallback                                               # 앱이 뭔가 하는 듯한 말은 하지 않는다
            seek = None
            sent = _press_sentence(text) if a.type == ActionType.ASK_USER else None
            if sent and not _is_later_or_negated(sent):
                seek = _seek_name(sent)                                       # 누르라면서 칠 칸이 없다 → "누르세요"는 말하지 않고 찾는다고 알린다
            if seek:
                return await self._found_or_seek(req, els, seek, text, deadline, prev_goal, meta, t0)
            else:
                resp = GuideResponse(goal=prev_goal, action=GuideAction.ask_user(help_mode.with_follow_up(text)), source=resp.source)
        meta["source"] = "help"
        return self._finish(resp, meta, t0)

    async def _locate_on_grid(self, image: Optional[str], name: str, els, deadline: float, meta: Dict[str, object]):
        """글자 목록에 없는 버튼을 사진에서 찾는다: 격자(A1~F8)를 그린 사진을 보여 주고 그 버튼이 든 칸 이름 하나만 고르게 한다.
        흐리거나 반사돼 글자 읽기가 놓친 버튼도 AI는 사진에서 본다 (2026-11-13 실기기: 위쪽 탭 줄에서 '푸드'만 안 읽혔다).
        → (TAP 할 칸 번호 또는 None, 사진 위치 Box 또는 None). 고른 격자 칸 안에 이름이 비슷한 OCR 칸이 있으면 그 칸에 맞춘다."""
        if not image or not name or not self.s.grid_locate:
            return None, None
        left = deadline - time.perf_counter()
        if left < 0.8:
            return None, None
        drawn = som.draw_grid(image)
        if drawn is None:
            return None, None
        grid_img, cells = drawn
        user = f"찾을 버튼: {name}"
        try:
            raw = await self._complete(user, max(0.5, min(self.s.llm_timeout_s, left)), list(cells) + [MISSING_ID], meta,
                                       system=help_mode.GRID_SYSTEM, image=grid_img)
            meta["llm_calls"] = int(meta["llm_calls"]) + 1
            picked = self.parser.parse(raw).targetId
        except (LlmTimeout, LlmUpstream, LlmRateLimited, ParseError):
            return None, None
        if picked not in cells:
            return None, None
        l, t, r, b = cells[picked]
        meta["grid"] = picked
        left = deadline - time.perf_counter()
        if self.s.grid_zoom and left >= 0.8:
            # 확대해서 한 번 더: 고른 칸과 그 둘레(3×3)를 잘라 키우고 촘촘한 격자로 다시 고른다 (성긴 격자는 바로 옆 칸으로 빗나가기 쉽다)
            cw, ch = r - l, b - t
            zoomed = som.crop_grid(image, (max(0.0, l - cw), max(0.0, t - ch), min(1.0, r + cw), min(1.0, b + ch)))
            if zoomed is not None:
                zimg, zcells = zoomed
                try:
                    raw2 = await self._complete(user, max(0.5, min(self.s.llm_timeout_s, left)), list(zcells) + [MISSING_ID], meta,
                                                system=help_mode.GRID_SYSTEM, image=zimg)
                    meta["llm_calls"] = int(meta["llm_calls"]) + 1
                    fine = self.parser.parse(raw2).targetId
                    if fine in zcells:
                        l, t, r, b = zcells[fine]
                        meta["grid_fine"] = fine
                except (LlmTimeout, LlmUpstream, LlmRateLimited, ParseError):
                    pass                                                     # 확대 실패 → 대략 찾은 칸을 쓴다
        inside = [e for e in els if l <= (e.rect.left + e.rect.right) / 2 <= r and t <= (e.rect.top + e.rect.bottom) / 2 <= b
                  and _name_score(name, e.text) >= 0.5]
        if inside:                                                           # 그 칸 안에 깨져 읽힌 같은 이름이 있으면 그 OCR 칸
            best = max(inside, key=lambda e: _name_score(name, e.text))
            return best, None
        pad = 0.02
        return None, Box(left=max(0.0, l - pad), top=max(0.0, t - pad), right=min(1.0, r + pad), bottom=min(1.0, b + pad))

    async def _found_or_seek(self, req, els, name: str, body: str, deadline: float, prev_goal, meta: Dict[str, object], t0: float):
        """이름은 아는데 칠 칸이 없다 → 사진 격자로 위치를 찾아 바로 노란 칸을 친다. 그래도 못 찾으면 찾기(seek)로 넘긴다."""
        el, box = await self._locate_on_grid(req.image, name, els, deadline, meta)
        say = _reason_only(body) or None
        meta["source"] = "help"
        if el is not None:
            return self._finish(GuideResponse(goal=prev_goal, action=GuideAction.tap(el.id, el.text), say=say, source="help"), meta, t0)
        if box is not None:
            meta["photo_box"] = True
            return self._finish(GuideResponse(goal=prev_goal, action=GuideAction.tap("photo", name), say=say, box=box, source="help"), meta, t0)
        meta["seek"] = True
        return self._finish(GuideResponse(goal=prev_goal, action=GuideAction.ask_user(_seek_text(body, name)), seek=name, source="help"), meta, t0)

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
                if first_failed:
                    await asyncio.sleep(0.6)                      # 일시 오류(키 검증 503 등)는 몇 초씩 연달아 나서, 바로 다시 보내면 같은 오류에 또 걸린다 (2026-11-13)
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
        alias = meta.pop("_alias", None)                                     # 사진에 그린 짧은 번호 → 앱이 아는 원래 칸 번호
        if alias and resp.action.targetId in alias:
            resp = resp.model_copy(update={"action": resp.action.model_copy(update={"targetId": alias[resp.action.targetId]})})
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
        kiosk = kiosk_action(req, goal)
        if kiosk is not None:
            r = GuideResponse(goal=goal, action=kiosk, source="rule")
        elif _is_done_screen(req.snapshot.elements):
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
