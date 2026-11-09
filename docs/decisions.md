# 결정 기록

기록 담당: P1 · 기준 문서: AI 키오스크 렌즈 시스템 설계서 (Android MVP) v0.1, 5일 개발 계획

형식: 결정이 나면 `결정` 칸을 채우고 날짜를 적는다. 바꿀 때는 지우지 말고 ~~줄을 그어~~ 남긴다.

---

## 1. 1일차 오전 결정 사항 (계획서 4장)

| # | 결정 사항 | 제안 | 담당 | 결정 |
|---|---|---|---|---|
| 1 | 대상 키오스크 1종 · 주문 플로우 | 촬영 가능 + 태블릿에 재현 가능한 곳. 메뉴 → 옵션 → 장바구니 → 결제 수단 선택 | 전원 | (미정) — **가장 먼저**. 정해져야 P2 촬영 · P4 평가 시작 |
| 2 | OCR 엔진 | ML Kit 한국어 인식 고정, 대안 평가는 보류 | P2 | (미정) |
| 3 | STT 방식 | 온디바이스 ko-KR만, 클라우드 폴백 보류 | P3 | (미정) |
| 4 | LLM 모델 · 서버 언어 · 호스팅 | 서버 구현 기준: FastAPI(Python) · 기본 모델 `claude-haiku-5-5` · 클라우드 1곳 + 로컬 실행 대비 | P4 | (미정 — 서버는 이미 이 구성으로 구현됨) |
| 5 | minSdk · 시연 기기 | minSdk 26 · 시연 기기 1대 고정 + 예비 1대 | P1 | **minSdk 26** (2026-11-09, 빌드에 반영) · 시연 기기: (모델명 기입) |
| 6 | DI 방식 | Hilt 경험자가 없으면 수동 DI | P1 | **수동 DI** (2026-11-09). 플레이버마다 `:app/di`에 조립 코드 하나. 생성자 주입 원칙 유지 → 나중에 Hilt로 바꾸기 쉬움. 이유: AGP 9 + Hilt는 KSP · 플러그인 호환 확인이 필요해 5일 일정에 위험 |
| 7 | 어르신 테스트 | 4일차 오후 1~2명 · 촬영 · 녹음 동의서 | P3 | (미정) |

### 범위 조정 (계획서 2장) — 팀 확인 필요
유지: 키오스크 1종 · 개인정보 원칙 · 모듈 구조 · 상태머신 10개 상태
축소: 트래킹은 OCR 3fps + EMA만 · 하이라이트는 PULSE_BORDER만 · 계약 테스트는 포트마다 2~3개 · 테스트 사진 30장
보류: STT 클라우드 폴백 · KioskProfile

결정: (미정)

---

## 2. `:core-model` · 서버 계약 → **계약 v1** (12:00 회의 안건)

**결정: 계약 v1 확정안** (P1, 2026-11-09) — 정본 [docs/contract.md](contract.md). 전원 승인 PR로 확정한다.

| # | 안건 | 결정 |
|---|---|---|
| A | `CameraFrame` (설계서는 Android 타입 `ImageProxy`를 들고 있음) | 인터페이스 + `image: Any` (`ImageProxy` 또는 `Bitmap`). 구현체 `ImageProxyFrame`은 `:app/camera`(P1). `:vision`은 `frame.image as? ImageProxy`로 꺼냄 |
| B | `SpeechOutput.isSpeaking` | 뺌 (설계서 16.4 따름). 에코 방지: `:domain`은 `speak()`가 끝난 뒤에만 `start()`, 300ms 지연은 `:voice` 안에서 |
| C | `:core-model`의 외부 라이브러리 | `kotlinx-coroutines-core`만 예외 (포트가 `Flow`를 씀) |
| D | JSON 직렬화 위치 | `:agent/remote`에서만. `:core-model`은 순수 유지 |
| E | `NormRect` 검증 | `isValid` 속성만 (생성자에서 예외 안 던짐) |
| F | `FrameMotion` | `frameId, dx, dy, scale, confidence` (정규화 단위, 선택 기능) |
| G | 서버 API 불일치 (서버는 설계서를 보기 전에 추정해 만들어짐) | **서버를 계약 v1에 맞춤** (이 PR). 입출력 모양만 바꾸고 판단 로직은 그대로. 테스트 60 → 82개 통과, 평가 정확도 동일 |
| H | 화면 전환 뒤 목표 유지 (서버는 상태를 저장하지 않음) | `GuideRequest.goal` 추가: 앱이 직전 응답의 `goal`을 돌려줌 |
| I | `HistoryItem.action` | `String` → `ActionType` enum. 기록 규칙은 `HistoryItem.of()` 한 곳 |
| J | 응답 `source` 필드 | 진단용 선택 필드로 유지. 앱은 무시 |
| K | 오류 본문 | 서버 형식 유지 `{"error": {"code", "message", "requestId"}}` (설계서 8.2와 다름) |
| L | SCROLL · WAIT | 계약에는 유지 (앱은 처리 가능해야 함). 서버 생성은 보류 — P4 판단 |
| M | 길이 제한 | 서버가 400으로 거절하는 기준을 `:core-model` 상수로 공유 (contract.md 3.6) |
| N | 서버 코드 위치 | zip → `server/` 폴더로 풀어서 관리 (리뷰 · CI를 위해) |

