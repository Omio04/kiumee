"""기차표 예매 키오스크 시뮬레이터: 카페가 아닌 키오스크에서도 서버가 똑같이 동작하는지 확인한다 ("어느 키오스크든 작동해야 한다").

  ANTHROPIC_API_KEY=... python scripts/simulate_train.py --llm anthropic
  python scripts/simulate_train.py --llm heuristic        # 키 없이 흐름만

카페 시뮬레이터(simulate.py)와 같은 방식: 가상 노인이 앱처럼 화면을 끝까지 진행한다. 화면은 손으로 만든 가짜(출발역 → 도착역 → 날짜/시간 →
열차 → 좌석 → 결제 수단 → 카드). 서버 판단 로직의 약점을 찾는 도구이지 실제 코레일 키오스크 정확도가 아니다.
"""
import argparse, asyncio, os, pathlib, re, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from app.agent import LlmGuideAgent                                  # noqa: E402
from app.llm import AnthropicLlmClient, GeminiLlmClient, HeuristicFakeLlmClient   # noqa: E402
from app.models import ActionType, GuideRequest, HistoryItem         # noqa: E402
from app.settings import Settings                                    # noqa: E402

B, P, T = "BUTTON_LIKE", "PRICE", "TEXT"


def el(i, text, kind, top):
    return {"id": f"e{i}", "text": text, "kind": kind, "confidence": 0.95,
            "rect": {"left": .1, "top": round(top, 3), "right": .9, "bottom": round(top + .05, 3)}}


def screen(*items):
    return [el(i, t, k, .03 + .075 * (i - 1)) for i, (t, k) in enumerate(items, 1)]


STATIONS = ("서울", "용산", "대전", "동대구", "부산")
WORLD = {
    "t01_home": screen(("KORAIL 승차권 발매기", T), ("승차권 예매", B), ("예약 승차권 찾기", B), ("환불", B), ("영수증 출력", B)),
    "t02_origin": screen(("출발역을 선택해 주세요", T), *[(s, B) for s in STATIONS], ("이전", B)),
    "t03_dest": screen(("도착역을 선택해 주세요", T), *[(s, B) for s in STATIONS], ("이전", B)),
    "t04_when": screen(("가는 날과 시간을 선택해 주세요", T), ("오늘", B), ("내일", B), ("모레", B), ("오전", B), ("오후", B), ("열차 조회", B), ("이전", B)),
    "t05_trains": screen(("열차를 선택해 주세요", T), ("KTX 101  06:00 → 08:40", B), ("59,800원", P), ("KTX 103  07:00 → 09:40", B),
                         ("59,800원", P), ("무궁화호 1301  06:30 → 11:50", B), ("32,000원", P), ("이전", B)),
    "t06_seat": screen(("좌석을 선택해 주세요", T), ("일반실", B), ("특실", B), ("창측", B), ("복도측", B), ("선택 완료", B), ("이전", B)),
    "t07_pay": screen(("결제 수단을 선택해 주세요", T), ("신용카드", B), ("삼성페이", B), ("카카오페이", B), ("이전", B)),
}
END = "t08_card"
SAME = {("t04_when", w) for w in ("오늘", "내일", "모레", "오전", "오후")} | {("t06_seat", w) for w in ("일반실", "특실", "창측", "복도측")}


def next_screen(screen_id, text):
    if screen_id == "t01_home" and text == "승차권 예매": return "t02_origin"
    if screen_id == "t02_origin" and text in STATIONS: return "t03_dest"
    if screen_id == "t03_dest" and text in STATIONS: return "t04_when"
    if screen_id == "t04_when" and text == "열차 조회": return "t05_trains"
    if screen_id == "t05_trains" and (text.startswith("KTX") or text.startswith("무궁화")): return "t06_seat"
    if screen_id == "t06_seat" and text == "선택 완료": return "t07_pay"
    if screen_id == "t07_pay" and text in ("신용카드", "삼성페이", "카카오페이"): return END
    if text == "이전":
        return {"t02_origin": "t01_home", "t03_dest": "t02_origin", "t05_trains": "t04_when", "t07_pay": "t06_seat"}.get(screen_id, screen_id)
    return screen_id


