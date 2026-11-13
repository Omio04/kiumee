# 계약 v1 — 모듈 · 서버가 주고받는 규격

> **상태:** 확정안 (P1, 2026-11-09). 전원 승인 PR로 확정한다.
> **이 문서가 정본이다.** 설계서 v0.1과 다르면 이 문서를 따른다 (달라진 점은 [5장](#5-설계서-v01과-달라진-점)).
>
> 코드 위치: 앱 `core-model/` · 서버 `server/app/models.py` · 예시 JSON `testdata/contract/`

---

## 0. 한눈에 보기

```
 카메라 ─frame─▶ FrameSink ┐                    ┌─ SpeechInput ◀─ 마이크
 (:app/camera)   (:vision) │                    │   (:voice)
                           ▼                    ▼
                    SnapshotSource ──▶ :domain (SessionRunner) ──GuideRequest──▶ GuideAgent ──HTTPS──▶ 서버 ──▶ LLM
                       (:vision)        상태머신 · 위치 문구     ◀─GuideResponse──  (:agent)
                                              │
                              ┌───────────────┴───────────────┐
                              ▼                               ▼
                     HighlightSurface (:overlay)     SpeechOutput (:voice)
                     노란 테두리 하이라이트            "화면 오른쪽 아래 깜빡이는 ~를 누르세요"
```

꼭 지킬 원칙 세 가지

1. **좌표는 정규화 0.0~1.0** (회전 보정된 분석 프레임 기준, 원점 좌상단). 픽셀로 바꾸는 건 `:overlay`만 한다.
2. **서버에는 글자와 좌표를 보낸다. 사진은 선택(`image`)이며 앱 설정 `kl.sendImage`로 끌 수 있다.** (2026-11-13 변경: 글자 읽기만으로는 AI가 화면을 오해했다.) 사진은 긴 변 960px JPEG(base64, 최대 700K자)이고 서버는 저장하지 않으며 LLM에만 보여 준다. 카드번호 같은 민감한 글자가 읽힌 요청에서는 서버가 사진을 버린다. 서버는 그 밖의 모르는 최상위 필드가 오면 400. **팀 합의 필요: 원래 "이미지 금지" 원칙을 바꾸는 것이다.**
3. **포트와 이 문서의 JSON 형식은 전원 승인 PR로만 바꾼다** ([6장](#6-바꾸는-규칙)).

---

## 1. 데이터 모델 (`core-model/.../model/`)

| 클래스 | 뜻 | 만드는 곳 | 쓰는 곳 |
|---|---|---|---|
| `NormRect` | 정규화 사각형 (left, top, right, bottom). `centerX`, `isValid` 등 | `:vision` | `:domain`, `:overlay`, 서버 |
| `UiElement` | 화면 글자 하나 (id, text, rect, confidence, kind) | `:vision` | 전부 |
| `ElementKind` | `TEXT` · `PRICE` · `BUTTON_LIKE`. **서버는 `BUTTON_LIKE`만 후보로 고른다** | `:vision` | 서버 |
| `ScreenSnapshot` | 화면 한 장 (screenId, frameId, timestampMs, elements, stableFrames) | `:vision` | `:domain`, 서버 |
| `CameraFrame` | 카메라 프레임 (메타데이터 + `image`) | `:app/camera` | `:vision` |
| `GuideRequest` · `GuideResponse` | 서버 요청 · 응답 | `:domain` | `:agent` |
| `GuideAction` · `ActionType` | 다음 행동 6종 (TAP · SCROLL · WAIT · ASK_USER · DONE · UNKNOWN) | 서버 | `:domain` |
| `HistoryItem` | 앱이 실행한 안내 한 건. **`HistoryItem.of(screenId, action)`로만 만든다** | `:domain` | 서버 |
| `HighlightTarget` · `HighlightStyle` | 하이라이트 대상 (5일 계획에서는 `PULSE_BORDER`만) | `:domain` | `:overlay` |
| `SttEvent` · `SttError` | 음성 인식 결과 (Partial · Final · Error) | `:voice` | `:domain` |
| `FrameMotion` | 프레임 간 이동 · 확대 추정 (선택 기능) | `:vision` 또는 `:app` | `:overlay` |

**요소 ID 규칙 (설계서 4.3, `:vision` 소유):** `"e" + hash(정규화 텍스트 + 중심점을 20×20 격자로 양자화한 셀)`. 같은 화면에서 프레임이 바뀌어도 같은 버튼이면 같은 id. 충돌하면 `_2`를 붙인다.

**`CameraFrame.image`:** 실제 카메라면 `androidx.camera.core.ImageProxy`, 사진 입력 디버그 모드면 `android.graphics.Bitmap`. 쓰는 쪽이 `frame.image as? ImageProxy`처럼 꺼낸다. 구현체(`ImageProxyFrame`)는 `:app/camera`(P1)에 둔다.

---

## 2. 포트 9개 (`core-model/.../port/Ports.kt`)

포트 = 모듈끼리 끼우는 규격. **쓰는 쪽은 자기가 필요한 포트 하나만 주입받는다.**

| 포트 | 구현 (담당) | 쓰는 쪽 | 반드시 지킬 것 (설계서 16.7) |
|---|---|---|---|
| `FrameSink` | `:vision` (P2) | `:app/camera` | `submit()`은 즉시 복귀 · 예외를 던지지 않음 · 밀리면 오래된 프레임을 버리고 `close()` |
| `SnapshotSource` | `:vision` (P2) | `:domain` | `stableFrames >= 2`인 스냅샷만 발행 · 받는 쪽이 느려도 막히지 않음 (최신만 유지) · `screenChanges`는 새 screenId |
| `SpeechInput` | `:voice` (P3) | `:domain` | `Final` 또는 `Error` **하나**를 내고 플로우 종료 · 취소하면 마이크 해제 · 실패는 예외가 아니라 `Error` 이벤트 |
| `SpeechOutput` | `:voice` (P3) | `:domain` | `speak()`는 재생이 **끝날 때** 복귀 · 코루틴 취소 시 즉시 중단 · `interrupt=true`면 직전 안내 중단 |
| `GuideAgent` | `:agent` (P4) | `:domain` | 예외 대신 `Result.failure` · 성공이면 `Tap.targetId`는 요청 스냅샷에 있음 |
| `HighlightSurface` | `:overlay` (P5) | `:domain` | 어느 스레드에서 불러도 안전 · `null`이면 숨김 · 같은 값을 또 불러도 무해 |
| `MotionSink` | `:overlay` (P5) | `:vision` 또는 `:app` | (선택 기능) |
| `Clock` | `:app` | 전부 | 시간은 `System.currentTimeMillis()`를 직접 쓰지 말고 `Clock`으로 받는다 (타임아웃 테스트용) |
| `Logger` | `:app` | 전부 | 발화 · 화면 글자 **원문 로그 금지** · 성능 구간 로그는 tag `KL_PERF` |

**계약 테스트는 실구현으로 (Fake 없음):** 각 담당은 자기 포트의 **계약 테스트**를 만들고 **실구현**으로 통과시킨다. 설계서 16.7의 "Fake와 실구현이 같은 계약 테스트를 상속"은 2일차 결정으로 바꿨다 — Fake 구현과 mock 플레이버는 두지 않는다 ([decisions.md](decisions.md) 6장 U3). JVM에서 못 돌리는 실구현(STT/TTS · ML Kit · 카메라)은 기기 확인 항목을 각 모듈 README에 적는다. 테스트 파일 안에서만 쓰는 작은 대역(stub)은 괜찮다 (앱에 들어가지 않음).

---

## 3. 서버 API — `POST /v1/guide/next`

- 헤더: `X-App-Key: <앱 키>` · `Content-Type: application/json`
- 타임아웃: 앱 → 서버 **6초**, 실패하면 1회 재시도 후 ERROR 안내 · 서버 → LLM 4초
- 서버는 상태를 저장하지 않는다. 필요한 건 매번 요청에 담아 보낸다 (goal · history)
- 필드 이름은 `:core-model`과 똑같다 (camelCase)

### 3.1 요청

```json
{
  "sessionId": "8f14e45f-ceea-467f-a8f2-3c1d2b9e7a10",
  "utterance": "따뜻한 아메리카노 한 잔 주세요",
  "goal": null,
  "snapshot": {
    "screenId": "s02_menu", "frameId": 1204, "timestampMs": 1762675200000, "stableFrames": 3,
    "elements": [
      {"id": "e4", "text": "아메리카노", "rect": {"left": 0.05, "top": 0.25, "right": 0.3, "bottom": 0.4},
       "confidence": 0.95, "kind": "BUTTON_LIKE"}
    ]
  },
  "history": []
}
```

| 필드 | 필수 | 뜻 |
|---|---|---|
| `sessionId` | 필수 | 세션 하나 = "말하기"부터 DONE/종료까지. 새 세션마다 새로 만든다 |
| `utterance` | 선택 (null 가능) | 이번에 **새로** 들은 말. 화면만 바뀐 재요청이면 null |
| `goal` | 선택 (null 가능) | 직전 응답의 `goal`을 그대로. 세션 첫 요청이면 null ([3.3](#33-goal-주고받기)) |
| `snapshot` | 필수 | 지금 화면. `ScreenSnapshot` 그대로 |
| `history` | 선택 (기본 `[]`) | 최근 5개, 오래된 것부터 ([3.4](#34-history)) |

전체 예시: [`request_first.json`](../testdata/contract/request_first.json) · [`request_after_screen_change.json`](../testdata/contract/request_after_screen_change.json) · [`request_answer.json`](../testdata/contract/request_answer.json)

### 3.2 응답 (200)

```json
{
  "goal": "따뜻한 아메리카노 한 잔 주세요",
  "action": {"type": "TAP", "targetId": "e4", "guideText": "아메리카노"},
  "source": "fastpath"
}
```

| `action.type` | 들어 있는 필드 | 앱이 할 일 |
|---|---|---|
| `TAP` | `targetId`, `guideText` | `targetId` 요소를 하이라이트하고 버튼 이름 또는 완성된 안내 문장을 읽음 → WAIT_CHANGE |
| `SCROLL` | `direction` (UP·DOWN·LEFT·RIGHT), `guideText` | "화면을 {방향} 밀어서 {guideText} 찾아 주세요" → WAIT_CHANGE. `guideText`는 **찾는 대상 이름** (짧은 말, 문장 아님) |
| `WAIT` | `guideText`, 선택 사항 `targetIds` (요청 스냅샷의 버튼 ID 최대 12개) | 문장을 읽고 해당 버튼을 강조한 뒤 화면 전환을 기다림 |
| `ASK_USER` | `question` | 그대로 읽고 → LISTENING. 대답은 다음 요청의 `utterance` |
| `DONE` | `guideText` | 그대로 읽고 → IDLE (세션 끝) |
| `UNKNOWN` | `reason` | 그대로 읽어도 되는 쉬운 문장 (60자 이내). → SCANNING |

- 해당 type에 없는 필드는 **아예 빠진다** (null로 오지 않음).
- 문장 필드(`guideText` · `question` · `reason`)에는 **위치 표현이 없다**. 위치 문구는 앱(`PositionPhraser`)이 만든다.
- TAP의 `guideText`는 항상 **화면의 실제 버튼 글자**다 (서버가 교정).
- SCROLL의 `guideText`는 **찾는 대상 이름**이다 (예: "딸기 스무디"). 서버는 문장이 오면 "원하시는 메뉴"로 바꾼다 — 앱 문장 안에 문장이 또 들어가지 않게 (2026-11-10).
- `source`는 진단용이다 (llm · fastpath · rule · fake · fallback). 앱은 무시한다. **모르는 필드는 무시하도록 파싱할 것.**

전체 예시: `testdata/contract/response_*.json` (type마다 하나씩)

### 3.3 goal 주고받기

서버는 기억하지 않는다. 그래서 **앱이 직전 응답의 `goal`을 들고 있다가 다음 요청에 그대로 넣는다.**

| 상황 | `utterance` | `goal` | 응답의 `goal` |
|---|---|---|---|
| 첫 요청 ("말하기" 후) | "따뜻한 아메리카노 한 잔 주세요" | null | "따뜻한 아메리카노 한 잔 주세요" |
| 화면만 바뀜 (재요청) | null | 직전 goal | 직전 goal 그대로 |
| 되물음(ASK_USER)에 대답 | "따뜻한 거요" | "커피 한 잔 주세요" | "커피 한 잔 주세요, 따뜻한 거요" |

- IDLE에서 "말하기"를 누르면 **새 세션**: 새 `sessionId` + `goal = null` + `history = []`.
- goal은 200자를 넘지 않게 서버가 앞부분을 자른다 (최근 말을 남김).

### 3.4 history

- 앱이 응답 하나를 실행할 때마다 `HistoryItem.of(screenId, action)`으로 하나 추가한다.
- 최근 5개(`GuideRequest.MAX_HISTORY`)만, 오래된 것부터 보낸다.
- `targetText`: TAP은 버튼 이름(일반 안내에서는 `guideText`), SCROLL · WAIT · DONE은 `guideText`, ASK_USER는 `question`, UNKNOWN은 null.

```json
"history": [
  {"screenId": "s02_menu", "action": "TAP", "targetText": "아메리카노"},
  {"screenId": "s03_options", "action": "ASK_USER", "targetText": "따뜻한 것과 차가운 것 중 어떤 걸로 드릴까요?"}
]
```

### 3.5 오류

본문: `{"error": {"code": "unauthorized", "message": "...", "requestId": "3f2a9c1b7d4e"}}` · 응답 헤더 `X-Request-Id`도 같은 값.
`message`는 **개발자용**이다. 어르신께 읽어 주지 않는다. 앱은 **status 코드**로 안내 문구를 고른다.

| status | code | 뜻 | 앱 처리 (제안) |
|---|---|---|---|
| 400 | `invalid_request` | 요청 형식 오류 (앱 버그) | 재시도 없이 ERROR |
| 401 | `unauthorized` | 앱 키 없음 · 틀림 | 재시도 없이 ERROR |
| 429 | `rate_limited` | 너무 잦음 (`Retry-After` 초) | 기다렸다가 1회 재시도 → ERROR |
| 502 | `llm_error` | LLM 쪽 오류 | 1회 재시도 → ERROR |
| 504 | `llm_timeout` | LLM 시간 초과 | 1회 재시도 → ERROR |
| 500 | `internal` | 서버 내부 오류 | 1회 재시도 → ERROR |

### 3.6 길이 제한 (넘으면 400)

| 항목 | 최대 | 앱에서 맞추는 곳 · 상수 |
|---|---|---|
| 요청 본문 | 64KB | — |
| `elements` 개수 | 300 | `:vision` · `ScreenSnapshot.MAX_ELEMENTS` |
| 요소 `id` | 64자 | `:vision` · `UiElement.MAX_ID_LENGTH` |
| 요소 `text` | 1~80자 | `:vision`(또는 `:agent`가 보내기 전에 자름) · `UiElement.MAX_TEXT_LENGTH` |
| `utterance` · `goal` | 200자 | `:agent` · `GuideRequest.MAX_UTTERANCE_LENGTH` · `MAX_GOAL_LENGTH` |
| `history` | 5개 (서버는 20개까지 받음) | `:domain` · `GuideRequest.MAX_HISTORY` |
| 요소 id 중복 | 금지 | `:vision` |
| `rect` | 0~1, right > left, bottom > top | `:vision` (`NormRect.isValid`) |
| 모르는 최상위 필드 | 금지 (`image`만 선택 필드로 허용: base64, 700K자 이하) | `:agent` |

### 3.7 `:agent` 직렬화 설정 (P4 참고)

```kotlin
val json = Json {
    classDiscriminator = "type"  // GuideAction을 "type"으로 구분 (@SerialName("TAP") 등)
    ignoreUnknownKeys = true     // source 등 모르는 필드는 무시
    explicitNulls = false        // null 필드는 보내지 않음 (서버는 둘 다 받음)
}
```

DTO는 `:agent/remote`에 두고 `:core-model`과 1:1로 변환한다. **`testdata/contract/*.json`을 읽어 왕복 테스트를 하면** 서버와 형식이 어긋나는 일을 막을 수 있다 (서버는 `server/tests/test_contract.py`가 같은 파일로 검사 중).

---

## 4. 한 세션의 흐름 (예시)

```
어르신: [말하기] "따뜻한 아메리카노 한 잔 주세요"
  → 요청 ① utterance="따뜻한 아메리카노 한 잔 주세요", goal=null, 메뉴 화면
  ← TAP e4 "아메리카노"   goal="따뜻한 아메리카노 한 잔 주세요"
  앱: "화면 왼쪽 위 깜빡이는 아메리카노를 누르세요" + 하이라이트 → WAIT_CHANGE
어르신이 누름 → 화면 전환 감지 → SCANNING
  → 요청 ② utterance=null, goal=(①의 goal), 옵션 화면, history=[TAP 아메리카노]
  ← TAP e2 "HOT"
  … 장바구니 → 결제 수단 선택 …
  ← DONE "결제 수단을 고르셨어요. 직원 또는 화면 안내를 따라주세요." → IDLE
```

---

## 5. 설계서 v0.1과 달라진 점

| 항목 | 설계서 v0.1 | 계약 v1 | 이유 |
|---|---|---|---|
| `CameraFrame` | `data class(imageProxy: ImageProxy, …)` | 인터페이스 + `image: Any` | `:core-model`은 순수 Kotlin이라 Android 타입 불가 |
| `SpeechOutput.isSpeaking` | 5장에 있음 | 없음 | 16.4(ISP 버전)를 따름. `speak()`가 끝까지 기다리므로 불필요 |
| `GuideRequest.goal` | 없음 | 추가 | 서버가 상태를 저장하지 않아도 화면 전환 뒤 목표 유지 |
| `HistoryItem.action` | `String` | `ActionType` enum | 오타 방지. JSON 값은 같음 |
| 응답 `source` | 없음 | 선택 필드 (진단용) | 서버 통계 · 평가용. 앱은 무시 |
| 오류 본문 | `{"error": "CODE", "message": …}` | `{"error": {"code", "message", "requestId"}}` | 이미 구현된 서버 형식 유지 + 추적용 requestId |
| SCROLL · WAIT | LLM이 고름 | **서버가 보냄** (2026-11-10부터, P4 · 구조화 출력과 함께) | 처음엔 보류했지만 앱이 이미 처리할 수 있어서 켬 |
| `:core-model` 의존 | 외부 라이브러리 금지 | `kotlinx-coroutines-core`만 예외 | 포트가 `Flow`를 씀 |
| JSON 직렬화 | (4.4) kotlinx.serialization | `:agent`에서만 | `:core-model`을 순수하게 유지 |
| 길이 제한 | 없음 | 상수로 명시 (3.6) | 서버가 400으로 거절하는 기준을 앱과 공유 |

---

## 6. 바꾸는 규칙

- `:core-model/port`, `:core-model/model`, 이 문서의 JSON 형식을 바꾸는 PR은 **전원 승인**.
- 그 PR 안에서 같이 고칠 것: `core-model/` · `server/app/models.py` · `testdata/contract/*.json` · 이 문서 · 영향받는 계약 테스트.
- 어댑터 내부 구현은 각 담당이 판단한다.
- 호환이 깨지는 변경은 계약 v2로 올리고 경로도 `/v2/...`로 바꾼다.
