"""환경변수 설정. 비밀 값(LLM 키, 앱 키)은 코드에 쓰지 않고 환경변수로만 받습니다."""
from __future__ import annotations

import os
from typing import Mapping
from dataclasses import dataclass, field

VERSION = "0.2.0"   # 0.2.0: API 계약 v1 (docs/contract.md)


def read_dotenv(path: "str | os.PathLike | None" = None) -> dict:
    """server/.env 의 KEY=VALUE 를 딕셔너리로 읽는다 (의존성 없이). 프로세스 환경변수(os.environ)는 건드리지 않는다.
    따옴표 · 주석(#) · 빈 줄을 처리한다. 파일이 없으면 빈 딕셔너리. 값은 로그에 남기지 않는다."""
    from pathlib import Path
    f = Path(path) if path else Path(__file__).resolve().parent.parent / ".env"
    try:
        lines = f.read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        return {}
    out: dict = {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        k, v = line.split("=", 1)
        k, v = k.strip(), v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
            v = v[1:-1]
        elif v.startswith("#"):
            v = ""                                          # KEY=   # 설명  → 값 없음 (.env.example 모양)
        elif " #" in v:
            v = v.split(" #", 1)[0].rstrip()
        if k:
            out[k] = v
    return out


def _env_bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    v = env.get(name)
    if v is None:
        return default
    return v.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    app_key: str = ""                         # X-App-Key. 비어 있으면 모든 요청을 401로 거절 (안전 기본값)
    llm_provider: str = "anthropic"           # anthropic | gemini | fake
    anthropic_api_key: str = field(default="", repr=False)
    gemini_api_key: str = field(default="", repr=False)
    fallback_model: str = "gemini-3.5-flash-lite"   # Claude 일시 오류 때 대신 답할 Gemini 모델 (GEMINI_API_KEY가 있을 때만)
    llm_model: str = "claude-haiku-5-5"       # p95 2초 예산에 맞춘 빠른 모델 (바꿀 때는 평가 스크립트로 비교)
    llm_timeout_s: float = 5.0                # LlmClient 타임아웃. 무료 Gemini는 가끔 4초를 넘어 4 → 5초 (앱 6초 안)
    total_budget_s: float = 5.5               # 요청 하나의 서버 처리 예산. 앱 타임아웃(6초) 안에 답이 도착하도록 LLM 재요청을 제한
    min_retry_s: float = 1.2                  # 남은 예산이 이보다 적으면 형식 오류가 나도 재요청하지 않음
    hedge_after_s: float = 1.6                # LLM이 이만큼 답이 없으면 같은 요청을 하나 더 보내 먼저 온 답을 쓴다 (0이면 끔)
    debug_prompts: bool = False               # 개발용: 요청 화면 · 프롬프트 · LLM 원답 · 결정을 서버 기록에 남김 (배포 금지)
    candidate_k: int = 8                      # 설계: 후보 K=8
    progress_hint: bool = True                # 진행 상황(지금까지 · 다음 단계) 요약을 LLM에 준다 (app/progress.py)
    expectation_check: bool = True            # 눌렀다던 메뉴가 새 화면에 안 보이면 LLM에 알린다 (app/expectation.py)
    som_marks: bool = False                   # 도움 모드: 사진 위 후보 칸마다 번호를 그려 AI가 번호를 고르게 한다 (app/som.py)
    grid_locate: bool = True                  # 도움 모드: 칠 칸이 글자 목록에 없으면 격자 사진으로 위치를 한 번 더 묻는다 (app/som.py draw_grid)
    grid_zoom: bool = True                    # 격자로 대략 찾은 뒤 그 주변을 확대해 한 번 더 고른다
    lexicon_fix: bool = False                 # 도움 모드: 키오스크 공통 낱말로 OCR 오타를 바로잡아 AI에게 보인다 (app/lexicon.py)
    help_mode: bool = False                   # 도움 모드: 어르신이 물어보면 그 순간의 화면을 보고 답한다 (app/help_mode.py)
    cache_size: int = 256                     # 같은 요청 재처리 방지 (0이면 끔)
    cache_ttl_s: float = 30.0
    fastpath: bool = True                     # 확실한 경우 LLM을 건너뜀(로컬 규칙)
    fast_min_score: float = 0.85
    fast_margin: float = 0.20
    rate_limit_per_min: int = 60              # 키+IP 기준 분당 요청 수
    max_body_bytes: int = 1024 * 1024         # 사진(선택, 약 100~200KB)이 붙을 수 있어 1MB. 사진 상한은 models.GuideRequest.image

    @staticmethod
    def from_env(env: "Mapping[str, str] | None" = None, dotenv_path: "str | os.PathLike | None" = None) -> "Settings":
        """env 를 주면 그것만 쓴다(시험용). 안 주면 server/.env 위에 프로세스 환경변수를 덮어 쓴 값을 쓴다 → 명령줄 값이 .env 보다 우선.
        어느 쪽도 os.environ 을 바꾸지 않는다."""
        env = dict(env) if env is not None else {**read_dotenv(dotenv_path), **os.environ}
        e = env.get
        return Settings(
            app_key=e("APP_KEY", ""),
            llm_provider=e("LLM_PROVIDER", "anthropic").strip().lower(),
            anthropic_api_key=e("ANTHROPIC_API_KEY", ""),
            gemini_api_key=e("GEMINI_API_KEY", ""),
            fallback_model=e("FALLBACK_MODEL", "gemini-3.5-flash-lite"),
            llm_model=e("LLM_MODEL", "gemini-3.5-flash-lite" if e("LLM_PROVIDER", "anthropic").strip().lower() == "gemini" else "claude-haiku-5-5"),
            llm_timeout_s=float(e("LLM_TIMEOUT_S", "5.0")),
            total_budget_s=float(e("TOTAL_BUDGET_S", "5.5")),
            min_retry_s=float(e("MIN_RETRY_S", "1.2")),
            hedge_after_s=float(e("HEDGE_AFTER_S", "1.6")),
            debug_prompts=_env_bool(env, "DEBUG_PROMPTS", False),
            candidate_k=int(e("CANDIDATE_K", "8")),
            progress_hint=_env_bool(env, "PROGRESS_HINT", True),
            expectation_check=_env_bool(env, "EXPECTATION_CHECK", True),
            help_mode=_env_bool(env, "HELP_MODE", False),
            som_marks=_env_bool(env, "SOM_MARKS", False),
            lexicon_fix=_env_bool(env, "LEXICON_FIX", False),
            grid_locate=_env_bool(env, "GRID_LOCATE", True),
            grid_zoom=_env_bool(env, "GRID_ZOOM", True),
            cache_size=int(e("CACHE_SIZE", "256")),
            cache_ttl_s=float(e("CACHE_TTL_S", "30")),
            fastpath=_env_bool(env, "FASTPATH", True),
            fast_min_score=float(e("FASTPATH_MIN_SCORE", "0.85")),
            fast_margin=float(e("FASTPATH_MARGIN", "0.20")),
            rate_limit_per_min=int(e("RATE_LIMIT_PER_MIN", "60")),
            max_body_bytes=int(e("MAX_BODY_BYTES", str(1024 * 1024))),
        )
