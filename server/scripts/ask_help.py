"""도움 모드 시험: 어르신이 막혔을 때 할 법한 질문을 서로 다른 화면에 던지고 AI의 대답을 보여 준다.

  ANTHROPIC_API_KEY=... python scripts/ask_help.py --llm anthropic

정답 판정이 아니라 **대답을 사람이 읽어 보는** 도구다 (자동으로 확인하는 것은 길이 · 금지어 · 후보 밖 버튼 안내 여부뿐).
카페 화면(testdata/snapshots)과 기차표 화면(simulate_train), 그리고 오류 팝업 화면을 쓴다.
"""
import argparse, asyncio, json, os, pathlib, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from app.agent import LlmGuideAgent                                   # noqa: E402
from app.llm import AnthropicLlmClient, HeuristicFakeLlmClient        # noqa: E402
from app.models import GuideRequest                                   # noqa: E402
from app.settings import Settings                                     # noqa: E402
from scripts.simulate_train import WORLD, el                          # noqa: E402

SNAPS = {p.stem: json.loads(p.read_text(encoding="utf-8"))["elements"] for p in (ROOT / "testdata/snapshots").glob("*.json")}
B, P, T = "BUTTON_LIKE", "PRICE", "TEXT"


def screen(*items):
    return [el(i, t, k, .03 + .075 * (i - 1)) for i, (t, k) in enumerate(items, 1)]


SCREENS = {
    "cafe_options": SNAPS["s03_options"], "cafe_cart": SNAPS["s04_cart"], "cafe_payment": SNAPS["s05_payment"],
    "cafe_card": SNAPS["s06_card"], "cafe_loyalty": SNAPS["s07_loyalty"],
    "cafe_alert": screen(("알림", T), ("메뉴를 먼저 선택해 주세요", T), ("확인", B)),
    "cafe_complete": screen(("결제가 완료되었습니다", T), ("주문번호 12", T), ("영수증 출력", B), ("처음으로", B)),
    "train_origin": WORLD["t02_origin"], "train_seat": WORLD["t06_seat"],
    "train_seat_alert": screen(("좌석을 선택해 주세요", T), ("일반실", B), ("특실", B), ("선택 완료", B), ("알림", T), ("좌석을 먼저 선택해 주세요", T), ("확인", B)),
    "pay_failed": screen(("카드 인식에 실패했습니다", T), ("카드를 다시 넣어 주세요", T), ("다시 시도", B), ("결제 취소", B)),
}
CASES = [  # (화면, 어르신 말, 이전 대화)
    ("cafe_alert", "이거 눌렀는데 왜 안 넘어가?", [("TAP", "담기")]),
    ("cafe_options", "이건 또 뭘 고르라는 거야?", []),
    ("cafe_loyalty", "이거 꼭 해야 하는 거야?", []),
    ("cafe_cart", "내가 주문한 게 맞아?", []),
    ("cafe_card", "카드는 어디에 대야 해?", []),
    ("cafe_payment", "아까 화면으로 어떻게 돌아가?", []),
    ("cafe_complete", "이제 끝난 거야?", []),
    ("cafe_card", "이제 끝난 거야?", []),
    ("train_seat_alert", "갑자기 막혔어 지금 뭐해야돼", []),
    ("pay_failed", "이거 안된다는데 어떻게 해야돼", []),
    ("train_origin", "이건 뭘 고르라는 거야", []),
    ("cafe_payment", "다 했어 괜찮아요", [("ASK_USER", "결제 수단을 고르는 화면이에요. 신용카드 중에서 고르세요. 또 도움이 필요하세요?")]),
]
BANNED = ("바뀌는 중", "처리 중", "기다려", "찾아볼게")


async def main(a):
    s = Settings(app_key="x", llm_provider=a.llm, cache_size=0, help_mode=True, anthropic_api_key=os.environ.get("ANTHROPIC_API_KEY", ""),
                 llm_model=os.environ.get("LLM_MODEL", "claude-haiku-5-5"))
    llm = AnthropicLlmClient(s.anthropic_api_key, s.llm_model) if a.llm == "anthropic" else HeuristicFakeLlmClient()
    agent = LlmGuideAgent(llm, s)
    bad = 0
    for i, (name, said, hist) in enumerate(CASES, 1):
        snap = {"screenId": f"{name}#{i}", "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": SCREENS[name]}
        req = GuideRequest.model_validate({"sessionId": f"help-{i}", "utterance": said, "goal": None, "snapshot": snap,
            "history": [{"screenId": "prev", "action": act, "targetText": txt} for act, txt in hist]})
        t0 = time.perf_counter()
        r = await agent.next(req)
        ms = (time.perf_counter() - t0) * 1000
        act = r.response.action
        text = act.question or act.guideText or act.reason or ""
        ids = {e["id"] for e in SCREENS[name]}
        problems = []
        if len(text) > 80: problems.append("80자 초과")
        if any(w in text for w in BANNED): problems.append("금지어")
        if act.targetId and act.targetId not in ids: problems.append("화면에 없는 버튼")
        bad += bool(problems)
        mark = "⚠️ " + ",".join(problems) if problems else ""
        print(f"[{name}] \"{said}\"\n   → {act.type.value}: {text}  ({r.meta.get('source')}, {ms:.0f}ms) {mark}")
    print(f"\n자동 점검 실패 {bad}/{len(CASES)}  (대답 내용은 직접 읽어 확인)")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", choices=["heuristic", "anthropic"], default="heuristic")
    asyncio.run(main(ap.parse_args()))