ANSWERS = {"origin": "서울이요", "dest": "부산이요", "date": "내일이요", "time": "오전이요", "train": "KTX로 할게요", "seat": "일반실이요",
           "pay": "카드로 할게요", "confirm": "네"}
_TOPICS = (("origin", ("출발", "어디서")), ("dest", ("도착", "어디로", "목적지", "가시")), ("date", ("언제", "날", "오늘", "내일")),
           ("time", ("오전", "오후", "시간")), ("train", ("열차", "KTX", "무궁화")), ("seat", ("좌석", "일반실", "특실", "창측")),
           ("pay", ("결제", "카드", "페이")))

SCENARIOS = [
    dict(id="T1 또렷하게", say="내일 오전에 서울에서 부산 가는 기차표 한 장 주세요", origin="서울", dest="부산", date="내일"),
    dict(id="T2 막연하게", say="부산 가는 표요", origin="서울", dest="부산", date="내일"),
    dict(id="T3 좌석을 물어봄", say="서울에서 부산 가는 표 주세요", origin="서울", dest="부산", date="내일", seat="일반실", inject={"seat": "특실이 뭐예요"}),
    dict(id="T4 마음 바꿈", say="서울에서 대전 가는 표 주세요", origin="서울", dest="부산", date="내일", inject={"date": "아니 대전 말고 부산으로 갈래요"}),
    dict(id="T5 군말·오인식", say="어... 그... 서울에서 부사 가는 기차표", origin="서울", dest="부산", date="내일"),
    dict(id="T6 도움 요청", say="어떻게 하는지 모르겠어요 직원 좀 불러줘요", expect_done=True),
    dict(id="T7 뭘 눌러요?", say="서울에서 부산 가는 표 주세요", origin="서울", dest="부산", date="내일", inject={"dest": "뭘 눌러야 돼요"}),
]


def topic_of(question):
    """사람은 질문이 고르라는 것에 대답한다. 선택지를 직접 말한 낱말이 가장 확실한 단서다."""
    q = question or ""
    last = ([x for x in re.split(r"(?<=[.?!])\s*", q) if x.strip()] or [q])[-1]
    for topic, words in (("time", ("오전", "오후")), ("seat", ("일반실", "특실", "창측", "복도측")), ("train", ("KTX", "열차", "무궁화", "101", "103")),
                         ("date", ("오늘", "내일", "모레", "언제", "날")), ("pay", ("결제", "카드", "페이")),
                         ("dest", ("도착", "어디로", "목적지")), ("origin", ("출발역", "어디서", "출발"))):
        if any(w in last for w in words):
            return topic
    return "confirm"


def history_item(sid, resp):
    a = resp.action
    text = a.guideText if a.type in (ActionType.TAP, ActionType.SCROLL, ActionType.WAIT, ActionType.DONE) else a.question
    return HistoryItem(screenId=sid, action=a.type, targetText=text)


