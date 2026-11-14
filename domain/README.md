# :domain — 세션 두뇌 (P1)

어르신이 "말하기"를 누른 순간부터 안내가 끝날 때까지, **지금 무엇을 해야 하는지 정하는 곳**이다.
순수 Kotlin 모듈이라 Android · OCR · 네트워크를 전혀 모르고, 포트(`:core-model/port`)만 쓴다.

> 다른 파트는 이 모듈을 직접 고치지 않는다. 다만 **자기 포트가 언제 불리는지** 알면 개발이 쉬워서 아래에 정리한다.

## 구조 (설계서 16.3 · 16.5)

| 위치 | 클래스 | 하는 일 |
|---|---|---|
| `session/` | `SessionStateMachine` | (지금 상태, 일어난 일) → (다음 상태, 할 일 목록). **순수 함수** — 직접 말하거나 서버를 부르지 않는다 |
| `session/` | `SessionState` · `Phase` · `Guidance` | 상태: 단계 10개 + 세션이 기억할 것 (목표 · 이력 · 최신 화면 · 안내 중인 버튼 · 번호표) |
| `session/` | `SessionEvent` | 일어난 일 10가지 (버튼 · 화면 · 음성 · 서버 · 시간 초과) |
| `session/` | `SessionEffect` · `Transition` | 할 일 9가지 (듣기 · 말하기 · 서버 요청 · 하이라이트 · 타이머) |
| `policy/` | `PositionPhraser` | 버튼 좌표 → "화면 오른쪽 아래" (가로 · 세로 3등분, 둘 다 가운데면 "화면 가운데") |
| `policy/` | `GuideTextComposer` | 어르신께 읽는 문장 전부. 예: "화면 왼쪽 위 깜빡이는 아메리카노 버튼을 누르세요" |
| `policy/` | `TimeoutPolicy` | 무음 8초 · SCANNING 10초 · WAIT_CHANGE 15초 (놓친 화면 전환 확인용) |
| `runner/` | `SessionRunner` | (2일차) 할 일을 포트 호출로 실행하고, 결과를 이벤트로 돌려 넣는다. **서버 호출은 여기서만** |

## 상태 10개 (설계서 6장)

| 상태 | 뜻 | 다음 |
|---|---|---|
| `IDLE` | 시작 대기 | "말하기" 버튼 → LISTENING |
| `LISTENING` | 음성 인식 중 | 인식 → PLANNING (화면이 없으면 SCANNING) / 무음 8초 · 말 없음 → IDLE (안내) / 그 밖의 인식 오류 → ERROR |
| `PLANNING` | 서버에 묻는 중 | TAP · SCROLL · WAIT · UNKNOWN → GUIDING / ASK_USER → ASKING / DONE → DONE / 실패 → ERROR / 그 사이 화면이 바뀜 → 다시 묻기 |
| `SCANNING` | 화면을 찾는 중 | 스냅샷 → PLANNING / 10초간 없음 → LOST |
| `GUIDING` | 하이라이트 + 안내 문장 읽는 중 | 다 읽음 → WAIT_CHANGE / 화면 바뀜 → 다시 묻기 / (도움 모드) 구슬 누름 → 음성 끊고 WAIT_CHANGE |
| `WAIT_CHANGE` | 어르신이 누르길 기다림 | 화면 바뀜 → 다시 묻기 / 15초 → 놓친 화면 전환이면(UNKNOWN 뒤 포함) 최신 화면으로 다시 묻기, 아니면 **조용히 기다림(같은 안내를 다시 읽지 않는다)** |
| `ASKING` | 되묻는 질문 읽는 중 | 다 읽음 → LISTENING (**다 읽은 뒤에야 듣기 시작 — 에코 방지**) / (도움 모드) 구슬 누름 → 음성 끊고 WAIT_CHANGE |
| `DONE` | 마무리 안내 읽는 중 | 다 읽음 → IDLE |
| `LOST` | "화면이 잘 보이게 비춰 주세요" 읽는 중 | 다 읽음 → SCANNING / 그 사이 화면 보임 → 바로 묻기 |
| `ERROR` | 오류 안내 읽는 중 | 다 읽음 → IDLE |

