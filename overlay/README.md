# :overlay — 하이라이트 (P5)

눌러야 할 버튼 위에 **깜빡이는 노란 테두리**를 그린다. 앱의 "손가락 표시".
정규화 좌표를 화면 픽셀로 바꾸는 일은 **이 모듈만** 한다.
계약 전체: [docs/contract.md](../docs/contract.md) 1 · 2장

## 만들 것

| 포트 | 실구현 |
|---|---|
| `HighlightSurface` (그리기) | `ViewOverlay` (상태는 `HighlightHolder`) |
| `MotionSink` (트래킹 입력, 선택) | `ViewOverlay` |

Fake는 두지 않는다 (2일차 결정, [decisions.md](../docs/decisions.md) 6장 U3). 계약 테스트는 실제 `ViewOverlay`가 쓰는 `HighlightHolder`로 돌린다.

```
kr.kiosklens.overlay
├─ transform/  CoordinateMapper(interface) · FillCenterMapper · FitCenterMapper · StretchMapper
│              VisibleArea · ScreenInsets   (하이라이트가 화면에 보이는지 판단)
├─ tracking/   BoxSmoother           (새 위치로 부드럽게 · 흔들림만큼 옮기기)
│              LumaGrid · GridMotionEstimator · CameraMotionTracker   (카메라 흔들림 추정, LK 광학 흐름 대신)
│              TrackingHealth        (위치를 놓치면 숨기기)
├─ style/      PulseCurve · PulseBorderPainter   (MagnifyPainter는 보류)
├─ HighlightHolder : HighlightSurface   (스레드 안전 · 멱등)
├─ VisibilityTracker                    (보임/안 보임이 바뀔 때만 알림)
├─ RenderStats                          (그리기 속도 재기, 기준 30fps)
├─ ViewOverlay : HighlightSurface, MotionSink   (Compose 대신 일반 View)
└─ debug/ OverlayCalibrationActivity    (src/debug — 디버그 빌드 전용 위치 확인 화면)
```

## 반드시 지킬 것

- `setTarget()`은 **어느 스레드에서 불러도 안전** · `null`이면 숨김 · 같은 값을 또 불러도 무해 (깜빡임이 리셋되면 안 됨)
- 입력 `HighlightTarget.rect`는 **회전 보정된 분석 프레임 기준 정규화 좌표**. 프리뷰 화면 좌표로의 변환은 `FillCenterMapper`가 한다 (프리뷰 `FILL_CENTER` 크롭 보정 포함)
  - 전제: 프리뷰와 분석 프레임이 **같은 종횡비(4:3)** 라서 같은 시야를 본다 (`:app/camera/CameraController`: 미리보기 `RATIO_4_3`, 분석은 4:3 · 1280×960 목표 — 2026-11-11). 이 설정을 바꾸면 위치가 어긋나니 함께 바꿀 것
  - 분석 프레임 크기(회전 보정 후)는 `CameraController` → `ViewOverlay.setAnalysisSize(w, h)`로 들어온다 (이미 연결됨)
  - 설계서 7.5의 `ImageProxyTransformFactory` + `CoordinateTransform`은 쓰지 않는다. 기기 확인에서 오차가 크면 그때 바꾼다
- 스타일 (설계서 10장): **두께 6dp 이상, 노란색 + 검은 외곽선**, 깜빡임 **1~2Hz (3Hz 초과 금지 — 광과민)**
- 스무딩: EMA α ≈ 0.3. 새 OCR 결과가 오면 150ms 동안 부드럽게 이동
- 추적이 1초 이상 실패하면 반투명으로 낮추고 재인식 요청 (4일차 계획) → 시험 뒤 "놓치면 바로 숨기고 새 위치가 오면 다시"로 바꿈. 아래 "흔들림 따라가기 · 위치를 놓치면 숨기기" 참고
- 렌더 30fps 이상, 메인 스레드 점유 최소화 (움직임 계산은 분석 스레드에서)

