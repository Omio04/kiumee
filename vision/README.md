# P2 비전 모듈

계약 정본: [docs/contract.md](../docs/contract.md) (계약 v1).
2026-11-10 P1이 P2 브랜치(`5641769`)의 `vision/`을 main 통합 브랜치로 옮겼다 (작성자 yjb578로 커밋, [decisions.md](../docs/decisions.md) 6장 U2).

메가커피 10장 예시를 반영한 개선·사용자 선택 흐름·담당 간 연결 사항:
[메가커피 검증 및 인계](docs/mega-coffee-handoff.md).
가짜 키오스크 관측을 반영한 전환·닫기 검출 개선과 통합 검증: [키오스크 인식 개선](docs/kiosk-recognition.md).
흐림·손떨림 화면의 실제 Android OCR 측정 및 자동 안내 검증: [휴대 촬영 시뮬레이션](docs/handheld-simulation.md).
맛·샷·매장/포장·일회용품·결제수단은 사용자가 고른다. 예시의 '연하게'를 기본값으로 정하지 않는다.
적립 화면에서는 검증된 닫기 아이콘을 후보로 전달한다.

## 공용 계약과 연결

- 실제 `:core-model`의 `CameraFrame`, `UiElement`, `ScreenSnapshot`, `FrameSink`, `SnapshotSource`, `Clock`, `Logger`만 쓴다. **계약 타입은 `:core-model` 한 곳에만 있다** — 단독 빌드용 복사본(`src/standalone`)은 통합하면서 지웠다.
- 옛 데모와의 호환을 위한 타입 별칭(`VisionModels.kt`)과 `ScreenAnalyzer` 진입점은 남아 있다. 팀 앱은 `createVisionScreenAnalyzer()`로 만든 `VisionScreenAnalyzer`를 포트로 주입한다 (`app/.../di/GraphFactory.kt`).
- 빌드는 저장소 규칙을 따른다: AGP 9 내장 Kotlin · compileSdk 37 · 라이브러리 버전은 `gradle/libs.versions.toml`.

## 동작

1. `submit(CameraFrame)`은 이미지·OCR에 접근하지 않고 용량 1의 큐에 넣고 복귀한다. OCR 1장과 대기 1장만 유지하며 오래된 대기 프레임은 즉시 반환한다.
2. 순차 워커가 약 3fps로 검사한다. 64×36 밝기 평균 차이가 4 미만이면 생략하고 2초마다 재인식한다. 회전 변화·화면 확인 중에는 차분 생략을 해제한다.
3. `MlKitOcrEngine`은 한국어 ML Kit로 줄 단위 글자와 박스를 읽는다. 회전을 반영한 분석 이미지 기준 0~1 좌표를 사용한다.
4. 필터 체인은 잘못된 좌표, 작은 영역, 낮은 신뢰도, 기호뿐인 줄, 긴 카드 번호, MM/YY·MM/YYYY 형태 유효기간을 제거한다. 보안 검사 후 80자·300개 제한을 적용한다.
5. 가격 분류를 먼저 적용하고 짧은 글자·동작 문구를 버튼 후보로 추정한다. 새 규칙은 `TextFilter` 또는 `ElementClassifier` 구현으로 추가한다.
6. 화면 번호(`screenId`)는 **최근 12프레임 중 60% 이상에서 읽힌 글자(지속 글자)** 를 기준으로 정한다. 지금 프레임에 그 글자가 절반 이상 보이면(포함 비율 `STAY` 0.5) 같은 화면이고, 기준에 없는 글자(화면과 같이 찍힌 키보드, 프레임마다 다르게 깨지는 긴 줄)는 점수를 깎지 않는다. 글자 비교(`sameText`)는 짧은 글자는 오타 1~2자, 12자 이상의 긴 글자는 길이의 35%까지 허용한다. 위치 변화는 따로 본다. 새 화면의 두 관측 간 확정 기준(Jaccard)은 0.6이다. 제목·행동 문구로 확인된 화면 종류나 실제 선택 요약이 바뀌어도 두 번 확인한다. 3개 미만 요소·실패 프레임은 안정 확인을 끊는다. 전환 후보 뒤 기존 화면으로 돌아와도 두 번 확인한다.
7. 요소 ID는 정규화 텍스트와 중심점 20×20 셀의 결정적 해시로 만든다. 충돌 시 `_2` 등을 붙인다. 같은 글자의 가까운 기존 요소를 먼저 찾아 경계 흔들림에도 ID를 유지한다. 작은 좌표 흔들림은 EMA로 완화하고 누락된 글자는 한 OCR 주기만 보존한다.
8. `ScreenSnapshot`에 실제 입력 `frameId`, `timestampMs`, `stableFrames >= 2`를 담는다. 최신 스냅샷 한 개를 재생하며 느린 구독자가 OCR을 막지 않는다. 첫 화면 확정 뒤 화면이 바뀌면 `screenChanges`를 발행한다.

