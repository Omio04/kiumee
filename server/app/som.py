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
