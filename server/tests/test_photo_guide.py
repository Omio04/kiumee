"""기본 안내도 사진을 보고 판단한다 (P1, 2026-11-13, decisions.md 11장).

평가(연습 키오스크 17장면 + 웹캠 사진 2장, 3번 평균): 사진 끔 → 켬, 글자 정상 11.7 → 17.3/19 · 글자 불량 7.3 → 15.0/19.
사진은 선택 필드(image)라 없으면 예전 그대로 글자만 본다. PHOTO_GUIDE=false로 끌 수 있다.
"""
import pytest

from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import ActionType, GuideRequest
from app.prompt import PHOTO_NOTE
from app.settings import Settings
from .conftest import make_req

IMG = "QUJD"                                           # 가짜 사진(base64). 내용은 보지 않고 넘어가는지만 본다
PICK_LATTE = '{"screenAsks":"메뉴","action":"GUIDE","targetId":"e5","guideText":"카페라떼"}'
PHOTO_PAY = '{"screenAsks":"메뉴","action":"GUIDE","box":[850,600,950,900],"boxText":"결제하기"}'


def _req(snaps, said="카페라떼 한 잔 주세요", screen="s02_menu", image=IMG, elements=None, history=None):
    d = make_req(snaps, screen, said, history=history or [])
    if image:
        d["image"] = image
    if elements is not None:
        d["snapshot"]["elements"] = elements
    return GuideRequest.model_validate(d)


async def _ask(req, *answers, **settings):
    llm = ScriptedFakeLlmClient(list(answers) or [PICK_LATTE])
    settings.setdefault("fastpath", False)
    out = await LlmGuideAgent(llm, Settings(app_key="k", **settings)).next(req)
    return out, llm


async def test_photo_and_note_go_to_the_ai_in_the_default_guide(snaps):
    out, llm = await _ask(_req(snaps))
    assert llm.calls[0]["image"] == IMG
    assert PHOTO_NOTE in llm.calls[0]["user"]
    assert out.response.action.type == ActionType.TAP and out.response.action.targetId == "e5"
    assert out.meta["photo"] is True


async def test_photo_guide_off_keeps_the_old_text_only_judgment(snaps):
    out, llm = await _ask(_req(snaps), photo_guide=False)
    assert llm.calls[0]["image"] is None
    assert PHOTO_NOTE not in llm.calls[0]["user"]


async def test_button_seen_only_on_the_photo_is_guided_with_its_box(snaps):
    out, _ = await _ask(_req(snaps, said="이제 결제할게요"), PHOTO_PAY)
    r = out.response
    assert r.action.type == ActionType.TAP and r.action.targetId == "photo" and r.action.guideText == "결제하기"
    assert (r.box.top, r.box.left, r.box.bottom, r.box.right) == (0.85, 0.6, 0.95, 0.9)    # [위, 왼쪽, 아래, 오른쪽] / 1000
    assert r.source == "photo" and out.meta["photo_box"] is True


async def test_nothing_read_by_ocr_still_asks_the_ai_when_there_is_a_photo(snaps):
    out, llm = await _ask(_req(snaps, elements=[]), PHOTO_PAY)
    assert len(llm.calls) == 1 and out.response.action.targetId == "photo"
    out, llm = await _ask(_req(snaps, elements=[], image=None), PHOTO_PAY)
    assert llm.calls == [] and out.response.action.type == ActionType.UNKNOWN      # 사진이 없으면 예전처럼 '못 찾음'


async def test_hidden_button_rule_gives_way_to_the_photo(snaps):
    # 메뉴 화면 글자에는 결제 버튼이 없다: 글자만 보면 "결제하기 버튼이 지금 안 보여요"로 막는다
    out, llm = await _ask(_req(snaps, said="결제할래요", image=None), PHOTO_PAY)
    assert llm.calls == [] and "결제하기 버튼이 지금 안 보여요" in (out.response.action.reason or "")
    out, llm = await _ask(_req(snaps, said="결제할래요"), PHOTO_PAY)                # 사진이 있으면 AI가 사진에서 찾는다
    assert len(llm.calls) == 1 and out.response.action.targetId == "photo"


async def test_photo_is_dropped_when_private_text_was_read(snaps):
    d = make_req(snaps, "s02_menu", "카페라떼 한 잔 주세요")
    d["image"] = IMG
    d["snapshot"]["elements"].append({"id": "p1", "text": "1234-5678-9012-3456", "kind": "TEXT", "confidence": 0.9,
                                      "rect": {"left": 0.1, "top": 0.9, "right": 0.4, "bottom": 0.95}})
    out, llm = await _ask(GuideRequest.model_validate(d))
    assert llm.calls[0]["image"] is None and out.meta["photo"] is False


