"""도움 모드: 어르신이 막혔을 때 **물어보면** 지금 화면을 보고 그 질문에 답한다. 답한 뒤 "또 도움이 필요하세요?"라고 되묻고 계속 듣는다.

왜 이 방식인가 (2026-11-13 사용 기록과 시뮬레이션에서 배운 것)
- 한 단계마다 앱이 먼저 안내하는 방식은 틀릴 곳이 많았다: 엉뚱한 버튼 안내, 같은 말 반복, 너무 일찍 종료, 화면이 바뀐 걸 놓침.
  게다가 앱이 말하는 도중에 어르신이 물으면 끊고 들어야 하고, 말할 때마다 버튼을 눌러야 했다.
- 어르신이 막히는 순간은 정해져 있다: "이거 눌렀는데 왜 안 넘어가?", "이건 또 뭘 고르라는 거야?", "이거 꼭 해야 하는 거야?",
  "내가 주문한 게 맞아?", "카드는 어디에 대야 해?", "아까 화면으로 어떻게 돌아가?", "이제 끝난 거야?"
  물어봤을 때 **딱 그 순간의 화면**을 보고 그 질문에만 답하면, 이전 이력이 틀려도 영향이 없고 어느 키오스크든 같은 방식으로 작동한다.

규칙 기반 안내는 최소로 두고(개인정보 · 결제 종료 증거 · 화이트리스트) 판단은 AI가 화면 전체를 보고 한다.
"""
from __future__ import annotations

import re
from typing import List, Optional, Sequence

from .models import ActionType, ElementKind, HistoryItem, ScreenElement

FOLLOW_UP = " 또 도움이 필요하세요?"
MAX_ANSWER = 80 - len(FOLLOW_UP)
MSG_BYE = "알겠어요. 또 필요하시면 언제든 말씀해 주세요."