### 확인만 하면 되는 것
- 요소 ID 규칙 (4.3)은 `:vision`(P2) 소유
- 좌표는 회전 보정된 분석 프레임 기준 정규화 좌표. 화면 좌표 변환은 `:overlay`만
- 패키지 루트: `kr.kiosklens.<module>` (core-model은 `kr.kiosklens.core`)
- 공용 예시 JSON: `testdata/contract/` (앱 · 서버가 같은 파일로 테스트)

---

## 3. 빌드 · 구조 (P1, 1일차)

- Gradle 9.6.0 · AGP 9.5.1 · Kotlin 2.2.10 · compileSdk 37 · minSdk 26 · Java 17 바이트코드
  - compileSdk 37: 팀원 PC · CI에 Android API 37 플랫폼 설치 필요. 어려우면 회의에서 내릴지 결정
- 플레이버: `real` / `mock` (설계서 16.8). mixed 조합은 2일차에 DI 조립에서 처리
- 아키텍처 검사 (설계서 16.9 일부):
  - `:core-model` · `:domain`은 `kotlin("jvm")` → Android import 시 컴파일 실패
  - 루트 `build.gradle.kts`: 어댑터끼리 의존하거나 순수 모듈이 바깥을 의존하면 빌드 실패
- 문서: 루트 README (전체 안내 · 협업 규칙) + 모듈마다 README (담당자용 할 일 · 계약 · 계약 테스트 목록)

---

## 4. 상태머신 (`:domain`, P1)

| # | 항목 | 결정 (2026-11-09) |
|---|---|---|
| S1 | 안내 문장 | **"{위치} 깜빡이는 {guideText} 버튼을 누르세요"**. 설계서의 "{guideText}를"은 HOT · ICE 같은 글자에서 을/를을 정하기 어려워 "버튼을"로 고정. 글자가 이미 "버튼"으로 끝나면 덧붙이지 않음 |
| S2 | 늦게 온 결과 | 번호표(ticket)로 구분해 무시 (종료 뒤 서버 응답, 옛 화면에 대한 응답 등) |
| S3 | 에코 방지 | ASKING은 질문을 **다 읽은 뒤**(`SpeechFinished`)에만 LISTENING으로 |
| S4 | 화면 전환 감지 | `screenChanges` + 다른 screenId의 스냅샷 둘 다. 새 화면 스냅샷이 이미 있으면 SCANNING 없이 바로 묻기 |
| S5 | 하이라이트 따라가기 | 같은 화면의 새 스냅샷마다 버튼 위치 갱신 (위치가 바뀌었을 때만 `Highlight`) |
| S6 | 묻는 사이 화면 전환 | 그 응답은 버리고 새 화면으로 다시 묻기. 답을 못 받은 발화는 다시 보냄 |
| S7 | UNKNOWN 응답 | 이유를 읽고 WAIT_CHANGE. 15초 뒤 다시 읽는 대신 **최신 화면으로 다시 묻기** (스냅샷마다 묻지 않게) |
| S8 | 오래된 화면 | `screenChanges`로 옛 화면 스냅샷을 버림. 시간 기준 만료는 필요하면 추가 |

2일차에 정할 것
- 진행 중(IDLE이 아닐 때) "말하기" 버튼: 지금은 무시. 다시 말하기(목표에 이어 붙이기)로 할지 결정
- 15초마다 다시 읽기의 횟수 제한: 지금은 제한 없음. 현장 테스트 뒤 결정

---

## 변경 이력

| 날짜 | 내용 |
|---|---|
| 2026-11-09 | 멀티모듈 스켈레톤 · `:core-model` 초안 (PR #1, 병합) |
| 2026-11-09 | 계약 v1 확정안 · 서버 정렬 · 팀 README (PR #2, 병합) |
| 2026-11-09 | 상태머신 · 위치 문구 · 안내 문장 (`feat/p1-state-machine`) |
