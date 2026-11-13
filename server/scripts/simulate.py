"""가상 노인 시나리오 시뮬레이터: 서버 에이전트를 앱처럼 끝까지 돌려 "실제로 쓸 때" 어디서 막히는지 찾는다.

  python scripts/simulate.py --llm heuristic                   # 키 없이 (흐름 점검)
  GEMINI_API_KEY=... python scripts/simulate.py --llm gemini --pace 3   # 진짜 LLM (무료 한도 때문에 호출 사이 3초)

앱의 동작을 흉내 낸다 (domain/SessionStateMachine 요약):
- 응답 TAP: 어르신이 그 버튼을 누른다 → 화면이 바뀐다 (주문 흐름: 매장/포장 → 메뉴 → 옵션 → 장바구니 → 결제 수단 → 카드)
- 응답 ASK_USER: 읽어 주고 어르신이 시나리오의 다음 대답을 한다 (goal은 앱이 돌려줌)
- 응답 UNKNOWN/WAIT/SCROLL: 화면 그대로 다시 묻는다 (정체로 센다)
- 이력은 앱처럼 최근 5개 (TAP=guideText, ASK_USER=question)

시나리오 근거 (자료조사, 2026-11-13): 노인은 음성 인식 오류가 많고(한 달 관찰 연구에서 상호작용의 약 1/4),
말이 느리고 끊기며 길고 복잡하게 말하고, 오류를 다시 말해도 잘 해결되지 않는다.
→ 군말 · 오인식 · 사투리 · 장황한 말 · 질문 · 되돌리기 · 도움 요청 · 말 안 한 화면 전환을 시험한다.
결과는 '실제 노인 정확도'가 아니라 서버 판단 로직의 약점 찾기용이다.
"""
import argparse, asyncio, json, os, pathlib, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from app.agent import LlmGuideAgent                                  # noqa: E402
from app.llm import GeminiLlmClient, HeuristicFakeLlmClient          # noqa: E402
from app.models import ActionType, GuideRequest, HistoryItem         # noqa: E402
from app.settings import Settings                                    # noqa: E402

SNAPS = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in (ROOT / "testdata/snapshots").glob("*.json")}
S1, S2, S3, S4, S5, S6 = "s01_dine", "s02_menu", "s03_options", "s04_cart", "s05_payment", "s06_card"

# (화면, 누른 글자) → 다음 화면. 없으면 같은 화면
NEXT = {
    (S1, "매장에서 먹고가기"): S2, (S1, "포장하기"): S2,
    (S2, "아메리카노"): S3, (S2, "카페라떼"): S3, (S2, "바닐라라떼"): S3, (S2, "녹차라떼"): S3, (S2, "딸기스무디"): S3,
    (S2, "장바구니 보기"): S4, (S2, "이전"): S1,
    (S3, "담기"): S4, (S3, "취소"): S2,
    (S4, "결제하기"): S5, (S4, "메뉴 더 담기"): S2,
    (S5, "신용카드"): S6, (S5, "삼성페이"): S6, (S5, "카카오페이"): S6, (S5, "이전"): S4,
}
# 누르면 같은 화면이지만 내용이 달라지는 버튼 (탭 · 옵션 선택 · 수량): 앱은 새 화면 번호를 받는다 (P2 ScreenTracker 선택 감지)
SAME_SCREEN_NEW_ID = {(S2, "커피"), (S2, "음료"), (S2, "디저트"), (S3, "HOT"), (S3, "ICE"), (S3, "샷 추가"), (S3, "시럽 추가"),
                      (S4, "+"), (S4, "-"), (S5, "쿠폰 사용")}
MENU = ("아메리카노", "카페라떼", "바닐라라떼", "녹차라떼", "딸기스무디")

# 시나리오: say = 처음 말. 되물음에는 질문 주제(dine/temp/extra/pay/menu/confirm)에 맞는 대답을 한다 (실제 노인처럼 물어본 것에 답함).
# inject = {주제: 그 주제를 처음 물을 때 대신 하는 말} (되묻기 · 질문 · 마음 바꿈 같은 돌발 상황), item/temp = 기대 결과
def persona(item="아메리카노", temp="HOT"):
    return {"dine": "매장에서 먹고 갈게요", "temp": {"HOT": "따뜻한 걸로요", "ICE": "시원한 걸로요"}[temp], "extra": "아니요 괜찮아요",
            "pay": "카드로 할게요", "menu": item, "confirm": "네"}


