"""도움 모드: 어르신이 물으면 그 순간의 화면을 보고 답하고 "또 도움이 필요하세요?"라고 되묻는다."""
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
    llm = ScriptedFakeLlmClient([answer("꼭 하지 않으셔도 돼요. 적립은 선택이라 건너뛸 수 있어요.")])
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


# ---- 말과 표시: "~을 누르세요"라고만 하고 칸을 안 가리키면 그 칸을 가리킨다
async def test_pressing_words_without_a_pointer_get_a_pointer(snaps):
    from app.agent import _pressed_candidate
    from app.models import ScreenElement
    mk = lambda i, t: ScreenElement.model_validate({"id": i, "text": t, "rect": {"left": 0.1, "top": 0.1, "right": 0.3, "bottom": 0.2}, "confidence": 0.9, "kind": "BUTTON_LIKE"})
    cands = {"a": mk("a", "×"), "b": mk("b", "승인 요청"), "c": mk("c", "취소")}
    assert _pressed_candidate("적립하지 않으시려면 오른쪽 위 ×를 누르시면 닫혀요.", cands).id == "a"
    assert _pressed_candidate("카드를 대세요. 준비되면 승인 요청을 누르세요.", cands) is None        # 순서 말 → 지금 누를 칸이 아니다
    assert _pressed_candidate("승인 요청을 누르거나 취소를 누르세요.", cands) is None               # 둘 다 → 모호
    assert _pressed_candidate("지금은 결제 화면이에요.", cands) is None
    cands["f"], cands["g"] = mk("f", "푸드"), mk("g", "커피")
    ask = "이 화면은 커피 메뉴만 있어요. 케이크는 위쪽 '푸드' 탭에 있을 것 같아요. 그곳을 눌러 볼까요?"
    assert _pressed_candidate(ask, cands).id == "f"                                              # 눌러라는 말 가장 가까이의 칸 (2026-11-13 실기기: 푸드 탭)
    assert _pressed_candidate("커피 메뉴가 있어요." + " 가" * 30 + " 눌러 보세요", cands) is None   # 멀면 믿지 않는다
    cands["d"] = mk("d", "티")
    assert _pressed_candidate("지금 음료 화면에는 아이스티가 안 보여요. 위쪽 '티' 메뉴를 눌러 보세요.", cands).id == "d"      # 따옴표가 붙어도


async def test_saying_press_x_also_points_at_x(snaps):
    """"적립을 안 하시려면 ×를 누르세요"라고만 말하고 칸을 안 가리키던 것 (2026-11-13 실기기) → × 칸을 가리키고 말은 앞에 읽는다."""
    llm = ScriptedFakeLlmClient([answer("꼭 하지 않으셔도 돼요. 적립을 안 하시려면 ×를 누르세요.")])
    r = await agent(llm).next(req(snaps, "s07_loyalty", "이거 꼭 해야 하는 거야?"))
    assert r.response.action.type.value == "TAP" and r.response.action.guideText.strip() == "×"
    assert r.response.say and "×" in r.response.say


# ---- 방금 안내한 칸을 또 가리키지 않는다 / 같은 지시를 두 번 읽지 않는다 (2026-11-13 실기기: 푸드를 누른 뒤에도 푸드)
def _food_req(snaps, said, history):
    d = make_req(snaps, "s01_dine", said, history=history)
    d["snapshot"]["elements"] = [
        {"id": "e1", "text": "푸드", "rect": {"left": 0.4, "top": 0.1, "right": 0.5, "bottom": 0.14}, "confidence": 0.9, "kind": "BUTTON_LIKE"},
        {"id": "e2", "text": "치즈케이크", "rect": {"left": 0.1, "top": 0.4, "right": 0.4, "bottom": 0.5}, "confidence": 0.9, "kind": "BUTTON_LIKE"},
        {"id": "e3", "text": "마카롱", "rect": {"left": 0.5, "top": 0.4, "right": 0.9, "bottom": 0.5}, "confidence": 0.9, "kind": "BUTTON_LIKE"}]
    return GuideRequest.model_validate(d)


def _guide(tid, text, msg):
    return '{"screenAsks":"x","action":"GUIDE","targetId":"%s","guideText":"%s","message":"%s"}' % (tid, text, msg)


