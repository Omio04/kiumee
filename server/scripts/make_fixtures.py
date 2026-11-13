"""손으로 정한 화면 6개(스냅샷)와 평가 케이스를 testdata/ 에 만듭니다.
실제 OCR 출력이 아니라 '이런 화면이면 이런 요소가 나올 것'이라고 손으로 쓴 값입니다.
실제 화면 사진에서 뽑은 스냅샷은 Android 팀이 같은 형식(JSON)으로 testdata/snapshots 에 넣으면 평가에 같이 쓰입니다."""
import json, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / "testdata"


def el(i, text, kind, l, t, r, b, conf=0.95):
    return {"id": i, "text": text, "kind": kind, "rect": {"left": l, "top": t, "right": r, "bottom": b}, "confidence": conf}


B, P, T = "BUTTON_LIKE", "PRICE", "TEXT"
SCREENS = {
    "s01_dine": [
        el("e1", "주문 방법을 선택해주세요", T, .2, .1, .8, .18),
        el("e2", "매장에서 먹고가기", B, .1, .4, .45, .6),
        el("e3", "포장하기", B, .55, .4, .9, .6),
        el("e4", "처음으로", B, .05, .9, .25, .97),
    ],
    "s02_menu": [
        el("e1", "커피", B, .02, .12, .2, .18), el("e2", "음료", B, .22, .12, .4, .18), el("e3", "디저트", B, .42, .12, .6, .18),
        el("e4", "아메리카노", B, .05, .25, .3, .4), el("e5", "카페라떼", B, .35, .25, .6, .4), el("e6", "바닐라라떼", B, .65, .25, .9, .4),
        el("e7", "녹차라떼", B, .05, .5, .3, .65), el("e8", "딸기스무디", B, .35, .5, .6, .65),
        el("e9", "4,500원", P, .05, .41, .3, .45), el("e10", "5,000원", P, .35, .41, .6, .45),
        el("e11", "장바구니 보기", B, .6, .9, .95, .97), el("e12", "이전", B, .05, .9, .25, .97),
    ],
    "s03_options": [
        el("e1", "아메리카노 옵션 선택", T, .2, .08, .8, .15),
        el("e2", "HOT", B, .1, .25, .4, .38), el("e3", "ICE", B, .55, .25, .85, .38),
        el("e4", "샷 추가", B, .1, .45, .4, .58), el("e5", "시럽 추가", B, .55, .45, .85, .58),
        el("e6", "담기", B, .55, .88, .95, .96), el("e7", "취소", B, .05, .88, .45, .96),
    ],
    "s04_cart": [
        el("e1", "주문 내역", T, .3, .05, .7, .1),
        el("e2", "아메리카노 1잔", T, .1, .2, .6, .26), el("e3", "4,500원", P, .7, .2, .9, .26),
        el("e4", "+", B, .1, .3, .2, .36), el("e5", "-", B, .25, .3, .35, .36),
        el("e6", "결제하기", B, .55, .88, .95, .96), el("e7", "메뉴 더 담기", B, .05, .88, .5, .96),
    ],
    "s05_payment": [
        el("e1", "결제 수단을 선택해주세요", T, .2, .08, .8, .15),
        el("e2", "신용카드", B, .1, .3, .45, .5), el("e3", "삼성페이", B, .55, .3, .9, .5),
        el("e4", "카카오페이", B, .1, .55, .45, .75), el("e5", "쿠폰 사용", B, .55, .55, .9, .75),
        el("e6", "이전", B, .05, .9, .25, .97),
    ],
    "s07_loyalty": [
        el("e1", "스탬프 적립을 하시겠어요?", T, .15, .2, .85, .3),
        el("e2", "×", B, .86, .12, .95, .18),
    ],
    "s06_card": [
        el("e1", "카드를 투입구에 넣어주세요", T, .15, .3, .85, .45),
        el("e2", "결제 취소", B, .35, .85, .65, .95),
    ],
}


