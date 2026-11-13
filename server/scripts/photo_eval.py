"""사진 안내 평가: 연습 키오스크 장면을 **실제 서버 판단 경로**(LlmGuideAgent)에 넣어 사진 끔/켬을 비교한다 (P1, 2026-11-13).

  python scripts/photo_eval.py [--variants off-perfect,on-perfect,off-noisy,on-noisy] [--seed 7] [--camera cases.json]

- 화면: vision/testdata/kiosk/index.html 을 Chrome(headless)으로 1080x1920에 띄워 장면마다 찍는다.
  원본은 바꾸지 않고, 임시 복사본 끝에 장면을 만드는 스크립트를 붙인다 (버튼 동작을 차례로 실행).
- 사진: 앱(FrameJpegCache)과 같게 긴 변 960px · JPEG 70.
- 글자 읽기(OCR) 흉내
  · perfect: 화면 글자 그대로. 앞에 창이 떠 있으면 앱(:vision SceneProcessor)처럼 그 창 안 글자만.
  · noisy: 버튼 글자 30% 빠짐 · 비슷한 글자로 바뀜(옵→읍 · 담→달 …) · 가격 꼬리 깨짐(2,000원→2,00021) · 그림 버튼(+ · −) 못 읽음.
- 판단: 서버 설정 그대로(server/.env: 기본 Claude Haiku 5.5 · 생각 끔). 바꾸는 것은 PHOTO_GUIDE 하나.
- 채점: 정답 버튼을 이름이나 위치로 가리키면 정답(사진 박스는 정답 버튼과 겹치면). 끝 · 되묻기 장면은 행동 종류로.
  겹침: 맞힌 장면의 노란 칸(사진 박스 · 버튼 전체 칸)이 화면의 버튼 전체와 겹친 비율(IoU, 1이면 똑같다).
- --camera: 실제 카메라 사진(로컬 전용, 저장소에 올리지 않음) [{"id","image","goal","labels","gt":{left,top,right,bottom}}].
  글자 읽기 결과 없이(OCR이 아무것도 못 읽은 경우) 사진만으로 찾는지 본다.
요구: Google Chrome, Pillow, server/.env 의 ANTHROPIC_API_KEY (요청마다 비용이 든다: Haiku 약 $0.0004).
"""
import argparse, asyncio, base64, dataclasses, io, json, os, pathlib, random, re, statistics, subprocess, sys, tempfile, time, urllib.parse

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from PIL import Image                                                     # noqa: E402
from app.llm import LlmError                                              # noqa: E402
from app.main import build_agent                                          # noqa: E402
from app.models import GuideRequest                                       # noqa: E402
from app.settings import Settings                                         # noqa: E402

KIOSK = ROOT.parent / "vision" / "testdata" / "kiosk" / "index.html"
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
CONFUSE = {"옵": "읍", "담": "달", "초": "조", "추": "주", "리": "라", "노": "도", "택": "력", "커": "거", "션": "센", "카": "가", "페": "패"}

GOAL_A = "아이스 아메리카노 한 잔 샷 추가해서 포장해 주세요. 빨대나 봉투는 필요 없고 카드로 결제할게요. 적립이랑 영수증은 안 해요"
GOAL_B = "따뜻한 카페라떼 두 잔 먹고 갈게요. 카드로 결제하고 적립이랑 영수증은 안 해요"

# (id, 목표, 이번 말(첫 장면만), 지금까지 누른 것, 장면을 만드는 동작, 정답 버튼 선택자, 맞는 행동, 맞는 버튼 글자)
A_STEPS = [["item", {"id": "0-2"}], ["opt", {"g": "shot", "o": "s1"}], ["opt-add", {}], ["pay", {}], ["takeout", {}],
           ["disp-none", {}], ["method", {"v": "카드결제"}], ["stamp-skip", {}], ["approve", {}], ["wait", {"ms": 1800}], ["noprint", {}]]
