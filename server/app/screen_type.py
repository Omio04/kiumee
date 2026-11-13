"""화면 종류 판별: 지금 화면이 무엇을 하는 화면인지 (먹고갈지/포장할지 · 메뉴 · 옵션 · 주문 확인 · 일회용품 · 적립 · 결제 수단 · 카드 결제 · 끝).

왜 필요한가
- 예전에는 화면의 글자 목록만 LLM에 주고 "이게 무슨 화면인지"를 매번 추측하게 했다. 새 화면이 뜨면 이 추측이 흔들려
  "버튼을 못 찾았어요"나 엉뚱한 안내가 나갔다 (2026-11-13 실기기: 아메리카노를 누른 뒤 새 화면).
- 서버가 글자(제목 · 버튼 이름)로 종류를 먼저 정하고 ① LLM에 "[화면 종류]"로 알려 주고 ② 어르신께 "옵션을 고르는 화면이에요"라고
  말해 주고 ③ 적립 화면은 LLM 없이 닫기(×)를 안내한다.

종류의 어휘는 P2의 `SceneProcessor`(loyalty · disposable · order-review · payment · product-options · card-approval · complete)와 맞췄다
(vision/docs/mega-coffee-handoff.md). 비전은 이 종류를 계약에 담아 보내지 않으므로(공용 JSON 불변) 서버가 글자로 다시 판단한다.
규칙 기반이라 즉시(마이크로초) 끝나고, 모르면 UNKNOWN으로 두어 LLM이 예전처럼 판단한다.
"""
from __future__ import annotations

from enum import Enum
from typing import Optional, Sequence

from .jamo import normalize
from .models import ElementKind, ScreenElement


class ScreenType(str, Enum):
    DINE = "DINE"                  # 먹고 갈지 포장할지
    MENU = "MENU"                  # 메뉴 목록
    OPTIONS = "OPTIONS"            # 온도 · 샷 · 크기 같은 옵션
    ORDER_REVIEW = "ORDER_REVIEW"  # 주문 내역 · 장바구니 확인
    DISPOSABLE = "DISPOSABLE"      # 일회용품 필요 여부
    LOYALTY = "LOYALTY"            # 스탬프 · 포인트 적립
    PAYMENT = "PAYMENT"            # 결제 수단 고르기
    CARD = "CARD"                  # 카드 넣기 · 승인 중 (결제 진행)
    COMPLETE = "COMPLETE"          # 결제 · 주문 끝
    START = "START"                # 시작 화면
    UNKNOWN = "UNKNOWN"


# 어르신께 읽어 드릴 이름 ("…화면이에요"로 이어진다)
LABEL = {
    ScreenType.DINE: "먹고 갈지 포장할지 정하는 화면",
    ScreenType.MENU: "메뉴를 고르는 화면",
    ScreenType.OPTIONS: "옵션을 고르는 화면",
    ScreenType.ORDER_REVIEW: "주문 내역을 확인하는 화면",
    ScreenType.DISPOSABLE: "일회용품을 고르는 화면",
    ScreenType.LOYALTY: "적립 화면",
    ScreenType.PAYMENT: "결제 수단을 고르는 화면",
    ScreenType.CARD: "카드 결제 화면",
    ScreenType.COMPLETE: "주문이 끝난 화면",
    ScreenType.START: "시작 화면",
}


def _k(*words: str):
    return tuple(normalize(w) for w in words)


_CARD = _k("카드를 넣", "카드를 투입", "카드 투입", "투입구", "카드를 삽입", "카드를 태그", "결제 진행", "결제 중", "승인 중")
_COMPLETE = _k("주문이 완료", "결제가 완료", "결제 완료", "주문 완료", "주문번호", "영수증을 출력", "정상 승인")
_LOYALTY = _k("적립")
_DISPOSABLE = _k("일회용품")
_PAY_WORDS = _k("신용카드", "삼성페이", "카카오페이", "페이코", "네이버페이", "간편결제", "모바일상품권", "토스")
_OPTION_TITLE = _k("옵션")
_OPTION_WORDS = _k("hot", "ice", "샷", "시럽", "사이즈", "휘핑", "연하게", "진하게", "담기", "선택완료")
_REVIEW = _k("주문 내역", "주문 세부 내역", "장바구니", "주문 확인")
_REVIEW_ACTION = _k("결제하기", "합계", "주문하기", "총 금액", "계산하기")
_TABS = _k("커피", "음료", "디저트", "베스트", "신메뉴", "티", "논커피", "스무디")
_START = _k("터치하여", "시작하려면", "주문하려면", "화면을 터치", "언어 선택")


