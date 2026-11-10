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
├─ tracking/   BoxSmoother  (MotionEstimator · LkMotionEstimator는 보류)
├─ style/      PulseCurve · PulseBorderPainter   (MagnifyPainter는 보류)
├─ HighlightHolder : HighlightSurface   (스레드 안전 · 멱등)
└─ ViewOverlay : HighlightSurface, MotionSink   (Compose 대신 일반 View)
```

## 반드시 지킬 것

- `setTarget()`은 **어느 스레드에서 불러도 안전** · `null`이면 숨김 · 같은 값을 또 불러도 무해 (깜빡임이 리셋되면 안 됨)
- 입력 `HighlightTarget.rect`는 **회전 보정된 분석 프레임 기준 정규화 좌표**. 프리뷰 화면 좌표로의 변환은 `FillCenterMapper`가 한다 (프리뷰 `FILL_CENTER` 크롭 보정 포함)
  - 전제: 프리뷰와 분석 프레임이 **같은 종횡비(4:3)** 라서 같은 시야를 본다 (`:app/camera/CameraController`가 둘 다 `RATIO_4_3`으로 설정). 이 설정을 바꾸면 위치가 어긋나니 함께 바꿀 것
  - 분석 프레임 크기(회전 보정 후)는 `CameraController` → `ViewOverlay.setAnalysisSize(w, h)`로 들어온다 (이미 연결됨)
  - 설계서 7.5의 `ImageProxyTransformFactory` + `CoordinateTransform`은 쓰지 않는다. 기기 확인에서 오차가 크면 그때 바꾼다
- 스타일 (설계서 10장): **두께 6dp 이상, 노란색 + 검은 외곽선**, 깜빡임 **1~2Hz (3Hz 초과 금지 — 광과민)**
- 스무딩: EMA α ≈ 0.3. 새 OCR 결과가 오면 150ms 동안 부드럽게 이동
- 추적이 1초 이상 실패하면 반투명으로 낮추고 재인식 요청 (4일차)
- 렌더 30fps 이상, 메인 스레드 점유 최소화

## 계약 테스트 (`OverlayTest.kt`의 `HighlightSurfaceContractSpec`)

실구현(`HighlightHolder`)이 상속해서 통과한다.

1. 처음에는 아무것도 보이지 않는다
2. 여러 스레드에서 동시에 `setTarget()`을 불러도 예외 없이 마지막 값이 남고, **화면에 그리라고 마지막으로 알린 값과 실제 상태가 같다**
3. `setTarget(null)`이면 숨긴다 · `null`을 두 번 넣어도 한 번만 숨긴다
4. 새 대상을 넣으면 그 대상으로 바뀐다
5. 같은 값(내용이 같은 새 객체 포함)을 또 넣으면 **상태도 그대로, 다시 그리지도 않는다** (깜빡임이 처음부터 다시 시작되면 안 됨)

## 할 일

- [x] 좌표 변환 · 하이라이트 그리기 · 스무딩 · 깜빡임 (JJ 구현, 2026-11-10 적용)
- [ ] **하이라이트 위치 확인 (PoC)**: 카메라 프리뷰 위에 고정 `NormRect`(네 모서리 + 가운데)를 그려 의도한 위치에 오는지 → 에뮬레이터(웹캠) → 실기기. 2일차 에뮬레이터 확인(P1)에서는 하이라이트까지 가지 못했다
- [ ] 기기 회전 시 박스 · 렌더 30fps 확인
- [ ] P2와 짝으로 위치 오차 측정 (목표: 버튼 크기의 20% 이내)
- [ ] 추적이 1초 이상 실패하면 반투명 (4일차) — 판단 주체(`:domain`이 판단하고 오버레이는 흐리게만)는 P1과 맞출 것

## P1과 맞출 것

- 화면 구성: `:app`의 `MainActivity`가 `PreviewView`(`FILL_CENTER`)와 `ViewOverlay`를 같은 크기(`match_parent`)로 겹쳐 둔다. 둘의 크기 · 위치가 달라지면 좌표가 어긋난다
- 분석 프레임 크기는 포트가 아니라 `ViewOverlay.setAnalysisSize()`로 직접 넘긴다 (계약 변경 없음)

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
- [x] 계약 테스트 보강 (P5, 2026-11-10): 처음 상태 · 새 대상 · 같은 값이면 다시 그리지 않음 · null 두 번 · 동시 호출 뒤 알린 값 = 상태. `HighlightHolder.setTarget`을 `@Synchronized`로 (동시 호출 때 알림 순서가 뒤바뀌는 문제). 테스트 18개
- **기기에서 확인 필요:** 실제 프리뷰 위 위치 오차(목표: 버튼 크기의 20% 이내), 기기 회전 시 박스, 30fps 렌더
