# :overlay — 하이라이트 (P5)

눌러야 할 버튼 위에 **깜빡이는 노란 테두리**를 그린다. 앱의 "손가락 표시".
정규화 좌표를 화면 픽셀로 바꾸는 일은 **이 모듈만** 한다.
계약 전체: [docs/contract.md](../docs/contract.md) 1 · 2장

## 만들 것

| 포트 | 실구현 | Fake |
|---|---|---|
| `HighlightSurface` (그리기) | `ComposeOverlay` | `FakeOverlay` — 받은 대상을 로그로 출력 |
| `MotionSink` (트래킹 입력, 선택) | `ComposeOverlay` | — |

```
kr.kiosklens.overlay
├─ transform/  CoordinateMapper(interface) · CameraXCoordinateMapper
├─ tracking/   BoxSmoother  (MotionEstimator · LkMotionEstimator는 보류)
├─ style/      HighlightPainter(interface) · PulseBorderPainter   (MagnifyPainter는 보류)
└─ ComposeOverlay : HighlightSurface, MotionSink
```

## 반드시 지킬 것

- `setTarget()`은 **어느 스레드에서 불러도 안전** · `null`이면 숨김 · 같은 값을 또 불러도 무해 (깜빡임이 리셋되면 안 됨)
- 입력 `HighlightTarget.rect`는 **회전 보정된 분석 프레임 기준 정규화 좌표**. 프리뷰 화면 좌표로의 변환은 `ImageProxyTransformFactory` + `CoordinateTransform` (회전 · 크롭 · 미러 보정, 프리뷰 `FILL_CENTER` 크롭 보정 포함)
- 스타일 (설계서 10장): **두께 6dp 이상, 노란색 + 검은 외곽선**, 깜빡임 **1~2Hz (3Hz 초과 금지 — 광과민)**
- 스무딩: EMA α ≈ 0.3. 새 OCR 결과가 오면 150ms 동안 부드럽게 이동
- 추적이 1초 이상 실패하면 반투명으로 낮추고 재인식 요청 (4일차)
- 렌더 30fps 이상, 메인 스레드 점유 최소화

## 계약 테스트 (먼저 쓰기)

1. 여러 스레드에서 동시에 `setTarget()`을 불러도 예외 없이 마지막 값이 남는다
2. `setTarget(null)`이면 아무것도 그리지 않는다
3. 같은 값을 연속으로 넣어도 상태가 바뀌지 않는다 (멱등)

## 할 일 (5일 계획)

- **1일차**: CameraX 프리뷰 위에 **고정 `NormRect`를 정확히 그리는 PoC** (회전 · 크롭 확인) · `FakeOverlay` + `HighlightSurface` 계약 테스트 → 완료 기준: 고정 박스가 프리뷰 위 의도한 위치에 그려짐
- **2일차**: `CameraXCoordinateMapper` · `PulseBorderPainter` · `BoxSmoother` · 픽스처로 하이라이트 표시
- **3일차**: P2와 짝으로 하이라이트 위치 오차 확인 (목표: 버튼 크기의 20% 이내)

## P1과 맞출 것

- PoC는 이 모듈 안의 디버그용 Activity로 만들어도 된다 (`camera-view` 의존 추가). 2일차에 P1이 카메라 연결 부분을 `:app/camera`로 옮겨 재사용한다
- 분석 프레임 정보(크기 · 회전)와 `PreviewView`를 `ComposeOverlay`에 넘기는 방법은 P5가 정하고 P1과 맞춘다. **포트가 아니므로 계약 변경 없이** `:app`이 `ComposeOverlay`의 메서드를 직접 불러도 된다

## 막히기 쉬운 곳

- 분석(ImageAnalysis)과 프리뷰(Preview)는 해상도 · 종횡비가 다를 수 있다. `FILL_CENTER`면 프리뷰가 잘려 보이므로 크롭 보정이 꼭 필요
- 기기를 돌렸을 때(회전) 박스가 틀어지는지 1일차에 고정 테스트로 확인
- 라이브러리는 이 모듈의 `build.gradle.kts`에만 추가 (`:domain` · `:core-model`에는 불가)

## 참고

설계서 4.1 (좌표계) · 7.5 (좌표 변환 · 트래킹) · 10장 (하이라이트 스타일)
