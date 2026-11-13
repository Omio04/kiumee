"""방법 비교 실험: 같은 시험 세트로 "어느 방법이 맞는 칸을 가장 잘 고르는가"를 잰다.

  ANTHROPIC_API_KEY=... GEMINI_API_KEY=... python scripts/compare_methods.py [--models haiku,gemini] [--seed 7]

시험 세트: 키오스크 7종(카페 메뉴 · 옵션 · 주문 확인 · 결제 수단 · 적립 · 버스표 · 병원)을 그림으로 그리고, 질문마다 정답 칸을 정해 둔다.
글자 읽기(OCR) 흉내: 실기기 로그(2026-11-13)에서 본 실수를 같은 꼴로 넣는다 — 모양이 비슷한 글자로 바뀜(옵→읍 · 담→달 · 초→조 …),
가격 꼬리 깨짐(2,000원→2,00021), 일부 칸 누락. 사진은 그린 화면을 줄였다 키우고 살짝 흐리게 해서 카메라처럼 만든다.

방법: 기본 / 낱말 보정(lexicon) / 번호 그린 사진(SoM) / 둘 다.
채점: 정답 칸을 가리키면 정답. 정답이 "묻기"면 칸을 안 가리켜야 정답. 정답 칸이 OCR에서 빠졌으면 엉뚱한 칸을 안 가리켜야 정답.
"""
import argparse, asyncio, base64, io, os, pathlib, random, statistics, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from PIL import Image, ImageDraw, ImageFilter, ImageFont                 # noqa: E402
from app.agent import LlmGuideAgent                                       # noqa: E402
from app.llm import AnthropicLlmClient, GeminiLlmClient                   # noqa: E402
from app.models import GuideRequest                                       # noqa: E402
from app.settings import Settings                                         # noqa: E402

W, H = 720, 960
FONT = "C:/Windows/Fonts/malgun.ttf" if os.path.exists("C:/Windows/Fonts/malgun.ttf") else None
CONFUSE = {"옵": "읍", "담": "달", "초": "조", "롱": "옹", "칩": "집", "햄": "행", "추": "주", "리": "라", "노": "도", "택": "력", "커": "거", "션": "센"}

