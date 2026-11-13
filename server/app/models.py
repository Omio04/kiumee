"""API 계약 v1 (요청·응답 모델).

정본: 저장소 루트의 docs/contract.md. 앱의 :core-model과 필드 이름이 1:1로 같습니다.
예시 JSON(testdata/contract/*.json)이 이 모델을 통과하는지 tests/test_contract.py가 확인합니다.

- 앱 계약: GuideRequest · GuideResponse · GuideAction(type = TAP | SCROLL | WAIT | ASK_USER | DONE | UNKNOWN)
- 내부용 : LlmAction (LLM이 내는 JSON, action = GUIDE | ASK_USER | DONE | UNKNOWN). 앱에는 나가지 않고
           ActionValidator가 앱 계약으로 바꿉니다.
"""
from __future__ import annotations

from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field, field_validator, model_serializer, model_validator

MAX_ELEMENTS = 300
MAX_HISTORY = 5          # 서버가 참고하는 최근 이력 개수 (앱도 최근 5개만 보냄)
MAX_TEXT = 80            # 요소 글자 · 응답 문장 최대 길이
MAX_GOAL = 200           # utterance · goal 최대 길이


class NormRect(BaseModel):
    """회전 보정된 분석 프레임 기준 정규화 좌표 (0.0~1.0)."""
    left: float = Field(ge=0.0, le=1.0)
    top: float = Field(ge=0.0, le=1.0)
    right: float = Field(ge=0.0, le=1.0)
    bottom: float = Field(ge=0.0, le=1.0)

    @model_validator(mode="after")
    def _ordered(self) -> "NormRect":
        if self.right <= self.left or self.bottom <= self.top:
            raise ValueError("rect must have positive area")
        return self


class ElementKind(str, Enum):
    BUTTON_LIKE = "BUTTON_LIKE"    # 누를 수 있어 보이는 글자 (후보가 될 수 있음)
    PRICE = "PRICE"                # 가격 글자 (후보 제외)
    TEXT = "TEXT"                  # 그 밖의 글자 (화면 문구로만 사용)


class ScreenElement(BaseModel):
    """앱의 UiElement."""
    id: str = Field(min_length=1, max_length=64)       # 요소 ID (설계서 4.3, 클라이언트가 부여한 값을 그대로 돌려줌)
    text: str = Field(min_length=1, max_length=MAX_TEXT)
    kind: ElementKind
    rect: NormRect
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)


class ScreenSnapshot(BaseModel):
    screenId: str = Field(min_length=1, max_length=64)
    frameId: int = Field(ge=0)
    timestampMs: int = Field(ge=0)
    stableFrames: int = Field(ge=0)
    elements: List[ScreenElement] = Field(max_length=MAX_ELEMENTS)

    @field_validator("elements")
    @classmethod
    def _unique_ids(cls, v: List[ScreenElement]) -> List[ScreenElement]:
        ids = [e.id for e in v]
        if len(ids) != len(set(ids)):
            raise ValueError("element ids must be unique")
        return v


class ActionType(str, Enum):
    TAP = "TAP"              # targetId 버튼을 누르게 안내
    SCROLL = "SCROLL"        # 화면을 밀어 찾게 안내 (계약에만 있음, 아직 안 보냄)
    WAIT = "WAIT"            # 화면 로딩 대기 (계약에만 있음, 아직 안 보냄)
    ASK_USER = "ASK_USER"    # 되묻기 → 앱은 LISTENING으로
    DONE = "DONE"            # 안내 종료
    UNKNOWN = "UNKNOWN"      # 맞는 버튼을 못 찾음


class ScrollDir(str, Enum):
    UP = "UP"
    DOWN = "DOWN"
    LEFT = "LEFT"
    RIGHT = "RIGHT"


class HistoryItem(BaseModel):
    """앱이 실행한 안내 한 건."""
    screenId: str = Field(min_length=1, max_length=64)
    action: ActionType
    targetText: Optional[str] = Field(default=None, max_length=MAX_GOAL)   # TAP 등: guideText / ASK_USER: question / UNKNOWN: null


