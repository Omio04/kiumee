"""LlmClient 포트와 구현.

- AnthropicLlmClient : 실제 Claude 호출 (타임아웃 4초). 요청 내용은 로그·오류 문구에 넣지 않습니다.
- ScriptedFakeLlmClient : 테스트용. 미리 정해 둔 응답을 차례로 돌려줌 (형식 오류·시간 초과 흉내 포함).
- HeuristicFakeLlmClient : 키 없이 서버를 돌려 보거나 Android 쪽 연동을 연습할 때 쓰는 규칙 기반 가짜.
"""
from __future__ import annotations

import asyncio
import copy
import json
from typing import Callable, List, Optional, Protocol, Sequence, Union

import httpx

from .prompt import CHOSEN_PREFIX
from .ranker import expand_query, match_score, same_item

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
        "guideText": {"type": "string", "description": "GUIDE일 때 버튼 글자, SCROLL일 때 찾는 메뉴 이름 (문장 아님)"},
        "message": {"type": "string", "description": "ASK_USER · DONE · UNKNOWN · WAIT일 때 쉬운 존댓말 한 문장"},
        "direction": {"type": "string", "enum": _DIRECTIONS, "description": "SCROLL일 때만"},
    },
    "required": ["action"],
}


def action_schema(target_ids: Optional[Sequence[str]] = None) -> dict:
    """target_ids가 있으면 targetId를 그 목록 안에서만 고르게 묶는다 (enum).
    Gemini 3.5가 번호 칸에 문장이나 없는 번호("e3daed2f-…", "e3|ICE0")를 넣던 문제 (2026-11-12 시험)."""
    if not target_ids:
        return ACTION_SCHEMA
    s = copy.deepcopy(ACTION_SCHEMA)
    s["properties"]["targetId"]["enum"] = list(target_ids)
    return s


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
    """target_ids: 이번 요청의 후보 버튼 번호. 주면 응답의 targetId를 그 안에서만 고르게 형식을 강제한다."""
    async def complete(self, system: str, user: str, timeout_s: float,
                       target_ids: Optional[Sequence[str]] = None) -> str: ...


class AnthropicLlmClient:
    # max_tokens: Haiku 5.5는 같은 글을 예전보다 30%쯤 많은 토큰으로 센다 → 한국어 문장이 든 JSON이 잘리지 않게 넉넉히
    def __init__(self, api_key: str, model: str, http: Optional[httpx.AsyncClient] = None, max_tokens: int = 300):
        if not api_key:
            raise ValueError("ANTHROPIC_API_KEY is required for the anthropic provider")
        self.api_key = api_key
        self.model = model
        self.max_tokens = max_tokens
        self._http = http or httpx.AsyncClient()

    async def _post(self, system: str, user: str, timeout_s: float,
                    target_ids: Optional[Sequence[str]] = None) -> httpx.Response:
        return await self._http.post(
            ANTHROPIC_URL,
            headers={"x-api-key": self.api_key, "anthropic-version": "2023-06-01", "content-type": "application/json"},
            json={
                "model": self.model,
                "max_tokens": self.max_tokens,
                # temperature는 보내지 않는다: 기본 모델 claude-haiku-5-5는 1이 아닌 값(0 포함)을 400으로 거절한다.
                # 출력 모양은 아래 도구 강제(decide · enum 스키마)가 잡는다.
                "system": system,
                "messages": [{"role": "user", "content": user}],
                "tools": [{"name": "decide", "description": "다음에 어르신이 할 행동 하나를 정한다.",
                           "input_schema": action_schema(target_ids)}],
                "tool_choice": {"type": "tool", "name": "decide"},
            },
            timeout=timeout_s,
        )

    async def complete(self, system: str, user: str, timeout_s: float,
                       target_ids: Optional[Sequence[str]] = None) -> str:
        try:
            r = await asyncio.wait_for(self._post(system, user, timeout_s, target_ids), timeout=timeout_s)
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