SCENARIOS = [
    dict(id="01 또렷하게", say="아메리카노 한 잔 주세요", item="아메리카노", temp="HOT"),
    dict(id="02 군말·머뭇", say="어... 그... 커피 있잖아... 아메리카노 그거요", item="아메리카노", temp="HOT", inject={"temp": "음... 뜨거운 걸로 줘요"}),
    dict(id="03 오인식(글자 틀림)", say="아에리카노 주세요", item="아메리카노", temp="HOT"),
    dict(id="04 오인식(띄어쓰기)", say="아메리 카노 한잔", item="아메리카노", temp="ICE"),
    dict(id="05 뜨신 커피(막연)", say="뜨신 커피 한 잔 줘요", item="아메리카노", temp="HOT"),
    dict(id="06 장황한 말", say="내가 오늘 병원 갔다 오는 길인데 목이 말라서 시원한 아메리카노 한 잔 마시고 싶어요", item="아메리카노", temp="ICE"),
    dict(id="07 메뉴를 물어봄", say="카페라떼가 뭐예요", item="카페라떼", temp="HOT", inject={"confirm": "그럼 그걸로 할게요"}),
    dict(id="08 다시 말해줘", say="아메리카노 주세요", item="아메리카노", temp="HOT", inject={"dine": "뭐라고요? 다시 말해줘요"}),
    dict(id="09 마음 바꿈", say="아메리카노 주세요", item="카페라떼", temp="HOT", inject={"dine": "아니 아메리카노 말고 라떼로 할래요"}),
    dict(id="10 도움 요청", say="모르겠어요 직원 좀 불러줘요", expect_done=True),
    dict(id="11 너무 막연함", say="커피요", item="아메리카노", temp="HOT"),
    dict(id="12 잘못 알아들음", say="회 기간", item="아메리카노", temp="HOT"),
    dict(id="13 뭘 눌러요?", say="아메리카노 주세요", item="아메리카노", temp="HOT", inject={"temp": "뭘 눌러야 돼요", "pay": "뭘 눌러야 돼요"}),
    dict(id="14 화면에 없는 결제", say="아메리카노 주세요", item="아메리카노", temp="HOT", inject={"pay": "현금으로 할게요"}),
    dict(id="15 카드번호를 말함", say="아메리카노 주세요", item="아메리카노", temp="HOT", inject={"pay": "카드 1234 5678 9012 3456"}),
    dict(id="16 옵션을 물어봄", say="아메리카노 주세요", item="아메리카노", temp="HOT", inject={"extra": "샷 추가가 뭐예요"}),
    dict(id="17 아이스 선택", say="아이스 아메리카노 주세요", item="아메리카노", temp="ICE"),
]


def topic_of(question):
    q = question or ""
    if "매장" in q or "포장" in q:
        return "dine"
    if any(w in q for w in ("HOT", "ICE", "따뜻", "시원", "차가", "뜨거")):
        return "temp"
    if any(w in q for w in ("샷", "시럽", "추가")):
        return "extra"
    if any(w in q for w in ("카드", "결제", "삼성", "카카오", "페이")):
        return "pay"
    if any(w in q for w in ("무엇", "드시고", "메뉴", "어떤 걸", "뭘")):
        return "menu"
    return "confirm"


def history_item(screen, resp):
    a = resp.action
    text = a.guideText if a.type in (ActionType.TAP, ActionType.SCROLL, ActionType.WAIT, ActionType.DONE) else a.question
    return HistoryItem(screenId=screen, action=a.type, targetText=text)