class GuideRequest(BaseModel):
    """POST /v1/guide/next 요청. 사진은 선택(image): 앱이 켠 경우에만 오며, 서버는 저장하지 않고 LLM에만 보여 줍니다."""
    sessionId: str = Field(min_length=1, max_length=64)
    utterance: Optional[str] = Field(default=None, max_length=MAX_GOAL)   # 이번에 새로 들은 말. 화면만 바뀐 경우 null
    goal: Optional[str] = Field(default=None, max_length=MAX_GOAL)        # 직전 응답의 goal을 앱이 그대로 돌려줌. 첫 요청은 null
    snapshot: ScreenSnapshot
    history: List[HistoryItem] = Field(default_factory=list, max_length=20)   # 앱은 5개 보냄. 서버는 마지막 5개만 사용

    # 지금 카메라 화면 사진 (JPEG을 base64로). 글자 읽기(OCR)가 틀리거나 빠뜨려도 AI가 실제 화면을 보고 답하게 한다 (2026-11-13 실기기).
    # 저장 · 로그 금지. 작게 줄여 보낸다(긴 변 약 960px) → 상한 700K자.
    image: Optional[str] = Field(default=None, max_length=700_000, pattern=r"^[A-Za-z0-9+/=\s]*$")

    model_config = {"extra": "forbid"}   # 모르는 필드가 오면 400

    @model_serializer(mode="wrap")
    def _drop_empty_image(self, handler):                                  # 사진이 없으면 필드 자체를 내보내지 않는다 (계약 예시와 같은 모양)
        d = handler(self)
        if d.get("image") is None:
            d.pop("image", None)
        return d


# type별로 반드시 있어야 하는 필드. 나머지 필드는 없어야 합니다.
_REQUIRED = {
    ActionType.TAP: ("targetId", "guideText"),
    ActionType.SCROLL: ("direction", "guideText"),
    ActionType.WAIT: ("guideText",),
    ActionType.ASK_USER: ("question",),
    ActionType.DONE: ("guideText",),
    ActionType.UNKNOWN: ("reason",),
}
_ACTION_FIELDS = ("targetId", "guideText", "direction", "question", "reason")


class GuideAction(BaseModel):
    """앱의 GuideAction. JSON 예: {"type": "TAP", "targetId": "e4", "guideText": "아메리카노"}"""
    type: ActionType
    targetIds: Optional[List[str]] = Field(default=None, max_length=12)
    targetId: Optional[str] = Field(default=None, max_length=64)
    guideText: Optional[str] = Field(default=None, max_length=MAX_TEXT)
    direction: Optional[ScrollDir] = None
    question: Optional[str] = Field(default=None, max_length=MAX_TEXT)
    reason: Optional[str] = Field(default=None, max_length=MAX_TEXT)

    model_config = {"extra": "forbid"}

    @model_validator(mode="after")
    def _fields_match_type(self) -> "GuideAction":
        if self.targetIds is not None and (
            self.type != ActionType.WAIT or any(not 1 <= len(target) <= 64 for target in self.targetIds)
        ):
            raise ValueError("targetIds are only supported by WAIT")
        need = _REQUIRED[self.type]
        for name in _ACTION_FIELDS:
            v = getattr(self, name)
            if name in need and (v is None or (isinstance(v, str) and not v.strip())):
                raise ValueError(f"{self.type.value} needs {name}")
            if name not in need and v is not None:
                raise ValueError(f"{self.type.value} must not carry {name}")
        return self

    @classmethod
    def tap(cls, target_id: str, guide_text: str) -> "GuideAction":
        return cls(type=ActionType.TAP, targetId=target_id, guideText=guide_text)

    @classmethod
    def ask_user(cls, question: str) -> "GuideAction":
        return cls(type=ActionType.ASK_USER, question=question)

    @classmethod
    def done(cls, guide_text: str) -> "GuideAction":
        return cls(type=ActionType.DONE, guideText=guide_text)

    @classmethod
    def unknown(cls, reason: str) -> "GuideAction":
        return cls(type=ActionType.UNKNOWN, reason=reason)


