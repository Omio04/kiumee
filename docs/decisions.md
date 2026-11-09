# 결정 기록

기록 담당: P1 · 기준 문서: AI 키오스크 렌즈 시스템 설계서 (Android MVP) v0.1, 5일 개발 계획

형식: 결정이 나면 `결정` 칸을 채우고 날짜를 적는다. 바꿀 때는 지우지 말고 줄을 그어 남긴다.

---

## 1. 1일차 오전 결정 사항 (09:00~11:00, 계획서 4장)

| # | 결정 사항 | 제안 | 담당 | 결정 |
|---|---|---|---|---|
| 1 | 대상 키오스크 1종 · 주문 플로우 | 촬영 가능 + 태블릿에 재현 가능한 곳. 메뉴 → 옵션 → 장바구니 → 결제 수단 선택 | 전원 | (미정) |
| 2 | OCR 엔진 | ML Kit 한국어 인식 고정, 대안 평가는 보류 | P2 | (미정) |
| 3 | STT 방식 | 온디바이스 ko-KR만, 클라우드 폴백 보류 | P3 | (미정) |
| 4 | LLM 모델 · 서버 언어 · 호스팅 | 서버 저장소 기준 FastAPI(Python) · 기본 모델 `claude-haiku-5-5` · 클라우드 1곳 + 로컬 실행 대비 | P4 | (미정) |
| 5 | minSdk · 시연 기기 | minSdk 26 (스켈레톤에 반영됨) · 시연 기기 1대 고정 + 예비 1대 | P1 | (미정, 기기 모델명 기입) |
| 6 | DI 방식 | Hilt 경험자 없으면 수동 DI. 생성자 주입 원칙은 유지 | P1 | (미정) |
| 7 | 어르신 테스트 | 4일차 오후 1~2명 · 촬영 · 녹음 동의서 | P3 | (미정) |

### 범위 조정 (계획서 2장) — 팀 확인 필요
유지: 키오스크 1종 · 개인정보 원칙 · 모듈 구조 · 상태머신 10개 상태
축소: 트래킹은 OCR 3fps + EMA만 · 하이라이트는 PULSE_BORDER만 · 계약 테스트는 포트마다 2~3개 · 테스트 사진 30장
보류: STT 클라우드 폴백 · KioskProfile

결정: (미정)

---

## 2. :core-model 확정 회의 (12:00, 30분)

초안 위치: `core-model/src/main/kotlin/kr/kiosklens/core/` (`model/`, `port/Ports.kt`)
확정 후 `port/` 변경은 **전원 승인 PR**로만 한다. Fake와 계약 테스트도 함께 수정.

### 2.1 설계서와 다르게 한 부분 (확인 필요)

| # | 항목 | 설계서 | 초안 | 이유 |
|---|---|---|---|---|
| A | `CameraFrame` | `data class(imageProxy: ImageProxy, ...)` | 인터페이스 (frameId, timestampMs, rotationDegrees, width, height, close()) | `:core-model`은 순수 Kotlin이라 `ImageProxy`를 참조할 수 없음 (16.2와 5장이 충돌) |
| B | `SpeechOutput.isSpeaking` | 5장에 있음 | 뺌 | 16.4(ISP 버전)를 따름. 16장이 우선. `speak()`가 재생 끝까지 suspend 하므로 에코 방지에 충분 |
| C | 외부 라이브러리 | `:core-model` import 금지 | `kotlinx-coroutines-core`만 예외 | 포트 시그니처가 `Flow`를 씀 (설계서 자체가 그렇게 정의) |
| D | JSON 직렬화 (4.4) | kotlinx.serialization | `:core-model`에는 넣지 않음. DTO와 직렬화는 `:agent/remote` (P4) | 16.3 패키지 트리에 `GuideRequestDto`가 `:agent`에 있음 |
| E | `NormRect` 검증 | 없음 | `isValid` 속성만 추가 (생성자에서 예외 안 던짐) | OCR 가장자리 값 때문에 앱이 죽지 않게 |
| F | `FrameMotion` | 정의 없음 | `frameId, dx, dy, scale, confidence` (정규화 단위) | 선택 기능. P5 확인 필요 |

