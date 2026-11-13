"""어떤 키오스크인가: 화면 글자로 가게(브랜드)와 종류(카페 · 햄버거 …)를 확률로 추정한다.

화면 인식은 글자를 자주 틀리게 읽는다("메가엠지씨커피"가 "메가엠지씨커피"로 정확히 읽히는 일은 드물다). 그래서 글자 하나가 정확히 일치하는 것이 아니라
자모 유사도(`jamo.dice`)로 비슷한 글자를 센다. 추정만 하고 단정하지 않는다: 확신이 낮으면 None.
브랜드를 알면 메뉴 이름 · 주문 흐름에 대한 기대를 LLM에 줄 수 있고, 어르신께 "메가커피 주문 화면이에요"라고 말해 줄 수 있다.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

from .jamo import dice, normalize
from .models import ScreenElement

# 브랜드 → (종류, 화면에 나오는 글자들). 로고 · 상단 이름 · 고유 메뉴 이름.
BRANDS: Dict[str, Tuple[str, Tuple[str, ...]]] = {
    "메가커피": ("카페", ("메가엠지씨커피", "메가커피", "메가mgc", "megamgc")),
    "스타벅스": ("카페", ("스타벅스", "starbucks")),
    "이디야커피": ("카페", ("이디야", "ediya")),
    "컴포즈커피": ("카페", ("컴포즈", "compose")),
    "빽다방": ("카페", ("빽다방", "paikdabang")),
    "투썸플레이스": ("카페", ("투썸", "twosome")),
    "할리스": ("카페", ("할리스", "hollys")),
    "맥도날드": ("햄버거", ("맥도날드", "mcdonald", "맥모닝", "빅맥")),
    "롯데리아": ("햄버거", ("롯데리아", "lotteria")),
    "버거킹": ("햄버거", ("버거킹", "burgerking", "와퍼")),
    "맘스터치": ("햄버거", ("맘스터치", "momstouch")),
    "KFC": ("햄버거", ("kfc", "치킨버거")),
    "서브웨이": ("샌드위치", ("서브웨이", "subway")),
}
# 가게 이름을 모를 때 쓰는 종류별 낱말
CATEGORY_WORDS: Dict[str, Tuple[str, ...]] = {
    "카페": ("커피", "아메리카노", "라떼", "디카페인", "에이드", "스무디", "티", "음료", "디저트", "베이커리"),
    "햄버거": ("버거", "세트", "사이드", "너겟", "감자튀김", "콜라"),
    "분식·식당": ("김밥", "떡볶이", "라면", "비빔밥", "찌개", "덮밥"),
    "표 · 예매": ("승차권", "출발", "도착", "좌석", "영화", "예매"),
}
BRAND_DICE = 0.6        # 글자 하나가 브랜드 낱말과 이만큼 비슷하면 그 브랜드 (자모 유사도). 다른 낱말은 0.45 이하 (컴퓨터 · 메가박스 · 신메뉴 측정)
MIN_FUZZY_LEN = 4       # 오타 매칭은 4글자 이상에만 (짧은 글자는 우연히 비슷해지기 쉽다). 정확히 포함하는 경우는 길이와 상관없이 인정


@dataclass(frozen=True)
class Kiosk:
    name: Optional[str]            # 가게 이름 (모르면 None)
    category: Optional[str]        # 종류 (카페 · 햄버거 …)
    confidence: float              # 0~1

    def text(self) -> Optional[str]:
        """어르신 · LLM에게 보여 줄 한 줄. 확신이 낮으면 None."""
        if self.confidence < 0.5:
            return None
        if self.name:
            return f"{self.name} {self.category} 키오스크"
        return f"{self.category} 키오스크"


def identify(elements: Sequence[ScreenElement]) -> Kiosk:
    """화면 글자로 가게와 종류를 추정한다."""
    texts = [t for t in (normalize(e.text) for e in elements) if t]
    # 1) 가게 이름: 글자가 브랜드 낱말을 품거나 자모가 비슷하면 (오타 허용)
    best: Tuple[float, Optional[str], Optional[str]] = (0.0, None, None)
    for brand, (category, words) in BRANDS.items():
        for w in words:
            nw = normalize(w)
            for t in texts:
                score = 1.0 if nw in t else (dice(t, nw) if len(t) >= MIN_FUZZY_LEN else 0.0)
                if score > best[0]:
                    best = (score, brand, category)
    if best[0] >= BRAND_DICE:
        return Kiosk(best[1], best[2], round(min(0.95, 0.4 + best[0] * 0.55), 2))
    # 2) 종류: 가게 이름은 모르지만 낱말로
    votes: List[Tuple[int, str]] = []
    for category, words in CATEGORY_WORDS.items():
        hits = sum(1 for w in words if any(normalize(w) in t for t in texts))
        if hits:
            votes.append((hits, category))
    if votes:
        hits, category = max(votes)
        return Kiosk(None, category, round(min(0.8, 0.3 + 0.15 * hits), 2))
    return Kiosk(None, None, 0.0)
