"""도움 모드: 어르신이 물으면 그 순간의 화면을 보고 답하고 "또 도움이 필요하세요?"라고 되묻는다."""
import pytest

from app import help_mode
from app.agent import LlmGuideAgent
from app.llm import ScriptedFakeLlmClient
from app.models import GuideRequest, HistoryItem
from app.settings import Settings
from .conftest import make_req

ASKED = "결제 수단을 고르는 화면이에요. 또 도움이 필요하세요?"


def agent(llm, **kw):
    return LlmGuideAgent(llm, Settings(**{"app_key": "k", "cache_size": 0, "hedge_after_s": 0, "help_mode": True, **kw}))


def req(snaps, screen, said, history=None, goal=None):
    return GuideRequest.model_validate(make_req(snaps, screen, said, goal=goal, history=history or []))


def answer(msg):
    return '{"screenAsks":"x","action":"ASK_USER","message":"%s"}' % msg


# ---- 문장 다듬기
def test_follow_up_is_appended_within_80_chars():
    assert help_mode.with_follow_up("안 하셔도 돼요.") == "안 하셔도 돼요. 또 도움이 필요하세요?"
    long = "첫 문장이에요. " + "가" * 70
    out = help_mode.with_follow_up(long)
    assert out == "첫 문장이에요. 또 도움이 필요하세요?" and len(out) <= 80


def test_follow_up_is_not_doubled_and_empty_answers_still_ask():
    already = "그건 이래요. 또 도움이 필요하세요?"
    assert help_mode.with_follow_up(already) == already
    assert help_mode.with_follow_up("") == "또 도움이 필요하세요?"
    assert all(len(help_mode.with_follow_up("가" * n)) <= 80 for n in (5, 30, 66, 67, 120))


def test_decline_only_counts_right_after_the_follow_up_question():
    asked = [HistoryItem(screenId="s", action="ASK_USER", targetText=ASKED)]
    for said in ("아니요", "없어요", "괜찮아요", "됐어요", "아니요 괜찮아요", "고마워요", "감사합니다"):
        assert help_mode.is_decline(said, asked), said
    for said in ("카드는 어디에 대요?", "아니 이거 왜 안 돼요", "이전으로 가고 싶어요"):
        assert not help_mode.is_decline(said, asked), said
    other = [HistoryItem(screenId="s", action="ASK_USER", targetText="매장에서 드실래요?")]
    assert not help_mode.is_decline("아니요", other)                    # 다른 질문의 "아니요"는 도움이 끝났다는 뜻이 아니다
    assert not help_mode.is_decline("아니요", [])


# ---- 에이전트
async def test_a_question_gets_the_answer_and_the_follow_up(snaps):
    llm = ScriptedFakeLlmClient([answer("꼭 하지 않으셔도 돼요. 적립을 안 하시려면 ×를 누르세요.")])
    r = await agent(llm).next(req(snaps, "s07_loyalty", "이거 꼭 해야 하는 거야?"))
    a = r.response.action
    assert a.type.value == "ASK_USER" and a.question.endswith("또 도움이 필요하세요?") and len(a.question) <= 80
    assert r.meta["source"] == "help" and "[화면 글자(위→아래)]" in llm.calls[0]["user"] and "어르신 말: 이거 꼭" in llm.calls[0]["user"]
    assert "도움을 주는" not in llm.calls[0]["system"] and "막힌 어르신" in llm.calls[0]["system"]


async def test_loyalty_is_not_auto_skipped_in_help_mode_the_user_decides(snaps):
    llm = ScriptedFakeLlmClient([answer("적립은 선택이에요.")])
    r = await agent(llm).next(req(snaps, "s07_loyalty", "이게 뭐예요"))
    assert len(llm.calls) == 1 and r.response.action.type.value == "ASK_USER"


async def test_the_card_screen_is_a_question_not_an_automatic_end(snaps):
    """결제 진행 문구가 보여도 "이제 끝난 거야?"에는 답해야 한다 (규칙으로 바로 종료하지 않는다)."""
    llm = ScriptedFakeLlmClient([answer("아직 끝나지 않았어요. 카드를 넣어 주세요.")])
    r = await agent(llm).next(req(snaps, "s06_card", "이제 끝난 거야?"))
    assert r.response.action.type.value == "ASK_USER" and "아직" in r.response.action.question


async def test_pointing_at_a_button_is_whitelisted(snaps):
    ok = next(e["id"] for e in snaps["s05_payment"]["elements"] if e["text"] == "이전")
    llm = ScriptedFakeLlmClient(['{"screenAsks":"결제","action":"GUIDE","targetId":"%s","guideText":"이전"}' % ok])
    r = await agent(llm).next(req(snaps, "s05_payment", "아까 화면으로 어떻게 돌아가?"))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText == "이전"
    llm = ScriptedFakeLlmClient(['{"screenAsks":"x","action":"GUIDE","targetId":"없는id","guideText":"뒤로"}'])
    r = await agent(llm).next(req(snaps, "s05_payment", "돌아가고 싶어"))
    assert r.response.action.type.value != "TAP"                        # 화면에 없는 버튼은 안내하지 않는다


async def test_declining_the_follow_up_ends_politely_without_the_llm(snaps):
    llm = ScriptedFakeLlmClient([answer("x")])
    hist = [{"screenId": "s", "action": "ASK_USER", "targetText": ASKED}]
    r = await agent(llm).next(req(snaps, "s05_payment", "아니요 괜찮아요", history=hist))
    assert r.response.action.type.value == "DONE" and "언제든" in r.response.action.guideText and not llm.calls


