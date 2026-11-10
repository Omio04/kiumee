"""LlmClient 포트와 구현.

- AnthropicLlmClient : 실제 Claude 호출 (타임아웃 4초). 요청 내용은 로그·오류 문구에 넣지 않습니다.
- ScriptedFakeLlmClient : 테스트용. 미리 정해 둔 응답을 차례로 돌려줌 (형식 오류·시간 초과 흉내 포함).
- HeuristicFakeLlmClient : 키 없이 서버를 돌려 보거나 Android 쪽 연동을 연습할 때 쓰는 규칙 기반 가짜.
"""
from __future__ import annotations

import asyncio
import json
from typing import Callable, List, Optional, Protocol, Union

import httpx

from .ranker import expand_query, match_score

ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"

# LLM이 내야 하는 JSON의 모양. 두 제공자 모두 "형식을 강제"하는 기능에 이 스키마를 넘깁니다:
#   Anthropic: 도구 하나(decide)를 tool_choice로 강제 → 입력이 항상 스키마를 따르는 JSON 객체
#   Gemini   : responseSchema (constrained decoding)
# 프롬프트에 "JSON만 출력하세요"라고 부탁하는 것보다 형식 오류(= 재요청 = 지연)가 크게 줄어듭니다.
_ACTIONS = ["GUIDE", "ASK_USER", "DONE", "UNKNOWN", "SCROLL", "WAIT"]
_DIRECTIONS = ["UP", "DOWN", "LEFT", "RIGHT"]
ACTION_SCHEMA = {
    "type": "object",
    "properties": {
        "action": {"type": "string", "enum": _ACTIONS},
        "targetId": {"type": "string", "description": "GUIDE일 때 [후보]의 id"},
        "guideText": {"type": "string", "description": "GUIDE일 때 버튼 글자"},
        "message": {"type": "string", "description": "ASK_USER · DONE · UNKNOWN · SCROLL · WAIT일 때 쉬운 존댓말 한 문장"},
        "direction": {"type": "string", "enum": _DIRECTIONS, "description": "SCROLL일 때만"},
    },
    "required": ["action"],
}


class LlmError(Exception):
    pass


class LlmTimeout(LlmError):
    """응답 시간 초과 → 504 llm_timeout"""


class LlmUpstream(LlmError):
    """인증·형식·5xx·네트워크 오류 → 502 llm_error"""

    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


class LlmRateLimited(LlmError):
    """LLM 쪽 사용량 제한 → 429 rate_limited"""

    def __init__(self, retry_after: int = 5):
        super().__init__("rate limited")
        self.retry_after = retry_after


class LlmClient(Protocol):
    async def complete(self, system: str, user: str, timeout_s: float) -> str: ...


class AnthropicLlmClient:
    def __init__(self, api_key: str, model: str, http: Optional[httpx.AsyncClient] = None, max_tokens: int = 150):
        if not api_key:
            raise ValueError("ANTHROPIC_API_KEY is required for the anthropic provider")
        self.api_key = api_key
        self.model = model
        self.max_tokens = max_tokens
        self._http = http or httpx.AsyncClient()

    async def _post(self, system: str, user: str, timeout_s: float) -> httpx.Response:
        return await self._http.post(
            ANTHROPIC_URL,
            headers={"x-api-key": self.api_key, "anthropic-version": "2023-06-01", "content-type": "application/json"},
            json={
                "model": self.model,
                "max_tokens": self.max_tokens,
                "temperature": 0,
                "system": system,
                "messages": [{"role": "user", "content": user}],
                "tools": [{"name": "decide", "description": "다음에 어르신이 할 행동 하나를 정한다.",
                           "input_schema": ACTION_SCHEMA}],
                "tool_choice": {"type": "tool", "name": "decide"},
            },
            timeout=timeout_s,
        )

    async def complete(self, system: str, user: str, timeout_s: float) -> str:
        try:
            r = await asyncio.wait_for(self._post(system, user, timeout_s), timeout=timeout_s)
        except (asyncio.TimeoutError, httpx.TimeoutException) as e:
            raise LlmTimeout("llm timeout") from e
        except httpx.HTTPError as e:
            raise LlmUpstream("network") from e
        if r.status_code == 429:
            try:
                ra = int(float(r.headers.get("retry-after", "5")))
            except ValueError:
                ra = 5
            raise LlmRateLimited(max(1, min(ra, 60)))
        if r.status_code in (401, 403):
            raise LlmUpstream("auth")
        if r.status_code >= 400:
            raise LlmUpstream(f"http_{r.status_code}")      # 본문은 남기지 않음
        try:
            data = r.json()
            blocks = data.get("content", [])
            for b in blocks:                                  # 강제한 도구 호출: 입력이 곧 JSON 객체
                if b.get("type") == "tool_use" and isinstance(b.get("input"), dict):
                    return json.dumps(b["input"], ensure_ascii=False)
            return "".join(b.get("text", "") for b in blocks if b.get("type") == "text")
        except ValueError as e:
            raise LlmUpstream("bad_body") from e

    async def aclose(self) -> None:
        await self._http.aclose()