class Box(BaseModel):
    """사진 안의 위치 (0~1, 왼쪽 위가 원점). 글자 읽기(OCR)에 없는 칸을 AI가 사진에서 직접 가리킬 때만 쓴다."""
    left: float = Field(ge=0, le=1)
    top: float = Field(ge=0, le=1)
    right: float = Field(ge=0, le=1)
    bottom: float = Field(ge=0, le=1)


class GuideResponse(BaseModel):
    goal: str = Field(default="", max_length=MAX_GOAL)   # 앱은 다음 요청의 goal에 그대로 넣음
    action: GuideAction
    say: Optional[str] = Field(default=None, max_length=MAX_TEXT)   # 도움 모드: 버튼을 가리킬 때 앞에 읽어 줄 이유 · 해결 방법 한 문장 (없으면 생략, 모르는 앱은 무시)
    seek: Optional[str] = Field(default=None, max_length=30)   # 도움 모드: 누를 칸 이름을 AI는 아는데 글자 읽기에 아직 없을 때. 앱이 이름을 기억했다가 읽히는 순간 노란 칸을 친다 (없으면 생략)
    box: Optional[Box] = None        # TAP인데 그 칸이 글자 목록에 없고 사진에서 찾은 경우의 위치 (targetId는 "photo"). 없으면 생략
    source: Optional[str] = None     # 진단용 (llm | fastpath | rule | fake | fallback). 내용이 아님. 앱은 무시


# ---------------- 내부용: LLM 출력 ----------------
class LlmKind(str, Enum):
    """LLM이 고를 수 있는 행동. 앱 계약의 ActionType과 다릅니다 (GUIDE → TAP으로 바뀌어 나감)."""
    GUIDE = "GUIDE"
    ASK_USER = "ASK_USER"
    DONE = "DONE"
    UNKNOWN = "UNKNOWN"
    SCROLL = "SCROLL"          # 찾는 버튼이 후보에 없지만 화면을 밀면 있을 것 같을 때
    WAIT = "WAIT"              # 화면이 아직 바뀌는 중이거나 불러오는 중일 때
    EXPLAIN = "EXPLAIN"        # 어르신이 메뉴 · 옵션이 무엇인지 물었을 때 (앱에는 ASK_USER로 나감, 목표는 그대로)


class LlmAction(BaseModel):
    """LLM이 돌려주는 JSON (검증 전)."""
    action: LlmKind
    targetId: Optional[str] = None
    guideText: Optional[str] = None
    message: Optional[str] = None
    direction: Optional[str] = None
    box: Optional[List[float]] = None      # 도움 모드: 사진에서 누를 칸 [위, 왼쪽, 아래, 오른쪽] 0~1000 (후보에 없을 때만)
    boxText: Optional[str] = None          # 그 칸에 적힌 글자

    @field_validator("box", mode="before")
    @classmethod
    def _box_like(cls, v):
        # 모델이 "850" · 소수 · 숫자가 아닌 값을 주어도 box 하나 때문에 답 전체를 버리지 않는다 (box만 없는 것으로)
        if v is None or not isinstance(v, (list, tuple)):
            return None
        try:
            return [float(x) for x in v][:4] if len(v) == 4 else None
        except (TypeError, ValueError):
            return None
    screenAsks: Optional[str] = None       # 진단용: 모델이 적은 "이 화면이 묻는 것" (앱에는 나가지 않음)

    @field_validator("targetId", "guideText", "message", "direction", mode="before")
    @classmethod
    def _null_like(cls, v):
        if isinstance(v, str) and v.strip().lower() in ("", "null", "none"):
            return None
        return v
