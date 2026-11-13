"""행동 후 검증: 눌렀다던 메뉴 이름이 새 화면에 안 보이면 LLM에 알린다."""
import copy

from app import expectation
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.settings import Settings
from .conftest import make_req

TAP_AMERICANO = [{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]


def req(snaps, screen, history, sid="s03_options#1", mutate=None):
    body = make_req(snaps, screen, None, goal="아메리카노", history=history)
    body["snapshot"]["screenId"] = sid
    if mutate:
        body["snapshot"]["elements"] = mutate(copy.deepcopy(body["snapshot"]["elements"]))
    return GuideRequest.model_validate(body)


def test_option_window_title_shows_the_name_so_it_was_pressed(snaps):
    assert expectation.pressed_but_not_visible(req(snaps, "s03_options", TAP_AMERICANO)) is None   # "아메리카노 옵션 선택"


def test_cart_line_also_counts(snaps):
    assert expectation.pressed_but_not_visible(req(snaps, "s04_cart", TAP_AMERICANO)) is None       # "아메리카노 1잔"


def test_name_missing_everywhere_raises_a_doubt(snaps):
    assert expectation.pressed_but_not_visible(req(snaps, "s05_payment", TAP_AMERICANO)) == "아메리카노"


def test_ocr_typo_is_tolerated(snaps):
    def typo(els):
        for e in els:
            e["text"] = e["text"].replace("아메리카노", "아메리키노")
        return els
    assert expectation.pressed_but_not_visible(req(snaps, "s03_options", TAP_AMERICANO, mutate=typo)) is None


def test_only_menu_taps_are_checked_and_same_screen_is_ignored(snaps):
    hot = [{"screenId": "s03_options", "action": "TAP", "targetText": "HOT"}]
    assert expectation.pressed_but_not_visible(req(snaps, "s05_payment", hot)) is None             # 옵션은 이름이 남는다는 보장이 없다
    assert expectation.pressed_but_not_visible(req(snaps, "s05_payment", TAP_AMERICANO, sid="s02_menu")) is None   # 같은 화면
    assert expectation.pressed_but_not_visible(req(snaps, "s05_payment", [])) is None


async def test_agent_tells_the_llm_about_the_mismatch_and_can_turn_it_off(snaps):
    async def run(**kw):
        llm = ScriptedFakeLlmClient(['{"action":"WAIT","message":"아직 화면이 안 바뀌었어요."}'])
        s = Settings(**{"app_key": "k", "fastpath": False, "cache_size": 0, "hedge_after_s": 0, **kw})
        await LlmGuideAgent(llm, s).next(req(snaps, "s05_payment", TAP_AMERICANO))
        return llm.calls[0]["user"]
    on = await run()
    assert "[기대와 다름] '아메리카노'을 눌렀다고 안내했는데" in on
    assert "[기대와 다름]" not in await run(expectation_check=False)


async def test_no_line_when_everything_matches(snaps):
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"따뜻한 걸로 드릴까요?"}'])
    s = Settings(app_key="k", fastpath=False, cache_size=0, hedge_after_s=0)
    await LlmGuideAgent(llm, s).next(req(snaps, "s03_options", TAP_AMERICANO))
    assert "[기대와 다름]" not in llm.calls[0]["user"]
