"""어느 키오스크든 작동해야 한다 (기차표 · 민원 · 무인 결제 …): 주문형(카페) 전용 장치는 주문형 화면에서만 켜진다."""
from app import screen_type
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.screen_type import ScreenType as T
from app.settings import Settings


def el(i, text, kind="BUTTON_LIKE"):
    return {"id": f"e{i}", "text": text, "kind": kind, "rect": {"left": .1, "top": .06 * i, "right": .9, "bottom": .06 * i + .05}}


TRAIN_ORIGIN = [el(1, "출발역을 선택해 주세요", "TEXT")] + [el(i + 2, s) for i, s in enumerate(("서울", "용산", "대전", "동대구", "부산"))] + [el(8, "이전")]
TRAIN_HOME = [el(1, "KORAIL 승차권 발매기", "TEXT"), el(2, "승차권 예매"), el(3, "예약 승차권 찾기"), el(4, "환불")]


def req(elements, utterance=None, goal=None, history=None, sid="t2"):
    return GuideRequest.model_validate({"sessionId": "any", "utterance": utterance, "goal": goal, "history": history or [],
        "snapshot": {"screenId": sid, "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": elements}})


def agent(llm):
    return LlmGuideAgent(llm, Settings(app_key="k", fastpath=True, cache_size=0, hedge_after_s=0))


def test_train_screens_are_not_mistaken_for_ordering_screens():
    from app.models import ScreenSnapshot
    for els in (TRAIN_ORIGIN, TRAIN_HOME):
        snap = ScreenSnapshot.model_validate({"screenId": "x", "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": els})
        t = screen_type.classify(snap.elements)
        assert not screen_type.is_ordering(t, None) and t not in screen_type.FASTPATH_TYPES


async def test_an_unfamiliar_screen_never_uses_the_fast_path_even_when_a_word_matches():
    """"부산 가는 표요"에 출발역 화면의 '부산'을 바로 안내하던 문제 (기차표 시뮬레이션 T2)."""
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"어디서 출발하세요?"}'])
    r = await agent(llm).next(req(TRAIN_ORIGIN, utterance="부산 가는 표요", goal=None))
    assert r.meta.get("source") != "fastpath" and len(llm.calls) == 1 and r.response.action.type.value == "ASK_USER"


async def test_ordering_only_hints_are_not_sent_for_a_train_kiosk():
    """"승차권 예매"를 눌렀는데 새 화면에 그 글자가 없다고 [기대와 다름] · "다음 단계: 담기"를 말하면 안 된다."""
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"e3","guideText":"서울"}'])
    hist = [{"screenId": "t1", "action": "TAP", "targetText": "승차권 예매"}]
    await agent(llm).next(req(TRAIN_ORIGIN, goal="서울에서 부산 가는 표", history=hist))
    user = llm.calls[0]["user"]
    assert "[기대와 다름]" not in user and "[진행]" not in user and "담기" not in user


def test_ordering_screens_still_get_the_ordering_helpers(snaps):
    from app.models import ScreenSnapshot
    for name in ("s01_dine", "s02_menu", "s03_options", "s04_cart"):
        snap = ScreenSnapshot.model_validate(snaps[name])
        assert screen_type.is_ordering(screen_type.classify(snap.elements), None), name
    assert screen_type.is_ordering(T.UNKNOWN, "카페")                        # 가게 종류를 알면 화면이 낯설어도 주문형


async def test_the_llm_decides_on_a_train_kiosk_and_the_answer_is_whitelisted():
    ids = {e["text"]: e["id"] for e in TRAIN_ORIGIN}
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"%s","guideText":"서울"}' % ids["서울"]])
    r = await agent(llm).next(req(TRAIN_ORIGIN, utterance="서울에서 부산 가는 표요"))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText == "서울"
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"없는id","guideText":"뉴욕"}'])
    r = await agent(llm).next(req(TRAIN_ORIGIN, utterance="뉴욕이요"))
    assert r.response.action.type.value != "TAP"                             # 화면에 없는 곳은 안내하지 않는다
