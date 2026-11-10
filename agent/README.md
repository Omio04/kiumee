# :agent — 서버 클라이언트 (P4)

`GuideRequest`를 서버(`POST /v1/guide/next`)로 보내고 `GuideResponse`를 받아 온다. 서버 본체는 [`server/`](../server/README.md).
계약 전체: [docs/contract.md](../docs/contract.md) 3장 — **이 모듈은 3장 전체가 곧 명세다**

## 만들 것

| 포트 | 실구현 |
|---|---|
| `GuideAgent` | `RemoteGuideAgent` |

Fake는 두지 않는다 (2일차 결정, [decisions.md](../docs/decisions.md) 6장 U3). 서버 없이 연습할 때는 서버를 `LLM_PROVIDER=fake`로 띄운다.

```
kr.kiosklens.agent
├─ remote/   Json(작은 JSON 도구) · DtoMapper
└─ RemoteGuideAgent : GuideAgent
```

## 반드시 지킬 것

- **예외 대신 `Result.failure`** (네트워크 · 타임아웃 · 4xx/5xx 모두)
- 성공이면 `Tap.targetId`는 **요청 스냅샷에 있다**. 서버가 보장하지만 앱에서도 한 번 더 확인하고, 없으면 `Unknown`으로 바꾼다
- 타임아웃: **시도 한 번 전체 6초** (connect · read 구간별이 아니라 합쳐서), 실패 시 **1회 재시도** (400 · 401은 재시도하지 않음, 429는 `Retry-After`만큼 기다림)
- 헤더 `X-App-Key`. **LLM 키는 절대 앱에 넣지 않는다**
- 보내기 전에 길이를 맞춘다: 요소 글자 80자 · 요소 300개 · utterance · goal 200자 · history 5개 (넘으면 서버가 400)
- 응답의 모르는 필드(`source` 등)는 무시

직렬화 설정

```kotlin
val json = Json {
    classDiscriminator = "type"  // GuideAction: @SerialName("TAP") 등
    ignoreUnknownKeys = true
    explicitNulls = false
}
```

## 계약 테스트 (먼저 쓰기)

1. 실패는 예외가 아니라 `Result.failure`다 (로컬 HTTP 서버 500 · 타임아웃)
2. 성공이면 `Tap.targetId`가 요청 스냅샷에 있거나 액션이 `Unknown`이다
3. **`testdata/contract/*.json` 왕복**: 요청 DTO를 직렬화하면 `request_*.json`과 같고, `response_*.json`을 읽으면 `GuideResponse`로 맞게 바뀐다 → 서버와 형식이 어긋나지 않는 보증 (서버는 `server/tests/test_contract.py`가 같은 파일로 검사 중)

## 서버 쪽 현황 (계약 v1 반영)

- P1이 서버 입출력 모양을 계약 v1에 맞춰 고쳤다 (2026-11-09). **버튼 고르는 로직(후보 축소 · LLM · 검증)은 그대로**
  - 테스트 60개 → 82개 전부 통과 · 평가 스크립트 정확도 그대로 (fake 13/13, heuristic 18/18)
  - LLM이 내는 형식(`GUIDE` 등)은 그대로 두고, `ActionValidator`가 앱 계약(`TAP` 등)으로 바꿔서 내보낸다
- 서버 코드는 이제 zip이 아니라 **`server/` 폴더**에 있다. 앞으로는 이 폴더에서 이어서 작업
- SCROLL · WAIT는 계약에는 있지만 서버가 아직 안 보낸다 → 프롬프트에 추가할지는 P4 판단

## 할 일 (5일 계획)

- **1일차**: 서버 스켈레톤 ✅ · 스키마 검증 ✅ · 오류 형식 ✅ · X-App-Key ✅ · FakeLlmClient ✅ (서버) / ~~`FakeGuideAgent`~~ (2일차에 Fake 없이 가기로 함) · 계약 테스트 (앱) ✅
- **2일차**: 후보 축소 · 프롬프트 · LlmClient 실구현 · 응답 검증 (서버에 대부분 있음) · **`RemoteGuideAgent` 초안** · 평가 스크립트
- **3일차**: 클라이언트 오류 처리 (400 · 401 · 429 · 502 · 504 → 사용자 안내) · 서버 클라우드 배포 · 지연 로그

## 막히기 쉬운 곳

- 에뮬레이터에서 PC의 로컬 서버는 `http://10.0.2.2:8080` (실기기는 같은 Wi-Fi의 PC IP)
- `http://`(로컬 서버)에 붙으려면 debug 빌드에 cleartext 허용(`networkSecurityConfig`)이 필요
- 앱 키는 저장소에 올리지 말고 `local.properties` → `BuildConfig` 필드로 주입하는 방식을 추천
- 키 없이 서버 띄우기: `cd server && APP_KEY=dev LLM_PROVIDER=fake uvicorn app.main:app --port 8080`

## 구현 현황 (이 PR)

- [x] `RemoteGuideAgent`(HttpURLConnection · **시도 한 번 전체 6초** · 1회 재시도 · 400/401 재시도 없음 · 429 `Retry-After`) · `DtoMapper`(보내기 전 길이 정리 포함)
- [x] 2026-11-10 (P1): 6초를 connect · read에 따로 걸어 한 번에 12초 넘게 걸릴 수 있던 것을 **전체 6초**로 고침 (계약 3장). `FakeGuideAgent`는 지움
- [x] **JSON은 `kotlinx.serialization` 대신 작은 직접 구현**(`remote/Json.kt`)으로 처리했다 (외부 의존 0, 계약 필드가 적음). 바꾸고 싶으면 `DtoMapper`만 고치면 되고 테스트가 그대로 보증한다.
- [x] 테스트 18개: `testdata/contract/*.json` 왕복, 로컬 HTTP 서버로 401 · 400 · 429 · 502 · 시간 초과 · **응답이 조금씩 늦게 오는 경우** · 연결 실패 · 깨진 응답, `Tap.targetId` 방어, 길이 제한
- 서버: **무료 Gemini 지원 추가** (`LLM_PROVIDER=gemini`, `GEMINI_API_KEY`) — [server/README.md](../server/README.md)

## 실패 → 사용자 안내 (3일차, P4)

`GuideFailure.kt`: `Throwable.guideFailureKind()` / `guideFailureMessage()`가 `RemoteGuideAgent`의 `Result.failure` 원인을 종류(`GuideFailureKind`)와 읽어 줄 문구로 바꾼다.

| 원인 | 종류 | 다시 시도 |
|---|---|---|
| 400 | BAD_REQUEST | 안 함 |
| 401 · 403 | UNAUTHORIZED | 안 함 |
| 429 | RATE_LIMITED | `Retry-After`만큼 기다려 1회 |
| 500 · 502 | SERVER_ERROR | 1회 |
| 504 · 앱 시간 초과 | TIMEOUT | 1회 |
| 연결 실패 · 호스트 없음 | OFFLINE | 1회 |
| 깨진 응답 | BAD_RESPONSE | 1회 |

⚠️ **P1에게 넘길 것**: 지금 `:domain`의 `SessionRunner`는 실패를 `GuideFailed(ticket)`로만 받아 원인을 모른다 (`:domain`은 `:agent`를 몰라야 하므로 내가 고치지 않았다). 문구를 실제로 읽게 하려면 `:app`에서 `GuideAgent`를 감싸 실패 원인을 문구로 바꾸거나, `GuideFailed`에 종류를 담는 `:core-model` 변경 PR(전원 승인)이 필요하다.
