"""화면 아래쪽이 카메라 밖이라 담기 버튼이 안 보일 때 지어내지 않는다 (2026-11-13 실기기 기록 그대로)."""
from app import hidden_button
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest
from app.ranker import candidate_pool, is_not_a_button
from app.settings import Settings

# 실제 기록의 화면 (담기 버튼은 카메라 밖, 노트북 키보드 글자까지 읽힘)
SEEN = ["카오톡%20받은%20파일/index.html", "선택하신 상품 의 옵션상품을 모두 선택해주세요", "(ICE)아메리카노", "2000",
        "선택된 읍션", "초기화", "텀블러 선택", "선택", "저당 스테비아 슈가 주가", "+6002", "Prt Sc", "Insert"]


def els(texts, kind="BUTTON_LIKE"):
    return GuideRequest.model_validate({"sessionId": "x", "snapshot": {"screenId": "s", "frameId": 1, "timestampMs": 0, "stableFrames": 2,
        "elements": [{"id": f"e{i}", "text": t, "kind": kind, "rect": {"left": .1, "top": .05 * i + .01, "right": .6, "bottom": .05 * i + .04}}
                     for i, t in enumerate(texts, 1)]}}).snapshot.elements


def test_the_add_button_is_missing_in_the_real_log_screen():
    for said in ("그대로 담을게", "그냥 그대로 담을게", "그대로 그냥 주문 담는다고", "담아 주세요"):
        assert hidden_button.missing_button(said, els(SEEN)) == "담기", said


def test_visible_button_even_with_ocr_noise_is_not_missing():
    for visible in ("주문담기", "담기", "주문 담기", "주문답기", "주문담기 2,000원"):
        assert hidden_button.missing_button("그대로 담을게", els(SEEN + [visible])) is None, visible


def test_checkout_and_other_talk():
    assert hidden_button.missing_button("결제할게요", els(SEEN)) == "결제하기"
    assert hidden_button.missing_button("결제할게요", els(SEEN + ["결제하기"])) is None
    assert hidden_button.missing_button("아메리카노 주세요", els(SEEN)) is None
    assert hidden_button.missing_button(None, els(SEEN)) is None
    assert hidden_button.missing_button("담을게", []) is None                      # 화면을 아예 못 읽었으면 다른 안내가 한다


def test_summary_lines_are_not_buttons():
    for t in ("선택된 옵션", "선택된 읍션", "선택하신 상품의 옵션상품을 모두 선택해주세요", "결제 수단을 선택해주세요"):
        assert is_not_a_button(t), t
    for t in ("선택", "텀블러 선택", "HOT", "담기", "초기화"):
        assert not is_not_a_button(t), t
    pool = [e.text for e in candidate_pool(els(SEEN))]
    assert "선택된 읍션" not in pool and "선택하신 상품 의 옵션상품을 모두 선택해주세요" not in pool


async def test_the_server_tells_the_user_to_back_the_phone_up_instead_of_guessing():
    llm = ScriptedFakeLlmClient(['{"action":"GUIDE","targetId":"e5","guideText":"선택된 읍션"}'])
    body = {"sessionId": "x", "utterance": "그대로 담을게", "goal": "아이스 아메리카노",
            "snapshot": {"screenId": "s8", "frameId": 1, "timestampMs": 0, "stableFrames": 2,
                         "elements": [e.model_dump(mode="json") for e in els(SEEN)]}, "history": []}
    r = await LlmGuideAgent(llm, Settings(app_key="k", cache_size=0, hedge_after_s=0)).next(GuideRequest.model_validate(body))
    a = r.response.action
    assert a.type.value == "UNKNOWN" and "담기" in a.reason and "뒤로" in a.reason and len(a.reason) <= 80
    assert len(llm.calls) == 0                                                     # LLM에 맡기지 않는다
    assert r.response.goal == "아이스 아메리카노, 그대로 담을게"