async def test_please_help_me_is_a_question_but_calling_the_staff_still_ends(snaps):
    llm = ScriptedFakeLlmClient([answer("이 화면은 결제 수단을 고르는 곳이에요.")])
    r = await agent(llm).next(req(snaps, "s05_payment", "도와주세요 지금 뭐 해야 돼요"))
    assert r.response.action.type.value == "ASK_USER" and len(llm.calls) == 1
    r = await agent(ScriptedFakeLlmClient([answer("x")])).next(req(snaps, "s05_payment", "직원 좀 불러주세요"))
    assert r.response.action.type.value == "DONE"


async def test_talk_that_makes_the_app_sound_busy_is_replaced(snaps):
    llm = ScriptedFakeLlmClient([answer("화면이 바뀌는 중이에요. 잠시만 기다려 주세요.")])
    r = await agent(llm).next(req(snaps, "s03_options", "지금 뭐 해요"))
    q = r.response.action.question
    assert "바뀌는 중" not in q and "기다려" not in q and q.startswith("옵션을 고르는 화면이에요")


async def test_without_an_utterance_the_old_screen_change_path_still_works(snaps):
    """서버가 도움 모드여도 말 없이 온 요청(옛 앱)은 예전 방식 그대로."""
    llm = ScriptedFakeLlmClient(['{"action":"ASK_USER","message":"따뜻한 걸로 드릴까요, 차가운 걸로 드릴까요?"}'])
    r = await agent(llm, fastpath=False).next(req(snaps, "s03_options", None, goal="아메리카노"))
    assert r.meta.get("source") != "help" and r.response.action.type.value == "ASK_USER"


async def test_unparseable_answer_still_gets_a_friendly_reply(snaps):
    llm = ScriptedFakeLlmClient(["이상한 글", "또 이상한 글"])
    r = await agent(llm).next(req(snaps, "s03_options", "이거 뭐예요"))
    assert r.response.action.type.value == "ASK_USER" and "옵션을 고르는 화면이에요" in r.response.action.question


# ---- 사진: 글자 읽기가 틀려도 AI가 실제 화면을 본다 (2026-11-13 실기기: 결제하기 버튼이 글자에 없어 엉뚱한 답)
async def test_photo_reaches_the_llm_in_help_mode(snaps):
    llm = ScriptedFakeLlmClient([answer("화면 맨 아래 초록색 칸이에요.")])
    d = make_req(snaps, "s01_dine", "결제 버튼이 어디야?", history=[])
    d["image"] = "QUJD"
    r = await agent(llm).next(GuideRequest.model_validate(d))
    assert llm.calls[0]["image"] == "QUJD"
    assert "사진" in llm.calls[0]["system"]
    assert r.response.action.question.startswith("화면 맨 아래")


async def test_no_photo_means_text_only_as_before(snaps):
    llm = ScriptedFakeLlmClient([answer("결제 수단을 고르는 화면이에요.")])
    await agent(llm).next(req(snaps, "s01_dine", "여기가 뭐 하는 화면이야?"))
    assert llm.calls[0]["image"] is None


async def test_photo_is_dropped_when_a_card_number_was_read(snaps):
    llm = ScriptedFakeLlmClient([answer("카드를 넣는 화면이에요.")])
    d = make_req(snaps, "s01_dine", "여기서 뭐 해?", history=[])
    d["image"] = "QUJD"
    d["snapshot"]["elements"].append({"id": "x9", "text": "1234 5678 9012 3456", "rect": {"left": 0.1, "top": 0.1, "right": 0.5, "bottom": 0.2}, "confidence": 0.9, "kind": "TEXT"})
    await agent(llm).next(GuideRequest.model_validate(d))
    assert llm.calls[0]["image"] is None                    # 글자는 걸렀어도 사진에는 번호가 남아 있을 수 있다


# ---- 글자 목록에 없고 사진에서만 보이는 칸: AI가 준 박스로 가리킨다
GUIDE_BOX = '{"screenAsks":"x","action":"GUIDE","targetId":null,"guideText":null,"message":"안 골라도 돼요.","box":[850,500,950,900],"boxText":"주문담기"}'


async def test_photo_box_points_at_a_button_the_ocr_missed(snaps):
    llm = ScriptedFakeLlmClient([GUIDE_BOX])
    d = make_req(snaps, "s01_dine", "그냥 결제하고 싶어", history=[]); d["image"] = "QUJD"
    r = (await agent(llm).next(GuideRequest.model_validate(d))).response
    assert r.action.type.value == "TAP" and r.action.targetId == "photo" and r.action.guideText == "주문담기"
    assert (r.box.left, r.box.top, r.box.right, r.box.bottom) == (0.5, 0.85, 0.9, 0.95)    # [위, 왼쪽, 아래, 오른쪽] 0~1000 → 0~1
    assert r.say == "안 골라도 돼요."


async def test_photo_box_is_ignored_without_a_photo_or_with_a_silly_box(snaps):
    d = make_req(snaps, "s01_dine", "그냥 결제하고 싶어", history=[])
    r = (await agent(ScriptedFakeLlmClient([GUIDE_BOX])).next(GuideRequest.model_validate(d))).response      # 사진 없음
    assert r.box is None and r.action.type.value != "TAP"
    d["image"] = "QUJD"
    huge = GUIDE_BOX.replace("[850,500,950,900]", "[0,0,1000,1000]")                                         # 화면 전체를 덮는 박스
    r = (await agent(ScriptedFakeLlmClient([huge])).next(GuideRequest.model_validate(d))).response
    assert r.box is None
