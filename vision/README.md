# :vision — 화면 읽기 (P2)

카메라 프레임에서 키오스크 화면의 글자를 읽어 **`ScreenSnapshot`**으로 만든다. 앱의 "눈".
계약 전체: [docs/contract.md](../docs/contract.md) 1 · 2장

## 만들 것

| 포트 | 실구현 | Fake |
|---|---|---|
| `FrameSink` (프레임 받기) | `VisionScreenAnalyzer` | `FakeScreenAnalyzer` — JSON 픽스처를 순서대로 재생 |
| `SnapshotSource` (결과 내보내기) | `VisionScreenAnalyzer` (한 클래스가 둘 다 구현해도 됨) | `FakeScreenAnalyzer` |

패키지 (설계서 16.3)

```
kr.kiosklens.vision
├─ ocr/       OcrEngine(interface) · MlKitOcrEngine
├─ classify/  ElementClassifier(interface) · PriceClassifier · ButtonLikeClassifier
├─ filter/    TextFilter(interface) · CardNumberFilter · LowConfidenceFilter · TinyAreaFilter
├─ pipeline/  FrameSampler · ElementBuilder · ScreenDiffer
└─ VisionScreenAnalyzer : FrameSink, SnapshotSource
```

새 규칙은 기존 클래스에 `if`를 추가하지 말고 **새 클래스**로 붙인다 (예: 새 필터 = `TextFilter` 구현 하나 추가).

## 반드시 지킬 것

- `submit()`은 **즉시 복귀**, 예외를 던지지 않음. 밀리면 오래된 프레임을 버리고 **`close()`** (안 닫으면 카메라가 멈춘다)
- `snapshots`는 **`stableFrames >= 2`**인 것만 발행. 받는 쪽이 느려도 막히지 않게 최신만 유지
- `screenChanges`: Jaccard 유사도 < 0.6이 연속 2프레임이면 새 screenId 발행. 요소 3개 미만 프레임은 판정에서 제외 (설계서 7.2)
- 좌표는 **회전 보정된 분석 프레임 기준 정규화 0~1** (`NormRect`). 픽셀 좌표를 내보내지 않는다
- 요소 ID: `"e" + hash(정규화 텍스트 + 중심점 20×20 격자 셀)`, 충돌 시 `_2` (설계서 4.3). 프레임이 바뀌어도 같은 버튼은 같은 id
- **보안 필터**: 숫자만 13~19자리(카드 번호), 카드 유효기간 패턴은 요소에서 제거
- 길이: 요소 글자 80자 이하 (`UiElement.MAX_TEXT_LENGTH`), 요소 300개 이하 (`ScreenSnapshot.MAX_ELEMENTS`). 넘으면 서버가 400
- 이미지는 앱 밖으로 나가지 않는다

`CameraFrame`에서 이미지 꺼내기

```kotlin
override fun submit(frame: CameraFrame) {
    when (val img = frame.image) {
        is ImageProxy -> { /* InputImage.fromMediaImage(img.image!!, frame.rotationDegrees) */ }
        is Bitmap -> { /* 사진 입력 디버그 모드 */ }
    }
    // 다 쓰면 frame.close()
}
```

## 계약 테스트 (먼저 쓰기)

추상 클래스 하나를 Fake와 실구현이 둘 다 상속해서 통과해야 한다.

1. `submit()`은 블로킹 없이 즉시 돌아오고, 이상한 프레임을 넣어도 예외를 던지지 않는다
2. `snapshots`는 `stableFrames < 2`인 스냅샷을 내보내지 않는다
3. 버려진(밀린) 프레임은 `close()`가 불린다

## 할 일 (5일 계획)

- **1일차**: 키오스크 촬영 (화면마다 정면 1장 + 변형) · 라벨 스키마 (`/testdata`) · 손 픽스처 5~6개 화면 · `FakeScreenAnalyzer` + 계약 테스트 · ML Kit 정지 사진 벤치마크 (목표 버튼이 자주 누락되면 즉시 공유)
- **2일차**: 실프레임 OCR (3fps 샘플링 · 64×36 차분 스킵) · 필터 체인 · PRICE / BUTTON_LIKE 분류 · 요소 ID · 사진 → 픽스처 자동 생성 (사진 입력 디버그 모드) → P4 · P5에 전달
- **3일차**: P5와 짝으로 화면 전환 감지 튜닝 · 요소 ID 안정성 · 하이라이트 위치 오차 확인

## 바로 쓸 수 있는 것

- **손 픽스처 예시:** `server/testdata/snapshots/*.json` — 카페 키오스크 6화면이 이미 `ScreenSnapshot` 형식(계약 v1)으로 있다. 대상 키오스크 사진이 나오기 전까지 `FakeScreenAnalyzer`가 재생할 첫 데이터로 쓰면 된다.
- 계약 예시: `testdata/contract/request_*.json`의 `snapshot` 부분

## 막히기 쉬운 곳

- **JVM 단위 테스트에서 `org.json`은 동작하지 않는다** (android.jar의 빈 껍데기). 픽스처 JSON은 `kotlinx.serialization`으로 읽는 것을 추천
- `ImageProxy`를 쓰려면 이 모듈에 `androidx.camera:camera-core` 의존을 추가. ML Kit 한국어는 `com.google.mlkit:text-recognition-korean`
- 라이브러리는 이 모듈의 `build.gradle.kts`에만 추가 (`:domain` · `:core-model`에는 불가)

## 참고

설계서 4.3 (요소 ID) · 7.1 (샘플링 · 필터 · 분류) · 7.2 (전환 감지) · 11.1 (테스트 데이터)

## 구현 현황 (이 PR)

- [x] `OcrEngine` · `MlKitOcrEngine`(ImageProxy / Bitmap) · 필터 4종(`CardNumberFilter` · `LowConfidenceFilter` · `TinyAreaFilter` · `BlankFilter`) · 분류기(`PriceClassifier` · `ButtonLikeClassifier`)
- [x] `ElementBuilder`(요소 ID = FNV-1a 해시 + 20×20 셀, 충돌 `_2`) · `ScreenDiffer`(Jaccard 0.6, 연속 2프레임, 요소 3개 미만 제외) · `FrameSampler`(3fps + 똑같은 프레임 건너뛰기) · `ImageProxyThumbnail`(64×36)
- [x] `VisionScreenAnalyzer : FrameSink, SnapshotSource` — 밀린 프레임은 `close()`, 모든 프레임은 정확히 한 번 닫힘
- [x] `FakeScreenAnalyzer` + `SampleScreens`(카페 5화면) + 계약 테스트(Fake · 실구현 공통)
- 테스트 22개(JVM). **기기에서 확인 필요:** ML Kit 실제 인식률(버튼 누락), 3fps에서의 지연, 요소 ID가 실제 카메라 프레임에서 안정적인지
