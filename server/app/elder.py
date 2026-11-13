"""어르신 말투 처리: 군말 · 되묻기 · 도움 요청 · 마음 바꿈 · 화면에 없는 결제 수단.

근거 (자료조사, 2026-11-13): 노인의 음성 상호작용은 오류가 잦고(한 달 관찰 연구에서 약 1/4), 말이 느리고 끊기며 군말 · 반복이 많고,
오류를 다시 말해도 잘 풀리지 않는다 (arXiv 2403.02421 · 2412.07388). 그래서 서버가 이런 말을 LLM에 맡기지 않고 먼저 알아듣는다:
- 군말("어...", "음", "그...", "있잖아")은 지워서 목표 · 검색에 섞이지 않게 한다
- "다시 말해줘"는 목표에 붙이지 않고 방금 한 안내를 다시 한다
- "직원 불러줘" · "그만할래요"는 안내를 끝낸다 (키오스크 앞에서 막힌 사람을 붙잡아 두지 않는다)
- "아니 그거 말고 라떼" 는 이전 목표에 덧붙이지 않고 바꾼다
모두 LLM 호출 없이 즉시 처리되어 지연도 줄인다.
"""
from __future__ import annotations

import re
from typing import Optional, Sequence, Tuple

from .jamo import normalize
from .models import ActionType, HistoryItem, ScreenElement
from .ranker import same_item

# 군말: 문장 안의 낱말 단위로만 지운다 ("어떤"의 "어"는 안 지운다)
_FILLER = re.compile(r"(?:(?<=\s)|^)(?:어+|음+|으음+|흠+|아+|에+|그+|저+|저기|있잖아|있잖아요|뭐더라|뭐지|그니까|그러니까|막|좀)(?:\.+|,|…)*(?=\s|$)")
_DOTS = re.compile(r"(?:\.{2,}|…)+")

_REPEAT = re.compile(r"다시\s*(한\s*번\s*)?(말|해|얘기|이야기)(?!씀?\s*(?:할|드릴|볼|줄)[게께])|뭐라(고|구)|못\s*들었|한\s*번\s*더(\s*(말|해))?|다시\s*한\s*번|잘\s*안\s*들")
# "자 다시 말할게" · "잠깐만요 말씀드릴게요": 이제 말하겠다는 알림. 요청이 아니므로 목표에 붙이지 않고 "네, 말씀해 주세요"만 한다
_PREFACE = re.compile(r"^[\s,.]*(?:자|잠깐만?요?|잠시만요?|어디\s*보자|그러니까)?[\s,.]*(?:다시\s*)?(?:말|말씀|얘기|이야기)\s*(?:할|드릴|해\s*볼|해볼)\s*[게께]요?[\s.!]*$")
_HELP = re.compile(r"직원|도와\s*(줘|주세요|달라|줄래)|도움이?\s*필요|못\s*하겠|어려워서|너무\s*어려")
_STOP = re.compile(r"그만\s*(할래|하고\s*싶|할게|둘래)|안\s*(할래|하고\s*싶|할게)|종료|끝낼래|나갈래")
_CORRECTION = re.compile(r"(?P<before>.+?)\s*(?:말고|아니고|아니라|대신에?)\s*(?P<after>.+)")

MSG_HELP = "알겠어요. 가까운 직원에게 도움을 요청해 주세요."
MSG_STOP = "알겠어요. 안내를 마칠게요."
MSG_CASH = "현금은 이 화면에서 고를 수 없어요. 카드로 하실래요, 직원에게 물어볼까요?"
MSG_LISTENING = "네, 말씀해 주세요."
MSG_REPEAT_FIRST = "무엇을 도와드릴까요?"


def clean_utterance(text: Optional[str]) -> Optional[str]:
    """군말 · 말줄임표를 지운다. 지우고 나서 낱말이 하나도 안 남으면 None (아무 말도 안 한 것)."""
    if not text:
        return None
    t = _DOTS.sub(" ", text)
    for _ in range(3):                              # "어 음 그" 처럼 군말이 연달아 있어도
        n = _FILLER.sub(" ", t)
        if n == t:
            break
        t = n
    t = re.sub(r"\s+", " ", t).strip(" ,.…")
    return t or None


def is_repeat(utterance: Optional[str]) -> bool:
    """방금 안내를 다시 해 달라는 말 ("뭐라고요?", "다시 말해줘")."""
    return bool(utterance) and bool(_REPEAT.search(utterance))


def is_preface(utterance: Optional[str]) -> bool:
    """이제 말하겠다는 알림 ("자 다시 말할게")."""
    return bool(utterance) and bool(_PREFACE.match(utterance))