## 하이라이트가 안 보일 때 알리는 신호

채우기(`FILL_CENTER`) 미리보기에서는 분석 프레임 좌우가 화면 밖으로 잘리고(1080×2400 기준 좌우 20%씩, `VisibleAreaTest`),
아래 버튼판이 화면 아래쪽을 가린다. 그 자리의 버튼을 가리키면 노란 테두리가 어르신 눈에 안 보인다.

- `ViewOverlay.setObstructedInsets(left, top, right, bottom)`: 가려진 두께(px). 예: 아래 버튼판 높이
- `ViewOverlay.onTargetVisibilityChanged = { visible -> }`: 메인 스레드에서 **바뀔 때만** 불림. `false` = 테두리 중심이 화면 밖이나 가려진 곳, `true` = 안 보임이 끝남(다시 보임 · 보이는 다른 버튼으로 바뀜 · 하이라이트가 없어짐)
- 같은 버튼이 계속 안 보이면 다시 부르지 않는다. 다른 버튼으로 바뀌면 처음부터 판단한다(새 버튼도 안 보이면 `false`를 다시 알림) (`VisibilityTracker`)
- **마지막으로 받은 값이 지금 상태다** — 받는 쪽은 상태로 써도 된다 (2026-11-12 수정: 전에는 안 보이던 버튼이 보이는 다른 버튼으로 바뀌거나 하이라이트가 없어지면 `true`를 알리지 않았다. P1 지적)
- 음성 안내("휴대폰을 조금 뒤로 빼 주세요")는 이 신호를 받은 `:app` → `:domain`이 한다 (P1과 맞출 것)

## 흔들림 따라가기 · 위치를 놓치면 숨기기 (P5)

OCR은 1초에 몇 번만 돈다(같은 화면이면 2초에 한 번). 그 사이 휴대폰이 흔들리면 노란 테두리가 버튼에서 벗어난다.
그래서 **카메라 프레임마다 화면 전체가 움직인 만큼** 테두리를 옮기고, 다음 OCR 결과가 오면 그 위치로 부드럽게 맞춘다.

- `CameraMotionTracker.onLumaFrame(...)`: 프레임의 밝기(Y) 평면을 96×128 격자로 줄여(`LumaGrid`) 직전 프레임과 비교 → `FrameMotion`(dx · dy · 신뢰도)
  - `GridMotionEstimator`: 거친 격자 → 촘촘한 격자 순으로 평행 이동을 찾고 칸보다 작은 단위까지 맞춘다. 원래 격자 기준 ±15칸(프레임 폭의 약 15%)까지
  - 확대 · 축소 · 회전은 보지 않는다. 무늬가 없거나(흰 벽 · 손으로 가림) 화면이 통째로 바뀌거나 찾는 범위를 넘게 움직이면 신뢰도가 낮다
  - 앞 프레임과 거의 같으면(가만히 있는데 초점만 잠깐 흐림) '움직임 없음'을 믿을 만하게 낸다 (2026-11-13)
  - 프레임 하나(1280×960)에 PC에서 약 0.5ms. 휴대폰은 몇 배 느릴 수 있어 기기에서 확인 필요
- `ViewOverlay.onFrameMotion(motion)`(MotionSink): 신뢰도 0.5 이상이면 테두리와 목표 위치를 그만큼 옮긴다. 안 보임 판단도 옮겨진 자리로 한다
- **위치를 놓치면 숨기기** (`TrackingHealth`): 테두리가 떠 있는데 믿을 수 없는 움직임이 **한 번이라도** 오면 그사이 얼마나 움직였는지 모르므로 **바로 숨기고** `onTrackingLost`를 한 번 부른다(재인식 요청용). **새 위치(OCR)가 오면** 그 자리에 다시 짚으며 나타난다. 놓친 동안 오는 움직임으로는 옮기지 않는다
  - 2026-11-13 팀 시험: 예전 규칙("1초 넘게 못 따라가면 흐리게")은 크게 움직여 놓친 뒤 다시 가만히 있으면 엉뚱한 자리를 또렷하게 계속 가리켰다. `:domain`은 위치가 **바뀌었을 때만** 다시 보내므로(`follow`), 놓친 뒤 같은 자리가 다시 오지 않으면 바로잡을 길이 없었다
  - 같은 위치가 다시 오면(지금 `:domain`은 안 보냄) 놓친 상태일 때만 그 자리로 다시 맞춘다 (`ViewOverlay.setTarget`)
  - 움직임을 아예 넣지 않으면 이 동작은 꺼진 것과 같다
