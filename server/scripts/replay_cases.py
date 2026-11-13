"""모아 둔 실제 실패 사례(testdata/real_logs/cases.json)를 서버로 다시 돌려 본다.

  GEMINI_API_KEY=... python scripts/replay_cases.py --llm gemini
  ANTHROPIC_API_KEY=... python scripts/replay_cases.py --llm anthropic

세는 것(정답을 사람이 매기지 않는 자동 점검):
  · 표시 빠짐: "누르세요"라고 말하면서 노란 칸이 없는 답  ← 가장 자주 나온 문제
  · 80자 초과 · 금지어
  · 정답(expect)이 적힌 사례는 맞고 틀림도 센다: expect 에 글자가 있으면 그 칸을 가리켜야 하고, 빈 목록이면 "맞는 칸이 안 보이니 엉뚱한 칸을 가리키면 안 된다"
화면 글자만 쓴다(사진 없음). 칸 위치는 로그에 없어서 위에서 아래로 순서만 붙인다.
"""
import argparse, asyncio, json, os, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from app.agent import LlmGuideAgent                                     # noqa: E402
from app.llm import AnthropicLlmClient, GeminiLlmClient, HeuristicFakeLlmClient   # noqa: E402
from app.models import GuideRequest                                     # noqa: E402
from app.settings import Settings                                       # noqa: E402

CASES = ROOT / "testdata" / "real_logs" / "cases.json"
PRESS = re.compile(r"눌러|누르|터치")
BANNED = ("바뀌는 중", "처리 중", "기다려", "찾아볼게")


def elements(texts):
    out = []
    for i, t in enumerate(texts):
        t = t.strip()
        if not t:
            continue
        kind = "BUTTON_LIKE" if len(t) <= 14 and not re.search(r"\d+\s*원|%", t) else "TEXT"
        top = min(0.95, 0.04 + 0.022 * i)
        out.append({"id": f"e{i}", "text": t[:24], "rect": {"left": 0.1, "top": top, "right": 0.6, "bottom": min(0.99, top + 0.02)}, "confidence": 0.9, "kind": kind})
    return out


async def main(a):
    cases = json.loads(CASES.read_text(encoding="utf-8"))
    if a.limit:
        cases = cases[-a.limit:]
    s = Settings(app_key="x", llm_provider=a.llm, cache_size=0, help_mode=True, llm_timeout_s=8.0, total_budget_s=12.0)
    if a.llm == "anthropic":
        llm = AnthropicLlmClient(os.environ["ANTHROPIC_API_KEY"], os.environ.get("LLM_MODEL", "claude-haiku-5-5"))
    elif a.llm == "gemini":
        llm = GeminiLlmClient(os.environ["GEMINI_API_KEY"], os.environ.get("LLM_MODEL", "gemini-3.5-flash-lite"))
    else:
        llm = HeuristicFakeLlmClient()
    agent = LlmGuideAgent(llm, s)
    n = tap = missing = errors = right = wrong = 0
    for i, c in enumerate(cases):
        await asyncio.sleep(a.pace)
        snap = {"screenId": f"r{i}", "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": elements(c["texts"])}
        try:
            r = (await agent.next(GuideRequest.model_validate({"sessionId": f"replay{i}", "utterance": c["utterance"], "goal": None, "snapshot": snap, "history": []}))).response
        except Exception as e:                                       # 시험 도구는 한 건이 실패해도 계속 간다
            errors += 1
            print(f"[{i}] \"{c['utterance']}\" → 오류 {type(e).__name__}")
            continue
        n += 1
        act = r.action
        text = r.say or act.question or act.reason or ""
        flags = []
        if act.type.value == "TAP":
            tap += 1
        elif PRESS.search(text):
            missing += 1
            flags.append("표시 빠짐")
        if len(text) > 80:
            flags.append("80자 초과")
        if any(w in text for w in BANNED):
            flags.append("금지어")
        if "expect" in c:
            pointed = act.type.value == "TAP"
            ok = any(e in (act.guideText or "") for e in c["expect"]) if c["expect"] else not pointed
            right, wrong = right + ok, wrong + (not ok)
            flags.append("정답" if ok else "오답")
        print(f"[{i}] \"{c['utterance']}\" → {act.type.value} {act.guideText or ''} | {text[:90]} {'⚠ ' + ','.join(flags) if flags else ''}")
    print(f"\n요약: {n}건 중 노란 칸 {tap}건, 누르라면서 표시 빠짐 {missing}건, 오류 {errors}건 | 정답이 적힌 사례 {right + wrong}건 중 정답 {right}건")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", choices=["heuristic", "anthropic", "gemini"], default="heuristic")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--pace", type=float, default=1.5)
    asyncio.run(main(ap.parse_args()))
