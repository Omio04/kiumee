# :domain — 세션 두뇌 (P1)

어르신이 "말하기"를 누른 순간부터 안내가 끝날 때까지, **지금 무엇을 해야 하는지 정하는 곳**이다.
순수 Kotlin 모듈이라 Android · OCR · 네트워크를 전혀 모르고, 포트(`:core-model/port`)만 쓴다.

> 다른 파트는 이 모듈을 직접 고치지 않는다. 다만 **자기 포트가 언제 불리는지** 알면 개발이 쉬워서 상태 표를 아래에 둔다.

## 구조 (설계서 16.3 · 16.5)

| 위치 | 클래스 | 하는 일 |
|---|---|---|
| `session/` | `SessionStateMachine` | (지금 상태, 일어난 일) → (다음 상태, 해야 할 일). **순수 함수** |
| `session/` | `SessionState` · `SessionEvent` · `SessionEffect` | 상태 · 이벤트 · 할 일 정의 |
| `runner/` | `SessionRunner` | 상태머신이 정한 "할 일"을 포트 호출로 실행. **서버 호출은 여기서만** |
| `policy/` | `PositionPhraser` | 버튼 좌표 → "화면 오른쪽 아래" (가로 · 세로 3등분, 가운데는 "가운데") |
| `policy/` | `GuideTextComposer` | 최종 문장: "{위치} 깜빡이는 {guideText}를 누르세요" |
| `policy/` | `TimeoutPolicy` | 무음 8초 · SCANNING 10초 · WAIT_CHANGE 15초 |

## 상태 10개 (설계서 6장)

| 상태 | 뜻 | 다음 |
|---|---|---|
| `IDLE` | 시작 대기 | "말하기" 버튼 → LISTENING |
| `LISTENING` | 음성 인식 중 (`SpeechInput.start()`) | Final → PLANNING / 무음 8초 → IDLE (안내 음성) |
| `PLANNING` | 서버에 요청 (`GuideAgent.next()`) | 스냅샷 없음 → SCANNING / 응답 → GUIDING |
| `SCANNING` | 키오스크 화면 찾는 중 (`SnapshotSource.snapshots`) | 스냅샷 → PLANNING(재요청) / 10초간 없음 → LOST |
| `GUIDING` | 하이라이트(`HighlightSurface`) + 음성(`SpeechOutput`) | TAP · SCROLL → WAIT_CHANGE |
| `WAIT_CHANGE` | 어르신이 누르고 화면이 바뀌길 기다림 (`screenChanges`) | 화면 전환 → SCANNING / 15초 무변화 → 안내 다시 읽고 대기 |
| `ASKING` | 되묻는 중 | 질문 읽기 → LISTENING |
| `DONE` | 완료 | 마무리 안내 → IDLE |
| `LOST` | 화면 인식 실패 | "키오스크 화면이 잘 보이게 비춰 주세요" → SCANNING |
| `ERROR` | 네트워크 · 권한 오류 | 안내 후 IDLE |

공통 규칙
- "처음부터" · 종료 버튼을 누르면 **어떤 상태에서든** IDLE.
- **TTS 재생 중에는 STT를 시작하지 않는다** (에코 방지): `speak()`가 끝난 뒤에만 `start()`를 부른다.
- 서버 호출은 `SessionRunner`에서만.
- 시간은 `Clock`으로만 잰다 → 타임아웃 테스트를 결정적으로.

## 진행 상황

- [ ] 1일차: `SessionStateMachine` 정상 경로 뼈대 + 전이 테스트
- [ ] 2일차: 상태 10개 전체 · 타임아웃 · `SessionRunner` · `PositionPhraser` · `GuideTextComposer`

## 테스트

```bash
./gradlew :domain:test
```