A_PRESSED = ["(ICE)아메리카노", "샷 추가", "주문담기", "결제하기", "포장하기", "선택안함", "카드결제", "닫기", "승인 요청", None, "미출력"]
SCENES = [
    ("A01_menu", 0, '[data-act="item"][data-id="0-2"]', {"TAP"}, ["ice아메리카노"]),
    ("A02_options", 1, '[data-act="opt"][data-g="shot"][data-o="s1"]', {"TAP"}, ["샷추가"]),
    ("A03_options_shot", 2, '[data-act="opt-add"]', {"TAP"}, ["주문담기"]),
    ("A04_menu_cart", 3, '[data-act="pay"]', {"TAP"}, ["결제하기"]),
    ("A05_review", 4, '[data-act="takeout"]', {"TAP"}, ["포장하기"]),
    ("A06_disposable", 5, '[data-act="disp-none"]', {"TAP"}, ["선택안함"]),
    ("A07_payment", 6, '[data-act="method"][data-v="카드결제"]', {"TAP"}, ["카드결제"]),
    ("A08_stamp", 7, '[data-act="stamp-skip"]', {"TAP"}, ["닫기", "×", "x"]),
    ("A09_card", 8, '[data-act="approve"]', {"TAP", "DONE", "WAIT"}, ["승인요청"]),   # 서버 설계: 카드를 넣는 화면이면 끝(DONE)
    ("A10_receipt", 10, '[data-act="noprint"]', {"TAP", "DONE"}, ["미출력"]),
    ("A11_done", 11, None, {"DONE"}, []),
]
B_SCENES = [
    ("B01_find_tab", [], [], '[data-act="tab"][data-tab="커피"]', {"TAP"}, ["커피", "추천메뉴"]),
    ("B02_coffee_tab", [["tab", {"tab": "커피"}]], ["커피"], '[data-act="item"][data-id="2-3"]', {"TAP"}, ["hot카페라떼"]),
    # 수량을 먼저 올리거나, 주문에 없는 옵션(샷 등)을 먼저 묻거나 둘 다 맞다 (서버 규칙: 말에 없는 옵션은 묻는다)
    ("B03_qty", [["tab", {"tab": "커피"}], ["item", {"id": "2-3"}]], ["커피", "(HOT)카페라떼"], '[data-act="oq"][data-d="1"]',
     {"TAP", "ASK_USER"}, ["+", "더하기", "수량"]),
    ("B04_wrong_item", [["tab", {"tab": "커피"}], ["item", {"id": "2-3"}], ["oq", {"d": "1"}], ["opt-add", {}], ["item", {"id": "2-8"}]],
     ["(HOT)카페라떼", "더하기", "주문담기", "결제하기"], '.op-foot [data-act="opt-close"]', {"TAP"}, ["취소", "닫기", "×"]),
    ("B05_delete_confirm", [["tab", {"tab": "커피"}], ["item", {"id": "2-3"}], ["oq", {"d": "1"}], ["opt-add", {}], ["home", {}]],
     ["(HOT)카페라떼", "더하기", "주문담기", "결제하기"], '[data-act="al-no"]', {"TAP"}, ["취소"]),
    ("B06_empty_alert", [["pay", {}]], ["결제하기"], '[data-act="al-no"]', {"TAP"}, ["확인"]),
]

