"""서버 쪽 2차 개인정보 방어선.

1차 방어선은 클라이언트(P2 필터 체인)입니다. 그래도 카드 번호·주민번호 같은 글자가 섞여 오면
서버가 LLM에 보내기 전에 지웁니다. 원문은 로그에 남기지 않습니다 (설계서 2.2).
"""
from __future__ import annotations

import re
from typing import List, Optional, Tuple

from .models import MAX_HISTORY, GuideRequest, HistoryItem, ScreenElement

# 13~19자리 숫자 (공백·하이픈 허용): 카드 번호
_CARD = re.compile(r"(?<!\d)(?:\d[ \-]?){13,19}(?!\d)")
# 주민등록번호 / 외국인등록번호 형태
_RRN = re.compile(r"(?<!\d)\d{6}[ \-]?[1-8]\d{6}(?!\d)")
# 말로 읽은 긴 숫자 (예: "일이삼사 오육칠팔 ..." 은 못 잡지만, 숫자로 받아쓴 경우는 잡음)
_LONG_DIGITS = re.compile(r"\d[\d ,\-]{6,}\d")


def is_sensitive(text: str) -> bool:
    return bool(_CARD.search(text) or _RRN.search(text))


def mask(text: str) -> str:
    """발화·이력 속 민감한 숫자를 지웁니다."""
    t = _RRN.sub("[숫자]", text)
    t = _CARD.sub("[숫자]", t)
    return t


def _mask_opt(text: Optional[str]) -> Optional[str]:
    return mask(text) if text else text


def sanitize(req: GuideRequest) -> Tuple[GuideRequest, int]:
    """민감한 요소를 빼고 발화·목표·이력을 마스킹한 새 요청과, 제거한 요소 개수(내용 아님)를 돌려줍니다."""
    kept: List[ScreenElement] = [e for e in req.snapshot.elements if not is_sensitive(e.text)]
    dropped = len(req.snapshot.elements) - len(kept)
    history = [
        HistoryItem(screenId=h.screenId, action=h.action, targetText=_mask_opt(h.targetText))
        for h in req.history[-MAX_HISTORY:]
    ]
    clean = GuideRequest(
        sessionId=req.sessionId,
        utterance=_mask_opt(req.utterance),
        goal=_mask_opt(req.goal),
        snapshot=req.snapshot.model_copy(update={"elements": kept}),
        history=history,
    )
    return clean, dropped
