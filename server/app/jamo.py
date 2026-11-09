"""한글 자모 분해 + n-그램 유사도. OCR이 한 글자를 잘못 읽어도(아메리카노 → 아메리키노) 가깝게 나옵니다."""
from __future__ import annotations

import re
from collections import Counter
from typing import Counter as CounterT

_CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
_JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
_JONG = ["", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ",
         "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"]

_KEEP = re.compile(r"[^0-9a-zA-Z가-힣ㄱ-ㅣ]")


def normalize(text: str) -> str:
    """공백·기호를 지우고 영문은 소문자로. 음절(한글 글자) 단위 비교에 씁니다."""
    return _KEEP.sub("", text).lower()


def to_jamo(text: str) -> str:
    out = []
    for ch in normalize(text):
        o = ord(ch)
        if 0xAC00 <= o <= 0xD7A3:
            i = o - 0xAC00
            out.append(_CHO[i // 588])
            out.append(_JUNG[(i % 588) // 28])
            j = _JONG[i % 28]
            if j:
                out.append(j)
        else:
            out.append(ch)
    return "".join(out)


def ngrams(s: str, sizes=(2, 3)) -> CounterT[str]:
    c: CounterT[str] = Counter()
    for n in sizes:
        for i in range(len(s) - n + 1):
            c[s[i:i + n]] += 1
    return c


def dice(a: str, b: str) -> float:
    """자모 n-그램 Dice 계수 (0~1)."""
    ga, gb = ngrams(to_jamo(a)), ngrams(to_jamo(b))
    ta, tb = sum(ga.values()), sum(gb.values())
    if ta == 0 or tb == 0:
        return 0.0
    inter = sum(min(v, gb[k]) for k, v in ga.items() if k in gb)
    return 2.0 * inter / (ta + tb)