async def test_does_not_point_again_at_the_tab_just_pressed(snaps):
    hist = [HistoryItem(screenId="s1", action="TAP", targetText="푸드")]
    llm = ScriptedFakeLlmClient([_guide("e1", "푸드", "케이크는 푸드 탭에 있어요."), _guide("e2", "치즈케이크", "치즈케이크가 보여요.")])
    r = (await agent(llm).next(_food_req(snaps, "케이크를 먹고 싶어", hist))).response
    assert r.action.type.value == "TAP" and r.action.guideText == "치즈케이크"            # 같은 칸이면 다시 묻고 다음 칸을 고른다
    assert len(llm.calls) == 2 and "이미 눌렀을 가능성" in llm.calls[1]["user"]


async def test_repeat_that_stays_the_same_gets_no_highlight(snaps):
    hist = [HistoryItem(screenId="s1", action="TAP", targetText="푸드")]
    llm = ScriptedFakeLlmClient([_guide("e1", "푸드", "푸드 탭이에요.")])
    r = (await agent(llm).next(_food_req(snaps, "케이크를 먹고 싶어", hist))).response
    assert r.action.type.value == "ASK_USER"                                           # 이미 누른 칸을 또 칠하지 않는다


async def test_first_time_pointing_is_not_blocked(snaps):
    llm = ScriptedFakeLlmClient([_guide("e1", "푸드", "케이크는 푸드 탭에 있어요.")])
    r = (await agent(llm).next(_food_req(snaps, "케이크를 먹고 싶어", []))).response
    assert r.action.type.value == "TAP" and r.action.guideText == "푸드" and len(llm.calls) == 1


async def test_say_keeps_the_reason_but_drops_the_press_sentence(snaps):
    llm = ScriptedFakeLlmClient([_guide("e1", "푸드", "케이크는 푸드 탭에 있어요. 위쪽 노란 칸 푸드를 눌러 주세요. 치즈케이크가 보여요.")])
    r = (await agent(llm).next(_food_req(snaps, "케이크를 먹고 싶어", []))).response
    assert r.say == "케이크는 푸드 탭에 있어요. 치즈케이크가 보여요."                  # 눌러 주세요는 앱이 따로 읽는다


# ---- 다른 키오스크로 옮겨 가면 옛 대화를 비운다 (2026-11-13 실기기: 버스표 대화가 카페 답에 섞임)
def _els(*texts):
    return [{"id": f"e{i}", "text": t, "rect": {"left": 0.05, "top": 0.05 + 0.04 * i, "right": 0.5, "bottom": 0.08 + 0.04 * i}, "confidence": 0.9, "kind": "BUTTON_LIKE"} for i, t in enumerate(texts)]


CAFE_COFFEE = ["메가엠지씨커피", "LANGUAGE", "처음으로", "신메뉴 추천메뉴", "커피", "디카페인", "음료", "푸드", "겨울시즌", "아메리카노", "헛개리카노", "장바구니 조회"]
CAFE_FOOD = ["메가엠지씨커피", "LANGUAGE", "처음으로", "신메뉴 추천메뉴", "커피", "디카페인", "음료", "푸드", "마카롱", "치즈케이크", "허니 브레드", "장바구니 조회"]
BUS = ["출발 시간을 선택해 주세요", "서울 광주", "06:10 한빛고속", "08:40 새길여객", "12:05 한빛고속", "20:19 한빛고속", "이전", "처음 화면", "좌석 선택"]


async def _two_requests(snaps, first, second, llm_answers):
    llm = ScriptedFakeLlmClient(llm_answers)
    ag = agent(llm)
    d1 = make_req(snaps, "s01_dine", "여기서 뭐 해?", history=[]); d1["snapshot"]["elements"] = _els(*first); d1["snapshot"]["screenId"] = "A"
    await ag.next(GuideRequest.model_validate(d1))
    hist = [HistoryItem(screenId="A", action="ASK_USER", targetText="출발 시간을 고르셔야 해요")]
    d2 = make_req(snaps, "s01_dine", "케이크를 먹고 싶어", history=hist); d2["snapshot"]["elements"] = _els(*second); d2["snapshot"]["screenId"] = "B"
    r = await ag.next(GuideRequest.model_validate(d2))
    return llm, r


async def test_history_is_cleared_when_the_kiosk_changes(snaps):
    llm, r = await _two_requests(snaps, BUS, CAFE_COFFEE, [answer("가요."), answer("푸드 탭이에요.")])
    assert "[이전 대화]" not in llm.calls[1]["user"] and r.meta.get("history_reset") is True


