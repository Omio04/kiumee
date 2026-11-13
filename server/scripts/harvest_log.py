"""실기기 로그(DEBUG_PROMPTS=1 서버 기록)에서 "어르신 말 + 그때 읽힌 화면 글자"를 모아 회귀 시험 자료로 만든다.

  python scripts/harvest_log.py server.log            # testdata/real_logs/cases.json 에 합친다 (같은 것은 한 번만)

화면 글자는 서버가 개인정보를 걸러낸 뒤의 것이고, 사진은 로그에 없다. 실패가 생길 때마다 이 스크립트로 모으면
"이번 문제는 어떻게 풀까"를 매번 새로 생각하지 않고 replay_cases.py 로 전체를 다시 돌려 볼 수 있다.
"""
import ast, json, pathlib, sys

OUT = pathlib.Path(__file__).resolve().parent.parent / "testdata" / "real_logs" / "cases.json"


def parse(log_text: str):
    for line in log_text.splitlines():
        if "KL_DEBUG 요청" not in line or "utter=" not in line or "화면=" not in line:
            continue
        try:
            utter = ast.literal_eval(line.split("utter=", 1)[1].split(" 이력=", 1)[0].strip())
            texts = ast.literal_eval(line.split("화면=", 1)[1].strip())
        except (ValueError, SyntaxError):
            continue                                              # 잘린 줄은 건너뛴다
        if utter and isinstance(texts, list) and texts:
            yield {"utterance": utter, "texts": [str(t) for t in texts]}


def main(path: str) -> None:
    found = list(parse(pathlib.Path(path).read_text(encoding="utf-8", errors="replace")))
    have = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else []
    seen = {(c["utterance"], tuple(c["texts"])) for c in have}
    added = [c for c in found if (c["utterance"], tuple(c["texts"])) not in seen]
    OUT.write_text(json.dumps(have + added, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"로그에서 {len(found)}건, 새로 {len(added)}건 추가 (전체 {len(have) + len(added)}건) → {OUT}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "server.log")
