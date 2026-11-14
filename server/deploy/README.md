# 서버를 Google Cloud Run에 올리기 (USB 없이 폰만으로)

지금까지는 PC에서 서버를 돌리고 USB 케이블(`adb reverse`)로 폰과 이었다. 케이블을 빼면 안내가 안 나온다.
이 문서는 서버를 클라우드에 올려서 폰이 인터넷으로 직접 붙게 하는 방법이다.

## 구조
```
폰 앱 ──(HTTPS, X-App-Key)──▶ Cloud Run(이 서버) ──▶ Claude API
```
- Claude 키 · 앱 키는 **Secret Manager**에만 있고 컨테이너 환경변수로 주입된다. 저장소 · 앱 · 로그에 없다.
- 앱에는 앱 키(`X-App-Key`)만 들어간다. 이 값은 APK에서 꺼낼 수 있어 약한 보호다 → 서버가 분당 요청 수를 제한한다(`RATE_LIMIT_PER_MIN`, 기본 60).

## 한 번만 하는 준비 (사람이 직접)
1. **gcloud 설치**: PowerShell에서 `winget install -e --id Google.CloudSDK` 후 새 터미널을 연다
2. **로그인**: `gcloud auth login` (브라우저가 열린다)
3. **프로젝트와 결제**: https://console.cloud.google.com 에서 프로젝트를 만들고 결제 계정을 연결한다 (Cloud Run은 결제 등록이 필요하다. 사용량이 적으면 무료 한도 안에서 거의 0원)

## 배포
```powershell
.\server\deploy\deploy-cloud-run.ps1 -Project <프로젝트-ID>
```
- 물어보는 것은 Claude API 키 하나뿐이다 (입력이 화면에 안 보인다). 앱 키는 새로 무작위로 만든다.
- 끝나면 서버 주소와 앱 키가 `local.properties`(git에 안 올라감)에 기록된다.
- 앱을 다시 설치한다: `.\gradlew :app:installDebug` → **USB를 빼고** 시험한다.

## 알아 둘 것
- Cloud Run은 `/healthz` 경로를 앞단에서 가로채 **404**를 돌려준다(서버 문제가 아님). 서버 확인은 `POST /v1/guide/next`를 앱 키 없이 보내 **401**이 오는지로 한다. 스크립트가 마지막에 이걸 해 준다.
- 처음 배포에서 `storage.objects.get ... 403`이 나면 빌드 계정에 권한이 없는 것이다: `gcloud projects add-iam-policy-binding <프로젝트> --member="serviceAccount:<프로젝트번호>-compute@developer.gserviceaccount.com" --role="roles/cloudbuild.builds.builder"`

## 비용과 속도
- `-MinInstances 1`(기본): 항상 켜져 있어 첫 요청이 느리지 않다. 사용량이 적어도 월 몇 달러가 나올 수 있다.
- `-MinInstances 0`: 안 쓸 때 0원이지만 오래 쉬었다 오면 첫 요청이 수 초 느리다. 시연 직전에는 1이 안전하다.
- 요청 하나의 서버 처리 예산은 5초(`TOTAL_BUDGET_S`)이고 앱 타임아웃은 6초다.

## 키 교체 · 중지
- 키 교체: 스크립트를 다시 실행하면 같은 이름의 비밀 값에 새 버전이 추가된다 (예전 키는 Anthropic 콘솔에서 폐기).
- 서버 중지: `gcloud run services delete kiosk-lens-agent --region asia-northeast3`
- 로그: `gcloud run services logs read kiosk-lens-agent --region asia-northeast3` (요청 본문은 남기지 않는다. `DEBUG_PROMPTS`는 배포에서 켜지 않는다)