def classify(elements: Sequence[ScreenElement]) -> ScreenType:
    """화면 글자(제목 · 버튼 이름 · 가격 개수)로 종류를 정한다. 모르면 UNKNOWN (LLM이 판단)."""
    texts = [normalize(e.text) for e in elements]

    def has(*words: str) -> bool:
        return any(w and w in t for t in texts for w in words)

    prices = sum(1 for e in elements if e.kind == ElementKind.PRICE)
    if has(*_CARD):
        return ScreenType.CARD
    if has(*_COMPLETE):
        return ScreenType.COMPLETE
    # 적립 창은 뒤 메뉴를 가려서 가격이 거의 안 보인다 ("포인트 사용"이 있는 결제 · 주문 화면과 구분)
    if has(*_LOYALTY) and prices <= 1:
        return ScreenType.LOYALTY
    if has(*_DISPOSABLE):
        return ScreenType.DISPOSABLE
    if has(normalize("결제수단")) or sum(1 for w in _PAY_WORDS if has(w)) >= 2:
        return ScreenType.PAYMENT
    if (has(normalize("매장")) and has(normalize("포장"))) or has(normalize("먹고가기")):
        return ScreenType.DINE
    if has(*_OPTION_TITLE) and has(*_OPTION_WORDS):
        return ScreenType.OPTIONS
    if has(*_REVIEW) and has(*_REVIEW_ACTION):
        return ScreenType.ORDER_REVIEW
    if prices >= 2 or sum(1 for w in _TABS if any(t == w for t in texts)) >= 2:
        return ScreenType.MENU
    if has(*_START):
        return ScreenType.START
    return ScreenType.UNKNOWN


# 주문형(카페 · 식당) 키오스크에서만 쓰는 장치(진행 단계 · 눌렀는지 확인)를 켜도 되는 화면 종류.
ORDERING_TYPES = frozenset({ScreenType.DINE, ScreenType.MENU, ScreenType.OPTIONS, ScreenType.ORDER_REVIEW, ScreenType.DISPOSABLE})
# AI 없이 바로 안내하는 "빠른 길"을 써도 되는 화면 종류. 낯선 화면(UNKNOWN: 기차표 · 민원 · 무인 결제 …)에서는 글자 하나만 보고
# 고르면 위험하다("부산 가는 표요"에 출발역 화면의 '부산'을 안내, 2026-11-13 기차표 시뮬레이션) → AI가 화면 전체를 보고 판단한다.
FASTPATH_TYPES = ORDERING_TYPES | {ScreenType.PAYMENT}


def is_ordering(t: ScreenType, kiosk_category) -> bool:
    """주문형 키오스크로 보이는가: 화면 종류가 주문형이거나 가게 종류가 카페 · 햄버거 · 식당일 때."""
    return t in ORDERING_TYPES or kiosk_category in ("카페", "햄버거", "샌드위치", "분식·식당")


def label(t: ScreenType) -> Optional[str]:
    return LABEL.get(t)


# 적립 화면에서 건너뛰는 버튼 (P2: 건너뛰기 모드에서는 적립 · 숫자를 후보로 내보내지 않고 실제로 확인된 ×만 내보낸다)
_SKIP = _k("×", "x", "✕", "✖", "닫기", "건너뛰기", "적립안함", "적립안해", "다음에", "생략", "아니오", "아니요", "괜찮습니다", "나중에")


# 닫기 기호: 글자 정리(normalize)를 하면 빈 글자가 되어 '←'(숫자판 지우기) 같은 다른 기호와 구별이 안 된다 → 원래 글자로 비교
_CLOSE_SYMBOLS = frozenset({"×", "✕", "✖", "x", "X"})