`:vision`은 이미지를 네트워크로 전송하지 않는다 (질문할 때 보내는 사진 한 장은 `:app`이 따로 보낸다). 로그는 `KL_PERF`에 처리 시간·발행 여부를, `KL_TRACK`에 화면 번호가 바뀔 후보가 된 이유(유사도 · 나타나고 사라진 글자 · 이미지 차이)를 기록한다.
ML Kit의 취소 불가능한 작업은 완료까지 기다린 뒤 입력을 반환하고 엔진을 닫는다.
`close()`는 종료를 요청하고, 완전한 자원 반환 확인은 `awaitClosed()`로 기다릴 수 있다.

## 팀 앱 조립

```kotlin
val analyzer = createVisionScreenAnalyzer(clock = monotonicClock, logger = appLogger)
val frameSink: kr.kiosklens.core.port.FrameSink = analyzer
val source: kr.kiosklens.core.port.SnapshotSource = analyzer
// P1의 CameraFrame 어댑터가 원본 frameId·timestampMs·rotationDegrees를 전달한다.
// source.snapshots.collect { snapshot -> ... }
// 종료: analyzer.close(); analyzer.awaitClosed()
```

샘플링·성능 측정용 Clock은 단조 증가 밀리초를 제공해야 한다. 스냅샷 timestampMs는
입력 CameraFrame의 값을 그대로 사용한다. 기본 Android 조립기는 elapsedRealtime을 사용한다.
엔진·필터·분류기·dispatcher·signature 함수를 주입하여 JVM에서도 검증할 수 있다.

## 사진 · JSON

- `PhotoAnalyzer`는 같은 사진에 OCR을 두 번 실행해 안정 결과만 반환한다. 읽을 수 있는 글자가 없거나 두 결과가 불안정하면 실패한다. 사진 프리뷰의 Bitmap은 재활용하지 않는다.
- `FakeScreenAnalyzer`는 2일차 결정(Fake 없이 실구현으로)에 따라 지웠다 ([decisions.md](../docs/decisions.md) 6장 U3).
- `SnapshotJson`은 Android `org.json` 대신 kotlinx.serialization을 사용하여 JVM 테스트에서도 동작한다. 프로덕션 HTTP 직렬화는 P4의 `:agent`가 담당한다.
- 옛 손 픽스처를 읽을 때만 누락된 frameId/timestampMs는 0, stableFrames는 2로 보완한다. 실시간 결과는 이 기본값을 사용하지 않는다.
- `SnapshotStream.value`는 기존 데모에서 최신 값을 조회하는 편의 속성이다. 포트의 Flow에서는 null을 발행하지 않는다.

## 실행과 검증

```bash
./gradlew :vision:testDebugUnitTest
```

```bash
./gradlew :app:installDebug
```

Windows에서 JDK · SDK를 따로 준비해야 할 때만 (`vision/.local`에 설치, 시스템 환경변수는 바꾸지 않음):

```powershell
. ./vision/tools/prepare-build.ps1 -InstallSdk
./vision/tools/verify.ps1
```

테스트 보고서: `vision/build/reports/tests/testDebugUnitTest/index.html`.
APK: `app/build/outputs/apk/debug/app-debug.apk` (팀 앱, 실구현만).

계약 테스트(`FrameSinkContractTest`)는 실제 파이프라인(`RealFrameSinkContractTest`)으로 돌린다.
OCR 엔진은 주입한 테스트 엔진이다. ML Kit 기기 인식 자체를 JVM 테스트로 검증한 것은 아니다.
스냅샷 왕복 변환은 저장소 루트 `testdata/contract`의 요청 JSON 3개를 읽어 검사한다 (서버 · `:agent`와 같은 정답지, 복사본 없음).

실기기에서 별도로 측정할 것:

- 라벨 사진 30장 이상에서 목표 글자 재현율 (목표 90% 이상).
- `adb logcat -s KL_PERF`에서 OCR 처리 시간 (목표 500ms 이하).
- 첫 화면·전환 확정 지연은 두 관측을 기다리는 시간이 있으므로 별도로 측정.
- 회전·각도·반사·가림에서 박스 위치와 ID 유지. 버튼 후보 박스는 실제 버튼 전체가 아닌 OCR 글자 영역이다.
- 팀 저장소(AGP 9.5 · SDK 37)에서 테스트 83개(1개 건너뜀) · 앱 빌드 통과를 확인했다 (2026-11-14; 40개 → 83개, 잡음 회귀 시험 `StaticSceneNoiseTest` 포함). 기기 동작은 위 항목으로 확인한다.