async def test_photo_is_dropped_on_the_loyalty_screen(snaps):
    # 적립 화면에서 어르신이 누른 전화번호는 앱이 글자에서 먼저 지운다 → 서버는 지운 글자를 모르니 화면 종류로 사진을 버린다
    out, llm = await _ask(_req(snaps, said="아니요", screen="s07_loyalty"))
    assert out.meta.get("photo_dropped") == "LOYALTY"
    assert all(c["image"] is None for c in llm.calls)


async def test_a_box_that_covers_half_the_screen_is_not_trusted(snaps):
    huge = '{"screenAsks":"메뉴","action":"GUIDE","box":[0,0,1000,1000],"boxText":"결제하기"}'
    out, _ = await _ask(_req(snaps, said="이제 결제할게요"), huge)
    assert out.response.box is None and out.response.action.targetId != "photo"


def _jpeg(w=100, h=200):
    import base64, io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (w, h), "white").save(buf, "JPEG")
    return base64.b64encode(buf.getvalue()).decode()


async def test_box_is_read_in_photo_pixels_when_the_size_is_known(snaps):
    # Claude는 픽셀 좌표가 정확하다: 사진 크기를 질문에 적고 [왼쪽, 위, 오른쪽, 아래] 픽셀로 받는다
    pay = '{"screenAsks":"메뉴","action":"GUIDE","box":[10,20,50,60],"boxText":"결제하기"}'
    out, llm = await _ask(_req(snaps, said="이제 결제할게요", image=_jpeg(100, 200)), pay)
    assert "사진 크기: 100×200 픽셀" in llm.calls[0]["user"]
    b = out.response.box
    assert (b.left, b.top, b.right, b.bottom) == (0.1, 0.1, 0.5, 0.3)


async def test_more_candidates_are_shown_when_there_is_a_photo(snaps):
    out, llm = await _ask(_req(snaps), photo_guide=True)
    with_photo = len(llm.calls[0]["target_ids"])
    out, llm = await _ask(_req(snaps), photo_guide=False)
    assert with_photo > len(llm.calls[0]["target_ids"])                  # 탭 · 진행 버튼이 빠지지 않게 넓힌다


def test_loyalty_skip_picks_the_close_mark_not_the_keypad_backspace():
    from app.models import ScreenElement
    from app.screen_type import skip_button
    def el(i, t):
        return ScreenElement.model_validate({"id": f"k{i}", "text": t, "kind": "BUTTON_LIKE", "confidence": 0.9,
                                             "rect": {"left": 0.1, "top": 0.1, "right": 0.2, "bottom": 0.2}})
    keypad = [el(i, t) for i, t in enumerate(["1", "2", "3", "←", "0", "적립"])]
    assert skip_button(keypad + [el(9, "×")]).text == "×"                 # 예전: 빈 낱말이 '←'와 맞아 지우기 키를 골랐다
    assert skip_button(keypad + [el(9, "닫기")]).text == "닫기"
    assert skip_button(keypad) is None


def _el(i, t, top, left=0.7, right=0.9, kind="BUTTON_LIKE"):
    return {"id": i, "text": t, "kind": kind, "confidence": 0.9,
            "rect": {"left": left, "top": top, "right": right, "bottom": top + 0.05}}


async def test_missing_is_offered_only_with_a_photo(snaps):
    # 형식이 목록 안 번호만 허용하면 사진에서 결제하기를 보고도 아무 칸(전체 · 연하게)이나 고른다 (2026-11-13 평가)
    _, llm = await _ask(_req(snaps))
    assert "MISSING" in llm.calls[0]["target_ids"]
    _, llm = await _ask(_req(snaps), photo_guide=False)
    assert "MISSING" not in llm.calls[0]["target_ids"]


async def test_a_picked_button_with_another_name_is_not_trusted(snaps):
    # 이름은 결제하기인데 번호는 '디저트'(e3) → 번호를 버리고 사진 위치로
    odd = '{"screenAsks":"메뉴","action":"GUIDE","targetId":"e3","guideText":"결제하기","box":[850,600,950,900]}'
    out, _ = await _ask(_req(snaps, said="이제 결제할게요"), odd)
    r = out.response
    assert r.action.targetId == "photo" and r.action.guideText == "결제하기" and r.box is not None
    assert out.meta["target_mismatch"] is True


async def test_missing_button_is_found_by_name_in_the_ocr_text(snaps):
    # 사진에서 본 '결제하기'가 글자 읽기에는 '결재하기'로 깨져 있다 → 박스보다 정확한 글자 칸으로 안내한다
    d = make_req(snaps, "s02_menu", "이제 결제할게요")
    d["image"] = IMG
    d["snapshot"]["elements"].append(_el("p9", "결재하기", 0.9))
    missing = '{"screenAsks":"메뉴","action":"GUIDE","targetId":"MISSING","guideText":"결제하기"}'
    out, _ = await _ask(GuideRequest.model_validate(d), missing)
    assert out.response.action.targetId == "p9" and out.response.box is None
    assert out.meta["name_found"] is True
    assert out.response.action.guideText == "결제하기"                     # 말은 사진에서 읽은 바른 이름으로


