"""환경변수 설정. 비밀 값(LLM 키, 앱 키)은 코드에 쓰지 않고 환경변수로만 받습니다."""
from __future__ import annotations

import os
from dataclasses import dataclass, field

VERSION = "0.2.0"   # 0.2.0: API 계약 v1 (docs/contract.md)


def _env_bool(name: str, default: bool) -> bool:
    v = os.environ.get(name)
    if v is None:
        return default
    return v.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    app_key: str = ""                         # X-App-Key. 비어 있으면 모든 요청을 401로 거절 (안전 기본값)
    llm_provider: str = "anthropic"           # anthropic | gemini | fake
    anthropic_api_key: str = field(default="", repr=False)
    gemini_api_key: str = field(default="", repr=False)
    llm_model: str = "claude-haiku-5-5"       # p95 2초 예산에 맞춘 빠른 모델 (바꿀 때는 평가 스크립트로 비교)
    llm_timeout_s: float = 5.0                # LlmClient 타임아웃. 무료 Gemini는 가끔 4초를 넘어 4 → 5초 (앱 6초 안)
    total_budget_s: float = 5.5               # 요청 하나의 서버 처리 예산. 앱 타임아웃(6초) 안에 답이 도착하도록 LLM 재요청을 제한
    min_retry_s: float = 1.2                  # 남은 예산이 이보다 적으면 형식 오류가 나도 재요청하지 않음
    hedge_after_s: float = 1.6                # LLM이 이만큼 답이 없으면 같은 요청을 하나 더 보내 먼저 온 답을 쓴다 (0이면 끔)
    debug_prompts: bool = False               # 개발용: 요청 화면 · 프롬프트 · LLM 원답 · 결정을 서버 기록에 남김 (배포 금지)
    candidate_k: int = 8                      # 설계: 후보 K=8
    progress_hint: bool = True                # 진행 상황(지금까지 · 다음 단계) 요약을 LLM에 준다 (app/progress.py)
    expectation_check: bool = True            # 눌렀다던 메뉴가 새 화면에 안 보이면 LLM에 알린다 (app/expectation.py)
    cache_size: int = 256                     # 같은 요청 재처리 방지 (0이면 끔)
    cache_ttl_s: float = 30.0
    fastpath: bool = True                     # 확실한 경우 LLM을 건너뜀(로컬 규칙)
    fast_min_score: float = 0.85
    fast_margin: float = 0.20
    rate_limit_per_min: int = 60              # 키+IP 기준 분당 요청 수
    max_body_bytes: int = 64 * 1024           # 이미지는 받지 않으므로 작게 제한

    @staticmethod
    def from_env() -> "Settings":
        e = os.environ.get
        return Settings(
            app_key=e("APP_KEY", ""),
            llm_provider=e("LLM_PROVIDER", "anthropic").strip().lower(),
            anthropic_api_key=e("ANTHROPIC_API_KEY", ""),
            gemini_api_key=e("GEMINI_API_KEY", ""),
            llm_model=e("LLM_MODEL", "gemini-3.5-flash-lite" if e("LLM_PROVIDER", "anthropic").strip().lower() == "gemini" else "claude-haiku-5-5"),
            llm_timeout_s=float(e("LLM_TIMEOUT_S", "5.0")),
            total_budget_s=float(e("TOTAL_BUDGET_S", "5.5")),
            min_retry_s=float(e("MIN_RETRY_S", "1.2")),
            hedge_after_s=float(e("HEDGE_AFTER_S", "1.6")),
            debug_prompts=_env_bool("DEBUG_PROMPTS", False),
            candidate_k=int(e("CANDIDATE_K", "8")),
            progress_hint=_env_bool("PROGRESS_HINT", True),
            expectation_check=_env_bool("EXPECTATION_CHECK", True),
            cache_size=int(e("CACHE_SIZE", "256")),
            cache_ttl_s=float(e("CACHE_TTL_S", "30")),
            fastpath=_env_bool("FASTPATH", True),
            fast_min_score=float(e("FASTPATH_MIN_SCORE", "0.85")),
            fast_margin=float(e("FASTPATH_MARGIN", "0.20")),
            rate_limit_per_min=int(e("RATE_LIMIT_PER_MIN", "60")),
            max_body_bytes=int(e("MAX_BODY_BYTES", str(64 * 1024))),
        )
