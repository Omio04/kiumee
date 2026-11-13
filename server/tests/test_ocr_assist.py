"""OCR 보조: 공통 낱말 보정(lexicon) · 번호를 그린 사진(Set-of-Mark). 둘 다 설정으로 켠다 (기본 꺼짐)."""
import base64
import io

from app import lexicon, som
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest, ScreenElement
from app.settings import Settings
from .conftest import make_req


def test_lexicon_fixes_the_confusions_seen_on_the_real_device():
    assert lexicon.correct("주문달기") == "주문담기"            # 담→달 (2026-11-13 로그)
    assert lexicon.correct("선택된 읍션") == "선택된 옵션"       # 옵→읍
    assert lexicon.correct("결재하기") == "결제하기"
    assert lexicon.correct("2,00021") == "2,000원" and lexicon.correct("4,500번") == "4,500원"


def test_lexicon_leaves_unknown_words_and_menu_names_alone():
    for t in ("아메리카노", "치즈케이크", "메가엠지씨커피", "Prt Sc", "e", "광주", "06:10 한빛고속"):
        assert lexicon.correct(t) == t
    assert lexicon.correct("결제하기") == "결제하기"


def _png_b64(w=200, h=300):
    from PIL import Image
    b = io.BytesIO(); Image.new("RGB", (w, h), (240, 240, 240)).save(b, "JPEG"); return base64.b64encode(b.getvalue()).decode()


def test_marks_are_drawn_on_the_photo():
    el = ScreenElement.model_validate({"id": "1", "text": "결제하기", "rect": {"left": 0.5, "top": 0.8, "right": 0.9, "bottom": 0.9}, "confidence": 0.9, "kind": "BUTTON_LIKE"})
    out = som.draw_marks(_png_b64(), {"1": el})
    from PIL import Image
    im = Image.open(io.BytesIO(base64.b64decode(out))).convert("RGB")
    assert im.getpixel((int(0.5 * 200), int(0.85 * 300)))[1] < 100        # 칸 테두리(자홍색)가 그려졌다
    assert som.draw_marks("깨진사진", {"1": el}) is None                     # 못 그리면 None → 원래 사진


async def test_short_number_is_mapped_back_to_the_real_element_id(snaps):
    d = make_req(snaps, "s01_dine", "결제하고 싶어", history=[])
    d["snapshot"]["elements"] = [
        {"id": "e6a7649f7", "text": "장바구니 조회", "rect": {"left": 0.1, "top": 0.8, "right": 0.4, "bottom": 0.85}, "confidence": 0.9, "kind": "BUTTON_LIKE"},
        {"id": "e3cc2940e", "text": "결제하기", "rect": {"left": 0.5, "top": 0.8, "right": 0.9, "bottom": 0.9}, "confidence": 0.9, "kind": "BUTTON_LIKE"}]
    d["image"] = _png_b64()
    llm = ScriptedFakeLlmClient(['{"screenAsks":"x","action":"GUIDE","targetId":"2","guideText":"결제하기","message":"담겨 있어요."}'])
    ag = LlmGuideAgent(llm, Settings(app_key="k", cache_size=0, hedge_after_s=0, help_mode=True, som_marks=True))
    r = await ag.next(GuideRequest.model_validate(d))
    assert r.response.action.targetId == "e3cc2940e"                         # 앱이 아는 원래 번호로 돌아간다
    assert "2|결제하기" in llm.calls[0]["user"] and llm.calls[0]["image"] != d["image"]   # 짧은 번호 + 번호를 그린 사진
    assert "_alias" not in r.meta


# ---- OCR이 못 읽은 버튼을 사진 격자로 찾는다 (대략 → 확대해서 정밀)
async def test_missing_button_is_found_on_the_photo_grid_and_zoomed(snaps):
    d = make_req(snaps, "s01_dine", "케이크 먹고 싶어", history=[])
    d["snapshot"]["elements"] = [{"id": "e1", "text": "커피", "rect": {"left": 0.0, "top": 0.05, "right": 0.15, "bottom": 0.12}, "confidence": 0.9, "kind": "BUTTON_LIKE"}]
    d["image"] = _png_b64(600, 800)
    llm = ScriptedFakeLlmClient([
        '{"screenAsks":"x","action":"GUIDE","targetId":"MISSING","guideText":"푸드","message":"케이크는 푸드에 있어요. 노란 칸 푸드를 눌러 주세요."}',
        '{"screenAsks":"x","action":"GUIDE","targetId":"F1","guideText":"푸드"}',     # 격자: 대략 오른쪽 위
        '{"screenAsks":"x","action":"GUIDE","targetId":"B2","guideText":"푸드"}'])    # 확대한 격자
    r = await LlmGuideAgent(llm, Settings(app_key="k", cache_size=0, hedge_after_s=0, help_mode=True, total_budget_s=20)).next(GuideRequest.model_validate(d))
    assert r.response.action.type.value == "TAP" and r.response.action.targetId == "photo" and r.response.box is not None
    assert r.meta.get("grid") == "F1" and r.meta.get("grid_fine") == "B2"
    assert r.response.box.left > 0.5 and r.response.box.top < 0.3                    # 오른쪽 위
    assert r.response.say == "케이크는 푸드에 있어요."                                # 누르라는 문장은 앱이 읽는다
    assert "F1" in llm.calls[1]["target_ids"] and llm.calls[1]["image"] != d["image"]   # 격자를 그린 사진


async def test_when_the_grid_cannot_find_it_the_app_keeps_seeking(snaps):
    d = make_req(snaps, "s01_dine", "결제하고 싶어", history=[]); d["image"] = _png_b64()
    llm = ScriptedFakeLlmClient(['{"screenAsks":"x","action":"GUIDE","targetId":"MISSING","guideText":"결제하기","message":"담겨 있어요."}',
                                 '{"screenAsks":"x","action":"GUIDE","targetId":"MISSING","guideText":"결제하기"}'])
    r = await LlmGuideAgent(llm, Settings(app_key="k", cache_size=0, hedge_after_s=0, help_mode=True, total_budget_s=20)).next(GuideRequest.model_validate(d))
    assert r.response.seek == "결제하기" and r.response.action.type.value == "ASK_USER"