공통 규칙
- 종료 버튼은 **어떤 단계에서든** IDLE (듣기 · 말하기 · 하이라이트 · 서버 요청 모두 정리).
- **번호표(ticket)**: 기다리는 일을 시작할 때마다 번호가 1 늘고, 결과 이벤트는 그 번호를 달고 온다. 번호가 다르면 지난 일이라 무시한다 (예: 종료 뒤에 늦게 온 서버 응답).
- 하이라이트는 같은 화면의 새 스냅샷이 올 때마다 **버튼을 따라간다** (위치가 바뀌었을 때만 갱신).
- 화면 전환은 `screenChanges`뿐 아니라 **다른 screenId의 스냅샷**으로도 알아챈다.

## SessionRunner가 지킬 것 (2일차, P3 · P4도 참고)

| 할 일 (`SessionEffect`) | 실행 | 돌려 넣을 이벤트 |
|---|---|---|
| `StartListening(ticket)` | `SpeechInput.start()` 수집 | Final → `SpeechRecognized(ticket, text)` · Error → `SpeechFailed(ticket, error)` |
| `Speak(text, ticket)` | `SpeechOutput.speak(text)` | **끝까지 읽고 돌아오면** `SpeechFinished(ticket)`. 취소되면 보내지 않는다 |
| `RequestGuide(request, ticket)` | `GuideAgent.next(request)` | 성공 → `GuideReceived(ticket, response)` · 실패(재시도 후) → `GuideFailed(ticket)` |
| `StartTimer(ms, ticket)` | `ms` 뒤 | `TimedOut(ticket)`. 새 타이머는 이전 타이머를 대신한다 |
| `Highlight` · `ClearHighlight` | `HighlightSurface.setTarget(target / null)` | — (여러 번 와도 무해해야 함) |
| `StopListening` · `StopSpeaking` · `CancelPending` | 각 포트의 stop · 진행 중인 타이머 · 요청 취소 | — |

- 이벤트는 **한 번에 하나씩, 들어온 순서대로** `reduce` 한다.
- 화면 이벤트: `SnapshotSource.snapshots` → `SnapshotReceived`, `screenChanges` → `ScreenChanged`.
- 버튼: "말하기" → `StartPressed(새 UUID)`, 종료 → `StopPressed`.

## 진행 상황

- [x] 1일차: `SessionStateMachine` 상태 10개 · 타임아웃 · 번호표 + 전이 테스트 16개, `PositionPhraser` · `GuideTextComposer` · `TimeoutPolicy` + 테스트 5개
- [ ] 2일차: `SessionRunner` (코루틴 · `Clock` 주입 타임아웃 테스트) · 진행 중 "말하기" 버튼 동작 결정

## 테스트

```bash
./gradlew :domain:test
```

## 구현 현황 (이 PR)

- [x] `runner/SessionRunner`: 이벤트를 한 줄로 모아 한 번에 하나씩 `reduce` · 효과를 포트 호출로 실행 · 포트 예외는 이벤트로 변환 · 종료 시 정리. 테스트 9개(가짜 포트 + 실제 코루틴).

## 도움 모드 (앱 기본, 2026-11-14)
`SessionStateMachine(helpMode = true)`가 앱 기본이다 (`kl.helpMode`). 어르신이 물어볼 때만 서버에 묻는다.
- 화면이 바뀌어도 서버에 다시 묻지 않고 **하이라이트만 따라간다** (`helpFollow`).
- **안내 음성을 읽는 중(GUIDING · ASKING)에 말하기 구슬을 누르면** `StopSpeaking`하고, 끝까지 읽은 것과 같은 다음 상태(WAIT_CHANGE)로 간다. 노란 칸은 지우지 않고 듣기도 시작하지 않는다. 서버 답을 기다리는 중(PLANNING)에는 무시한다 (`interruptSpeech`).
- 안내를 다 읽은 뒤 15초가 지나도 **같은 문장을 다시 읽지 않는다.** 놓친 화면 전환이 의심될 때만 다시 묻는다 (`missedChange`).
- 서버가 사진에서 본 버튼 전체 칸은 글자 상자의 가로 · 세로 **2배까지만** 그린다 (`HighlightArea.BUTTON_MAX_RATIO`).
- 예전 방식(화면 전환마다 자동 안내)은 `helpMode = false`.
- 상태 머신 단위 테스트 94개 (`:domain`, 2026-11-14).