def skip_button(elements: Sequence[ScreenElement]) -> Optional[ScreenElement]:
    """적립 같은 창을 닫는 버튼 (종류와 상관없이 글자로). 없으면 None.
    2026-11-13: '×'가 빈 낱말("")로 바뀌어 적립 창 숫자판의 '←'를 닫기로 골랐다 → 기호는 원래 글자로, 빈 낱말은 건너뛴다."""
    for e in elements:                                  # 닫기 기호(×)가 가장 확실하다
        if e.text.strip() in _CLOSE_SYMBOLS:
            return e
    for word in _SKIP:                                  # 그다음 낱말 순서대로 (닫기 · 건너뛰기 …)
        if not word:
            continue
        for e in elements:
            if normalize(e.text) == word:
                return e
    return None


# ---------------------------------------------------------------- 확률
def _evidence(elements: Sequence[ScreenElement]) -> dict:
    """종류마다 글자가 얼마나 그 종류답게 보이는가 (서로 다른 단서의 개수 × 가중치). 점수일 뿐 확률이 아니다."""
    texts = [normalize(e.text) for e in elements]

    def hits(words) -> int:
        return sum(1 for w in words if w and any(w in t for t in texts))

    prices = sum(1 for e in elements if e.kind == ElementKind.PRICE)
    tabs = sum(1 for w in _TABS if any(t == w for t in texts))
    pay = hits(_PAY_WORDS)
    score = {
        ScreenType.CARD: 3.0 * hits(_CARD),
        ScreenType.COMPLETE: 3.0 * hits(_COMPLETE),
        ScreenType.LOYALTY: (3.0 if hits(_LOYALTY) else 0.0) * (1.0 if prices <= 1 else 0.3),
        ScreenType.DISPOSABLE: 3.0 * hits(_DISPOSABLE),
        ScreenType.PAYMENT: 3.0 * hits(_k("결제수단")) + 1.0 * pay,
        ScreenType.DINE: 1.5 * hits(_k("매장")) + 1.5 * hits(_k("포장")) + 2.0 * hits(_k("먹고가기")),
        ScreenType.OPTIONS: (1.5 * hits(_OPTION_TITLE)) + min(3, hits(_OPTION_WORDS)) * 1.0 if hits(_OPTION_TITLE) or hits(_OPTION_WORDS) >= 2 else 0.0,
        ScreenType.ORDER_REVIEW: 1.5 * (1 if hits(_REVIEW) else 0) + 1.5 * (1 if hits(_REVIEW_ACTION) else 0),
        ScreenType.MENU: (1.0 + 0.5 * min(prices, 4) if prices >= 2 else 0.0) + 1.0 * min(tabs, 3),
        ScreenType.START: 2.0 * hits(_START),
    }
    return score


UNKNOWN_PRIOR = 0.7      # 단서가 약하면 "모름"에 확률을 남긴다


def probabilities(elements: Sequence[ScreenElement]):
    """[(종류, 확률)] 확률 높은 순. 단서들의 점수를 정규화한 것이다 (보정된 확률이 아니라 '이 종류답게 보이는 정도'의 상대 비율).
    단서가 하나뿐이면 UNKNOWN에도 확률이 남아 확신이 낮게 나온다."""
    ev = _evidence(elements)
    ev[ScreenType.UNKNOWN] = UNKNOWN_PRIOR
    total = sum(ev.values())
    ranked = sorted(((t, s / total) for t, s in ev.items() if s > 0), key=lambda x: -x[1])
    return [(t, round(p, 2)) for t, p in ranked]


def describe_probabilities(probs) -> Optional[str]:
    """LLM에 줄 한 줄: "옵션을 고르는 화면 85%" 또는 헷갈릴 때 "옵션을 고르는 화면 55% · 주문 내역을 확인하는 화면 40%". 모르면 None."""
    shown = [(t, p) for t, p in probs if t != ScreenType.UNKNOWN and p >= 0.15][:2]
    if not shown:
        return None
    return " · ".join(f"{LABEL[t]} {int(round(p * 100))}%" for t, p in shown)