async def run_one(agent, sc, max_turns, pace):
    screen_id, goal, hist, sid, serial = "t01_home", None, [], "t01_home", 0
    utter, inject = sc["say"], dict(sc.get("inject", {}))
    taps, trace, stalls, asks, result = [], [], 0, 0, "미완료"
    for _ in range(max_turns):
        snap = {"screenId": sid, "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": WORLD[screen_id]}
        req = GuideRequest.model_validate({"sessionId": f"train-{sc['id'][:2]}", "utterance": utter, "goal": goal, "snapshot": snap,
                                           "history": [h.model_dump(mode="json") for h in hist[-5:]]})
        if pace:
            await asyncio.sleep(pace)
        t0 = time.perf_counter()
        try:
            r = await agent.next(req)
        except Exception as e:
            trace.append(f"[{screen_id}] '{utter}' → 서버오류 {type(e).__name__}"); stalls += 1; utter = None
            continue
        ms = (time.perf_counter() - t0) * 1000
        a, goal = r.response.action, r.response.goal
        hist.append(history_item(sid, r.response))
        trace.append(f"[{screen_id}] '{utter or '(말 없음)'}' → {a.type.value} {(a.guideText or a.question or a.reason or '')[:44]} ({r.meta.get('source')}, {ms:.0f}ms)")
        utter = None
        if a.type == ActionType.DONE:
            result = "DONE"; break
        if a.type == ActionType.TAP:
            taps.append((screen_id, a.guideText))
            nxt = next_screen(screen_id, a.guideText)
            if nxt == END:
                result = "카드 화면 도달"; break
            if nxt != screen_id or (screen_id, a.guideText) in SAME:
                serial += 1; sid = f"{nxt}#{serial}"
            screen_id = nxt
        elif a.type == ActionType.ASK_USER:
            asks += 1
            topic = topic_of(a.question)
            utter = inject.pop(topic, None) or ANSWERS[topic]
            if topic == "train" and any(w in (a.question or "") for w in ("101", "103", "어느")):
                utter = "첫 번째로요"
            if topic == "seat" and "창측" in (a.question or ""):
                utter = "창측이요"
            if topic == "time" and "오후" in (a.question or "") and "오전" not in (a.question or ""):
                utter = "오후요"
        else:
            stalls += 1
            if stalls >= 3:
                result = "정체"; break
    problems = []
    first = lambda s, ok=None: next((t for sc_, t in taps if sc_ == s and (ok is None or t in ok)), None)
    if sc.get("expect_done"):
        if result != "DONE": problems.append("도움 요청인데 안내를 끝내지 않음")
    else:
        if result != "카드 화면 도달": problems.append(f"끝까지 못 감: {result}")
        for key, where in (("origin", "t02_origin"), ("dest", "t03_dest")):
            got = first(where, STATIONS)
            if sc.get(key) and got != sc[key]: problems.append(f"{key} 틀림: {got!r} (기대 {sc[key]})")
        if sc.get("date") and first("t04_when", ("오늘", "내일", "모레")) != sc["date"]:
            problems.append(f"날짜 틀림: {first('t04_when', ('오늘', '내일', '모레'))!r} (기대 {sc['date']})")
        if sc.get("seat") and first("t06_seat", ("일반실", "특실")) != sc["seat"]:
            problems.append(f"좌석 틀림: {first('t06_seat', ('일반실', '특실'))!r} (기대 {sc['seat']})")
    return dict(id=sc["id"], ok=not problems, problems=problems, turns=len(trace), asks=asks, stalls=stalls, trace=trace)


async def main(a):
    s = Settings(app_key="x", llm_provider=a.llm, cache_size=0, fastpath=True, anthropic_api_key=os.environ.get("ANTHROPIC_API_KEY", ""),
                 gemini_api_key=os.environ.get("GEMINI_API_KEY", ""), llm_model=os.environ.get("LLM_MODEL", "claude-haiku-5-5"))
    llm = {"anthropic": lambda: AnthropicLlmClient(s.anthropic_api_key, s.llm_model),
           "gemini": lambda: GeminiLlmClient(s.gemini_api_key, "gemini-3.5-flash-lite")}.get(a.llm, HeuristicFakeLlmClient)()
    agent = LlmGuideAgent(llm, s)
    rows = [await run_one(agent, sc, a.max_turns, a.pace if a.llm == "gemini" else 0) for sc in SCENARIOS if a.only in sc["id"]]
    for r in rows:
        print(f"{'OK  ' if r['ok'] else 'FAIL'} {r['id']}  (응답 {r['turns']}회, 되물음 {r['asks']}, 정체 {r['stalls']})")
        if not r["ok"] or a.verbose:
            for p in r["problems"]: print("     ✗", p)
            for t in r["trace"]: print("     ·", t)
    print(f"\n성공 {sum(r['ok'] for r in rows)}/{len(rows)}  (provider={a.llm})")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", choices=["heuristic", "gemini", "anthropic"], default="heuristic")
    ap.add_argument("--max-turns", type=int, default=24)
    ap.add_argument("--pace", type=float, default=3.0)
    ap.add_argument("--only", default="")
    ap.add_argument("--verbose", action="store_true")
    asyncio.run(main(ap.parse_args()))