DRIVER = r"""<script>
(function(){
  const p = new URLSearchParams(location.search);
  if (!p.get('scene')) return;
  S.timerOn = false;
  const steps = JSON.parse(p.get('steps') || '[]'), target = p.get('target');
  const W = () => innerWidth, H = () => innerHeight;
  const norm = r => ({left: r.left / W(), top: r.top / H(), right: r.right / W(), bottom: r.bottom / H()});
  const shown = r => r.width > 2 && r.height > 2 && r.bottom > 0 && r.right > 0 && r.top < H() && r.left < W();
  function collect(){
    const pops = Array.from(document.querySelectorAll('#layer > .pop, #layer > .done'));
    const root = pops.length ? pops[pops.length - 1] : document.getElementById('kiosk');
    const out = [], walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()){
      const n = walker.currentNode, t = n.textContent.replace(/\s+/g, ' ').trim();
      if (!t) continue;
      const range = document.createRange(); range.selectNodeContents(n);
      const r = range.getBoundingClientRect(); if (!shown(r)) continue;
      const price = /^[+\-]?[\d,]+\s*원$/.test(t);
      out.push({text: t, kind: price ? 'PRICE' : (n.parentElement.closest('button') ? 'BUTTON_LIKE' : 'TEXT'), rect: norm(r)});
    }
    root.querySelectorAll('button[aria-label]').forEach(b => {          // 그림 버튼(× · + · −): 이름만 있는 버튼
      const r = b.getBoundingClientRect();
      if (shown(r) && !b.innerText.trim()) out.push({text: b.getAttribute('aria-label'), kind: 'BUTTON_LIKE', rect: norm(r), icon: true});
    });
    return out;
  }
  let i = 0;
  function next(){
    if (i < steps.length){ const [a, d] = steps[i++];
      if (a === 'wait'){ setTimeout(next, d.ms); return; }
      ACT[a](d || {}); setTimeout(next, 60); return; }
    let gt = null;
    if (target){ const el = document.querySelector(target); if (el) gt = norm(el.getBoundingClientRect()); }
    const pre = document.createElement('pre'); pre.id = 'gt'; pre.style.display = 'none';
    pre.textContent = JSON.stringify({gt, elements: collect()});
    document.body.appendChild(pre);
  }
  setTimeout(next, 100);
})();
</script>"""


