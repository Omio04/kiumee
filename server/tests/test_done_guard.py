"""이른 '끝' 막기 (P1, 2026-11-12 시험).

잘못 알아들은 말("회 기간")에 LLM이 "결제가 완료되었습니다"라고 답해 세션이 끝났고, 아메리카노를 고르기도 전에
결제하기를 누른 뒤 "주문이 완료되었습니다"라고 하기도 했다. 고른 것이 없는데 LLM이 DONE이면 끝내지 않고 다시 묻는다
(앱은 묻고 나서 바로 듣는다)."""
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import ActionType, GuideRequest
from app.settings import Settings
from app.validator import MSG_NOT_DONE
from .conftest import make_req

LLM_DONE = '{"action":"DONE","message":"결제가 완료되었습니다."}'
SCREEN = "s02_menu"


def _req(snaps, history, utterance=None, goal="아메리카노 한잔"):
    return GuideRequest.model_validate(make_req(snaps, SCREEN, utterance=utterance, goal=goal, history=history))


async def _next(req):
    return await LlmGuideAgent(ScriptedFakeLlmClient([LLM_DONE]), Settings(app_key="k", fastpath=False)).next(req)


async def test_first_misheard_utterance_is_asked_again_not_finished(snaps):
    r = await _next(_req(snaps, [], utterance="회 기간", goal=None))
    assert r.response.action.type == ActionType.ASK_USER
    assert r.response.action.question == MSG_NOT_DONE
    assert r.meta["rejected"] is True and r.meta["source"] == "rule"


async def test_payment_pressed_before_any_menu_is_not_finished(snaps):
    # 결제하기 · 담기는 진행 버튼이라 '고른 것'이 아니다
    history = [{"screenId": "s1", "action": "UNKNOWN"},
               {"screenId": "s1", "action": "TAP", "targetText": "결제하기"}]
    r = await _next(_req(snaps, history))
    assert r.response.action.type == ActionType.ASK_USER


async def test_done_after_a_chosen_menu_stays_done(snaps):
    history = [{"screenId": "s1", "action": "TAP", "targetText": "아메리카노"},
               {"screenId": "s2", "action": "TAP", "targetText": "결제하기"}]
    r = await _next(_req(snaps, history))
    assert r.response.action.type == ActionType.DONE


async def test_full_history_may_have_dropped_the_menu_so_done_is_trusted(snaps):
    # 앱은 최근 5개만 보낸다: 옵션 · 담기 · 결제로 꽉 차면 앞에서 고른 메뉴가 밀려났을 수 있다
    history = [{"screenId": f"s{i}", "action": "TAP", "targetText": t}
               for i, t in enumerate(["ICE", "샷 추가", "담기", "포장", "결제하기"])]
    r = await _next(_req(snaps, history))
    assert r.response.action.type == ActionType.DONE


async def test_asks_again_only_once_per_session(snaps):
    history = [{"screenId": "s1", "action": "ASK_USER", "targetText": MSG_NOT_DONE}]
    r = await _next(_req(snaps, history, utterance="다 했어"))
    assert r.response.action.type == ActionType.DONE


# ---- 고른 것 없이 결제로 가지 않기 (2026-11-13): 메뉴 이름을 못 읽은 화면에서 "아메리카노 한 잔"에 '결제하기'를 안내했다 ----

LLM_PAY = '{"action":"GUIDE","targetId":"e6","guideText":"결제하기"}'      # s04_cart의 e6 = 결제하기


def _cart_req(snaps, goal, history=None, keep=None):
    body = make_req(snaps, "s04_cart", utterance=None, goal=goal, history=history or [])
    if keep is not None:                                    # 메뉴 이름을 못 읽은 화면 흉내: 일부 글자만 남김
        body["snapshot"]["elements"] = [e for e in body["snapshot"]["elements"] if e["id"] in keep]
    return GuideRequest.model_validate(body)


async def _pay(req):
    return await LlmGuideAgent(ScriptedFakeLlmClient([LLM_PAY]), Settings(app_key="k", fastpath=False)).next(req)


async def test_checkout_without_the_menu_anywhere_on_screen_is_held_back(snaps):
    from app.validator import MSG_NOT_SEEN
    r = await _pay(_cart_req(snaps, "아메리카노 한잔", history=[{"screenId": "s1", "action": "UNKNOWN"}],
                             keep={"e4", "e5", "e6"}))
    assert r.response.action.type == ActionType.UNKNOWN
    assert r.response.action.reason == MSG_NOT_SEEN
    assert r.meta["rejected"] is True


async def test_checkout_is_fine_when_the_menu_is_already_in_the_cart(snaps):
    r = await _pay(_cart_req(snaps, "아메리카노 한잔"))                  # 장바구니에 '아메리카노 1잔'
    assert r.response.action.type == ActionType.TAP and r.response.action.guideText == "결제하기"


async def test_checkout_is_fine_when_payment_is_the_goal(snaps):
    r = await _pay(_cart_req(snaps, "결제해 줘", keep={"e4", "e5", "e6"}))
    assert r.response.action.type == ActionType.TAP


async def test_checkout_is_fine_after_a_menu_was_chosen(snaps):
    r = await _pay(_cart_req(snaps, "딸기 스무디 한잔", keep={"e4", "e5", "e6"},
                             history=[{"screenId": "s1", "action": "TAP", "targetText": "딸기 스무디"}]))
    assert r.response.action.type == ActionType.TAP


def test_menu_seen_tolerates_ocr_typos():
    from app.agent import _item_seen
    from app.models import ScreenElement
    def el(t):
        return ScreenElement.model_validate({"id": "x", "text": t, "kind": "TEXT", "confidence": 0.9,
                                             "rect": {"left": 0.1, "top": 0.1, "right": 0.3, "bottom": 0.2}})
    assert _item_seen([el("CE에리카노")], "아메리카노 한잔 주세요")       # 글자 인식 오타도 보인 것
    assert not _item_seen([el("카페라떼"), el("결제하기"), el("27009")], "아메리카노 한잔 주세요")
    assert not _item_seen([el("메뉴를 선택해 주세요")], "아메리카노 주세요")   # "주세요"는 메뉴 이름이 아님


async def test_done_answer_or_no_menu_name_is_not_held_back(snaps):
    # "다 됐어요"(짧은 대답) · 메뉴 이름 없는 말은 결제로 넘어가도 된다 (test_answers의 장바구니 "다 됐어요" → 결제하기)
    for goal in ("다 됐어요", "이제 끝났어", "응"):
        r = await _pay(GuideRequest.model_validate(make_req(snaps, "s04_cart", utterance=goal, goal=None)))
        assert r.response.action.type == ActionType.TAP, goal
