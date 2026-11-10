"""FastAPI 앱. POST /v1/guide/next 하나와 /healthz.

로그에는 내용(발화·화면 글자)을 절대 남기지 않고, 개수·시간·경로만 남깁니다.
"""
from __future__ import annotations

import hmac
import json
import logging
import time
import uuid
from typing import Optional

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .agent import FakeGuideAgent, GuideAgent, LlmGuideAgent
from .errors import ApiError, body
from .llm import (AnthropicLlmClient, GeminiLlmClient, HeuristicFakeLlmClient, LlmRateLimited, LlmTimeout, LlmUpstream)
from .models import GuideRequest
from .ratelimit import RateLimiter
from .settings import VERSION, Settings

log = logging.getLogger("kl")
if not log.handlers and not logging.getLogger().handlers:      # uvicorn은 앱 로거를 켜 주지 않으므로 직접 출력
    _h = logging.StreamHandler()
    _h.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    log.addHandler(_h)
    log.setLevel(logging.INFO)
    log.propagate = False


def build_agent(s: Settings) -> GuideAgent:
    if s.llm_provider == "fake":
        return LlmGuideAgent(HeuristicFakeLlmClient(), s)
    if s.llm_provider == "gemini":
        return LlmGuideAgent(GeminiLlmClient(s.gemini_api_key, s.llm_model), s)
    return LlmGuideAgent(AnthropicLlmClient(s.anthropic_api_key, s.llm_model), s)


def create_app(settings: Optional[Settings] = None, agent: Optional[GuideAgent] = None) -> FastAPI:
    s = settings or Settings.from_env()
    ag = agent or build_agent(s)
    limiter = RateLimiter(s.rate_limit_per_min)
    app = FastAPI(title="kiosk-lens-agent", version=VERSION, docs_url=None, redoc_url=None, openapi_url=None)

    def err(status: int, code: str, message: str, rid: str, headers: Optional[dict] = None) -> JSONResponse:
        h = {"X-Request-Id": rid}
        h.update(headers or {})
        return JSONResponse(body(code, message, rid), status_code=status, headers=h)

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError):
        # 입력 값을 응답에 되돌려 주지 않습니다(어르신 발화·화면 글자 보호). 위치만 알려 줍니다.
        rid = getattr(request.state, "rid", uuid.uuid4().hex[:12])
        locs = sorted({".".join(str(p) for p in e.get("loc", ())[1:4]) for e in exc.errors()})[:5]
        return err(400, "invalid_request", "요청 형식이 올바르지 않습니다: " + ", ".join(l for l in locs if l), rid)

    @app.get("/healthz")
    async def healthz():
        return {"ok": True, "version": VERSION}

    @app.post("/v1/guide/next")
    async def guide_next(request: Request):
        t0 = time.perf_counter()
        rid = uuid.uuid4().hex[:12]
        request.state.rid = rid
        try:
            # 인증
            key = request.headers.get("x-app-key", "")
            if not s.app_key or not hmac.compare_digest(key.encode(), s.app_key.encode()):
                raise ApiError(401, "unauthorized", "인증에 실패했습니다.")
            # 속도 제한 (앱 키 단위)
            ok, wait = limiter.allow(key)
            if not ok:
                raise ApiError(429, "rate_limited", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                               {"Retry-After": str(max(1, int(wait + 0.999)))})
            # 본문 크기
            cl = request.headers.get("content-length")
            if cl and cl.isdigit() and int(cl) > s.max_body_bytes:
                raise ApiError(400, "invalid_request", "요청이 너무 큽니다.")
            raw = await request.body()
            if len(raw) > s.max_body_bytes:
                raise ApiError(400, "invalid_request", "요청이 너무 큽니다.")
            try:
                data = json.loads(raw)
            except ValueError:
                raise ApiError(400, "invalid_request", "JSON 형식이 아닙니다.")
            try:
                req = GuideRequest.model_validate(data)
            except Exception as e:  # pydantic ValidationError — 값은 되돌려주지 않음
                locs = sorted({".".join(str(p) for p in x.get("loc", ())[:3]) for x in getattr(e, "errors", lambda: [])()})[:5]
                raise ApiError(400, "invalid_request", "요청 형식이 올바르지 않습니다: " + ", ".join(locs))
            # 판단
            try:
                result = await ag.next(req)
            except LlmTimeout:
                raise ApiError(504, "llm_timeout", "AI 응답이 늦어지고 있어요.")
            except LlmRateLimited as e:
                raise ApiError(429, "rate_limited", "AI 사용량이 많습니다. 잠시 후 다시 시도해 주세요.",
                               {"Retry-After": str(e.retry_after)})
            except LlmUpstream:
                raise ApiError(502, "llm_error", "AI 서버에 문제가 있어요.")
            ms = (time.perf_counter() - t0) * 1000
            m = result.meta
            log.info("KL_PERF rid=%s status=200 action=%s source=%s cand=%s llm=%s retry=%s rej=%s drop=%s ms=%.0f",
                     rid, result.response.action.type.value, m.get("source"), m.get("candidates"), m.get("llm_calls"),
                     m.get("retry"), m.get("rejected"), m.get("dropped"), ms)
            return JSONResponse(result.response.model_dump(mode="json", exclude_none=True),
                                headers={"X-Request-Id": rid})
        except ApiError as e:
            log.info("KL_PERF rid=%s status=%s code=%s ms=%.0f", rid, e.status, e.code, (time.perf_counter() - t0) * 1000)
            return err(e.status, e.code, e.message, rid, e.headers)
        except Exception:
            log.exception("KL_ERR rid=%s", rid)          # 예외 종류·스택만, 요청 내용 없음
            return err(500, "internal", "서버 내부 오류입니다.", rid)

    return app


def _default_app():
    import os
    if os.environ.get("KL_SKIP_APP_INIT") == "1":
        return None
    return create_app()


app = _default_app()