async def test_history_is_kept_when_only_the_tab_changes(snaps):
    llm, r = await _two_requests(snaps, CAFE_COFFEE, CAFE_FOOD, [answer("가요."), answer("치즈케이크가 보여요.")])
    assert "[이전 대화]" in llm.calls[1]["user"] and not r.meta.get("history_reset")


# ---- 실기기 로그 (2026-11-13 19:48): AI는 사진에서 '주문담기'를 읽어 말했는데, 글자 읽기는 '주문달기'로 깨뜨려 칸이 안 쳐졌다
def test_garbled_ocr_name_is_matched_to_the_name_the_ai_read_from_the_photo():
    from app.agent import _pressed_candidate
    from app.models import ScreenElement
    mk = lambda i, t: ScreenElement.model_validate({"id": i, "text": t, "rect": {"left": 0.1, "top": 0.1, "right": 0.3, "bottom": 0.2}, "confidence": 0.9, "kind": "BUTTON_LIKE"})
    cands = {"a": mk("a", "주문달기"), "b": mk("b", "초기화|"), "c": mk("c", "텀블러 선택"), "d": mk("d", "저당 스테비아 슈가 추가")}
    # 먼저 + 지금 누를 칸: "결제하시려면 먼저 '주문담기'를 눌러야 해요" (예전에는 "먼저" 때문에 포기했다)
    assert _pressed_candidate("결제하시려면 먼저 '주문담기'를 눌러야 해요. 화면 하단 주황색 칸이에요.", cands).id == "a"
    # 깨진 글자라도 전혀 다른 칸은 가리키지 않는다
    assert _pressed_candidate("결제하시려면 먼저 '장바구니 조회'를 눌러야 해요.", cands) is None
    # 나중 단계는 여전히 가리키지 않는다
    assert _pressed_candidate("옵션을 먼저 골라야 해요. 다 고르면 '주문담기'를 누르세요.", cands) is None


def test_fuzzy_match_needs_a_clear_winner():
    from app.agent import _pressed_candidate
    from app.models import ScreenElement
    mk = lambda i, t: ScreenElement.model_validate({"id": i, "text": t, "rect": {"left": 0.1, "top": 0.1, "right": 0.3, "bottom": 0.2}, "confidence": 0.9, "kind": "BUTTON_LIKE"})
    cands = {"a": mk("a", "주문담기"), "b": mk("b", "주문달기")}                          # 둘 다 비슷하다 → 어느 칸인지 모른다
    assert _pressed_candidate("'주문담기'를 눌러 주세요.", cands).id == "a"                  # 정확히 같은 칸이 있으면 그쪽
    cands2 = {"a": mk("a", "주문담기X"), "b": mk("b", "주문담7")}
    assert _pressed_candidate("'주문담기'를 눌러 주세요.", cands2) is None


# ---- 누르라고 하면서 칠 칸이 없으면 "누르세요"라고 말하지 않고 찾는다고 알린다 (2026-11-13 실기기 로그: 주문담기 · 2,000원 결제하기가 글자 목록에 없었다)
async def test_press_words_without_any_candidate_become_a_seek_request(snaps):
    llm = ScriptedFakeLlmClient([answer("빨리 결제하려면 옵션을 먼저 정해야 해요. 없으면 '주문담기'를 누르세요.")])
    r = (await agent(llm).next(_food_req(snaps, "빨리 결제하고 싶은데", []))).response
    assert r.action.type.value == "ASK_USER" and r.seek == "주문담기"
    assert "누르세요" not in r.action.question and "주문담기" in r.action.question and "비춰" in r.action.question and len(r.action.question) <= 80


async def test_price_is_removed_from_the_sought_name(snaps):
    llm = ScriptedFakeLlmClient([answer("화면 하단의 '2,000원 결제하기' 검은 칸을 누르시면 결제로 넘어가요.")])
    r = (await agent(llm).next(_food_req(snaps, "이제 뭐 해야 돼", []))).response
    assert r.seek == "결제하기"


async def test_a_matching_candidate_wins_over_seeking(snaps):
    llm = ScriptedFakeLlmClient([answer("케이크는 위쪽 '푸드'를 눌러 보세요.")])
    r = (await agent(llm).next(_food_req(snaps, "케이크 먹고 싶어", []))).response
    assert r.action.type.value == "TAP" and r.action.guideText == "푸드" and r.seek is None            # 칸이 있으면 찾지 않고 바로 가리킨다


