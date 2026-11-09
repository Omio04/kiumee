# kiumee — AI 키오스크 렌즈 (Android MVP)

스마트폰 카메라로 키오스크 화면을 비추고 원하는 것을 말하면, **눌러야 할 버튼을 하이라이트와 음성으로 안내**하는 앱.
앱은 키오스크를 대신 누르지 않고 안내만 한다.

## 처음 왔다면 이 순서로 읽기

1. **[docs/contract.md](docs/contract.md)** — 모듈 · 서버가 주고받는 규격 (계약 v1). 전원 필독
2. **자기 모듈 README** — 아래 표에서 자기 담당 모듈
3. [docs/decisions.md](docs/decisions.md) — 지금까지 정한 것과 아직 안 정한 것

## 모듈과 담당

| 모듈 | 담당 | 하는 일 | README |
|---|---|---|---|
| `:core-model` | P1 | 공용 데이터 + 포트(규격). 전원이 여기에 맞춰 개발 | [core-model](core-model/README.md) |
| `:domain` | P1 | 두뇌: 상태머신 · SessionRunner · 위치 문구 | [domain](domain/README.md) |
| `:app` | P1 | 조립(DI) · 화면(UI) · 카메라 연결 | [app](app/README.md) |
| `:vision` | P2 | 눈: 화면 글자 읽기 (OCR) · 화면 전환 감지 | [vision](vision/README.md) |
| `:voice` | P3 | 귀와 입: 음성 인식(STT) · 음성 안내(TTS) | [voice](voice/README.md) |
| `:agent` | P4 | 서버 클라이언트 | [agent](agent/README.md) |
| `server/` | P4 | 다음에 누를 버튼을 고르는 서버 (FastAPI + LLM) | [server](server/README.md) |
| `:overlay` | P5 | 손가락 표시: 좌표 변환 · 하이라이트 | [overlay](overlay/README.md) |

```
:app ──▶ 전부
:vision · :voice · :agent · :overlay ──▶ :domain ──▶ :core-model
```

- 어댑터(`:vision` · `:voice` · `:agent` · `:overlay`)끼리는 **서로 의존 금지**. 위반하면 빌드가 실패한다.
- `:core-model` · `:domain`은 순수 Kotlin 모듈이라 `import android...`를 쓰면 **컴파일이 안 된다**.

## 빌드 · 테스트

```bash
./gradlew :core-model:test :domain:test   # 순수 모듈 단위 테스트
```

```bash
./gradlew :app:assembleMockDebug          # 모든 포트가 Fake인 mock 플레이버
```

```bash
./gradlew :app:installMockDebug           # 연결된 기기에 설치
```

```bash
cd server && pip install -r requirements-dev.txt && KL_SKIP_APP_INIT=1 pytest -q   # 서버 테스트
```

필요한 것: JDK 17 이상 · Android SDK **API 37** 플랫폼 (SDK Manager에서 설치) · `local.properties`에 `sdk.dir` (Android Studio로 열면 자동 생성) · 서버는 Python 3.9 이상

## 협업 규칙 (5일 계획 9장)

| 항목 | 규칙 |
|---|---|
| 브랜치 | `main`은 항상 빌드 가능. 기능 브랜치는 `feat/p2-ocr-pipeline` 형식. **`main`에 직접 push 금지** |
| PR | 반나절 이하 분량으로 작게. 지정 리뷰어 1명 승인 후 병합 |
| 포트 · 계약 변경 | `:core-model` 또는 `docs/contract.md`의 JSON 형식을 바꾸는 PR은 **전원 승인**. Fake와 계약 테스트도 같이 수정 |
| 병합 시간 | 매일 13:00 · 19:00. 긴급 수정은 P1 승인 시 예외 |
| 통합 빌드 | 19:00 병합 후 P1이 시연 기기에 설치 → 5분 데모 |
| 성능 로그 | 구간 시작 · 끝에 `KL_PERF` 태그 로그 |
| 비밀 정보 | LLM 키는 서버에만. 앱에는 `X-App-Key`만 |

리뷰어 지정

| PR 작성자 | P1 | P2 | P3 | P4 | P5 |
|---|---|---|---|---|---|
| 리뷰어 | P4 | P5 | P1 | P3 | P2 |

PR 리뷰 체크리스트 (설계서 16.12)
- **S** 바꿀 이유가 두 가지 이상인 클래스는 분리했는가?
- **O** 새 동작을 `if` / `when` 추가가 아니라 새 클래스로 해결했는가?
- **L** 계약 테스트가 Fake와 실구현 모두에서 통과하는가?
- **I** 주입받은 포트에 쓰지 않는 메서드가 있는가?
- **D** `:domain` · `:core-model`에 플랫폼 · 라이브러리 import가 생겼는가? 생성자 주입 외의 의존 획득이 있는가?

## 하루 리듬

| 시간 | 내용 |
|---|---|
| 10:00 | 스탠드업 10분: 어제 한 일 · 오늘 할 일 · 막힌 점 (P1 진행) |
| 13:00 | 1차 병합 |
| 19:00 | 2차 병합 → 통합 빌드 설치 → 5분 데모 |

## 폴더

```
core-model/  domain/  app/            P1
vision/                               P2
voice/                                P3
agent/  server/                       P4
overlay/                              P5
docs/            contract.md (계약) · decisions.md (결정 기록)
testdata/contract/   계약 예시 JSON — 앱과 서버가 같이 쓰는 정답지
```
