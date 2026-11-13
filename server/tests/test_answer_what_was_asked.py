"""어르신이 한 말에 그 말에 대한 대답을 한다 (2026-11-13 실기기: "캐모마일 먹고 싶어"에 "Micros, 클로드, Branch 중에서 고를 수 있어요", 같은 말 반복)."""
from app import elder, screen_guide
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest, ScreenSnapshot
from .conftest import make_req
from app.settings import Settings


def agent(llm):
    return LlmGuideAgent(llm, Settings(app_key="k", fastpath=True, cache_size=0, hedge_after_s=0))


async def ask(llm, snaps, screen, utt, **kw):
    return await agent(llm).next(GuideRequest.model_validate(make_req(snaps, screen, utt, **kw)))


async def test_a_specific_answer_that_the_item_is_not_on_screen_is_kept_not_replaced(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN","message":"화면에 캐모마일 메뉴가 보이지 않아요. 메뉴 목록을 아래로 더 내려 찾아볼게요."}'])
    r = await ask(llm, snaps, "s02_menu", "캐모마일 먹고 싶어")
    a = r.response.action
    assert a.type.value == "UNKNOWN"
    assert a.reason.startswith("화면에 캐모마일 메뉴가 보이지 않아요.")            # 어르신 질문에 대한 대답이 남는다
    assert "찾아볼게" not in a.reason and "밀어서" in a.reason                     # 앱이 직접 하는 말은 어르신이 할 일로


async def test_a_stock_complaint_is_still_turned_into_a_screen_description(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN","message":"버튼을 찾지 못했어요. 화면이 잘 보이게 비춰 주세요."}'])
    r = await ask(llm, snaps, "s03_options", "아무거나", goal="아메리카노")
    assert r.response.action.type.value == "ASK_USER" and r.response.action.question.startswith("옵션을 고르는 화면이에요.")


def test_menu_screen_description_does_not_read_out_junk_choices(snaps):
    s = ScreenSnapshot.model_validate(snaps["s02_menu"])
    text = screen_guide.describe(s.elements, changed=True, label="메뉴를 고르는 화면")
    assert text == "메뉴를 고르는 화면이에요. 드시고 싶은 메뉴 이름을 말씀해 주세요."


def test_latin_fragments_from_other_windows_are_not_choices():
    import copy
    base = {"id": "e", "text": "", "kind": "BUTTON_LIKE", "rect": {"left": .1, "top": .1, "right": .4, "bottom": .2}}
    texts = ["Micros", "Branch", "API ke", "HOT", "ICE", "담기", "×"]
    els = ScreenSnapshot.model_validate({"screenId": "x", "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": [
        dict(copy.deepcopy(base), id=f"e{i}", text=t, rect={"left": .1, "top": .05 * i + .01, "right": .4, "bottom": .05 * i + .04})
        for i, t in enumerate(texts)]}).elements
    got = screen_guide.choices(els)
    assert got and not {"Micros", "Branch", "API ke"} & set(got)                 # 다른 창의 영문 조각은 읽어 주지 않는다


def test_saying_that_i_will_speak_again_is_not_a_repeat_request():
    for u in ("자 다시 말할게", "잠깐만요 다시 말씀드릴게요", "다시 말할게요", "어디 보자 말해볼게"):
        assert elder.is_preface(u) and not elder.is_repeat(u), u
    for u in ("다시 말해줘", "뭐라고요", "다시 한번 말해 주세요"):
        assert elder.is_repeat(u) and not elder.is_preface(u), u


async def test_preface_gets_a_short_acknowledgement_and_keeps_the_goal(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"UNKNOWN"}'])
    r = await ask(llm, snaps, "s02_menu", "자 다시 말할게", goal="캐모마일 먹고 싶어")
    assert r.response.action.question == "네, 말씀해 주세요." and r.response.goal == "캐모마일 먹고 싶어" and not llm.calls


async def test_the_same_question_is_not_repeated_over_and_over(snaps):
    q = "어떤 걸로 드릴까요?"
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"%s"}' % q])
    one = [{"screenId": "s02_menu", "action": "ASK_USER", "targetText": q}]
    r = await ask(llm, snaps, "s02_menu", "음", goal="무언가", history=one)
    assert r.response.action.type.value == "ASK_USER" and r.response.action.question != q       # 한 번 반복하면 다른 말로

    two = one * 2
    r = await ask(ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"%s"}' % q]), snaps, "s02_menu", "음", goal="무언가", history=two)
    assert r.response.action.type.value == "DONE" and "직원" in r.response.action.guideText     # 계속되면 직원 도움


async def test_a_new_question_is_never_blocked_by_the_repeat_guard(snaps):
    hist = [{"screenId": "s02_menu", "action": "ASK_USER", "targetText": "다른 질문이에요?"}] * 3
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"새 질문이에요?"}'])
    r = await ask(llm, snaps, "s02_menu", "음", goal="무언가", history=hist)
    assert r.response.action.question == "새 질문이에요?"
