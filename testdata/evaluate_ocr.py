"""Compare target labels with exported ScreenSnapshot JSON files."""
import argparse
import json
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path


def key(text):
    normalized = unicodedata.normalize("NFKC", text).casefold()
    return "".join(character for character in normalized if character.isalnum())


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("labels", type=Path)
    parser.add_argument("snapshots", type=Path)
    args = parser.parse_args()
    paths = sorted(path for path in args.labels.glob("*.json") if path.name != "schema.json")
    if not paths:
        parser.error("라벨 JSON이 없습니다.")
    scores = defaultdict(lambda: [0, 0])
    missing = []
    for path in paths:
        label = read_json(path)
        expected = key(label["answerText"])
        if not expected:
            parser.error(f"정답 글자가 비어 있습니다: {path.name}")
        snapshot_path = args.snapshots / Path(label["image"]).with_suffix(".json").name
        hit = False
        if snapshot_path.exists():
            hit = any(key(element["text"]) == expected for element in read_json(snapshot_path)["elements"])
        else:
            missing.append(snapshot_path.name)
        for category in ("전체", label["variant"]):
            scores[category][0] += int(hit)
            scores[category][1] += 1
        print(f"{path.name}  {'PASS' if hit else 'MISS'}")
    for category, (hits, total) in scores.items():
        print(f"{category}  {hits}/{total}  {100 * hits / total:.1f}%")
    if missing:
        print(f"스냅샷 누락 {len(missing)}건 (MISS 포함): {', '.join(missing)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