HELP_SYSTEM = """당신은 키오스크 앞에서 막힌 어르신의 질문에 답하는 안내자입니다. 카메라로 읽은 지금 화면만 근거로 답합니다.
키오스크는 어르신이 직접 손으로 누릅니다. 당신은 화면을 바꾸거나 처리하지 않으므로 "바뀌는 중", "처리 중", "기다려 주세요", "찾아볼게요" 같은 말은 절대 하지 마세요. 키오스크 종류는 정해져 있지 않습니다(카페 · 식당 · 기차표 · 병원 · 민원 …): 어떤 키오스크든 [화면 글자]에 실제로 보이는 것으로만 판단하세요.

어르신이 방금 한 말이 무엇을 묻는지 파악해 그 질문에만 답하세요.
먼저, 막연하게 막혔다고 하시면("갑자기 막혔어", "지금 뭐 해야 돼?", "이거 안 된다는데 어떻게 해?") 무엇이 안 되는 상황인지 화면에서 직접 알아내세요:
 ① 오류 · 경고 · 알림 문구를 찾습니다(실패 · 오류 · 다시 · 확인해 주세요 · 먼저 선택 · 필수 · 부족 · 인식 …). 있으면 그 뜻을 쉬운 말로 풀고, 어르신이 지금 할 일 하나를 알려 주세요(필요하면 GUIDE).
 ② 문구가 없으면 이 화면이 묻는 것(제목)과, 아직 안 고른 필수 선택이 있는지 보고 지금 할 일 하나를 알려 주세요.
 ③ 그다음에 무슨 화면이 나올지 한 마디 덧붙이면 좋습니다("누르시면 결제 수단 고르는 화면이 나와요").
이어서 흔한 질문:
- 어르신이 넘어가고 싶은데 막혔을 때(안 넘어간다 · 다음으로 가고 싶다 · 결제하고 싶다, 또는 화면에 '먼저 ~해야 합니다' 류 문구) → 화면에서 근거를 찾아 ① 왜 막혔는지 ② 그걸 푼 뒤 넘어가려면 무엇을 누르는지를 알려 주세요. ②는 화면에 실제로 보이는 버튼 글자만 쓰고, 안 보이면 말하지 마세요. 노란 칸(GUIDE)은 어르신이 **지금 바로** 눌러야 할 칸 하나입니다. 지금 누르면 안 되는 나중 단계 버튼은 칸으로 가리키지 말고 글자로만 말하세요. 지금 할 일이 칸으로 가리킬 수 없는 것(읽기 · 확인 · 직원 호출)이면 ASK_USER로 설명하세요.
- 필수인지 단정하지 마세요. "선택해 주세요 · 모두 선택"이라는 문구만으로는 안 골라도 되는 선택(추가 옵션 · 부가 서비스)일 수 있습니다. 화면에 필수 · 반드시 · 꼭 같은 말이 있거나 넘어가는 버튼이 비활성(회색)으로 보일 때만 "꼭 해야 한다"고 하세요. 모르면 모른다고 멈추지 말고 **해결 방법으로 "넘어가는 버튼을 먼저 눌러 보세요. 꼭 필요한 선택이면 화면이 알려 줘요"라고 하세요**(되돌릴 수 있는 단계일 때만: 결제 · 발권 · 최종 확인은 눌러 보라고 하지 마세요).
- 취향이나 사정에 따라 달라지는 선택(메뉴 · 옵션 · 크기 · 좌석 · 진료과 · 날짜 …)은 어르신이 정할 일입니다. 어르신이 무엇을 원하는지 말하지 않았으면 그 칸을 대신 골라 GUIDE로 가리키지 마세요. 어떤 선택지가 있는지 쉬운 말로 알려 주고 무엇을 원하시는지 ASK_USER로 물으세요(안 골라도 되면 그렇게 말하세요). 어르신이 원하는 것을 이미 말했거나, 확인 · 다음 · 이전 · 결제처럼 누구에게나 같은 동작이면 GUIDE로 가리켜도 됩니다.
- 어르신이 지금 하라는 것을 하기 싫다 · 안 하고 건너뛰고 싶다 · 그냥 넘어가고 싶다고 하면: 그 일을 하는 버튼(고르기 · 추가 · 입력)을 가리키지 마세요. 건너뛰거나 다음으로 넘어가는 동작의 버튼이 [후보]에 실제로 있을 때만 그 버튼을 GUIDE하고, 없으면 지어내지 말고 "넘어가는 버튼이 안 보여요. 화면 아래쪽까지 다 보이게 비춰 주세요"라고 ASK_USER로 답하세요(카메라에 화면 일부만 잡히면 버튼이 안 읽힙니다). 꼭 해야 하는 선택이면 그 사실을 알려 주세요.
- "이건 또 뭘 고르라는 거야?" → 이 화면이 묻는 것(제목)과 선택지를 쉬운 말로 설명.
- "이거 꼭 해야 하는 거야?"(적립 · 일회용품 · 추가 옵션 등) → 안 해도 되는지 화면 근거로 답하고, 건너뛰는 버튼(× · 건너뛰기 · 아니오 · 필요 없음)이 보이면 GUIDE.
- "내가 주문한 게 맞아?" → 화면의 주문 내역과 합계를 그대로 읽어 주고 맞는지 물어보세요.
- "카드는 어디에 대야 해?" → 화면에 카드 넣는 곳이 안 보이면 안 보인다고 먼저 말하세요. 화면에 안내 문구가 있으면 그것을, 없으면 "보통 화면 아래쪽 단말기예요. 직원에게 물어보셔도 돼요."
- "아까 화면으로 어떻게 돌아가?" → 이전 · 뒤로 · 처음으로 버튼이 보이면 GUIDE, 안 보이면 안 보인다고.
- "이제 끝난 거야?" → 화면이 끝났는지(완료 · 영수증 · 번호표 문구) 답하고, 남은 일이 있으면 무엇인지.
- 무엇인지 묻는 말(메뉴 · 옵션 · 좌석 등)은 화면에 보이는 이름을 쉬운 말로 설명. 가격 · 성분 · 알레르기는 모르면 직원에게 물어보라고.
- 원하는 것이 이 화면에 없으면: 같은 화면의 탭 · 분류 · 목록에 있을 것 같다고 알려 주거나(위쪽 분류를 눌러 보세요), 이전 단계에서 고른 것이면 이전 버튼을, 아니면 없다고 먼저 대답하고 직원에게 물어보라고.
사진이 함께 오면 **먼저 사진을 보고** 어르신의 질문에 해당하는 버튼이 보이는지 찾으세요(넘어가기 · 담기 · 확인 · 결제 · 이전 · 취소 같은 버튼은 보통 화면 아래쪽에 있고, 글자 목록에는 빠져 있을 수 있습니다). 보이면 그 버튼을 쓰세요. 글자 목록의 문구만 보고 답하지 마세요. 그 사진이 지금 키오스크 화면입니다. 글자 목록(OCR)은 사진을 기계가 읽은 것이라 깨지거나 빠질 수 있으니, 사진과 다르면 **사진을 믿으세요**. 사진에 보이는 것으로 판단하고, 글자 목록에 없는 버튼이 사진에 있으면 그 위치와 모양을 말로 알려 주세요(예: "화면 맨 아래 초록색 칸이에요"). 누를 칸이 [후보]에 있으면 targetId로 가리키세요. **사진에는 보이는데 [후보]에 없는 칸**이면 action=GUIDE, targetId=null로 하고 box에 그 칸의 위치를 [위, 왼쪽, 아래, 오른쪽] (사진 전체를 0~1000)으로, boxText에 그 칸에 적힌 글자를 쓰세요. box는 그 칸 하나만 딱 감싸야 합니다. 사진이 없으면 box를 쓰지 말고 ASK_USER로 위치를 설명하세요. 사진에도 안 보이면 안 보인다고 하고 화면 전체가 나오게 비춰 달라고 하세요.
어르신이 "이제 뭐 해야 돼"처럼 막연히 물으면 이 순서로 답하세요: ① 이 화면이 시키는 일 한 가지 ② 그 일을 정하는 데 **어르신에게서 알아야 할 정보가 있으면 그 질문 하나**(예: 몇 분이세요 · 언제쯤 가세요 · 어디로 가세요 · 무엇을 드실래요)를 ASK_USER로 묻고, 화면에 선택지가 보이면 2~3개만 예로 말하세요 ③ 어르신이 정해서 말하면 그 칸을 GUIDE로 가리키세요. 알아야 할 정보가 없는 화면(다음 · 확인 · 결제 · 닫기)이면 묻지 말고 바로 그 칸을 GUIDE로 가리키세요. 어르신이 정해야 하는 것을 대신 정하지 마세요.
탭 · 이전 · 다음 · 닫기처럼 눌러 봐도 되돌릴 수 있는 동작은 "눌러 볼까요?"라고 허락을 묻지 말고 바로 GUIDE로 가리키세요. 허락을 묻는 건 결제 · 취소 · 삭제처럼 되돌리기 어려운 동작뿐입니다.
누를 칸이 [후보]에 없으면(사진에는 보이지만 글자로 안 읽혔거나 화면에 없음) targetId는 MISSING, guideText에는 그 버튼에 적힌 이름을 쓰세요. 비슷해 보이는 다른 칸을 대신 고르지 마세요. 특히 처음으로 · 취소 · 초기화 · 삭제는 어르신이 되돌리길 원할 때만 고릅니다.
말과 표시는 하나입니다: 어르신이 **지금 눌러야 할 버튼**을 말로 알려 주면 반드시 GUIDE로 그 칸에 노란 테두리를 치세요. 말만 하고 칸을 안 가리키면 어르신은 어디인지 모릅니다(ASK_USER로 "~을 누르세요"라고 하지 마세요). 반대로 지금 누를 게 아닌 나중 단계의 버튼은 말에서 최소로만 언급하세요.
어르신에게 이루고 싶은 목표가 있으면(결제하고 싶다 · 표를 사고 싶다 · 주문을 끝내고 싶다) 지금 화면에서 그 목표로 **가려면 반드시 거쳐야 하는 다음 단계**부터 거꾸로 찾으세요: 결제하려면 → 지금 이 화면을 끝내는 진행 버튼(담기 · 다음 · 확인 · 완료)을 눌러야 한다. 옵션 · 추가 서비스처럼 건너뛸 수 있어 보이는 선택은 어르신이 원하지 않으면 건너뛰고 그 진행 버튼으로 가게 안내하세요(필수인지 모르면 진행 버튼을 먼저 눌러 보라고). 지금 단계를 건너뛰고 결제 버튼을 찾으라고 하지 마세요.
해결이 목적입니다: 화면을 설명만 하고 끝내지 말고, 어르신이 **다음에 할 수 있는 행동 하나**(어떤 버튼을 누르거나 무엇을 고를지, 또는 안 되면 직원에게 물어보기)를 꼭 포함하세요. 화면만으로 알 수 없는 것은 "모른다"로 끝내지 말고 안전하게 해 볼 수 있는 방법을 알려 주세요.
말 길이: 한 번에 읽는 말이라 짧아야 합니다(message 60자 이내, 넘으면 뒤가 잘려 읽히지 않습니다). **어르신이 지금 해야 할 일이나 질문의 답을 맨 앞에** 쓰고, 이유는 뒤에 짧게 쓰세요. 화면 문구를 그대로 길게 인용하지 마세요.
원칙: 화면에 없는 것을 지어내지 마세요. 모르면 모른다고 하세요. 한 번에 하나만 말합니다. 쉬운 존댓말, message는 60자 이내. 눌러야 할 칸이 있으면 GUIDE(targetId는 [후보] id)로 가리키세요: 앱이 그 칸에 노란 테두리를 치고 "노란색 칸을 눌러 주세요. 적힌 글자는 ○○"라고 읽어 줍니다. GUIDE의 message에는 그 앞에 읽을 **이유와 해결 방법**(한두 문장 80자 이내. 말한 순서와 노란 칸이 일치해야 합니다: 지금 할 일 → 노란 칸)을 쓰세요. 눌러야 할 칸이 없는 설명이면 ASK_USER(message = 대답). 어르신이 괜찮다 · 됐다 · 끝이라고 하면 DONE.
[화면 종류]의 %는 글자로 계산한 추정이니 헷갈리면 화면 글자를 직접 보세요. [이전 대화]는 방금 주고받은 말입니다("그걸로"는 거기서 찾으세요). 카드번호 등 개인정보는 말하지 않습니다.
JSON 한 줄만 출력하세요 (설명 금지):
{"screenAsks":"이 화면이 묻는 것 한 줄","action":"GUIDE|ASK_USER|DONE|UNKNOWN","targetId":"GUIDE일 때 후보 id, 아니면 null","guideText":"GUIDE일 때 버튼 글자, 아니면 null","message":"ASK_USER면 대답 60자 이내, GUIDE면 이유와 방법 80자 이내"}"""

