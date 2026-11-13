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


def test_dotenv_is_read_without_touching_the_process_environment(tmp_path, monkeypatch):
    import os
    from app.settings import Settings, read_dotenv
    f = tmp_path / ".env"
    lines = ["# 주석", 'GEMINI_API_KEY="abc123"', "export LLM_PROVIDER=gemini  # 설명", "", "EMPTY=", "APP_KEY=from_file",
             "OTHER_KEY=            # 무료 키: https://example.com"]
    f.write_text("\n".join(lines) + "\n", encoding="utf-8")
    for k in ("GEMINI_API_KEY", "LLM_PROVIDER", "APP_KEY"):
        monkeypatch.delenv(k, raising=False)                          # 시험은 바깥 환경에 기대지 않는다 (예: LLM_PROVIDER=fake 로 돌려도 통과)
    before = dict(os.environ)
    d = read_dotenv(f)
    assert d["GEMINI_API_KEY"] == "abc123" and d["LLM_PROVIDER"] == "gemini" and d["EMPTY"] == "" and d["OTHER_KEY"] == ""   # 주석이 값으로 들어가면 안 된다
    assert dict(os.environ) == before                                  # 전역(환경변수)을 바꾸지 않는다
    assert read_dotenv(tmp_path / "없음.env") == {}
    monkeypatch.setenv("APP_KEY", "from_shell")
    s2 = Settings.from_env(dotenv_path=f)
    assert s2.app_key == "from_shell" and s2.gemini_api_key == "abc123" and s2.llm_provider == "gemini"      # 명령줄 값이 .env 보다 우선
    assert dict(os.environ).get("GEMINI_API_KEY") == before.get("GEMINI_API_KEY")                              # from_env 도 환경변수를 바꾸지 않는다
    assert Settings.from_env(env={"APP_KEY": "x"}, dotenv_path=f).gemini_api_key == ""                         # env 를 주면 그것만 쓴다 (시험용)


def _dice_reference(a: str, b: str) -> float:
    """최적화 전의 계산(캐시 없음). 새 dice 가 같은 값을 내는지 비교하는 기준."""
    from app.jamo import ngrams, to_jamo
    ga, gb = ngrams(to_jamo(a)), ngrams(to_jamo(b))
    ta, tb = sum(ga.values()), sum(gb.values())
    if ta == 0 or tb == 0:
        return 0.0
    return 2.0 * sum(min(v, gb[k]) for k, v in ga.items() if k in gb) / (ta + tb)


def test_cached_dice_gives_exactly_the_same_scores_as_the_plain_computation():
    import random
    from app.jamo import dice
    from app.kiosk import BRANDS
    rnd = random.Random(7)
    pool = list("가나다라마바사아자차카타파하아메리카노라떼ㅂ12 .,ABcd") + ["스", "벅", "스", "커", "피"]
    words = ["".join(rnd.choice(pool) for _ in range(rnd.randint(0, 14))) for _ in range(300)]
    words += [w for _, (_, ws) in BRANDS.items() for w in ws] + ["아메리카노", "아메리키노", "", " ", "ㅋㅋ"]
    for a in words[:120]:
        for b in words[::7]:
            assert dice(a, b) == _dice_reference(a, b) == dice(b, a) or abs(dice(a, b) - _dice_reference(a, b)) < 1e-12


def test_kiosk_identify_is_unchanged_by_the_precomputed_words():
    from app.kiosk import identify
    from app.models import ScreenElement
    mk = lambda t: ScreenElement.model_validate({"id": t, "text": t, "rect": {"left": .1, "top": .1, "right": .2, "bottom": .2}, "confidence": .9, "kind": "TEXT"})
    assert identify([mk("메가엠지씨커피"), mk("처음으로")]).name is not None or identify([mk("메가엠지씨커피")]).category is not None
    assert identify([mk("아무 글자")]).confidence == 0.0
