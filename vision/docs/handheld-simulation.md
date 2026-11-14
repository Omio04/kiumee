# 휴대 촬영 조건과 자동 주문 안내 검증 (2026-11-13)

기준 브랜치: `feat/p1-photo-haiku`, 기준 커밋 `63a161f`.
사용자가 제공한 원본 HTML을 변경하지 않고 Playwright로 실제 버튼을 눌렀다.
이미지의 글자를 DOM에서 추출해 OCR 정답으로 대신 사용하지 않았다.

## 문제와 수정

- 앱의 기본 `HELP_MODE=true`가 화면 전환 뒤 자동 요청을 막았다. 기본을 `false`로 바꾸고 기존 도움 모드는 명시적 선택으로 유지했다.
- ASK_USER를 읽거나 답을 듣는 중 직접 키오스크를 누르면 다음 화면이 무시됐다. 화면 전환 시 음성 인식을 중단하고 최신 화면으로 안내를 요청한다. 처음 주문이나 변경 사항을 말하는 중에는 끊지 않는다. 이전 음성 결과는 ticket으로 무시한다.
- 손떨림으로 서로 다른 글자가 깨져도, 공통 배경이 비슷하다는 이유로 새 화면을 확정했다. 실제 나타나고 사라진 글자의 변화도 연속 관측에서 일치해야 확정한다.
- 실제 ML Kit가 `ICE`를 `1CE` 또는 `CE`로 읽었다. 화면 비교용 키와 아이스 아메리카노 후보 매칭에만 제한적으로 허용한다. 원문·좌표·ID를 바꾸거나 HOT, 꿀/바닐라/디카페인 음료를 같은 음료로 취급하지 않는다.
- 카드 삽입/승인 중/영수증 선택은 WAIT로 유지하고 주문 완료 화면에서 종료한다. 이 부분은 주문 문구를 중간에 변경해도 적용한다. 결제 실패나 ‘완료되지 않았습니다’는 완료로 처리하지 않는다.

공용 JSON/포트는 변경하지 않았다. 기존 사진 기반 판단과 하이라이트 구현은 유지했다.
이번 개선은 관측 자료에 따른 화면 추적·판단 로직과 회귀 테스트 보강이며 ML Kit/Claude 모델 가중치를 재학습한 것이 아니다.

## 측정 방법과 결과

Android API 37 에뮬레이터에서 앱과 같은 `MlKitOcrEngine`(한국어 ML Kit 16.0.1)을 실행했다.
720×1280 래스터 이미지에 실제 OCR을 수행하고 `Elements.filter` → `ScreenTracker`로 처리했다.

| 자료 | 조건 | 화면 종류 일치 | 판단 보류 | 다른 종류로 오분류 | 실제 전환 감지 | 거짓 전환 |
|---|---|---:|---:|---:|---:|---:|
| 개발 자료 72장 | 12화면 × 선명/반복/회전 ±1.5°/이동/스큐/블러 0.3~1.4px | 64 | 8 | 0 | 10/10 | 수정 전 1 → 수정 후 0 |
| 추가 검증 48장 | 12화면 × 서로 다른 이동/회전 최대 2.4°/스큐/블러/3px 수평 모션 블러 | 46 | 2 | 0 | 10/10 | 0 |

추가 검증 자료에는 같은 선명한 프레임 2장을 연속으로 넣지 않았다.
12화면 중 일회용품 선택 전/후는 같은 단계로 취급한다. 따라서 주문 흐름은 11단계, 실제 전환은 10개다.
이는 **화면 종류 판정 및 전환 지표**이며 글자 정확도나 실환경 성공률이 아니다.
흐려서 근거가 부족하면 판단을 보류하고 다음 관측을 기다린다.

실제 OCR 스냅샷에 프로덕션 서버 안내를 적용하고, 그 응답을 상태머신에 재생했다.
개발 자료와 추가 자료 모두 첫 주문 발화 1회로 11단계 안내가 생성됐다.
스냅샷/전환 이벤트 도착 순서를 바꿔도 완료까지 진행했다.
서버 검증은 이 경로의 결정적 규칙을 사용하며 유료 LLM 호출은 0회였다.

