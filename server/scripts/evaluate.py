"""평가 스크립트: 케이스별 정확도와 지연(p50/p95)을 냅니다.

  python scripts/evaluate.py --llm fake                # 키 없이 (requiresLlm 케이스는 건너뜀)
  python scripts/evaluate.py --llm heuristic           # 가짜 LLM까지 포함해 전체 흐름 점검
  ANTHROPIC_API_KEY=... python scripts/evaluate.py --llm anthropic --repeat 3 --fail-under 0.85

fake/heuristic 숫자는 '파이프라인이 맞게 이어졌는지'를 보는 값이지, 실제 LLM 정확도가 아닙니다.
목표(정확도 85%, p95 2초)는 --llm anthropic 로 실제 화면 스냅샷을 넣어 재야 의미가 있습니다.
"""
import argparse, asyncio, copy, json, pathlib, statistics, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from app.agent import FakeGuideAgent, LlmGuideAgent          # noqa: E402
from app.llm import AnthropicLlmClient, HeuristicFakeLlmClient  # noqa: E402
from app.models import GuideRequest                          # noqa: E402
from app.settings import Settings                            # noqa: E402


def load_cases():
    cases = json.loads((ROOT / "testdata/evalset/cases.json").read_text(encoding="utf-8"))
    snaps = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in (ROOT / "testdata/snapshots").glob("*.json")}
    return cases, snaps


def build_request(case, snaps):
    snap = copy.deepcopy(snaps[case["screen"]])
    for e in snap["elements"]:
        e["text"] = case.get("mutate", {}).get(e["text"], e["text"])
    return GuideRequest.model_validate({
        "sessionId": f"eval-{case['id']}",
        "utterance": case["goal"] or None,          # 케이스의 goal = 이번에 어르신이 한 말
        "goal": case.get("prevGoal"),               # 앱이 돌려준 직전 목표 (없으면 첫 요청)
        "snapshot": snap,
        "history": case.get("history", []),
    }), snap


def judge(case, snap, resp):
    if resp.action.type.value != case["expectAction"]:
        return False
    if case["expectAction"] == "TAP":
        want = case["answerText"]
        got = next((e["text"] for e in snap["elements"] if e["id"] == resp.action.targetId), None)
        return got == want
    return True


async def run(args):
    s = Settings(app_key="x", llm_provider=args.llm, fastpath=not args.no_fastpath,
                 anthropic_api_key=__import__("os").environ.get("ANTHROPIC_API_KEY", ""),
                 gemini_api_key=__import__("os").environ.get("GEMINI_API_KEY", ""),
                 llm_model=__import__("os").environ.get("LLM_MODEL", "gemini-2.5-flash-lite" if args.llm == "gemini" else "claude-haiku-5-5"))
    if args.llm == "fake":
        agent = FakeGuideAgent()
    elif args.llm == "heuristic":
        agent = LlmGuideAgent(HeuristicFakeLlmClient(), s)
    elif args.llm == "gemini":
        from app.llm import GeminiLlmClient
        agent = LlmGuideAgent(GeminiLlmClient(s.gemini_api_key, s.llm_model), s)
    else:
        agent = LlmGuideAgent(AnthropicLlmClient(s.anthropic_api_key, s.llm_model), s)
    cases, snaps = load_cases()
    ok = total = skipped = 0
    lat, rows, fails = [], [], []
    for _ in range(args.repeat):
        for c in cases:
            if args.llm == "fake" and c.get("requiresLlm"):
                skipped += 1
                continue
            req, snap = build_request(c, snaps)
            t0 = time.perf_counter()
            try:
                r = await agent.next(req)
                resp, src = r.response, r.meta.get("source")
            except Exception as e:
                total += 1
                fails.append((c["id"], f"error:{type(e).__name__}"))
                continue
            ms = (time.perf_counter() - t0) * 1000
            lat.append(ms)
            total += 1
            good = judge(c, snap, resp)
            ok += good
            if not good:
                fails.append((c["id"], f"{resp.action.type.value}/{resp.action.guideText}"))
            rows.append((c["id"], c["variant"], good, src, ms))
    acc = ok / total if total else 0.0
    lat.sort()
    p = lambda q: lat[min(len(lat) - 1, int(q * len(lat)))] if lat else 0.0
    print(f"provider={args.llm} fastpath={not args.no_fastpath} cases={total} skipped={skipped}")
    print(f"accuracy={acc:.3f} ({ok}/{total})   latency p50={statistics.median(lat) if lat else 0:.1f}ms p95={p(0.95):.1f}ms")
    for cid, why in fails:
        print(f"  FAIL {cid}: {why}")
    srcs = {}
    for _, _, _, src, _ in rows:
        srcs[src] = srcs.get(src, 0) + 1
    print("sources:", srcs)
    if args.llm == "fake":
        print("※ fake는 LLM 없는 규칙 에이전트입니다. 실제 정확도가 아닙니다.")
    return 0 if acc >= args.fail_under else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", choices=["fake", "heuristic", "anthropic", "gemini"], default="fake")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--no-fastpath", action="store_true")
    ap.add_argument("--fail-under", type=float, default=0.0)
    sys.exit(asyncio.run(run(ap.parse_args())))