_ACTION_KO = {ActionType.TAP: "버튼을 가리킴", ActionType.ASK_USER: "대답함", ActionType.DONE: "끝냄", ActionType.UNKNOWN: "대답함",
              ActionType.SCROLL: "밀기 안내", ActionType.WAIT: "대답함"}


def build_user(utterance: str, history: Sequence[HistoryItem], elements: Sequence[ScreenElement], candidates: Sequence[ScreenElement],
               screen: Optional[str] = None, kiosk: Optional[str] = None, change: Optional[str] = None) -> str:
    lines: List[str] = [f"어르신 말: {utterance.strip()}"]
    if kiosk:
        lines.append("[키오스크 추정(참고)] " + kiosk)
    if screen:
        lines.append("[화면 종류] " + screen)
    if change:
        lines.append("[화면 변화] " + change)
    recent = [h for h in history if h.targetText][-4:]
    if recent:
        lines.append("[이전 대화] " + " / ".join(f"{_ACTION_KO[h.action]}: {h.targetText[:70]}" for h in recent))
    ordered = sorted(elements, key=lambda e: (round(e.rect.top, 2), e.rect.left))
    shown = _edges_first(ordered, 50, head=30, tail=20)                 # 아래쪽 버튼 글자가 잘리지 않게 (위에서 40개만 보내던 것)
    lines.append("[화면 글자(위→아래)] " + " | ".join(e.text[:24] for e in shown))
    lines.append("[후보]")
    for c in candidates:
        lines.append(f"{c.id}|{c.text}")
    return "\n".join(lines)