주문: ICE 아메리카노 → 옵션 → 장바구니 → 포장 → 일회용품 → 결제수단 → 적립 → 카드 → 승인 중 → 영수증 → 주문번호.
원본에는 컵홀더 항목이 없다. 실제 선택지는 빨대/스틱, 캐리어/봉투, 직접 가져 갈게요이며 이를 사용했다.
이 선택들은 테스트 시나리오일 뿐 실제 사용자의 기본값으로 설정하지 않는다.

## 추가 상황

- 원본 HTML 직접 조작: 빈 장바구니 경고, 잘못 연 HOT 음료 취소, 포장, 일회용품 생략, 적립 생략, 결제 취소, 잘못된 번호 입력 후 수정, 영수증 미출력, 완료 후 초기화, 매장 이용, 영수증 시간 초과. 브라우저 상태 검증 23개 통과.
- 관측 재생: 옵션 닫기, 뒤로 가기, 결제 취소 후 재진입을 포함한 18단계 경로, 반복적인 카메라 가림, 한 프레임만 보인 팝업.
- 상태머신: 질문을 읽는 중/듣는 중 직접 선택, 같은 화면 유지, 주문 변경 발화 보호, 늦은 STT/TTS 결과 무시.
- 서버: 다른 음료/온도와 혼동 금지, 주문 문구 변경 후 승인·영수증 안내 유지, 결제 실패를 완료로 오해하지 않기.

## 재현

저장소 루트에서 실행한다. 원본 HTML 경로는 실제 파일 위치로 바꾼다.

```powershell
npm install --prefix .local playwright@1.65.0 sharp@0.35.5
& .local/node_modules/.bin/playwright.cmd install chromium
node vision/tools/capture-kiosk.cjs 'C:/path/index.html'
node vision/tools/capture-kiosk.cjs 'C:/path/index.html' --holdout
node vision/tools/exercise-kiosk.cjs 'C:/path/index.html'
./gradlew :vision:assembleDebugAndroidTest '-PkioskAssets=.local/kiosk-holdout'
adb install -r vision/build/outputs/apk/androidTest/debug/vision-debug-androidTest.apk
adb shell am instrument -w kr.kiosklens.vision.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/kr.kiosklens.vision.test/files/handheld-results.json .local/handheld-holdout.json
node vision/tools/summarize-handheld.cjs .local/handheld-holdout.json
./gradlew :vision:testDebugUnitTest :domain:test
python vision/tools/replay-guide.py
```

기본 이미지 세트는 `-PkioskAssets`를 생략한다. Gradle connected 테스트는 종료 시 테스트 앱과 외부 파일을 지울 수 있으므로 보고서 보존에는 위 adb 직접 실행을 사용한다.
Python은 `server/requirements-dev.txt` 의존성을 설치한다.
생성 이미지와 원본 HTML, 임시 도구는 앱에 포함하지 않는다. 관측 JSON과 회귀 테스트만 저장한다.
전화번호는 원본의 오프라인 연습용으로 합성한 `01000000000`을 사용했다.

## 적용과 남은 확인

이 시뮬레이션은 화면 전환을 자동으로 안내하는 방식(예전 기본)을 전제로 한다. 지금 기본은 도움 모드(2026-11-14)이므로, 재현하려면 `local.properties`에 `kl.helpMode=false`를 넣고 앱을 다시 빌드하고, 서버도 `HELP_MODE=false`로 재시작한다.
사진 인식/일반 질문은 기존 설정을 유지한다.
실물 휴대폰 카메라의 반사광, 모아레, 자동초점, 실제 서버 지연, 마이크·스피커 품질은 이 시뮬레이션으로 측정하지 않았다.
실기기에서 한 차례 주문을 끝까지 수행하는 확인은 별도로 필요하다.
