# :agent — 서버 클라이언트 (P4)

`GuideRequest`를 서버(`POST /v1/guide/next`)로 보내고 `GuideResponse`를 받아 온다. 서버 본체는 [`server/`](../server/README.md).
계약 전체: [docs/contract.md](../docs/contract.md) 3장 — **이 모듈은 3장 전체가 곧 명세다**

## 만들 것

| 포트 | 실구현 | Fake |
|---|---|---|
| `GuideAgent` | `RemoteGuideAgent` | `FakeGuideAgent` — 규칙 기반 고정 응답 (예: 1등 BUTTON_LIKE 요소로 TAP, 요소 없으면 UNKNOWN) |

```
kr.kiosklens.agent
├─ remote/   GuideApi · GuideRequestDto · GuideResponseDto · DtoMapper
└─ RemoteGuideAgent : GuideAgent
```

## 반드시 지킬 것

- **예외 대신 `Result.failure`** (네트워크 · 타임아웃 · 4xx/5xx 모두)
- 성공이면 `Tap.targetId`는 **요청 스냅샷에 있다**. 서버가 보장하지만 앱에서도 한 번 더 확인하고, 없으면 `Unknown`으로 바꾼다
- 타임아웃 **6초**, 실패 시 **1회 재시도** (400 · 401은 재시도하지 않음, 429는 `Retry-After`만큼 기다림)
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

1. 실패는 예외가 아니라 `Result.failure`다 (MockWebServer 500 · 타임아웃)
2. 성공이면 `Tap.targetId`가 요청 스냅샷에 있거나 액션이 `Unknown`이다
3. **`testdata/contract/*.json` 왕복**: 요청 DTO를 직렬화하면 `request_*.json`과 같고, `response_*.json`을 읽으면 `GuideResponse`로 맞게 바뀐다 → 서버와 형식이 어긋나지 않는 보증 (서버는 `server/tests/test_contract.py`가 같은 파일로 검사 중)

## 서버 쪽 현황 (계약 v1 반영)

- P1이 서버 입출력 모양을 계약 v1에 맞춰 고쳤다 (2026-11-09). **버튼 고르는 로직(후보 축소 · LLM · 검증)은 그대로**
  - 테스트 60개 → 82개 전부 통과 · 평가 스크립트 정확도 그대로 (fake 13/13, heuristic 18/18)
  - LLM이 내는 형식(`GUIDE` 등)은 그대로 두고, `ActionValidator`가 앱 계약(`TAP` 등)으로 바꿔서 내보낸다
- 서버 코드는 이제 zip이 아니라 **`server/` 폴더**에 있다. 앞으로는 이 폴더에서 이어서 작업
- SCROLL · WAIT는 계약에는 있지만 서버가 아직 안 보낸다 → 프롬프트에 추가할지는 P4 판단

## 할 일 (5일 계획)

- **1일차**: 서버 스켈레톤 ✅ · 스키마 검증 ✅ · 오류 형식 ✅ · X-App-Key ✅ · FakeLlmClient ✅ (서버) / **`FakeGuideAgent` + 계약 테스트 (앱)** ⬜
- **2일차**: 후보 축소 · 프롬프트 · LlmClient 실구현 · 응답 검증 (서버에 대부분 있음) · **`RemoteGuideAgent` 초안** · 평가 스크립트
- **3일차**: 클라이언트 오류 처리 (400 · 401 · 429 · 502 · 504 → 사용자 안내) · 서버 클라우드 배포 · 지연 로그

## 막히기 쉬운 곳

- 에뮬레이터에서 PC의 로컬 서버는 `http://10.0.2.2:8080` (실기기는 같은 Wi-Fi의 PC IP)
- `http://`(로컬 서버)에 붙으려면 debug 빌드에 cleartext 허용(`networkSecurityConfig`)이 필요
- 앱 키는 저장소에 올리지 말고 `local.properties` → `BuildConfig` 필드로 주입하는 방식을 추천
- 키 없이 서버 띄우기: `cd server && APP_KEY=dev LLM_PROVIDER=fake uvicorn app.main:app --port 8080`
