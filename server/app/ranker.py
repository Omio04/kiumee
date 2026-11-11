"""JamoNgramRanker: 화면의 많은 요소 중 LLM에 보여줄 후보 K개만 고릅니다 (설계: K=8).

후보를 줄이면 (1) 프롬프트가 짧아져 p95 2초를 맞추기 쉽고 (2) LLM이 화면에 없는 버튼을 지어내기 어렵습니다.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Sequence

from .jamo import dice, normalize
from .models import ElementKind, ScreenElement

# 질문에 직접 안 나와도 흐름상 자주 눌러야 하는 버튼: 점수가 낮아도 후보에 남도록 살짝 가산
NAV_WORDS = ("다음", "확인", "담기", "장바구니", "결제", "주문", "완료", "이전", "취소", "처음", "더 담기", "닫기")
OPTION_WORDS = ("hot", "ice", "핫", "아이스", "따뜻", "차가", "시원", "보통", "큰", "작은", "레귤러", "라지", "샷", "시럽",
                "추가", "없음", "선택안함", "매장", "포장")
NAV_BOOST = 0.12
OPTION_BOOST = 0.08

# 발화 속 말을 화면 글자로 바꿔 주는 작은 사전 (예: "따뜻한" → HOT). 질문 안에 들어 있기만 하면 붙인다.
SYNONYMS = {
    "따뜻": "hot", "뜨거": "hot", "뜨겁": "hot", "핫": "hot",
    "아이스": "ice", "차가": "ice", "차갑": "ice", "시원": "ice", "냉": "ice",
    "먹고": "매장", "식사": "매장", "여기서": "매장",
    "가져": "포장", "싸": "포장", "테이크아웃": "포장",
    "계산": "결제", "돈낼": "결제", "카톡": "카카오", "카카오톡": "카카오",
    "하나 더": "추가", "더요": "추가", "더 넣": "추가", "더 주": "추가",
    "신용": "카드", "체크": "카드",
    "다음": "다음", "뒤로": "이전", "처음": "처음", "현금": "현금", "쿠폰": "쿠폰", "영수증": "영수증",
    "큰거": "큰", "큰 거": "큰", "작은거": "작은", "작은 거": "작은", "보통": "보통",
}

# 짧은 대답 → 버튼 글자. "네" · "좋아" · "끝" · "아니" 같은 말은 다른 말 속에도 흔해서
# ("아메리카노 네 잔", "제가 좋아하는 라떼", "끝까지 시원한 걸로 하나 더", "아니 아이스로 주세요")
# 위 사전처럼 "들어 있으면 붙이기"를 하면 결제 · 취소 같은 엉뚱한 버튼을 고른다.
# 그래서 이번 요청에 새로 들어온 발화(utterance) 전체가 대답일 때만 붙인다 (화면만 바뀐 요청에는 붙이지 않음).
ANSWERS = {
    "확인": ("네", "네네", "예", "응", "맞아", "맞아요", "좋아", "좋아요", "그래", "그래요", "네 맞아요", "네 좋아요", "네 그래요"),
    "결제": ("다 됐어", "다 됐어요", "다 됐습니다", "끝", "끝이에요", "끝났어요"),
    "취소": ("아니", "아니요", "아니에요", "아뇨", "그만", "그만할래요"),
}
_ANSWER_OF = {normalize(w): button for button, words in ANSWERS.items() for w in words}

_NAV = tuple(normalize(w) for w in NAV_WORDS)
_OPT = tuple(normalize(w) for w in OPTION_WORDS)


def answer_button(utterance: Optional[str]) -> Optional[str]:
    """발화 전체가 짧은 대답("네", "다 됐어요", "아니요" …)이면 그 대답이 가리키는 버튼 글자, 아니면 None."""
    return _ANSWER_OF.get(normalize(utterance)) if utterance else None


def expand_query(query: str, utterance: Optional[str] = None) -> str:
    """질문(목표)에 화면 글자 쪽 말을 덧붙인다. utterance = 이번 요청에 새로 들어온 말 (짧은 대답 판단에만 씀)."""
    extra = [v for k, v in SYNONYMS.items() if k in query]
    answer = answer_button(utterance)
    if answer:
        extra.append(answer)
    return (query + " " + " ".join(extra)).strip() if extra else query


@dataclass(frozen=True)
class Scored:
    element: ScreenElement
    match: float        # 질문과 글자의 실제 유사도 (0~1, 가산 없음) — 빠른 길 판단에 사용
    rank: float         # match + 흐름 가산 — 후보 순서에 사용


def same_item(text: str, pressed: str) -> bool:
    """방금 누른 버튼과 같은 메뉴인가. 한쪽이 다른 쪽을 품으면 같다 ("(ICE)아메리카노" ⊃ "아메리카노":
    옵션 창 제목 · 담은 목록처럼 이름에 꾸밈말이 붙어도 같은 메뉴). 글자 유사도 0.85 이상도 같다."""
    a, b = normalize(text), normalize(pressed)
    if not a or not b:
        return False
    if a == b or (len(b) >= 2 and b in a) or (len(a) >= 2 and a in b):
        return True
    return match_score(text, pressed) >= 0.85


def match_score(text: str, query: str) -> float:
    """질문(query)과 버튼 글자(text)의 유사도 0~1."""
    nt, nq = normalize(text), normalize(query)
    if not nt or not nq:
        return 0.0
    s = dice(text, query)
    if len(nt) >= 2 and nt in nq:                       # 질문 안에 버튼 글자가 통째로 들어 있음 (바닐라라떼 ⊂ 바닐라라떼주세요)
        s = max(s, 0.9 + 0.1 * min(1.0, len(nt) / len(nq)))
    elif len(nq) >= 2 and nq in nt:                     # 질문이 버튼 글자의 일부 (라떼 ⊂ 카페라떼)
        s = max(s, 0.7)
    else:
        for tok in query.split():                       # 질문의 낱말 하나가 버튼 글자 안에 있음 (qr ⊂ 간편결제qr)
            nk = normalize(tok)
            if len(nk) >= 2 and nk in nt:
                s = max(s, 0.6)
                break
    return min(1.0, s)


def _reading_order(e: ScreenElement):
    return (round(e.rect.top, 2), e.rect.left)


class JamoNgramRanker:
    def __init__(self, k: int = 8):
        self.k = k

    def rank(self, elements: Sequence[ScreenElement], query: str, utterance: Optional[str] = None) -> List[Scored]:
        q = expand_query(query, utterance)
        scored: List[Scored] = []
        for e in elements:
            if e.kind != ElementKind.BUTTON_LIKE:
                continue                               # 가격·일반 글자는 후보가 아님
            m = match_score(e.text, q) if q.strip() else 0.0
            nt = normalize(e.text)
            boost = 0.0
            if any(w and w in nt for w in _NAV):
                boost = NAV_BOOST
            elif any(w and w in nt for w in _OPT):
                boost = OPTION_BOOST
            scored.append(Scored(e, m, m + boost))
        # 점수 높은 순, 동점이면 위→아래, 왼→오른쪽 (읽는 순서)
        scored.sort(key=lambda s: (-s.rank, _reading_order(s.element)))
        return scored[: self.k]