- 계약(포트 · `:core-model`) 변경 없음: `MotionSink` · `FrameMotion`은 계약 v1에 이미 있다

## 그리기 속도 재기 (합격 기준 30fps 이상, 측정 P5)

- `ViewOverlay.renderStats`: 최근 1초의 fps · 가장 길었던 간격(ms) (`RenderStats`). 하이라이트가 없으면 쉬었다가 다시 잰다
- `ViewOverlay.onRenderStats = { s -> }`: 그리는 동안 약 1초마다 (KL_PERF 로그용)
- 위치 확인 화면 위쪽 넷째 줄에 바로 보인다

## 위치 확인 화면 (PoC, 디버그 빌드 전용)

`OverlayCalibrationActivity` — 음성 · 서버 · OCR 없이 좌표 변환이 맞는지 눈으로 확인한다.

- 고정 노란 테두리 5개(네 모서리 + 가운데), 분석 프레임 사진을 같은 변환으로 반투명하게 겹쳐 그림 → **계산이 맞으면 미리보기와 한 겹, 틀리면 두 겹**
- 화면에 보이는 분석 프레임 범위, 채우기/맞추기 전환, 가짜 버튼판, 가운데 테두리 보임/안 보임 표시
- **따라가기** 버튼: 켜면 카메라 움직임을 테두리에 넣는다 → 휴대폰을 흔들어도 테두리가 처음 겹쳐 있던 물건에 붙어 다녀야 한다. 카메라를 손으로 가리면 1초 뒤 흐려져야 한다. 끄면 원래 고정 위치로
- 위쪽 넷째 줄: 그리기 fps(가장 느린 간격) · 움직임(dx, dy, 신뢰도) · 추적 상태
- 실행: `adb shell am start -n kr.kiosklens.app/kr.kiosklens.overlay.debug.OverlayCalibrationActivity`
  (Android Studio: Run 구성의 Launch를 `Specified Activity`로 바꿔도 된다)

## 계약 테스트 (`OverlayTest.kt`의 `HighlightSurfaceContractSpec`)

실구현(`HighlightHolder`)이 상속해서 통과한다.

1. 처음에는 아무것도 보이지 않는다
2. 여러 스레드에서 동시에 `setTarget()`을 불러도 예외 없이 마지막 값이 남고, **화면에 그리라고 마지막으로 알린 값과 실제 상태가 같다**
3. `setTarget(null)`이면 숨긴다 · `null`을 두 번 넣어도 한 번만 숨긴다
4. 새 대상을 넣으면 그 대상으로 바뀐다
5. 같은 값(내용이 같은 새 객체 포함)을 또 넣으면 **상태도 그대로, 다시 그리지도 않는다** (깜빡임이 처음부터 다시 시작되면 안 됨)

## 할 일

