"""키오스크 공통 낱말로 글자 읽기(OCR) 오타를 바로잡는다 (자모 단위 편집 거리).

왜: 실기기 로그에서 틀린 글자 대부분이 모양이 비슷한 글자의 자모 하나 차이였다 (옵→읍 · 담→달 · 초→조 · 롱→옹 · 칩→집, 2026-11-13).
어느 키오스크든 쓰는 진행 · 결제 낱말만 사전에 둔다 (메뉴 이름은 가게마다 달라 넣지 않는다). 원문은 그대로 두고, 고친 글자는
AI에게 보여 주는 화면 글자와 후보에만 쓴다. 가까운 낱말이 둘이면 고치지 않는다.
"""
from __future__ import annotations

import re
from functools import lru_cache
from typing import Optional

from .jamo import normalize, to_jamo

WORDS = (
    "결제하기", "결제", "카드결제", "간편결제", "현금결제", "주문담기", "장바구니", "장바구니 조회", "담기", "주문하기", "주문",
    "취소", "전체취소", "확인", "다음", "이전", "처음으로", "닫기", "완료", "선택완료", "선택", "선택안함", "추가", "삭제",
    "옵션", "선택된 옵션", "포장하기", "먹고가기", "포장", "매장", "적립", "적립하기", "적립 안함", "건너뛰기", "영수증",
    "출력", "미출력", "승인 요청", "일시불", "할부", "카드번호", "총 결제금액", "결제금액", "주문금액", "수량", "가격",
    "좌석", "좌석 선택", "인원", "성인", "어린이", "출발", "도착", "출발지", "도착지", "출발 시간", "날짜", "발권", "예매",
    "접수", "접수하기", "진료과", "번호표", "예", "아니오", "네", "아니요", "메뉴", "커피", "음료", "푸드", "디저트", "세트",
    "단품", "사이즈", "온도", "샷 추가", "시럽", "토핑", "남은시간", "처음 화면",
)

_PRICE = re.compile(r"^(\d{1,3}(?:[,.]\d{3})+|\d{3,6})\s*(?:원|21|91|01|2l|권|번|일)?$")   # "2,00021" "4,500번" → "2,000원"


@lru_cache(maxsize=1)
def _index():
    return [(w, normalize(w), to_jamo(w)) for w in WORDS]


def _lev(a: str, b: str, cap: int) -> int:
    if abs(len(a) - len(b)) > cap:
        return cap + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        best = i
        for j, cb in enumerate(b, 1):
            v = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
            cur.append(v)
            best = min(best, v)
        if best > cap:
            return cap + 1
        prev = cur
    return prev[-1]


@lru_cache(maxsize=4096)
def correct(text: str) -> str:
    """사전 낱말과 자모 한두 개 차이면 그 낱말로 바꾼다. 가격의 깨진 꼬리(21 · 번)는 '원'으로. 아니면 그대로."""
    raw = (text or "").strip()
    m = _PRICE.match(raw.replace(" ", ""))
    if m and any(c.isdigit() for c in raw):
        digits = m.group(1).replace(".", ",")
        return f"{digits}원"
    n = normalize(raw)
    if len(n) < 2 or not re.search(r"[가-힣]", n):
        return raw
    jn = to_jamo(raw)
    cap = 1 if len(n) <= 3 else 2                        # 짧은 낱말은 자모 1개, 긴 낱말은 2개까지
    hits = []
    for w, nw, jw in _index():
        if n == nw:
            return raw                                  # 이미 사전 낱말
        if abs(len(nw) - len(n)) > 1:
            continue
        d = _lev(jn, jw, cap)
        if d <= cap:
            hits.append((d, w))
    hits.sort()
    if not hits or (len(hits) > 1 and hits[0][0] == hits[1][0] and hits[0][1] != hits[1][1]):
        return raw                                      # 없거나 똑같이 가까운 낱말이 둘 → 고치지 않는다
    return hits[0][1]


def correct_or_none(text: str) -> Optional[str]:
    c = correct(text)
    return c if c != (text or "").strip() else None
