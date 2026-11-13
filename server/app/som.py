"""Set-of-Mark: 사진 위 후보 칸마다 번호를 그려서 AI가 좌표 대신 번호를 고르게 한다.

근거: SoM (Yang et al. 2023, arXiv:2310.11441) — 번호를 붙인 이미지로 GPT-4V가 미세조정된 검출 모델을 앞섰다.
OmniParser (Microsoft) — "검출 박스에 번호 → VLM은 번호만 고름" 방식으로 GPT-4o 화면 위치 맞히기 0.8% → 39.6%.
노란 칸은 여전히 글자 읽기(OCR) 칸을 그대로 쓴다. 사진은 저장하지 않고 이 요청에서만 만든다.
"""
from __future__ import annotations

import base64
import io
from typing import Dict, Optional, Sequence

from .models import ScreenElement


def draw_marks(image_b64: str, marks: Dict[str, ScreenElement]) -> Optional[str]:
    """번호 → 칸. 칸마다 테두리와 번호표를 그린 JPEG(base64)를 돌려준다. 그리지 못하면 None (원래 사진을 쓴다)."""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        return None
    try:
        im = Image.open(io.BytesIO(base64.b64decode(image_b64))).convert("RGB")
    except Exception:
        return None
    w, h = im.size
    draw = ImageDraw.Draw(im)
    size = max(12, int(min(w, h) * 0.022))
    try:
        font = ImageFont.truetype("arial.ttf", size)
    except OSError:
        font = ImageFont.load_default()
    for label, el in marks.items():
        r = el.rect
        box = (int(r.left * w), int(r.top * h), int(r.right * w), int(r.bottom * h))
        draw.rectangle(box, outline=(255, 0, 255), width=2)
        tw = int(draw.textlength(label, font=font)) + 6
        tag = (box[0], max(0, box[1] - size - 4), box[0] + tw, max(size + 4, box[1]))
        draw.rectangle(tag, fill=(255, 0, 255))
        draw.text((tag[0] + 3, tag[1] + 1), label, fill=(255, 255, 255), font=font)
    out = io.BytesIO()
    im.save(out, "JPEG", quality=80)
    return base64.b64encode(out.getvalue()).decode()


def short_labels(ids: Sequence[str]) -> Dict[str, str]:
    """긴 칸 번호(e6a7649f7) 대신 그림에 쓸 짧은 번호(1 · 2 · 3 …): 짧은 번호 → 원래 번호."""
    return {str(i + 1): cid for i, cid in enumerate(ids)}


GRID_COLS = "ABCDEF"
GRID_ROWS = 8


def draw_grid(image_b64: str, cols: str = GRID_COLS, rows: int = GRID_ROWS):
    """사진 위에 격자와 칸 이름(A1 … F8)을 그린다 → (그린 사진 base64, {칸 이름: (왼, 위, 오른, 아래) 0~1}).
    AI에게 숫자 좌표를 달라고 하면 부정확하다. 이름 붙은 칸을 고르게 하면 훨씬 정확하다 (Set-of-Mark와 같은 원리).
    글자 읽기가 버튼을 못 읽었을 때(흐림 · 반사 · 작은 글씨) AI가 사진에서 본 위치로 노란 칸을 치려고 쓴다. 못 그리면 None."""
    try:
        from PIL import Image, ImageDraw, ImageFont
        im = Image.open(io.BytesIO(base64.b64decode(image_b64))).convert("RGB")
    except Exception:
        return None
    w, h = im.size
    draw = ImageDraw.Draw(im)
    size = max(11, int(min(w, h) * 0.024))
    try:
        font = ImageFont.truetype("arial.ttf", size)
    except OSError:
        font = ImageFont.load_default()
    cells = {}
    cw, ch = w / len(cols), h / rows
    for i in range(1, len(cols)):
        draw.line([(int(i * cw), 0), (int(i * cw), h)], fill=(0, 200, 255), width=1)
    for j in range(1, rows):
        draw.line([(0, int(j * ch)), (w, int(j * ch))], fill=(0, 200, 255), width=1)
    for i, c in enumerate(cols):
        for j in range(rows):
            label = f"{c}{j + 1}"
            x, y = int(i * cw) + 2, int(j * ch) + 2
            tw = int(draw.textlength(label, font=font)) + 4
            draw.rectangle((x, y, x + tw, y + size + 2), fill=(0, 120, 200))
            draw.text((x + 2, y), label, fill=(255, 255, 255), font=font)
            cells[label] = (i / len(cols), j / rows, (i + 1) / len(cols), (j + 1) / rows)
    out = io.BytesIO()
    im.save(out, "JPEG", quality=80)
    return base64.b64encode(out.getvalue()).decode(), cells


def crop_grid(image_b64: str, region, cols: str = "ABCD", rows: int = 4, out_long: int = 900):
    """사진의 region(왼, 위, 오른, 아래 0~1)만 잘라 크게 키우고 촘촘한 격자를 그린다 → (사진 base64, {칸 이름: 전체 사진 기준 0~1 칸}).
    대략 찾은 칸 주변을 확대해서 한 번 더 고르게 하면, 성긴 격자에서 바로 옆 칸으로 빗나가던 것이 바로잡힌다."""
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(base64.b64decode(image_b64))).convert("RGB")
    except Exception:
        return None
    w, h = im.size
    l, t, r, b = region
    box = (int(l * w), int(t * h), max(int(l * w) + 2, int(r * w)), max(int(t * h) + 2, int(b * h)))
    crop = im.crop(box)
    scale = out_long / max(crop.size)
    crop = crop.resize((max(1, int(crop.size[0] * scale)), max(1, int(crop.size[1] * scale))))
    buf = io.BytesIO(); crop.save(buf, "JPEG", quality=85)
    drawn = draw_grid(base64.b64encode(buf.getvalue()).decode(), cols, rows)
    if drawn is None:
        return None
    img, local = drawn
    rw, rh = r - l, b - t
    return img, {k: (l + a * rw, t + bb * rh, l + c * rw, t + d * rh) for k, (a, bb, c, d) in local.items()}
