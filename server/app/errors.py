"""오류 응답 형식. 클라이언트(P4 :agent)는 status + error.code로 사용자 안내를 고릅니다.

    {"error": {"code": "unauthorized", "message": "...", "requestId": "..."}}

status → code
  400 invalid_request   요청 형식 오류 (입력 내용은 오류 문구에 넣지 않음)
  401 unauthorized      X-App-Key 없음/틀림
  429 rate_limited      요청이 너무 잦음 (Retry-After 헤더)
  502 llm_error         LLM 쪽 오류 (인증·형식·5xx)
  504 llm_timeout       LLM 응답 시간 초과
  500 internal          서버 내부 오류
"""
from __future__ import annotations

from typing import Dict, Optional


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, headers: Optional[Dict[str, str]] = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.headers = headers or {}


def body(code: str, message: str, request_id: str = "") -> dict:
    return {"error": {"code": code, "message": message, "requestId": request_id}}
