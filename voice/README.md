# :voice — 듣기 · 말하기 (P3)

어르신의 말을 글자로 바꾸고(STT), 안내 문장을 소리로 읽는다(TTS). 앱의 "귀와 입".
계약 전체: [docs/contract.md](../docs/contract.md) 2장

## 만들 것

| 포트 | 실구현 |
|---|---|
| `SpeechInput` | `AndroidSpeechInput` (SpeechRecognizer, ko-KR) |
| `SpeechOutput` | `AndroidSpeechOutput` (TextToSpeech, ko-KR) |

Fake는 두지 않는다 (2일차 결정, [decisions.md](../docs/decisions.md) 6장 U3). 실구현은 JVM에서 돌릴 수 없어서 아래 계약은 **기기에서** 확인한다.

## 반드시 지킬 것

`SpeechInput.start(): Flow<SttEvent>`
- `Final` 또는 `Error` **하나**를 내보낸 뒤 플로우를 끝낸다 (그 전에 `Partial`은 여러 번 가능)
- 수집을 취소하면 마이크 자원을 해제한다
- 실패는 예외가 아니라 `SttEvent.Error(SttError.NO_SPEECH | NETWORK | BUSY | PERMISSION)`

`SpeechOutput.speak(text, interrupt = true)`
- 재생이 **끝날 때** 돌아온다 (suspend)
- 코루틴이 취소되면 즉시 멈춘다
- `interrupt = true`면 직전 안내를 바로 끊고 새 안내를 읽는다

규칙 (설계서 7.6)
- STT: 부분 결과 사용 · 발화 후 **1.2초 무음이면 Final** · 실패 시 **1회 자동 재시도**
- TTS: 속도 **0.85** · 새 안내가 오면 이전 안내 중단
- **에코 방지**: `:domain`은 `speak()`가 끝난 뒤에만 `start()`를 부른다. **TTS 종료 후 300ms 지연은 `:voice` 안에서 보장**한다 (예: 마지막 TTS 종료 시각을 모듈 안에서 공유)
- 문구 (3일차, LOST · ERROR · ASKING): 비난 · 전문용어 금지, 쉬운 존댓말

## 계약 확인 항목 (기기에서)

`SpeechInput`
1. 정상이면 `Final` 하나가 나오고 플로우가 끝난다
2. 실패는 예외 없이 `Error` 이벤트로 나오고 플로우가 끝난다
3. 수집을 취소하면 자원이 해제된다 (`stop()` 이후 다시 `start()` 가능)

`SpeechOutput`
1. `speak()`는 재생이 끝난 뒤 돌아온다
2. 취소하면 즉시 멈춘다
3. `interrupt = true`면 앞 안내가 끊긴다

## 할 일 (5일 계획)

- **1일차**: 키오스크 촬영 · ~~`FakeSpeechInput` + 계약 테스트~~ (2일차에 Fake 없이 가기로 함) · 실기기 STT(ko-KR) / TTS 확인 · **GitHub Actions CI** · **PR 템플릿** (루트 README의 16.12 체크리스트) · 어르신 테스트 섭외, 동의서 초안
- **2일차**: 오전 STT 규칙 · TTS · 에코 방지 · 계약 테스트 통과 / 오후 태블릿 키오스크 재현 · P1 UI 지원
- **3일차**: LOST · ERROR · ASKING 음성 문구 · AskUser → LISTENING 흐름 · 무음 8초 안내

## CI에서 돌릴 명령 (참고)

```bash
./gradlew :core-model:test :domain:test :app:assembleDebug
```

```bash
cd server && pip install -r requirements-dev.txt && KL_SKIP_APP_INIT=1 pytest -q
```

- JDK 17 이상 필요 (Gradle 9.6)
- `compileSdk = 37`이라 러너에 **Android API 37 플랫폼**이 없으면 `sdkmanager "platforms;android-37"` 설치 단계가 필요

## 막히기 쉬운 곳