async def test_later_step_buttons_are_not_sought(snaps):
    llm = ScriptedFakeLlmClient([answer("옵션을 먼저 골라야 해요. 다 고르면 '주문담기'를 누르세요.")])
    r = (await agent(llm).next(_food_req(snaps, "결제하고 싶어", []))).response
    assert r.seek is None                                                                               # 지금 누를 칸이 아니면 찾지 않는다


# ---- 실기기 로그 (2026-11-13 20:0x): 결제하기는 글자 목록에 없는데, 형식이 번호를 강제해서 AI가 엉뚱한 칸(마카옹 · 상품) 번호를 골랐다
CART = ["메가엠지씨커피", "처음으로", "푸드", "상품", "허니 브레드", "마카옹", "조코칩 쿠키", "치즈케이크", "장바구니 조회", "남은시간", "선택한 상품", "1개"]


def _cart_req(snaps, said):
    d = make_req(snaps, "s01_dine", said, history=[])
    d["snapshot"]["elements"] = _els(*CART)
    return GuideRequest.model_validate(d)


def _guide_raw(target_id, text, msg):
    return '{"screenAsks":"x","action":"GUIDE","targetId":%s,"guideText":"%s","message":"%s"}' % (target_id, text, msg)


async def test_wrong_number_with_a_different_name_is_not_highlighted(snaps):
    macaron = CART.index("마카옹")
    llm = ScriptedFakeLlmClient([_guide_raw(f'"e{macaron}"', "결제하기", "치즈케이크 1개가 담겨 있어요. 아래 검은 결제하기 칸을 눌러 주세요.")])
    r = await agent(llm).next(_cart_req(snaps, "이제 뭐 해야 돼"))
    assert r.response.action.type.value == "ASK_USER" and r.response.seek == "결제하기"         # 마카옹에 노란 칸을 치지 않는다
    assert "누르" not in r.response.action.question and "치즈케이크 1개가 담겨 있어요." in r.response.action.question
    assert r.meta.get("target_mismatch") is True


async def test_null_number_with_a_name_becomes_a_seek_not_an_error(snaps):
    llm = ScriptedFakeLlmClient([_guide_raw('"null"', "4,500원 결제하기", "맞으면 아래 노란 칸 결제하기를 눌러 주세요.")])
    r = await agent(llm).next(_cart_req(snaps, "결제하고 싶다니까"))
    assert r.response.seek == "결제하기" and "찾지 못했어요" not in r.response.action.question       # 예전: "버튼을 찾지 못했어요"


async def test_garbled_but_same_name_is_still_trusted(snaps):
    d = make_req(snaps, "s01_dine", "결제하고 싶어", history=[]); d["snapshot"]["elements"] = _els("초기화", "텀블러 선택", "주문달기")
    llm = ScriptedFakeLlmClient([_guide_raw('"e2"', "주문담기", "옵션은 안 고르셔도 돼요.")])
    r = (await agent(llm).next(GuideRequest.model_validate(d))).response
    assert r.action.type.value == "TAP" and r.action.targetId == "e2"                                  # 주문담기 ↔ 주문달기는 같은 칸으로 믿는다


# ---- 되돌리는 버튼은 원할 때만 / 목록에 없음(MISSING)은 찾기로
async def test_reset_button_is_not_pointed_unless_the_elder_wants_to_undo(snaps):
    home = CART.index("처음으로")
    llm = ScriptedFakeLlmClient([_guide_raw(f'"e{home}"', "처음으로", "아직 메뉴 고르는 화면이에요. 처음으로를 눌러 주세요.")])
    r = await agent(llm).next(_cart_req(snaps, "결재를 하고 싶어"))
    assert r.response.action.type.value == "ASK_USER" and r.meta.get("unsafe_target") is True
    llm2 = ScriptedFakeLlmClient([_guide_raw(f'"e{home}"', "처음으로", "처음 화면으로 갈게요.")])
    r2 = await agent(llm2).next(_cart_req(snaps, "잘못 눌렀어 처음부터 다시 할래"))
    assert r2.response.action.type.value == "TAP" and r2.response.action.guideText == "처음으로"   # 원하면 가리킨다


async def test_missing_target_becomes_a_seek_and_missing_is_offered_to_the_llm(snaps):
    llm = ScriptedFakeLlmClient([_guide_raw('"MISSING"', "결제하기", "치즈케이크가 담겨 있어요.")])
    r = await agent(llm).next(_cart_req(snaps, "결제하고 싶어"))
    assert r.response.seek == "결제하기" and "MISSING" in llm.calls[0]["target_ids"]