# (글자, 종류, 왼, 위, 오른, 아래) — 픽셀. 종류 B=버튼, T=글자, P=가격
SCENES = {
    "카페 메뉴": [("메가커피", "T", 20, 15, 300, 60), ("처음으로", "B", 560, 15, 700, 60),
               ("커피", "B", 20, 80, 130, 125), ("디카페인", "B", 140, 80, 270, 125), ("음료", "B", 280, 80, 390, 125), ("티", "B", 400, 80, 510, 125), ("푸드", "B", 520, 80, 630, 125)]
              + [(n, "B", 30 + (i % 3) * 230, 160 + (i // 3) * 150, 230 + (i % 3) * 230, 260 + (i // 3) * 150) for i, n in enumerate(
                  ["아메리카노", "카페라떼", "바닐라라떼", "카라멜마끼아또", "콜드브루", "에스프레소", "헤이즐넛라떼", "카페모카", "돌체라떼", "아인슈페너", "연유라떼", "흑당라떼"])]
              + [("장바구니 조회", "B", 30, 860, 340, 940), ("결제하기", "B", 380, 860, 700, 940)],
    "옵션": [("선택하신 상품의 옵션을 선택해 주세요", "T", 20, 20, 700, 70), ("아메리카노 2,000원", "T", 20, 90, 500, 140),
             ("샷 추가", "B", 40, 200, 340, 280), ("텀블러 사용", "B", 380, 200, 680, 280), ("당도 조절", "B", 40, 310, 340, 390),
             ("시럽 추가", "B", 380, 310, 680, 390), ("선택된 옵션 없음", "T", 40, 440, 680, 490), ("취소", "B", 30, 860, 340, 940), ("주문담기", "B", 380, 860, 700, 940)],
    "주문 확인": [("주문 내역을 확인해 주세요", "T", 20, 20, 700, 70), ("아메리카노 1개 2,000원", "T", 40, 120, 680, 170),
               ("치즈케이크 1개 4,500원", "T", 40, 180, 680, 230), ("결제금액 6,500원", "T", 40, 300, 680, 350),
               ("먹고가기", "B", 40, 600, 340, 720), ("포장하기", "B", 380, 600, 680, 720), ("이전", "B", 30, 860, 340, 940)],
    "결제 수단": [("결제 수단을 선택해 주세요", "T", 20, 20, 700, 70), ("카드결제", "B", 40, 150, 680, 260), ("간편결제", "B", 40, 290, 680, 400),
               ("상품권", "B", 40, 430, 680, 540), ("이전", "B", 30, 860, 340, 940)],
    "적립": [("포인트를 적립하시겠어요?", "T", 20, 60, 700, 120), ("휴대폰 번호 입력", "T", 40, 200, 680, 260),
             ("적립하기", "B", 40, 700, 340, 800), ("건너뛰기", "B", 380, 700, 680, 800)],
    "버스표": [("출발 시간을 선택해 주세요", "T", 20, 20, 700, 70), ("서울 → 광주", "T", 20, 90, 500, 140),
              ("06:10 한빛고속", "B", 40, 200, 680, 270), ("08:40 새길여객", "B", 40, 290, 680, 360), ("12:05 한빛고속", "B", 40, 380, 680, 450),
              ("20:19 한빛고속", "B", 40, 470, 680, 540), ("이전", "B", 30, 860, 340, 940), ("처음으로", "B", 380, 860, 700, 940)],
    "병원": [("진료과를 선택해 주세요", "T", 20, 20, 700, 70), ("내과", "B", 40, 150, 340, 260), ("정형외과", "B", 380, 150, 680, 260),
             ("이비인후과", "B", 40, 290, 340, 400), ("안과", "B", 380, 290, 680, 400), ("처음으로", "B", 30, 860, 340, 940)],
}
# (화면, 어르신 말, 정답 칸 글자들 — 빈 목록이면 "칸을 가리키지 말고 물어야 함")
CASES = [
    ("카페 메뉴", "아이스티 마시고 싶어", ["티"]), ("카페 메뉴", "아메리카노 주세요", ["아메리카노"]), ("카페 메뉴", "결제하고 싶어", ["결제하기", "장바구니 조회"]),
    ("옵션", "그냥 결제하고 싶어", ["주문담기"]), ("옵션", "샷 하나 더 넣어 줘", ["샷 추가"]), ("옵션", "옵션 안 할래", ["주문담기"]),
    ("주문 확인", "이제 뭐 해야 돼", []), ("주문 확인", "포장해 갈게요", ["포장하기"]),
    ("결제 수단", "카드로 할게", ["카드결제"]), ("결제 수단", "삼성페이 돼?", ["간편결제"]),
    ("적립", "적립 안 할래", ["건너뛰기"]), ("적립", "이거 꼭 해야 돼?", ["건너뛰기", ""]),
    ("버스표", "이제 뭐 해야 돼", []), ("버스표", "낮 열두 시쯤 가고 싶어", ["12:05 한빛고속"]),
    ("병원", "무릎이 너무 아파서 왔어", ["정형외과"]), ("병원", "어디 눌러야 돼?", []),
]


def render(scene):
    im = Image.new("RGB", (W, H), (248, 248, 248)); d = ImageDraw.Draw(im)
    for text, kind, l, t, r, b in scene:
        size = 30 if (b - t) > 60 else 24
        f = ImageFont.truetype(FONT, size) if FONT else ImageFont.load_default()
        if kind == "B":
            dark = text in ("결제하기", "주문담기")
            d.rounded_rectangle([l, t, r, b], 14, fill=(30, 30, 30) if dark else (255, 255, 255), outline=(150, 150, 150), width=2)
            d.text((l + 18, t + (b - t - size) // 2), text, font=f, fill=(255, 255, 255) if dark else (20, 20, 20))
        else:
            d.text((l, t), text, font=f, fill=(20, 20, 20))
    im = im.resize((W * 3 // 5, H * 3 // 5)).resize((W, H)).filter(ImageFilter.GaussianBlur(0.8))   # 카메라처럼 조금 뭉개기
    buf = io.BytesIO(); im.save(buf, "JPEG", quality=70); return base64.b64encode(buf.getvalue()).decode()


def ocr(scene, rnd, garble=0.35, drop=0.12):
    """실기기 로그에서 본 실수를 흉내 낸 글자 읽기 결과. 원래 글자 → (읽힌 요소, 정답 글자)"""
    out, truth = [], {}
    for i, (text, kind, l, t, r, b) in enumerate(scene):
        if kind == "B" and rnd.random() < drop:
            continue                                                   # 칸 누락
        read = "".join(CONFUSE.get(c, c) if c in CONFUSE and rnd.random() < garble else c for c in text)
        if "원" in read and rnd.random() < 0.4:
            read = read.replace("원", rnd.choice(["21", "91", "번"]))
        eid = f"x{i:02d}{rnd.randint(100, 999)}"
        out.append({"id": eid, "text": read, "rect": {"left": l / W, "top": t / H, "right": r / W, "bottom": b / H}, "confidence": 0.9,
                    "kind": "BUTTON_LIKE" if kind == "B" else ("PRICE" if kind == "P" else "TEXT")})
        truth[eid] = text
    return out, truth


def make_llm(name):
    if name == "haiku":
        return AnthropicLlmClient(os.environ["ANTHROPIC_API_KEY"], "claude-haiku-5-5"), "anthropic"
    if name == "gemini":
        return GeminiLlmClient(os.environ["GEMINI_API_KEY"], "gemini-3.5-flash-lite"), "gemini"
    return AnthropicLlmClient(os.environ["ANTHROPIC_API_KEY"], name), "anthropic"


async def run(model, method, seed, pace, hard=False):
    llm, prov = make_llm(model)
    s = Settings(app_key="x", llm_provider=prov, cache_size=0, help_mode=True, llm_timeout_s=10.0, total_budget_s=14.0,
                 som_marks=method in ("SoM", "둘 다"), lexicon_fix=method in ("낱말 보정", "둘 다"))
    ag = LlmGuideAgent(llm, s)
    rnd = random.Random(seed)
    ok = wrong = errors = 0; ms = []; detail = []
    for k, (scene, said, expect) in enumerate(CASES):
        els, truth = ocr(SCENES[scene], rnd, *((0.9, 0.3) if hard else (0.35, 0.12)))
        present = [e for e in els if truth[e["id"]] in expect]
        req = GuideRequest.model_validate({"sessionId": f"c{k}", "utterance": said, "goal": None, "history": [], "image": render(SCENES[scene]),
                                           "snapshot": {"screenId": f"{scene}{k}", "frameId": 1, "timestampMs": 0, "stableFrames": 2, "elements": els}})
        await asyncio.sleep(pace)
        t0 = time.perf_counter()
        try:
            r = (await ag.next(req)).response
        except Exception as e:
            detail.append(f"{scene}/{said}: 오류 {type(e).__name__}"); errors += 1; continue
        ms.append((time.perf_counter() - t0) * 1000)
        a = r.action
        hit = truth.get(a.targetId) if a.type.value == "TAP" else None
        if a.type.value == "TAP" and a.targetId == "photo":
            hit = (a.guideText or "")                                  # 사진 위치로 가리킴 → 이름으로 채점
        if not [e for e in expect if e]:                                # 물어야 하는 질문
            good = a.type.value != "TAP"
        elif not present:                                               # 정답 칸이 OCR에서 빠짐 → 엉뚱한 칸만 안 가리키면 정답
            good = a.type.value != "TAP" or any(x and x in (hit or "") for x in expect)
        else:
            good = hit is not None and any(x and (hit == x or x in hit) for x in expect) or ("" in expect and a.type.value != "TAP")
        ok += good; wrong += (not good) and a.type.value == "TAP"
        if not good:
            detail.append(f"{scene}/{said}: {a.type.value} {hit or a.guideText or ''} (정답 {expect or '묻기'}{'' if present or not expect else ', OCR에 없음'})")
    return ok, wrong, statistics.median(ms) if ms else 0, detail, errors


async def main(a):
    methods = ["기본", "낱말 보정", "SoM", "둘 다"]
    print(f"시험 {len(CASES)}문항 · 화면 {len(SCENES)}종 · OCR 흉내 seed={a.seed}\n")
    rows = []
    for model in a.models.split(","):
        for m in methods:
            ok, wrong, med, detail, errors = await run(model, m, a.seed, a.pace, a.hard)
            if errors == 0:
                rows.append((model, m, ok, wrong, med))                 # 오류가 난 줄은 비교에서 뺀다 (한도 초과 등)
            print(f"[{model:6s} · {m:5s}] 정답 {ok}/{len(CASES)} ({100 * ok // len(CASES)}%) · 엉뚱한 칸 {wrong} · 오류 {errors} · 응답 중앙 {med:.0f}ms")
            for d in detail:
                print("      ✗", d)
    if not rows:
        print("\n오류 없이 끝난 줄이 없어 비교할 수 없습니다.")
        return
    best = max(rows, key=lambda r: (r[2], -r[3], -r[4]))
    print(f"\n가장 좋음: {best[0]} · {best[1]} (정답 {best[2]}/{len(CASES)}, 엉뚱한 칸 {best[3]}, {best[4]:.0f}ms)")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="haiku,gemini")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--pace", type=float, default=1.0)
    ap.add_argument("--hard", action="store_true", help="OCR 실수를 훨씬 심하게: 바뀔 수 있는 글자의 90%가 바뀌고 칸의 30%가 빠진다")
    asyncio.run(main(ap.parse_args()))
