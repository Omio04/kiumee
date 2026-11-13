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