def _gemini_schema(target_ids: Optional[Sequence[str]] = None) -> dict:
    """Gemini responseSchema는 OpenAPI 부분집합이라 대문자 type을 씁니다 (description · enum은 그대로 허용)."""
    def conv(n):
        if isinstance(n, dict):
            return {k: (v.upper() if k == "type" and isinstance(v, str) else conv(v)) for k, v in n.items()}
        return n
    return conv(action_schema(target_ids))


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

    async def _post(self, system: str, user: str, timeout_s: float,
                    target_ids: Optional[Sequence[str]] = None) -> httpx.Response:
        return await self._http.post(
            self.URL.format(model=self.model),
            headers={"x-goog-api-key": self.api_key, "content-type": "application/json"},
            json={
                "systemInstruction": {"parts": [{"text": system}]},
                "contents": [{"role": "user", "parts": [{"text": user}]}],
                # temperature는 보내지 않는다 (기본값 1.0): Gemini 3 계열은 낮추면 출력이 망가질 수 있다 —
                # 0으로 보냈을 때 번호 칸에 문장이 섞여 나왔다 (2026-11-12 시험). 모양은 responseSchema가 잡는다.
                "generationConfig": {"maxOutputTokens": self.max_tokens, "responseMimeType": "application/json",
                                     "responseSchema": _gemini_schema(target_ids)},
            },
            timeout=timeout_s,
        )

    async def complete(self, system: str, user: str, timeout_s: float,
                       target_ids: Optional[Sequence[str]] = None) -> str:
        try:
            r = await asyncio.wait_for(self._post(system, user, timeout_s, target_ids), timeout=timeout_s)
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

    async def complete(self, system: str, user: str, timeout_s: float,
                       target_ids: Optional[Sequence[str]] = None) -> str:
        self.calls.append({"system": system, "user": user, "timeout_s": timeout_s, "target_ids": target_ids})
        step = self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        if isinstance(step, Exception):
            raise step
        if callable(step):
            return step(system, user)
        return step


class HeuristicFakeLlmClient:
    """프롬프트를 읽어 글자 유사도로 고르는 가짜 LLM. 진짜 LLM 성능을 대신하지 못합니다(파이프라인 연습용)."""

    UNKNOWN_BELOW = 0.30

    async def complete(self, system: str, user: str, timeout_s: float,
                       target_ids: Optional[Sequence[str]] = None) -> str:
        goal, cands, in_cands, chosen = "", [], False, []
        for line in user.splitlines():
            if line.startswith("목표(어르신 말):"):
                goal = line.split(":", 1)[1].strip()
            elif line.startswith(CHOSEN_PREFIX):
                chosen = [c.strip() for c in line[len(CHOSEN_PREFIX):].split(" (", 1)[0].split(",") if c.strip()]
            elif line.strip() == "[후보]":
                in_cands = True
            elif in_cands and "|" in line:
                cid, text = line.split("|", 1)
                cands.append((cid, text))
        if goal == "(없음)":
            goal = ""
        if chosen:                                           # 이미 고른 메뉴는 다시 고르지 않는다 (화면이 바뀜)
            cands = [c for c in cands if not any(same_item(c[1], p) for p in chosen)]
        if not goal:
            return '{"action":"ASK_USER","targetId":null,"guideText":null,"message":"무엇을 드시고 싶으세요?"}'
        q = expand_query(goal, goal.rsplit(",", 1)[-1])     # 가짜라 이번 발화를 따로 모름 → 목표의 마지막 쉼표 뒤를 이번 발화로 봄
        best = max(cands, key=lambda c: match_score(c[1], q), default=None)
        if best is None or match_score(best[1], q) < self.UNKNOWN_BELOW:
            return '{"action":"UNKNOWN","targetId":null,"guideText":null,"message":"버튼을 찾지 못했어요. 화면을 위로 밀어 찾아보세요."}'
        return '{"action":"GUIDE","targetId":"%s","guideText":"%s","message":null}' % (best[0], best[1].replace('"', ""))