def is_help(utterance: Optional[str]) -> bool:
    return bool(utterance) and bool(_HELP.search(utterance))


def is_stop(utterance: Optional[str]) -> bool:
    return bool(utterance) and bool(_STOP.search(utterance))


def correction_of(utterance: Optional[str], prev_goal: Optional[str]) -> Optional[str]:
    """앞서 원한 것을 부정하며 바꾼 말이면 새로 원하는 것만 ("아메리카노 말고 라떼로 할래요" → "라떼로 할래요"), 아니면 None.
    부정하는 말("아메리카노")이 이전 목표에 있을 때만 바꾼다: "샷 추가 말고 시럽"처럼 옵션만 바꾸는 말이 주문 목표(아메리카노)를
    통째로 지우면 안 되기 때문. "다른 거"처럼 새 내용이 막연하면 LLM에 맡긴다."""
    if not utterance or not prev_goal:
        return None
    m = _CORRECTION.search(utterance)
    if not m:
        return None
    after = m.group("after").strip(" ,.")
    na = normalize(after)
    if len(na) < 2 or na.startswith(("다른", "딴", "아무")):
        return None
    goal = normalize(prev_goal)
    tokens = [normalize(t) for t in re.split(r"\s+", m.group("before")) if normalize(t) not in ("아니", "아니요", "아냐", "그거", "그건", "이거", "이건")]
    return after if any(len(t) >= 2 and t in goal for t in tokens) else None


_AGREE = re.compile(r"(?<![가-힣])(?:(?:그|이)\s*(?:걸|거|것)(?:로|으로)?(?:\s*(?:요|할게|할래|주세요|줘|해))?|그렇게\s*해|좋아요?|그래요?|맞아요?|네|예|응)(?![가-힣])")
_EXPLAINED = re.compile(r"^(?P<name>.{1,14}?)(?:은|는)\s.+이걸로 하실래요\?$")


def resolve_referent(utterance: Optional[str], history: Sequence[HistoryItem]) -> Optional[str]:
    """메뉴 설명을 들은 뒤 "그걸로 할게요" · "네"라고 하면 설명한 메뉴 이름으로 풀어서 "카페라떼 주세요"로 돌려준다.
    설명 단계에서는 주문 목표를 바꾸지 않으므로(질문이 목표에 섞이면 안 된다) 이 대답이 올 때 서버가 "그걸로"가 무엇인지 잊는다
    (2026-11-13 시뮬레이션: "카페라떼가 뭐예요" → "그럼 그걸로 할게요" → 엉뚱한 바닐라라떼). 해당 없으면 None."""
    if not utterance or not history:
        return None
    last = history[-1]
    if last.action != ActionType.ASK_USER or not last.targetText:
        return None
    m = _EXPLAINED.match(last.targetText.strip())
    if not m:
        return None
    name = m.group("name").strip()
    from . import menu_info
    if menu_info._lookup(name) is None:                          # 사전에 있는 메뉴 · 옵션 설명일 때만
        return None
    said = utterance.strip()
    if menu_info._lookup(said) is not None:                      # 다른 메뉴를 직접 말했으면 그 말을 따른다
        return None
    core = re.sub(r"[\s.!,?]+", " ", said).strip()
    if not _AGREE.search(core) or any(w in core for w in ("아니", "싫", "말고", "다른")):
        return None
    return f"{name} 주세요"


def repeat_action(history: Sequence[HistoryItem], elements: Sequence[ScreenElement]) -> Tuple[str, Optional[ScreenElement]]:
    """방금 한 안내를 다시: ("ask", None, 질문) · ("tap", 요소) 중 하나를 (종류, 요소)로 돌려준다.
    종류 "tap"이면 요소를 다시 가리키고, "ask:질문"이면 같은 질문을 다시 한다, "first"면 처음 인사."""
    for h in reversed(history):
        if h.action == ActionType.ASK_USER and h.targetText:
            return "ask:" + h.targetText, None
        if h.action == ActionType.TAP and h.targetText:
            for e in elements:
                if same_item(e.text, h.targetText):
                    return "tap", e
            break                                    # 화면이 바뀌어 그 버튼이 없음 → 아래 처음 인사 대신 호출한 쪽이 판단
    return "first", None


def cash_unavailable(utterance: Optional[str], elements: Sequence[ScreenElement]) -> bool:
    """"현금으로 할게요"인데 화면에 현금 버튼이 없다 (카드 · 페이만 있는 키오스크가 많다)."""
    if not utterance or "현금" not in utterance:
        return False
    return not any("현금" in e.text for e in elements)