**A 후속 결정 필요:** `ImageProxy`를 감싼 Android 구현체를 어디에 두나?
- 제안: `:vision`이 `ImageProxyFrame : CameraFrame`을 제공하고, `:app/camera`가 생성해서 `FrameSink.submit()`에 넘긴다. (`:app`은 원래 모든 모듈에 의존하므로 규칙 위반 없음. `:vision`은 자기 클래스로 다운캐스트해서 `ImageProxy`를 꺼냄.)
- 사진 입력 디버그 모드(P2)는 같은 방식으로 `BitmapFrame : CameraFrame`을 쓴다.

결정: (미정)

### 2.2 서버 API와 설계서의 불일치 (P4 · 전원)

`server/app/models.py`는 설계서를 보기 전에 계획서만 보고 추정해 만든 것이라 (파일 주석에 명시됨) 설계서 4.2 · 8.1과 다르다.

| 항목 | 설계서 4.2 · 8.1 | 현재 서버 |
|---|---|---|
| 응답 형태 | `{"goal", "action": {"type": "TAP", "targetId", "guideText"}}` | `{"action": "GUIDE", "targetId", "guideText", "message", "source"}` (평평한 구조, goal 없음) |
| 행동 종류 | TAP · SCROLL · WAIT · ASK_USER · DONE · UNKNOWN | GUIDE · ASK_USER · DONE · UNKNOWN |
| 이력 항목 | `{screenId, action, targetText}` | `{role: USER/GUIDE, text, targetId}` |
| 스냅샷 | screenId, frameId, timestampMs, elements, stableFrames | screenId, elements (나머지 필드는 무시됨) |
| sessionId | 필수 | 선택 |
| 오류 본문 | `{"error": "CODE", "message": "..."}` | `{"error": {"code", "message", "requestId"}}` |

선택지:
1. **서버를 설계서 8.1에 맞춘다** — 모두가 보는 문서와 실제가 같아짐. 서버 README도 "회의에서 이름만 맞추면 된다"고 되어 있음. (P1 제안)
2. 서버는 그대로 두고 `:agent`의 `DtoMapper`가 변환한다 — 서버 변경 없음. 대신 설계서를 고쳐야 함.

어느 쪽이든 앱 쪽 `:core-model`의 `GuideAction`은 설계서대로 두고, 차이는 `:agent` 안에서만 흡수한다.

결정: (미정)

### 2.3 확인만 하면 되는 것
- 요소 ID 규칙 (4.3)은 `:vision`(P2) 소유
- 좌표는 회전 보정된 분석 프레임 기준 정규화 좌표. 화면 좌표 변환은 `:overlay`만
- 패키지 루트: `kr.kiosklens.<module>` (core-model은 `kr.kiosklens.core`)
- 포트 9개: FrameSink · SnapshotSource · SpeechInput · SpeechOutput · GuideAgent · HighlightSurface · MotionSink · Clock · Logger

결정: (미정)

---

## 3. 빌드 · 구조 (P1, 1일차)

- Gradle 9.6.0 · AGP 9.5.1 · Kotlin 2.2.10 · compileSdk 37 · minSdk 26 · Java 17 바이트코드
- 플레이버: `real` / `mock` (설계서 16.8). mixed 조합은 2일차에 DI 조립에서 처리
- 아키텍처 검사 (설계서 16.9 일부):
  - `:core-model` · `:domain`은 `kotlin("jvm")` → Android import 시 컴파일 실패
  - 루트 `build.gradle.kts`: 어댑터끼리 의존하거나 순수 모듈이 바깥을 의존하면 빌드 실패