def render(work: pathlib.Path, scene: str, steps, target):
    """장면 하나: (사진 base64, 화면 글자 목록, 정답 위치)"""
    q = urllib.parse.urlencode({"timer": "off", "scene": scene, "steps": json.dumps(steps, ensure_ascii=False), "target": target or ""})
    url = f"file://{work / 'kiosk.html'}?{q}"
    budget = 700 + 90 * len(steps) + sum(s[1].get("ms", 0) for s in steps if s[0] == "wait")
    base = [CHROME, "--headless=new", "--hide-scrollbars", "--no-first-run", "--no-default-browser-check",
            f"--user-data-dir={work / 'profile'}", "--window-size=1080,1920", f"--virtual-time-budget={budget}"]
    png = work / f"{scene}.png"
    subprocess.run(base + [f"--screenshot={png}", url], capture_output=True, timeout=120)
    dom = subprocess.run(base + ["--dump-dom", url], capture_output=True, text=True, timeout=120).stdout
    m = re.search(r'<pre id="gt"[^>]*>(.*?)</pre>', dom, re.S)
    info = json.loads(m.group(1).replace("&quot;", '"').replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"))
    img = Image.open(png).convert("RGB")
    img.thumbnail((960, 960))                                             # 앱: 긴 변 960px · JPEG 70
    buf = io.BytesIO(); img.save(buf, "JPEG", quality=70)
    return base64.b64encode(buf.getvalue()).decode(), info["elements"], info["gt"]


def noisy(elements, rnd: random.Random):
    out = []
    for e in elements:
        if e.get("icon") and e["text"] != "닫기":
            continue                                                       # 그림 버튼(+ · −)은 글자 읽기가 못 읽는다
        if e["kind"] == "BUTTON_LIKE" and e["text"] not in ("닫기",) and rnd.random() < 0.3:
            continue                                                       # 버튼 글자 30% 빠짐
        t = "×" if e.get("icon") else e["text"]
        if e["kind"] == "PRICE":
            t = t.replace("원", "21").replace(" ", "")                    # 가격 꼬리 깨짐
        elif rnd.random() < 0.35:
            t = "".join(CONFUSE.get(c, c) if rnd.random() < 0.5 else c for c in t)
        out.append({**e, "text": t})
    return out


def snapshot(scene, elements):
    els = [{"id": f"e{i + 1}", "text": e["text"][:120], "kind": e["kind"], "confidence": 0.9, "rect": e["rect"]}
           for i, e in enumerate(elements)]
    return {"screenId": f"s-{scene}", "frameId": 1, "timestampMs": 0, "elements": els, "stableFrames": 2}


def iou(b, gt):
    """노란 칸과 정답 버튼(화면의 버튼 전체)이 겹친 비율: 겹친 넓이 / 합친 넓이 (1이면 똑같다)"""
    w = max(0.0, min(b.right, gt["right"]) - max(b.left, gt["left"]))
    h = max(0.0, min(b.bottom, gt["bottom"]) - max(b.top, gt["top"]))
    inter = w * h
    union = (b.right - b.left) * (b.bottom - b.top) + (gt["right"] - gt["left"]) * (gt["bottom"] - gt["top"]) - inter
    return inter / union if union > 0 else 0.0


def key(t):
    return re.sub(r"[\s()\[\]{}.,·:/'\"\-원0-9]", "", (t or "").lower())


def judge(case, resp, els):
    a = resp.action
    t = a.type.value
    if t not in case["actions"]:
        return False
    if t != "TAP":
        return True
    gt = case.get("gt")
    if resp.box is not None and gt:                                        # 사진 박스: 정답 버튼과 겹치면
        b = resp.box
        return not (b.right < gt["left"] or b.left > gt["right"] or b.bottom < gt["top"] or b.top > gt["bottom"])
    el = next((e for e in els if e["id"] == a.targetId), None)
    if el is not None and gt:
        cx, cy = (el["rect"]["left"] + el["rect"]["right"]) / 2, (el["rect"]["top"] + el["rect"]["bottom"]) / 2
        if gt["left"] - 0.01 <= cx <= gt["right"] + 0.01 and gt["top"] - 0.01 <= cy <= gt["bottom"] + 0.01:
            return True
    return any(key(l) and key(l) in key(a.guideText) for l in case["labels"])


def build_cases(seed: int, camera: str | None):
    work = pathlib.Path(tempfile.mkdtemp(prefix="kl-photo-eval-"))
    html = KIOSK.read_text(encoding="utf-8").replace("</body>", DRIVER + "</body>")
    (work / "kiosk.html").write_text(html, encoding="utf-8")
    cases = []
    for scene, n, target, actions, labels in SCENES:
        img, els, gt = render(work, scene, A_STEPS[:n], target)
        hist = [{"screenId": f"h{i}", "action": "TAP", "targetText": t} for i, t in enumerate(A_PRESSED[:n]) if t][-5:]
        cases.append(dict(id=scene, goal=GOAL_A, said=GOAL_A if n == 0 else None, hist=hist, image=img, els=els, gt=gt,
                          actions=actions, labels=labels))
    for scene, steps, pressed, target, actions, labels in B_SCENES:
        img, els, gt = render(work, scene, steps, target)
        hist = [{"screenId": f"h{i}", "action": "TAP", "targetText": t} for i, t in enumerate(pressed)][-5:]
        cases.append(dict(id=scene, goal=GOAL_B, said=GOAL_B if not steps else None, hist=hist, image=img, els=els, gt=gt,
                          actions=actions, labels=labels))
    if camera:
        for c in json.loads(pathlib.Path(camera).read_text(encoding="utf-8")):
            img = Image.open(c["image"]).convert("RGB"); img.thumbnail((960, 960))
            buf = io.BytesIO(); img.save(buf, "JPEG", quality=70)
            cases.append(dict(id=c["id"], goal=c["goal"], said=c["goal"], hist=[], image=base64.b64encode(buf.getvalue()).decode(),
                              els=[], gt=c["gt"], actions={"TAP"}, labels=c["labels"], camera=True))
    rnd = random.Random(seed)
    for c in cases:
        c["noisy"] = noisy(c["els"], rnd)
    return cases


async def run(variant: str, cases, base: Settings):
    photo, ocr = variant.split("-")
    agent = build_agent(dataclasses.replace(base, photo_guide=(photo == "on")))
    rows = []
    for c in cases:
        els = c["els"] if ocr == "perfect" else c["noisy"]
        snap = snapshot(c["id"], els)
        body = {"sessionId": f"eval-{variant}-{c['id']}", "utterance": c["said"], "goal": None if c["said"] else c["goal"],
                "snapshot": snap, "history": c["hist"], "image": c["image"]}
        res, err = None, ""
        for attempt in range(3):                    # Claude 혼잡(503) · 시간 초과: 앱처럼 잠깐 뒤 다시 묻는다
            t0 = time.perf_counter()
            try:
                res = await agent.next(GuideRequest.model_validate(body))
                break
            except LlmError as e:
                err = type(e).__name__
                await asyncio.sleep(2 + 3 * attempt)
        ms = (time.perf_counter() - t0) * 1000
        if res is None:                             # 세 번 다 실패: 오답으로 세고 다음 장면
            rows.append(dict(id=c["id"], ok=False, ms=ms, src="error", calls=0, box=False, whole=False, iou=None))
            print(f"  ✗ {c['id']:20} 오류 {err} (3번 실패)", flush=True)
            continue
        ok = judge(c, res.response, snap["elements"])
        a = res.response.action
        b = res.response.box
        overlap = iou(b, c["gt"]) if (b is not None and c.get("gt") and ok) else None
        rows.append(dict(id=c["id"], ok=ok, ms=ms, src=res.meta.get("source"), calls=res.meta.get("llm_calls", 0),
                         box=b is not None, whole=bool(res.meta.get("button_box")), iou=overlap))
        kind = " 버튼전체칸" if res.meta.get("button_box") else (" 사진박스" if b is not None else "")
        print(f"  {'✓' if ok else '✗'} {c['id']:20} {ms / 1000:5.2f}초 {a.type.value:8} {(a.guideText or a.question or a.reason or '')[:30]!r:34} "
              f"경로={res.meta.get('source')}{kind}{f' 겹침 {overlap:.2f}' if overlap is not None else ''}", flush=True)
    return rows


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variants", default="off-perfect,on-perfect,off-noisy,on-noisy")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--camera")
    args = ap.parse_args()
    base = Settings.from_env()
    print(f"AI: {base.llm_provider} · {base.llm_model} (생각 끔) / 장면 만드는 중…", flush=True)
    cases = build_cases(args.seed, args.camera)
    summary = []
    for v in args.variants.split(","):
        print(f"\n=== 사진 {'켬' if v.startswith('on') else '끔'} · 글자 읽기 {'정상' if v.endswith('perfect') else '불량'} ({v})", flush=True)
        rows = await run(v, cases, base)
        lat = sorted(r["ms"] for r in rows)
        ious = [r["iou"] for r in rows if r["iou"] is not None]
        summary.append((v, sum(r["ok"] for r in rows), len(rows), statistics.median(lat), lat[-1],
                        sum(r["calls"] for r in rows), sum(r["box"] for r in rows), sum(r["whole"] for r in rows),
                        statistics.median(ious) if ious else None))
    print("\n변형          정답      시간(중앙값 · 가장 느림)   AI 호출  상자(버튼전체칸)  겹침(중앙값)")
    for v, ok, n, med, worst, calls, boxes, whole, ov in summary:
        print(f"{v:13} {ok:2}/{n:<2}     {med / 1000:4.2f}초 · {worst / 1000:4.2f}초          {calls:3}      {boxes:2} ({whole:2})        "
              f"{'-' if ov is None else f'{ov:.2f}'}")


if __name__ == "__main__":
    asyncio.run(main())