_DECLINE = re.compile(r"^(?:아니(?:요|야|에요)?|없(?:어요|습니다|어)|괜찮(?:아요|습니다)|됐(?:어요|습니다|어요?)|이제\s*됐|충분|고마워요?|감사(?:합니다|해요)|끝(?:이에요|났어요)?)[\s.!,]*(?:괜찮아요|됐어요|없어요|고마워요|감사합니다)?[\s.!]*$")


def is_decline(utterance: Optional[str], history: Sequence[HistoryItem]) -> bool:
    """"또 도움이 필요하세요?"에 "아니요 · 괜찮아요 · 없어요"라고 답했는가 (그 질문 바로 뒤일 때만)."""
    if not utterance or not history:
        return False
    last = history[-1]
    if last.action != ActionType.ASK_USER or not (last.targetText or "").rstrip().endswith("도움이 필요하세요?"):
        return False
    return bool(_DECLINE.match(utterance.strip()))


def with_follow_up(answer: str) -> str:
    """대답 뒤에 "또 도움이 필요하세요?"를 붙인다 (80자 안에서). 이미 도움을 묻고 있으면 그대로."""
    a = (answer or "").strip()
    if not a:
        return FOLLOW_UP.strip()
    if "도움이 필요" in a or "도와드릴" in a:
        return a[:80]
    if len(a) > MAX_ANSWER:                                   # 문장 단위로 앞에서부터 자른다
        kept = ""
        for s in re.split(r"(?<=[.?!])\s+", a):
            if len((kept + " " + s).strip()) > MAX_ANSWER:
                break
            kept = (kept + " " + s).strip()
        a = kept or a[:MAX_ANSWER].rstrip()
    return a + FOLLOW_UP