async def run_one(agent, sc, max_turns, pace):
    screen, goal, hist = S1, None, []
    sid, serial = S1, 0
    utter = sc["say"]
    base = persona(sc.get("item") or "아메리카노", sc.get("temp") or "HOT")
    inject = dict(sc.get("inject", {}))
    taps, trace, stalls, asks = [], [], 0, 0
    result = "미완료"
    for turn in range(max_turns):
        snap = dict(SNAPS[screen], screenId=sid)
        req = GuideRequest.model_validate({"sessionId": "sim", "utterance": utter, "goal": goal, "snapshot": snap,
                                           "history": [h.model_dump(mode="json") for h in hist[-5:]]})
        if pace:
            await asyncio.sleep(pace)
        t0 = time.perf_counter()
        try:
            r = await agent.next(req)
        except Exception as e:
            trace.append(f"[{screen}] '{utter}' → 서버오류 {type(e).__name__}")
            stalls += 1; utter = None
            continue
        ms = (time.perf_counter() - t0) * 1000
        resp = r.response
        goal = resp.goal
        a = resp.action
        hist.append(history_item(sid, resp))
        desc = a.guideText or a.question or a.reason or ""
        trace.append(f"[{screen}] '{utter or '(말 없음)'}' → {a.type.value} {desc[:40]} ({r.meta.get('source')}, {ms:.0f}ms)")
        utter = None
        if a.type == ActionType.DONE:
            result = "DONE"; break
        if a.type == ActionType.TAP:
            taps.append((screen, a.guideText))
            nxt = NEXT.get((screen, a.guideText), screen)
            if nxt == S6:
                result = "카드 화면 도달"; break
            if nxt != screen or (screen, a.guideText) in SAME_SCREEN_NEW_ID:
                serial += 1
                sid = f"{nxt}#{serial}"                   # 앱이 새 화면 번호를 부여
            screen = nxt
        elif a.type == ActionType.ASK_USER:
            asks += 1
            topic = topic_of(a.question)
            utter = inject.pop(topic, None) or base[topic]
        else:
            stalls += 1
            if stalls >= 3:
                result = "정체(못 찾음 반복)"; break
    problems = []
    item = next((t for s, t in taps if s == S2 and t in MENU), None)
    temp = next((t for s, t in taps if s == S3 and t in ("HOT", "ICE")), None)
    if sc.get("expect_done"):
        if result != "DONE":
            problems.append("도움 요청인데 안내를 끝내지 않음")
    else:
        if result != "카드 화면 도달":
            problems.append(f"끝까지 못 감: {result}")
        if sc.get("item") and item != sc["item"]:
            problems.append(f"메뉴 틀림: {item!r} (기대 {sc['item']})")
        if sc.get("temp") and temp != sc["temp"]:
            problems.append(f"HOT/ICE 틀림: {temp!r} (기대 {sc['temp']})")
        wrong = [(s, t) for s, t in taps if t in ("취소", "처음으로", "결제 취소", "이전")]
        if wrong:
            problems.append(f"엉뚱한 버튼 누르게 안내: {wrong}")
    return dict(id=sc["id"], ok=not problems, problems=problems, turns=len(trace), asks=asks, stalls=stalls, trace=trace)


async def main(a):
    s = Settings(app_key="x", llm_provider=a.llm, cache_size=0, fastpath=True,
                 gemini_api_key=os.environ.get("GEMINI_API_KEY", ""),
                 llm_model=os.environ.get("LLM_MODEL", "gemini-3.5-flash-lite"))
    llm = GeminiLlmClient(s.gemini_api_key, s.llm_model) if a.llm == "gemini" else HeuristicFakeLlmClient()
    agent = LlmGuideAgent(llm, s)
    rows = []
    for sc in SCENARIOS:
        if a.only and a.only not in sc["id"]:
            continue
        rows.append(await run_one(agent, sc, a.max_turns, a.pace if a.llm == "gemini" else 0))
    ok = sum(r["ok"] for r in rows)
    for r in rows:
        print(f"{'OK  ' if r['ok'] else 'FAIL'} {r['id']}  (응답 {r['turns']}회, 되물음 {r['asks']}, 정체 {r['stalls']})")
        if not r["ok"] or a.verbose:
            for p in r["problems"]:
                print("     ✗", p)
            for t in r["trace"]:
                print("     ·", t)
    print(f"\n성공 {ok}/{len(rows)}  (provider={a.llm})")
    return 0 if ok == len(rows) else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", choices=["heuristic", "gemini"], default="heuristic")
    ap.add_argument("--max-turns", type=int, default=14)
    ap.add_argument("--pace", type=float, default=3.0)
    ap.add_argument("--only", default="")
    ap.add_argument("--verbose", action="store_true")
    sys.exit(asyncio.run(main(ap.parse_args())))
