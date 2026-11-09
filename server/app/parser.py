"""ActionParser: LLM 응답 텍스트 → LlmAction. 설명이 앞뒤에 붙거나 코드 블록에 싸여 와도 첫 JSON 객체만 꺼냅니다."""
from __future__ import annotations

import json
from typing import Optional

from pydantic import ValidationError

from .models import LlmAction


class ParseError(ValueError):
    pass


def _first_json_object(raw: str) -> Optional[str]:
    start = raw.find("{")
    while start != -1:
        depth, in_str, esc = 0, False, False
        for i in range(start, len(raw)):
            ch = raw[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
                continue
            if ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return raw[start:i + 1]
        start = raw.find("{", start + 1)
    return None


class ActionParser:
    def parse(self, raw: str) -> LlmAction:
        blob = _first_json_object(raw or "")
        if blob is None:
            raise ParseError("no json object")
        try:
            data = json.loads(blob)
        except json.JSONDecodeError as e:
            raise ParseError("invalid json") from e
        if not isinstance(data, dict):
            raise ParseError("not an object")
        try:
            return LlmAction.model_validate(data)
        except ValidationError as e:
            raise ParseError("schema mismatch") from e