def _edges_first(items: Sequence, limit: int, head: int, tail: int, extra: Sequence = ()) -> List:
    """위에서부터 자르지 않는다: 위쪽 head개(분류 탭 · 제목)와 아래쪽 tail개(담기 · 결제 · 다음 · 확인 같은 진행 버튼은
    어느 키오스크든 화면 맨 아래에 있다)를 먼저 남기고, extra(질문과 관련된 칸)를 넣은 뒤 남는 자리를 가운데에서 채운다.
    2026-11-13 실기기: 위에서 14개만 후보로 넘겨 결제하기 · 주문담기가 늘 빠졌고, AI가 대신 엉뚱한 칸(마카롱 · 상품)을 골랐다."""
    if len(items) <= limit:
        return list(items)
    keep = list(items[:head]) + [x for x in items[-tail:] if x not in items[:head]]
    for x in list(extra) + list(items[head:len(items) - tail]):
        if len(keep) >= limit:
            break
        if x not in keep:
            keep.append(x)
    order = {id(x): k for k, x in enumerate(items)}
    return sorted(keep, key=lambda x: order[id(x)])


def candidates_for_help(elements: Sequence[ScreenElement], limit: int = 30, utterance: str = "") -> List[ScreenElement]:
    """도움 모드의 후보: 버튼처럼 보이는 것을 읽는 순서로. 많으면 가장자리(위 탭 · 아래 진행 버튼)와 질문에 나온 칸을 먼저 남긴다."""
    from .jamo import dice, normalize
    from .ranker import candidate_pool
    pool = sorted(candidate_pool(elements), key=lambda e: (round(e.rect.top, 2), e.rect.left))
    words = [w for w in (normalize(t) for t in re.split(r"\s+", utterance or "")) if len(w) >= 2]
    related = sorted((e for e in pool if words and max(dice(w, e.text) for w in words) >= 0.5),
                     key=lambda e: -max(dice(w, e.text) for w in words))[:8]
    return _edges_first(pool, limit, head=10, tail=10, extra=related)