- `RECORD_AUDIO` 권한은 이 모듈 `AndroidManifest.xml`에 선언 (앱으로 합쳐짐). 런타임 요청 화면은 P1
- `SpeechRecognizer`는 **메인 스레드**에서 만들고 써야 한다
- 온디바이스 인식: `SpeechRecognizer.createOnDeviceSpeechRecognizer()`는 **Android 12(API 31)부터**. minSdk 26이라 26~30 기기는 일반 `SpeechRecognizer` + `RecognizerIntent.EXTRA_PREFER_OFFLINE`
- `TextToSpeech`는 초기화가 비동기다. 끝났는지는 `UtteranceProgressListener`로 받아서 `speak()`를 suspend로 만든다

## 참고

설계서 7.6 (STT/TTS 규칙) · 10장 (UX · 접근성)

## 구현 현황 (이 PR)

- [x] `AndroidSpeechInput`(ko-KR · 부분 결과 · 1.2초 무음 종료 · 1회 자동 재시도 · 온디바이스 우선) · `AndroidSpeechOutput`(속도 0.85 · 문장 단위 · 취소 즉시 중단) · `EchoGuard`(종료 후 300ms)
- [x] 오류 매핑 · 재시도 · 후보 선택 · 에코 방지 · 문장 나누기 테스트 (5개, JVM). `FakeSpeechInput` · `FakeSpeechOutput`과 그 계약 테스트는 2026-11-10에 지움 (Fake 없이 실구현으로)
- **기기에서 확인 필요:** 실제 STT/TTS 동작 전부 (`SpeechRecognizer` · `TextToSpeech`는 JVM에서 돌릴 수 없음), 기기별 한국어 음성 데이터 설치 여부

## 알려진 문제 — 한국어 음성 모델이 없으면 STT가 아예 안 됨 (P3 처리)

2026-11-10 P1이 에뮬레이터(Android 17 · API 37 Google Play 이미지, 소리 · 마이크 끔)에서 앱을 실행해 찾았다. [decisions.md](../docs/decisions.md) 6장 U7.

**증상**
- "말하기"를 누르면 곧바로 `stt error=13 mapped=NETWORK` → 1회 재시도도 `stt error=11` → ERROR → "말씀을 알아듣지 못했어요. 다시 말하기 버튼을 눌러 주세요."
- 13 = `ERROR_LANGUAGE_UNAVAILABLE` (기기에 한국어 온디바이스 음성 모델이 없음), 11 = `ERROR_SERVER_DISCONNECTED`

**원인**
- `AndroidSpeechInput.createRecognizer()`는 `isOnDeviceRecognitionAvailable()`이면 **언어 팩 유무를 보지 않고** 온디바이스 인식기를 쓴다
- 12 · 13(언어 미지원 · 없음)일 때 일반 인식기로 넘어가지 않고, 자동 재시도도 같은 온디바이스 인식기로 다시 해서 또 실패한다
- `SttMapping`이 12 · 13을 `NETWORK`로 바꿔서, 화면 문구가 원인(모델 없음)과 다르다

**영향**: 한국어 음성 팩을 받지 않은 실기기에서는 음성 인식이 전혀 안 된다 → 시연 위험

**고칠 방향 (P3가 결정 #3 "온디바이스 ko-KR만"과 함께 정할 것)**
1. 12 · 13이면 일반 인식기(`SpeechRecognizer.createSpeechRecognizer`)로 한 번 더 시도 — 시연 안정성에 가장 효과적. 대신 네트워크를 쓸 수 있다 (결정 #3 수정 필요)
2. API 33+에서 `checkRecognitionSupport` · `triggerModelDownload`로 한국어 팩을 미리 받게 안내
3. 안내 문구: 지금 `SttError` 4종으로는 "모델 없음"을 구분할 수 없다. 새 값을 넣으려면 `:core-model` 변경이라 **전원 승인 PR**이 필요하다 (아니면 `:voice` 안에서 1번으로 해결)

**재현**: 한국어 음성 팩이 없는 에뮬레이터 · 기기에서 "말하기" → `adb logcat -s AndroidSpeechInput`

**시연 기기 준비**: 기기의 음성 인식 설정에서 한국어(오프라인) 음성 팩이 설치돼 있는지 미리 확인
