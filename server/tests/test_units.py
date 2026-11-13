import pytest
from app.jamo import dice, normalize
from app.ranker import JamoNgramRanker
from app.parser import ActionParser, ParseError
from app.validator import ActionValidator
from app.models import ActionType, GuideAction, GuideRequest, LlmAction, LlmKind, ScreenElement
from app import privacy
from app.ratelimit import RateLimiter


def els(snaps, name):
    return [ScreenElement.model_validate(e) for e in snaps[name]["elements"]]


def test_dice_typo_closer_than_other():
    assert dice("아메리카노", "아메리키노") > dice("아메리카노", "카페라떼")


def test_ranker_only_buttons_and_k(snaps):
    r = JamoNgramRanker(3).rank(els(snaps, "s02_menu"), "아메리카노 주세요")
    assert len(r) == 3 and r[0].element.text == "아메리카노"
    assert all(x.element.kind.value == "BUTTON_LIKE" for x in r)


def test_ranker_ocr_noise(snaps):
    e = els(snaps, "s02_menu")
    e[3] = e[3].model_copy(update={"text": "아메리키노"})
    assert JamoNgramRanker(8).rank(e, "아메리카노 주세요")[0].element.text == "아메리키노"


@pytest.mark.parametrize("raw", ['{"action":"GUIDE","targetId":"e2","guideText":"x","message":null}',
                                 '```json\n{"action":"UNKNOWN","message":"모르겠어요"}\n```',
                                 '네! {"action":"DONE","message":"끝"} 입니다'])
def test_parser_ok(raw):
    ActionParser().parse(raw)


@pytest.mark.parametrize("raw", ["", "그냥 말", '{"action":"JUMP"}', '{"action":', "[1,2]"])
def test_parser_bad(raw):
    with pytest.raises(ParseError):
        ActionParser().parse(raw)


def test_validator_whitelist(snaps):
    cands = {e.id: e for e in els(snaps, "s01_dine")}
    bad = ActionValidator().validate(LlmAction(action=LlmKind.GUIDE, targetId="zzz", guideText="x"), cands)
    assert bad.action.type == ActionType.UNKNOWN and bad.action.targetId is None
    good = ActionValidator().validate(LlmAction(action=LlmKind.GUIDE, targetId="e3", guideText="엉뚱"), cands)
    assert good.action.type == ActionType.TAP and good.action.guideText == "포장하기"   # 글자는 실제 버튼 글자로 교정


def test_validator_maps_llm_kinds_to_contract():
    v = ActionValidator()
    assert v.validate(LlmAction(action=LlmKind.ASK_USER, message="뭐 드릴까요?"), {}).action.question == "뭐 드릴까요?"
    assert v.validate(LlmAction(action=LlmKind.DONE, message="끝났어요."), {}).action.guideText == "끝났어요."
    assert v.validate(LlmAction(action=LlmKind.UNKNOWN, message="못 찾았어요."), {}).action.reason == "못 찾았어요."
    assert v.validate(LlmAction(action=LlmKind.DONE), {}, goal="커피").goal == "커피"


def test_validator_long_message_replaced():
    r = ActionValidator().validate(LlmAction(action=LlmKind.ASK_USER, message="가" * 100), {})
    assert len(r.action.question) <= 60


def test_action_fields_must_match_type():
    with pytest.raises(Exception):
        GuideAction(type=ActionType.TAP, guideText="아메리카노")                 # targetId 없음
    with pytest.raises(Exception):
        GuideAction(type=ActionType.ASK_USER, question="뭐요?", targetId="e1")   # 필요 없는 필드
    with pytest.raises(Exception):
        GuideAction(type=ActionType.SCROLL, guideText="메뉴")                   # direction 없음
    assert GuideAction(type=ActionType.WAIT, guideText="잠시만요").guideText == "잠시만요"


def test_privacy_drops_card_number(snaps):
    import copy
    snap = copy.deepcopy(snaps["s01_dine"])
    snap["elements"].append({"id": "e9", "text": "1234 5678 9012 3456", "kind": "TEXT",
                             "rect": {"left": .1, "top": .1, "right": .2, "bottom": .2}})
    d = {"sessionId": "s", "utterance": "카드번호 1234 5678 9012 3456", "goal": "카드 1234-5678-9012-3456 결제",
         "snapshot": snap, "history": [{"screenId": "s05", "action": "TAP", "targetText": "1234-5678-9012-3456"}]}
    clean, n = privacy.sanitize(GuideRequest.model_validate(d))
    assert "3456" not in clean.utterance and "3456" not in clean.goal and n == 1
    assert all("3456" not in e.text for e in clean.snapshot.elements)
    assert all("3456" not in (h.targetText or "") for h in clean.history)
    assert clean.snapshot.frameId == snap["frameId"] and clean.sessionId == "s"


def test_request_image_is_optional_base64(snaps):
    ok = {"sessionId": "s", "utterance": "", "snapshot": snaps["s01_dine"]}
    assert GuideRequest.model_validate(ok).image is None              # 사진이 없어도 통과, 내보낼 때도 필드가 없다
    assert "image" not in GuideRequest.model_validate(ok).model_dump(mode="json")
    assert GuideRequest.model_validate({**ok, "image": "AAAA"}).image == "AAAA"
    with pytest.raises(Exception):
        GuideRequest.model_validate({**ok, "image": "<script>"})      # base64가 아니면 거절
    with pytest.raises(Exception):
        GuideRequest.model_validate({**ok, "image": "A" * 700_001})   # 상한


def test_ratelimit():
    t = [0.0]
    rl = RateLimiter(2, clock=lambda: t[0])
    assert rl.allow("k")[0] and rl.allow("k")[0]
    ok, wait = rl.allow("k")
    assert not ok and wait > 0
    t[0] += 61
    assert rl.allow("k")[0]
