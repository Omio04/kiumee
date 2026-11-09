# :core-model — 공용 데이터 · 포트 (P1)

**모든 모듈이 이 규격에 맞춰 개발한다.** 다른 파트 코드를 기다리지 않고 동시에 개발할 수 있는 이유가 이 모듈이다.

- 순수 Kotlin 모듈 (`kotlin("jvm")`). Android SDK를 참조할 수 없다 → `import android...`를 쓰면 컴파일 실패
- 외부 라이브러리는 `kotlinx-coroutines-core`만 (포트가 `Flow`를 써서)
- 자세한 뜻과 JSON 형식: **[docs/contract.md](../docs/contract.md)**

## 안에 있는 것

```
kr.kiosklens.core
├─ model/
│  ├─ Geometry.kt      NormRect(정규화 좌표) · FrameMotion
│  ├─ Screen.kt        ElementKind · UiElement · ScreenSnapshot
│  ├─ CameraFrame.kt   CameraFrame (image = ImageProxy 또는 Bitmap)
│  ├─ Guide.kt         GuideRequest · GuideResponse · GuideAction(6종) · ActionType · HistoryItem · ScrollDir
│  ├─ Highlight.kt     HighlightTarget · HighlightStyle
│  └─ Speech.kt        SttEvent · SttError
└─ port/
   └─ Ports.kt         FrameSink · SnapshotSource · SpeechInput · SpeechOutput · GuideAgent
                       HighlightSurface · MotionSink · Clock · Logger
```

## 쓰는 법

각 모듈의 `build.gradle.kts`에 이미 `implementation(project(":domain"))`가 있고, `:domain`이 `:core-model`을 `api`로 내보내므로 바로 import 하면 된다.

```kotlin
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.core.port.SnapshotSource

class VisionScreenAnalyzer(/* 생성자 주입 */) : FrameSink, SnapshotSource { ... }
```

자주 쓰는 도우미

| 이름 | 쓰임 |
|---|---|
| `NormRect.centerX` · `centerY` · `isValid` | 중심점, 범위 · 넓이 검사 |
| `ScreenSnapshot.findElement(id)` | TAP 응답의 `targetId`로 요소 찾기 |
| `GuideAction.type` | 행동 종류 (`ActionType.TAP` 등) |
| `HistoryItem.of(screenId, action)` | 이력 한 건 만들기 (기록 규칙이 여기 한 곳에 있음) |
| `UiElement.MAX_TEXT_LENGTH` · `ScreenSnapshot.MAX_ELEMENTS` · `GuideRequest.MAX_*` | 서버가 400으로 거절하는 길이 기준 |

## 고칠 때 규칙

- 이 모듈의 변경은 **전원 승인 PR**로만 한다.
- 같은 PR에서 함께 고칠 것: `server/app/models.py` · `testdata/contract/*.json` · `docs/contract.md` · 영향받는 Fake와 계약 테스트.
- 필드를 늘리고 싶으면 먼저 "꼭 계약이어야 하나, 내 모듈 안에서 끝낼 수 있나"를 생각한다. 대부분은 모듈 안에서 끝난다.

## 테스트

```bash
./gradlew :core-model:test
```
