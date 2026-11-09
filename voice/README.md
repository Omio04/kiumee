# :voice — 듣기 · 말하기 (P3)

어르신의 말을 글자로 바꾸고(STT), 안내 문장을 소리로 읽는다(TTS). 앱의 "귀와 입".
계약 전체: [docs/contract.md](../docs/contract.md) 2장

## 만들 것

| 포트 | 실구현 | Fake |
|---|---|---|
| `SpeechInput` | `AndroidSpeechInput` (SpeechRecognizer, ko-KR) | `FakeSpeechInput` — 미리 정한 문장을 Partial → Final로 발행 |
| `SpeechOutput` | `AndroidSpeechOutput` (TextToSpeech, ko-KR) | `FakeSpeechOutput` — 말한 문장을 기록 (mock 플레이버용) |

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

## 계약 테스트 (먼저 쓰기)

`SpeechInput`
1. 정상이면 `Final` 하나가 나오고 플로우가 끝난다
2. 실패는 예외 없이 `Error` 이벤트로 나오고 플로우가 끝난다
3. 수집을 취소하면 자원이 해제된다 (`stop()` 이후 다시 `start()` 가능)

`SpeechOutput`
1. `speak()`는 재생이 끝난 뒤 돌아온다
2. 취소하면 즉시 멈춘다
3. `interrupt = true`면 앞 안내가 끊긴다

## 할 일 (5일 계획)

- **1일차**: 키오스크 촬영 · `FakeSpeechInput` + 계약 테스트 · 실기기 STT(ko-KR) / TTS 확인 · **GitHub Actions CI** · **PR 템플릿** (루트 README의 16.12 체크리스트) · 어르신 테스트 섭외, 동의서 초안
- **2일차**: 오전 STT 규칙 · TTS · 에코 방지 · 계약 테스트 통과 / 오후 태블릿 키오스크 재현 · P1 UI 지원
- **3일차**: LOST · ERROR · ASKING 음성 문구 · AskUser → LISTENING 흐름 · 무음 8초 안내

## CI에서 돌릴 명령 (참고)

```bash
./gradlew :core-model:test :domain:test :app:assembleMockDebug
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
