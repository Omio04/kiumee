"""Reproducible image stress evaluation of the supplied kiosk using Windows OCR.

Install pillow, playwright and winrt-Windows.{Media.Ocr,Globalization,Graphics.Imaging,Storage,Storage.Streams,Foundation}.
Run playwright install chromium, then this script with --output outside the source tree.
This measures Windows OCR, not Android ML Kit. No model weights are trained.
"""
import argparse
import asyncio
import json
from pathlib import Path

from PIL import Image, ImageFilter, ImageEnhance
from playwright.async_api import async_playwright
from winrt.windows.globalization import Language
from winrt.windows.graphics.imaging import BitmapDecoder
from winrt.windows.media.ocr import OcrEngine
from winrt.windows.storage.streams import InMemoryRandomAccessStream, DataWriter

STAGES = [
    ("menu", None, ["아메리카노"]),
    ("options", '[data-act="item"][data-id="0-2"]', ["주문담기", "선택된"]),
    ("cart", '[data-act="opt-add"]', ["결제하기"]),
    ("review", '[data-act="pay"]', ["먹고가기", "포장하기"]),
    ("disposable", '[data-act="takeout"]', ["선택안함", "선택완료"]),
    ("payment", '[data-act="disp-none"]', ["카드결제", "앱카드"]),
    ("loyalty", '[data-act="method"][data-v="카드결제"]', ["적립"]),
    ("card", '[data-act="stamp-skip"]', ["승인", "카드"]),
    ("receipt", '[data-act="approve"]', ["출력"]),
    ("complete", '[data-act="noprint"]', ["주문번호", "완료"]),
]


async def recognize(engine, path):
    stream = InMemoryRandomAccessStream()
    writer = DataWriter(stream)
    writer.write_bytes(path.read_bytes())
    await writer.store_async()
    writer.detach_stream()
    writer.close()
    stream.seek(0)
    decoder = await BitmapDecoder.create_async(stream)
    bitmap = await decoder.get_software_bitmap_async()
    result = await engine.recognize_async(bitmap)
    lines = [{"text": line.text, "words": [
        {"text": word.text, "x": word.bounding_rect.x, "y": word.bounding_rect.y,
         "width": word.bounding_rect.width, "height": word.bounding_rect.height}
        for word in line.words]} for line in result.lines]
    bitmap.close()
    stream.close()
    return lines


async def main(args):
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    engine = OcrEngine.try_create_from_language(Language("ko-KR"))
    if engine is None:
        raise RuntimeError("Windows Korean OCR language pack is required")
    records = []
    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        page = await browser.new_page(viewport={"width": 720, "height": 1280})
        await page.goto(args.html.resolve().as_uri() + "?timer=off")
        for stage, selector, expected in STAGES:
            if stage == "options":
                selector = '[data-act="item"]'
                await page.locator(selector).filter(has_text="(ICE)아메리카노").first.click()
            elif selector:
                await page.locator(selector).last.click()
            if stage == "receipt":
                await page.locator('[data-act="noprint"]').wait_for()
            # Freeze practice countdowns only after the intended stage has arrived.
            await page.evaluate("stopSub()")
            original = out / f"{stage}-clean.png"
            await page.screenshot(path=str(original))
            source = Image.open(original).convert("RGB")
            variants = {
                "clean": source,
                "blur-light": source.filter(ImageFilter.GaussianBlur(.7)),
                "blur-medium": source.filter(ImageFilter.GaussianBlur(1.2)),
                "shake-left": source.transform(source.size, Image.Transform.AFFINE, (1, 0, 22, 0, 1, -14)),
                "tilt": source.rotate(2.5, resample=Image.Resampling.BICUBIC, fillcolor="gray"),
                "dim": ImageEnhance.Contrast(ImageEnhance.Brightness(source).enhance(.65)).enhance(.7),
            }
            for variant, im in variants.items():
                path = out / f"{stage}-{variant}.png"
                im.save(path)
                raw = await recognize(engine, path)
                # Fixed preprocessing, evaluated on the exact same pixels.
                enhanced = im.resize((1080, 1920), Image.Resampling.LANCZOS).filter(
                    ImageFilter.UnsharpMask(radius=1.2, percent=140, threshold=3))
                enhanced_path = out / f"{stage}-{variant}-enhanced.png"
                enhanced.save(enhanced_path)
                processed = await recognize(engine, enhanced_path)
                def hits(lines):
                    text = "".join(line["text"] for line in lines).replace(" ", "")
                    return sum(word in text for word in expected)
                records.append({"stage": stage, "variant": variant, "expected": expected,
                                "raw_hits": hits(raw), "enhanced_hits": hits(processed),
                                "raw": raw, "enhanced": processed})
            print(stage, flush=True)
        await browser.close()
    summary = {"engine": "Windows Korean OCR", "cases": len(records),
               "expected_total": sum(len(r["expected"]) for r in records),
               "raw_hits": sum(r["raw_hits"] for r in records),
               "enhanced_hits": sum(r["enhanced_hits"] for r in records)}
    (out / "results.json").write_text(json.dumps({"summary": summary, "cases": records},
                                               ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--html", type=Path, default=Path(__file__).resolve().parents[1] / "testdata/kiosk/index.html")
    parser.add_argument("--output", type=Path, required=True)
    asyncio.run(main(parser.parse_args()))