def case(cid, screen, goal, action, answer=None, requires_llm=False, history=None, variant="clean", mutate=None,
         prev_goal=None):
    """goal: 이번에 어르신이 한 말(요청의 utterance) / prev_goal: 앱이 돌려준 직전 목표(요청의 goal)
    action: 기대하는 응답 type (계약 v1: TAP | ASK_USER | DONE | UNKNOWN …)"""
    c = {"id": cid, "screen": screen, "goal": goal, "variant": variant, "expectAction": action,
         "answerText": answer, "requiresLlm": requires_llm}
    if prev_goal:
        c["prevGoal"] = prev_goal
    if history:
        c["history"] = history
    if mutate:
        c["mutate"] = mutate          # {원래 글자: OCR이 잘못 읽은 글자}
    return c


CASES = [
    case("c01", "s01_dine", "여기서 먹고 갈래요", "TAP", "매장에서 먹고가기"),
    case("c02", "s01_dine", "포장해 주세요", "TAP", "포장하기"),
    case("c03", "s02_menu", "아메리카노 주세요", "TAP", "아메리카노"),
    case("c04", "s02_menu", "라떼 하나요", "TAP", "카페라떼"),
    case("c05", "s02_menu", "딸기 스무디 먹고 싶어", "TAP", "딸기스무디"),
    case("c06", "s03_options", "따뜻한 걸로요", "TAP", "HOT"),
    case("c07", "s03_options", "시원한 걸로 줘", "TAP", "ICE"),
    case("c08", "s03_options", "샷 하나 더요", "TAP", "샷 추가"),
    case("c09", "s04_cart", "계산할게요", "TAP", "결제하기"),
    case("c10", "s05_payment", "카드로 낼게요", "TAP", "신용카드"),
    case("c11", "s05_payment", "카톡으로 결제할래", "TAP", "카카오페이", True),
    case("c12", "s06_card", "", "DONE"),
    # OCR 오인식
    case("n01", "s02_menu", "아메리카노 주세요", "TAP", "아메리키노", variant="ocr-noise", mutate={"아메리카노": "아메리키노"}),
    case("n02", "s02_menu", "바닐라 라떼요", "TAP", "바닐라래떼", variant="ocr-noise", mutate={"바닐라라떼": "바닐라래떼"}),
    # 되묻기/모름
    case("a01", "s02_menu", "", "ASK_USER", requires_llm=True),
    # 화면에 없는 것을 말하면 "못 찾았어요"로 끝내지 않고 지금 고를 수 있는 것을 알려 준다 (2026-11-13)
    case("u01", "s01_dine", "비빔밥 주세요", "ASK_USER", requires_llm=True),
    case("u02", "s05_payment", "아메리카노 주세요", "ASK_USER", requires_llm=True),
    # 음식 질문: 고르지 않고 설명 (목표는 그대로)
    case("q01", "s02_menu", "카페라떼가 뭐야", "ASK_USER", requires_llm=True),
    case("q02", "s02_menu", "바닐라라떼는 무슨 맛이에요", "ASK_USER", requires_llm=True, prev_goal="아메리카노 주세요"),
    case("q03", "s03_options", "샷 추가가 뭔가요", "ASK_USER", requires_llm=True, prev_goal="아메리카노 주세요"),
    case("q04", "s03_options", "뭘 눌러야 돼요", "ASK_USER", requires_llm=True, prev_goal="아메리카노 주세요"),
    # 앱이 돌려준 목표로 이어 가기 (화면만 바뀜, 새 발화 없음)
    case("h01", "s03_options", "", "TAP", "ICE", True, prev_goal="아이스 아메리카노 주세요",
         history=[{"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"}]),
]

# 가짜 화면 변형: 같은 화면을 요소 순서/신뢰도만 흔들어 둔 것은 만들지 않음(과장 방지)
for i, (name, els) in enumerate(SCREENS.items(), start=1):
    # 앱의 ScreenSnapshot과 같은 형식 (계약 v1). frameId·timestampMs는 손으로 쓴 값이라 의미 없음
    snap = {"screenId": name, "frameId": i, "timestampMs": 0, "stableFrames": 2, "elements": els}
    (ROOT / "snapshots" / f"{name}.json").write_text(json.dumps(snap, ensure_ascii=False, indent=1), encoding="utf-8")
(ROOT / "evalset" / "cases.json").write_text(json.dumps(CASES, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"snapshots={len(SCREENS)} cases={len(CASES)}")