def _gemini_schema() -> dict:
    """Gemini responseSchema는 OpenAPI 부분집합이라 대문자 type을 씁니다 (description은 그대로 허용)."""
    def conv(n):
        if isinstance(n, dict):
            return {k: (v.upper() if k == "type" and isinstance(v, str) else conv(v)) for k, v in n.items()}
        return n
    return conv(ACTION_SCHEMA)


class GeminiLlmClient:
    """Google Gemini (무료 한도가 있는 API 키로 쓸 수 있음). 키는 서버 환경변수 GEMINI_API_KEY 에만 둔다.
    JSON 응답을 강제(responseMimeType)하고, 오류는 AnthropicLlmClient 와 같은 예외로 바꾼다."""

    URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

    def __init__(self, api_key: str, model: str, http: Optional[httpx.AsyncClient] = None, max_tokens: int = 256):
        if not api_key:
            raise ValueError("GEMINI_API_KEY is required for the gemini provider")
        self.api_key = api_key
        self.model = model
        self.max_tokens = max_tokens
        self._http = http or httpx.AsyncClient()

    async def _post(self, system: str, user: str, timeout_s: float) -> httpx.Response:
        return await self._http.post(
            self.URL.format(model=self.model),
            headers={"x-goog-api-key": self.api_key, "content-type": "application/json"},
            json={
                "systemInstruction": {"parts": [{"text": system}]},
                "contents": [{"role": "user", "parts": [{"text": user}]}],
                "generationConfig": {"temperature": 0, "maxOutputTokens": self.max_tokens, "responseMimeType": "application/json",
                                     "responseSchema": _gemini_schema()},
            },
            timeout=timeout_s,
        )

    async def complete(self, system: str, user: str, timeout_s: float) -> str:
        try:
            r = await asyncio.wait_for(self._post(system, user, timeout_s), timeout=timeout_s)
        except (asyncio.TimeoutError, httpx.TimeoutException) as e:
            raise LlmTimeout("llm timeout") from e
        except httpx.HTTPError as e:
            raise LlmUpstream("network") from e
        if r.status_code == 429:
            try:
                ra = int(float(r.headers.get("retry-after", "5")))
            except ValueError:
                ra = 5
            raise LlmRateLimited(max(1, min(ra, 60)))
        if r.status_code in (401, 403):
            raise LlmUpstream("auth")
        if r.status_code >= 400:
            raise LlmUpstream(f"http_{r.status_code}")          # 본문은 남기지 않음
        try:
            cands = r.json().get("candidates") or []
            parts = (cands[0].get("content") or {}).get("parts") or [] if cands else []
            return "".join(p.get("text", "") for p in parts if isinstance(p, dict))
        except (ValueError, AttributeError, IndexError) as e:
            raise LlmUpstream("bad_body") from e

    async def aclose(self) -> None:
        await self._http.aclose()


# ---------------- Fake들 ----------------
Step = Union[str, Exception, Callable[[str, str], str]]


class ScriptedFakeLlmClient:
    """responses를 차례로 소비. 문자열은 그대로 반환, Exception은 던짐, 함수는 (system,user)로 호출.
    다 쓰면 마지막 응답을 반복합니다. calls에 호출 횟수·입력이 남습니다."""

    def __init__(self, responses: List[Step]):
        self.responses = list(responses)
        self.calls: List[dict] = []

    async def complete(self, system: str, user: str, timeout_s: float) -> str:
        self.calls.append({"system": system, "user": user, "timeout_s": timeout_s})
        step = self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        if isinstance(step, Exception):
            raise step
        if callable(step):
            return step(system, user)
        return step


class HeuristicFakeLlmClient:
    """프롬프트를 읽어 글자 유사도로 고르는 가짜 LLM. 진짜 LLM 성능을 대신하지 못합니다(파이프라인 연습용)."""

    UNKNOWN_BELOW = 0.30

    async def complete(self, system: str, user: str, timeout_s: float) -> str:
        goal, cands, in_cands = "", [], False
        for line in user.splitlines():
            if line.startswith("목표(어르신 말):"):
                goal = line.split(":", 1)[1].strip()
            elif line.strip() == "[후보]":
                in_cands = True
            elif in_cands and "|" in line:
                cid, text = line.split("|", 1)
                cands.append((cid, text))
        if goal == "(없음)":
            goal = ""
        if not goal:
            return '{"action":"ASK_USER","targetId":null,"guideText":null,"message":"무엇을 드시고 싶으세요?"}'
        q = expand_query(goal)
        best = max(cands, key=lambda c: match_score(c[1], q), default=None)
        if best is None or match_score(best[1], q) < self.UNKNOWN_BELOW:
            return '{"action":"UNKNOWN","targetId":null,"guideText":null,"message":"버튼을 찾지 못했어요. 화면을 위로 밀어 찾아보세요."}'
        return '{"action":"GUIDE","targetId":"%s","guideText":"%s","message":null}' % (best[0], best[1].replace('"', ""))