async def test_a_garbled_button_name_is_spoken_as_read_on_the_photo(snaps):
    # 글자 읽기는 '가드결제'로 깨졌지만 AI가 사진에서 '카드결제'로 읽었다 → 칸은 글자 칸, 말은 사진 이름 (2026-11-13 평가 A07)
    d = make_req(snaps, "s02_menu", "카드로 할게요")
    d["image"] = IMG
    d["snapshot"]["elements"].append(_el("p9", "가드결제", 0.9))
    pick = '{"screenAsks":"결제","action":"GUIDE","targetId":"p9","guideText":"카드결제"}'
    out, _ = await _ask(GuideRequest.model_validate(d), pick)
    assert out.response.action.targetId == "p9" and out.response.action.guideText == "카드결제"
    other = '{"screenAsks":"결제","action":"GUIDE","targetId":"p9","guideText":"가드결제 2,000원"}'
    out, _ = await _ask(GuideRequest.model_validate(d), other)
    assert out.response.action.guideText == "가드결제"                     # 같은 글자면 그대로 (가격은 떼고 비교)


async def test_same_name_buttons_follow_the_ai_box(snaps):
    # 같은 글자의 칸이 여럿('선택')이면 AI가 사진에서 가리킨 자리와 가까운 칸
    d = make_req(snaps, "s02_menu", "샷 추가할게요")
    d["image"] = IMG
    d["snapshot"]["elements"] += [_el("q1", "선택", 0.3), _el("q2", "선택", 0.6)]
    pick = '{"screenAsks":"옵션","action":"GUIDE","targetId":"MISSING","guideText":"선택","box":[600,700,650,900]}'
    out, _ = await _ask(GuideRequest.model_validate(d), pick)
    assert out.response.action.targetId == "q2"
    no_box = '{"screenAsks":"옵션","action":"GUIDE","targetId":"MISSING","guideText":"선택"}'
    out, _ = await _ask(GuideRequest.model_validate(d), no_box)
    assert out.response.action.type != ActionType.TAP                     # 어느 칸인지 모르면 가리키지 않는다 (화면 설명으로 되묻는다)


async def test_the_yellow_box_wraps_the_whole_button(snaps):
    # 글자 칸(카페라떼 글자)으로 골라도 AI가 사진에서 본 버튼 전체 상자를 같이 보낸다 → 앱이 버튼 전체에 노란 칸 (2026-11-13 요청)
    whole = '{"screenAsks":"메뉴","action":"GUIDE","targetId":"e5","guideText":"카페라떼","box":[33,40,62,92]}'
    out, _ = await _ask(_req(snaps, image=_jpeg(100, 200)), whole)
    r = out.response
    assert r.action.targetId == "e5" and out.meta["button_box"] is True
    assert (r.box.left, r.box.top, r.box.right, r.box.bottom) == pytest.approx((0.33, 0.2, 0.62, 0.46))   # 글자 칸(0.35~0.6)까지 품는다


async def test_a_button_box_that_misses_the_button_text_is_dropped(snaps):
    # 사진과 글자 읽기는 다른 순간이다: 상자가 고른 버튼 글자와 안 겹치면(휴대폰이 움직였거나 AI가 빗나감) 글자 칸만 쓴다
    off = '{"screenAsks":"메뉴","action":"GUIDE","targetId":"e5","guideText":"카페라떼","box":[70,130,90,180]}'
    out, _ = await _ask(_req(snaps, image=_jpeg(100, 200)), off)
    assert out.response.action.targetId == "e5" and out.response.box is None


async def test_the_fast_path_waits_for_the_photo(snaps):
    # 빠른 길은 글자만 보고 골라서 버튼 전체 상자도, 사진에만 보이는 선택 표시도 모른다 → 사진이 있으면 AI가 본다
    out, llm = await _ask(_req(snaps, image=None), fastpath=True)
    assert llm.calls == [] and out.meta["source"] == "fastpath"
    out, llm = await _ask(_req(snaps), fastpath=True)
    assert len(llm.calls) == 1


def test_the_spoken_name_is_fixed_only_for_garbled_text():
    from app.agent import _photo_name
    from app.models import ScreenElement
    def el(t):
        return ScreenElement.model_validate({"id": t, "text": t, "kind": "BUTTON_LIKE", "confidence": 0.9,
                                             "rect": {"left": 0.1, "top": 0.1, "right": 0.2, "bottom": 0.2}})
    assert _photo_name("카드결제", "가드결제", [el("가드결제"), el("현금결제")]) == "카드결제"
    assert _photo_name("포장하기", "매장하기", [el("매장하기"), el("포장하기")]) is None    # 둘 다 있는 다른 버튼
    assert _photo_name("포장", "매장", [el("매장")]) is None                                # 짧은 이름은 바꾸지 않는다
    assert _photo_name("주문담기", "주문달기", [el("주문달기")]) == "주문담기"