- [x] 좌표 변환 · 하이라이트 그리기 · 스무딩 · 깜빡임 (JJ 구현, 2026-11-10 적용)
- [x] 계약 테스트 보강 · 동시 호출 알림 순서 수정 (P5)
- [x] 위치 확인 화면(PoC) 코드 · 화면 잘림 테스트 (P5)
- [x] 하이라이트가 안 보일 때 알리는 신호 — 오버레이 쪽 (P5)
- [ ] **PoC를 에뮬레이터 → 실기기에서 실행**: 필름이 한 겹으로 겹치는지, 보임/안 보임 표시가 바뀌는지. 2일차 에뮬레이터 확인(P1)에서는 하이라이트까지 가지 못했다
- [x] 그리기 속도 재는 장치 (`RenderStats`, P5 2026-11-12) — [ ] **기기에서 30fps 확인** (위치 확인 화면 넷째 줄)
- [ ] P2와 짝으로 위치 오차 측정 (목표: 버튼 크기의 20% 이내)
- [x] 흔들림 따라가기 · 추적 1초 실패 → 흐리게 — 오버레이 쪽 (P5 2026-11-12, 계약 변경 없이 오버레이가 판단)
  - [ ] `:app` 연결 (P1, 아래 "P1과 맞출 것") · [ ] 기기에서 흔들어 보기 (위치 확인 화면 "따라가기")
  - `:domain`이 버튼을 못 찾으면 숨기는 동작(decisions D6)과는 따로 돈다: 그쪽은 OCR 기준, 이쪽은 프레임 사이 흔들림 기준

## P1과 맞출 것

- 화면 구성: `:app`의 `MainActivity`가 `PreviewView`(`FILL_CENTER`)와 `ViewOverlay`를 같은 크기(`match_parent`)로 겹쳐 둔다. 둘의 크기 · 위치가 달라지면 좌표가 어긋난다
- 분석 프레임 크기는 포트가 아니라 `ViewOverlay.setAnalysisSize()`로 직접 넘긴다 (계약 변경 없음)
- **안 보임 신호 연결**: `MainActivity`에서 버튼판 높이를 `setObstructedInsets(0, 0, 0, panel.height)`로 넣고, `onTargetVisibilityChanged`가 `false`면 runner에 이벤트를 넘겨 `:domain`이 안내 중(GUIDING · WAIT_CHANGE)일 때만 "휴대폰을 조금 뒤로 빼 주세요"를 읽게 (5~10초 간격 제한 권장)
- **채우기 / 맞추기 결정**: 실기기에서 어르신이 자연스럽게 비출 때 가장자리 · 아래쪽 버튼이 실제로 가려지는지 보고, 채우기 유지 또는 맞추기(`FIT_CENTER` + 버튼판 위쪽에만 미리보기, 오버레이는 `FitCenterMapper`)를 정한다
- **흔들림 따라가기 연결** (`:app`, 몇 줄): `CameraController` 분석 콜백에서 `sink.submit(...)` **전에** 밝기 평면을 읽어 넘긴다 (넘긴 뒤에는 ImageProxy가 닫혔을 수 있음)
  ```kotlin
  // CameraController: private val motion = CameraMotionTracker() · 생성자에 onMotion: (FrameMotion) -> Unit 추가
  val y = proxy.planes[0]
  motion.onLumaFrame(frameId, y.buffer, y.rowStride, y.pixelStride, proxy.width, proxy.height, rot)?.let(onMotion)
  sink.submit(ImageProxyFrame(...))
  // MainActivity: onMotion = overlay::onFrameMotion   (어느 스레드든 안전)
  ```
- **재인식 요청**: `overlay.onTrackingLost = { ... }`에서 `:vision`에 바로 다시 읽기를 요청할지 (P2와 함께). 연결하지 않아도 테두리는 흐려진다
- **KL_PERF 로그**: `overlay.onRenderStats = { s -> logger.d("KL_PERF", "overlay fps=${s.fps} worst=${s.worstMs}ms") }`

## 막히기 쉬운 곳

- 분석(ImageAnalysis)과 프리뷰(Preview)는 해상도 · 종횡비가 다를 수 있다. `FILL_CENTER`면 프리뷰가 잘려 보이므로 크롭 보정이 꼭 필요
- 기기를 돌렸을 때(회전) 박스가 틀어지는지 1일차에 고정 테스트로 확인
- 라이브러리는 이 모듈의 `build.gradle.kts`에만 추가 (`:domain` · `:core-model`에는 불가)

