"""서버 응답 지연 측정 (4일차 '응답 p95 2초'). 실행 중인 서버에 평가 케이스를 HTTP로 보내 p50/p95/p99를 냅니다.

  APP_KEY=dev LLM_PROVIDER=fake uvicorn app.main:app --port 8080 &
  python scripts/loadtest.py --url http://127.0.0.1:8080 --key dev --repeat 3 --concurrency 4 --fail-over-ms 2000

요청 내용(발화 · 화면 글자)은 출력하지 않습니다. 캐시가 결과를 왜곡하지 않도록 --no-cache-bust를 주지 않으면
요청마다 목표 뒤에 번호를 붙여 캐시를 피합니다 (진짜 LLM 호출 지연을 재기 위해).
"""
import argparse, asyncio, json, pathlib, sys, time

import httpx

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from scripts.evaluate import build_request, load_cases  # noqa: E402


def pct(sorted_ms, q):
    return sorted_ms[min(len(sorted_ms) - 1, int(q * len(sorted_ms)))] if sorted_ms else 0.0


async def main(a):
    cases, snaps = load_cases()
    bodies = []
    n = 0
    for _ in range(a.repeat):
        for c in cases:
            req, _ = build_request(c, snaps)
            d = req.model_dump(mode="json")
            if not a.no_cache_bust and d.get("utterance"):
                n += 1
                d["utterance"] = f"{d['utterance']} {n}"
            bodies.append(d)
    sem = asyncio.Semaphore(a.concurrency)
    lat, status = [], {}

    async def one(cl, body):
        async with sem:
            t0 = time.perf_counter()
            try:
                r = await cl.post("/v1/guide/next", json=body, headers={"X-App-Key": a.key})
                code = r.status_code
            except httpx.HTTPError as e:
                code = type(e).__name__
            ms = (time.perf_counter() - t0) * 1000
            status[code] = status.get(code, 0) + 1
            if code == 200:
                lat.append(ms)

    async with httpx.AsyncClient(base_url=a.url, timeout=a.timeout) as cl:
        await asyncio.gather(*(one(cl, b) for b in bodies))
    lat.sort()
    print(f"requests={len(bodies)} status={status}")
    print(f"latency p50={pct(lat, .5):.0f}ms p95={pct(lat, .95):.0f}ms p99={pct(lat, .99):.0f}ms max={lat[-1] if lat else 0:.0f}ms")
    ok = bool(lat) and pct(lat, .95) <= a.fail_over_ms and set(status) == {200}
    print("PASS" if ok else "FAIL", f"(p95 <= {a.fail_over_ms:.0f}ms, 전부 200)")
    return 0 if ok else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8080")
    ap.add_argument("--key", required=True)
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--concurrency", type=int, default=2)
    ap.add_argument("--timeout", type=float, default=8.0)
    ap.add_argument("--fail-over-ms", type=float, default=2000.0)
    ap.add_argument("--no-cache-bust", action="store_true")
    sys.exit(asyncio.run(main(ap.parse_args())))
