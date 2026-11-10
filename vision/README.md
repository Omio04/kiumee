# P2 비전 모듈

계약 기준: [Omio04/kiumee main docs/contract.md](https://github.com/Omio04/kiumee/blob/65727cc886909ea5f061606d4f5f69f842372541/docs/contract.md),
커밋 `65727cc886909ea5f061606d4f5f69f842372541` (2026-11-09 확인).
GitHub main에는 변경하지 않았다. 이 폴더는 Git 저장소가 없는 ZIP 작업본이다.

메가커피 10장 예시를 반영한 개선·사용자 선택 흐름·담당 간 연결 사항:
[메가커피 검증 및 인계](docs/mega-coffee-handoff.md).
맛·샷·매장/포장·일회용품·결제수단은 사용자가 고른다. 예시의 '연하게'를 기본값으로 정하지 않는다.
적립 화면에서는 검증된 닫기 아이콘을 후보로 전달한다.

## 공용 계약과 연결

- 팀 프로젝트에서는 실제 `:core-model`의 `CameraFrame`, `UiElement`, `ScreenSnapshot`, `FrameSink`, `SnapshotSource`, `Clock`, `Logger`를 사용한다.
- 현재 ZIP에는 `:core-model`이 없어서 `src/standalone/kotlin`에 해당 커밋의 공용 소스 7개를 그대로 보관한다. `:core-model`이 존재하면 이 디렉터리는 **컴파일에서 제외**한다. 공용 계약을 수정한 것이 아니다.
- 기존 `app/`을 수정하지 않고 실행하기 위한 타입 별칭과 `ScreenAnalyzer` 진입점을 유지한다. 새 팀 앱은 `VisionScreenAnalyzer`를 포트로 주입한다.
- AGP 8에서는 Kotlin Android 플러그인을 적용하고 AGP 9 이상에서는 내장 Kotlin을 사용한다. 로컬 ZIP은 compileSdk 35, 팀 프로젝트는 main과 같은 37을 사용한다. 이 둘의 루트 Gradle 설정은 변경하지 않는다.
- `src/standalone`은 팀의 공용 모델과 함께 빌드하지 않는다. main에 반영할 때도 P2의 `vision/` 파일만 전달하면 된다.

## 동작

1. `submit(CameraFrame)`은 이미지·OCR에 접근하지 않고 용량 1의 큐에 넣고 복귀한다. OCR 1장과 대기 1장만 유지하며 오래된 대기 프레임은 즉시 반환한다.
2. 순차 워커가 약 3fps로 검사한다. 64×36 밝기 평균 차이가 4 미만이면 생략하고 2초마다 재인식한다. 회전 변화·화면 확인 중에는 차분 생략을 해제한다.
3. `MlKitOcrEngine`은 한국어 ML Kit로 줄 단위 글자와 박스를 읽는다. 회전을 반영한 분석 이미지 기준 0~1 좌표를 사용한다.
4. 필터 체인은 잘못된 좌표, 작은 영역, 낮은 신뢰도, 기호뿐인 줄, 긴 카드 번호, MM/YY·MM/YYYY 형태 유효기간을 제거한다. 보안 검사 후 80자·300개 제한을 적용한다.
5. 가격 분류를 먼저 적용하고 짧은 글자·동작 문구를 버튼 후보로 추정한다. 새 규칙은 `TextFilter` 또는 `ElementClassifier` 구현으로 추가한다.
6. 텍스트 집합 Jaccard 유사도 0.6을 기준으로 두 번 확인한 화면만 발행한다. 3개 미만 요소·실패 프레임은 안정 확인을 끊는다. 전환 후보 뒤 기존 화면으로 돌아와도 두 번 확인한다.
7. 요소 ID는 정규화 텍스트와 중심점 20×20 셀의 결정적 해시로 만든다. 충돌 시 `_2` 등을 붙인다. 같은 글자의 가까운 기존 요소를 먼저 찾아 경계 흔들림에도 ID를 유지한다. 작은 좌표 흔들림은 EMA로 완화하고 누락된 글자는 한 OCR 주기만 보존한다.
8. `ScreenSnapshot`에 실제 입력 `frameId`, `timestampMs`, `stableFrames >= 2`를 담는다. 최신 스냅샷 한 개를 재생하며 느린 구독자가 OCR을 막지 않는다. 첫 화면 확정 뒤 화면이 바뀌면 `screenChanges`를 발행한다.

이미지는 네트워크로 전송하지 않는다. 로그는 `KL_PERF`에 처리 시간·발행 여부만 기록한다.
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

## 사진과 Fake

- `PhotoAnalyzer`는 같은 사진에 OCR을 두 번 실행해 안정 결과만 반환한다. 읽을 수 있는 글자가 없거나 두 결과가 불안정하면 실패한다. 사진 프리뷰의 Bitmap은 재활용하지 않는다.
- `FakeScreenAnalyzer(fixtures)`는 제출 프레임마다 다음 픽스처로 진행하고 입력을 반환한다. `show(snapshot)`로 직접 표시할 수도 있다. 불안정한 픽스처는 발행하지 않는다.
- `SnapshotJson`은 Android `org.json` 대신 kotlinx.serialization을 사용하여 JVM 테스트에서도 동작한다. 프로덕션 HTTP 직렬화는 P4의 `:agent`가 담당한다.
- 옛 손 픽스처를 읽을 때만 누락된 frameId/timestampMs는 0, stableFrames는 2로 보완한다. 실시간 결과는 이 기본값을 사용하지 않는다.
- `SnapshotStream.value`는 기존 데모에서 최신 값을 조회하는 편의 속성이다. 포트의 Flow에서는 null을 발행하지 않는다.

## 실행과 검증 (PowerShell)

현재 PC의 JDK 25는 로컬 Gradle 8.9와 호환되지 않아, `vision/.local`에 JDK 17과 SDK를 별도로 준비했다.
시스템 환경변수를 영구 변경하지 않는다. 새 환경에서 준비하려면 다음을 실행한다
(공식 배포본 다운로드, 체크섬 확인, Android SDK 라이선스 동의 포함).

```powershell
. ./vision/tools/prepare-build.ps1 -InstallSdk
./vision/tools/verify.ps1
. ./vision/tools/prepare-build.ps1
./gradlew.bat :app:installDebug
& "$env:ANDROID_HOME/platform-tools/adb.exe" shell am start -n kr.kiosklens.demo/.MainActivity
```

테스트 보고서: `vision/build/reports/tests/testDebugUnitTest/index.html`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

테스트는 Fake와 실제 파이프라인이 같은 추상 계약 테스트를 상속한다. 실제 파이프라인 테스트의
OCR 엔진은 주입한 테스트 엔진이다. ML Kit 기기 인식 자체를 JVM 테스트로 검증한 것은 아니다.
원본 main의 요청 JSON 3개를 테스트 리소스로 보관해 스냅샷 왕복 변환을 검사한다.

실기기에서 별도로 측정할 것:

- 라벨 사진 30장 이상에서 목표 글자 재현율 (목표 90% 이상).
- `adb logcat -s KL_PERF`에서 OCR 처리 시간 (목표 500ms 이하).
- 첫 화면·전환 확정 지연은 두 관측을 기다리는 시간이 있으므로 별도로 측정.
- 회전·각도·반사·가림에서 박스 위치와 ID 유지. 버튼 후보 박스는 실제 버튼 전체가 아닌 OCR 글자 영역이다.
- 본 ZIP에서 테스트·APK 빌드가 통과해도 팀 앱 전체 조립과 AGP 9/SDK 37 빌드는 별도 확인이 필요하다.