## 참고

설계서 4.1 (좌표계) · 7.5 (좌표 변환 · 트래킹) · 10장 (하이라이트 스타일)

## 구현 현황 (이 PR)

- [x] `ViewOverlay`(**Compose 대신 일반 View** — 컴파일러 플러그인 없이 쓰려고. 포트 계약은 같다) · `HighlightHolder`(스레드 안전 · 멱등)
- [x] `FillCenterMapper` · `FitCenterMapper` · `StretchMapper`(좌표 변환) · `BoxSmoother`(EMA α=0.3, 시간 보정) · `PulseCurve`(1.5Hz, 최소 밝기 유지) · `PulseBorderPainter`(6dp 이상 노랑 + 검정 외곽)
- [x] 테스트 14개(JVM). `FakeOverlay`와 그 계약 테스트는 2026-11-10에 지움 (Fake 없이 실구현으로)
- [x] 계약 테스트 보강 (P5, 2026-11-10): 처음 상태 · 새 대상 · 같은 값이면 다시 그리지 않음 · null 두 번 · 동시 호출 뒤 알린 값 = 상태. `HighlightHolder.setTarget`을 `@Synchronized`로 (동시 호출 때 알림 순서가 뒤바뀌는 문제)
- [x] 위치 확인 화면(PoC) · 화면 잘림 테스트 · 안 보임 신호 (P5, 2026-11-10). 테스트 31개(JVM)
- [x] 결정적 순서 테스트 `HighlightHolderOrderTest` (P1, 2026-11-10): 위 동시 호출 확인은 `@Synchronized`를 빼도 15번 모두 통과해서 버그를 못 잡았다. A를 알리는 도중에 B를 넣어 매번 잡는다. 테스트 32개(JVM)
- [x] 인식 표시 `RecognitionView` · `RecognitionLayout` (P1, 2026-11-10): 카메라가 인식한 글자 · 버튼을 미리보기 위에 테두리 + 글자로 그린다(버튼 하늘색 · 글자 흰색, 카메라를 돌려 인식 상태가 바뀌면 바로 지움 · 안전장치 3.5초 — `:vision`이 그대로인 화면은 2초마다 다시 읽기 때문). 노란 하이라이트와 같은 `FillCenterMapper`를 써서 같은 자리에 그려진다. `:app`이 디버그 빌드에서만 붙인다. 테스트 35개(JVM)
- [x] 안 보임 신호 빈틈 수정 (P5, 2026-11-12): "안 보임"을 알린 뒤 보이는 다른 버튼으로 바뀌거나 하이라이트가 없어지면 `true`를 알린다. 받은 값을 차례로 따라가면 늘 지금 상태와 같은지 확인하는 테스트 포함. 테스트 39개(JVM)
- [x] 4일차 분량 미리 (P5, 2026-11-12): 흔들림 따라가기(`CameraMotionTracker` · `GridMotionEstimator` · `LumaGrid`) · 추적 1초 실패 → 흐리게(`TrackingHealth`) · 그리기 속도 재기(`RenderStats`) · 위치 확인 화면 "따라가기" 버튼과 넷째 줄. 테스트 64개(JVM)
- [x] 시험 반영 (P5, 2026-11-13): 크게 움직여 놓치면 엉뚱한 자리를 계속 가리키던 문제 → 놓치면 숨기고 새 위치가 오면 다시 짚기. 가만히 있는데 흐리기만 하면 놓치지 않게. 테스트 68개(JVM)
- **기기에서 확인 필요:** PoC 실행, 실제 프리뷰 위 위치 오차(목표: 버튼 크기의 20% 이내), 30fps 렌더, 흔들어서 따라가는지 · 가리면 흐려지는지, 움직임 계산 시간
