# Google Cloud Run에 서버를 올린다 (USB 없이 폰만으로 쓰기 위해).
#
#   .\server\deploy\deploy-cloud-run.ps1 -Project <구글-클라우드-프로젝트-ID>
#
# 하는 일: (1) 필요한 API 켜기 (2) Claude 키(입력받음) · 앱 키(새로 무작위 생성)를 Secret Manager에만 저장
#          (3) 컨테이너 빌드 · 배포 (4) 주소 확인(/healthz) (5) local.properties(git에 안 올라감)에 서버 주소 · 앱 키 기록
# 키는 화면 · 로그 · 저장소 어디에도 남기지 않는다. 다시 실행하면 같은 이름의 비밀 값에 새 버전이 추가된다 (키 교체).
#
# Windows PowerShell 5.1 주의: $ErrorActionPreference = "Stop" 이면 gcloud가 stderr에 한 줄만 써도(예: 아직 없는 비밀 값을
# 조회할 때의 NOT_FOUND) 치명적 오류로 바뀌어 스크립트가 죽는다. 그래서 여기서는 Continue로 두고 종료 코드로만 성공을 판단한다.
param(
    [Parameter(Mandatory = $true)][string]$Project,
    [string]$Region = "asia-northeast3",          # 서울
    [string]$Service = "kiosk-lens-agent",
    [int]$MinInstances = 1,                       # 1이면 항상 켜져 있어 첫 요청이 느리지 않다. 0이면 비용은 줄지만 첫 요청이 수 초 느리다
    [string]$Model = "claude-haiku-5-5",
    [switch]$ReplaceKeys                          # 이미 저장된 비밀 값이 있어도 Claude 키를 다시 입력받아 새 버전으로 바꾼다 (키 교체)
)
$ErrorActionPreference = "Continue"
$serverDir = Split-Path -Parent $PSScriptRoot
$repoDir = Split-Path -Parent $serverDir

function Fail([string]$What) { throw "$What 실패 (gcloud 종료 코드 $LASTEXITCODE). 위 메시지를 확인하세요." }

if (-not (Get-Command gcloud -ErrorAction SilentlyContinue)) { throw "gcloud가 없습니다. server/deploy/README.md 의 1단계를 먼저 하세요." }

gcloud config set project $Project 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "프로젝트 설정" }

Write-Host "1) 필요한 API를 켭니다 (처음에는 1~2분)..."
gcloud services enable run.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com artifactregistry.googleapis.com 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { Fail "API 켜기" }

# 비밀 값을 임시 파일(줄바꿈 없이)로 넘겨 Secret Manager에 저장하고 파일은 바로 지운다.
# 먼저 만들어 보고, 이미 있으면(종료 코드 != 0) 새 버전을 추가한다.
function Set-Secret([string]$Name, [string]$Value) {
    $tmp = New-TemporaryFile
    try {
        Set-Content -Path $tmp -Value $Value -NoNewline -Encoding ascii
        gcloud secrets create $Name --data-file=$tmp --replication-policy=automatic 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            gcloud secrets versions add $Name --data-file=$tmp 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) { Fail "비밀 값 '$Name' 저장" }
        }
    } finally { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
}

function Test-Secret([string]$Name) { gcloud secrets describe $Name 2>&1 | Out-Null; return ($LASTEXITCODE -eq 0) }

Write-Host "2) 비밀 값을 저장합니다."
if (-not $ReplaceKeys -and (Test-Secret "kl-anthropic-key") -and (Test-Secret "kl-app-key")) {
    Write-Host "   비밀 값이 이미 저장돼 있어 그대로 씁니다 (Claude 키를 바꾸려면 -ReplaceKeys)"
    $appKey = (gcloud secrets versions access latest --secret=kl-app-key 2>$null).Trim()
    if ([string]::IsNullOrWhiteSpace($appKey)) { throw "저장된 앱 키를 못 읽었습니다. -ReplaceKeys 로 다시 실행하세요." }
} else {
$secure = Read-Host "Anthropic(Claude) API 키 (입력은 화면에 안 보입니다)" -AsSecureString
$claudeKey = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if ([string]::IsNullOrWhiteSpace($claudeKey)) { throw "키가 비어 있습니다." }
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$appKey = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
Set-Secret "kl-anthropic-key" $claudeKey
Set-Secret "kl-app-key" $appKey
$claudeKey = $null
Write-Host "   비밀 값 2개 저장 완료 (kl-anthropic-key, kl-app-key)"
}

# Cloud Run이 쓰는 기본 서비스 계정이 이 비밀 값 두 개만 읽게 한다
$number = (gcloud projects describe $Project --format="value(projectNumber)" 2>$null).Trim()
if ([string]::IsNullOrWhiteSpace($number)) { throw "프로젝트 번호를 못 읽었습니다. 로그인/프로젝트 ID를 확인하세요." }
$account = "serviceAccount:$number-compute@developer.gserviceaccount.com"
foreach ($s in "kl-anthropic-key", "kl-app-key") {
    gcloud secrets add-iam-policy-binding $s --member=$account --role="roles/secretmanager.secretAccessor" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "비밀 값 '$s' 읽기 권한 부여" }
}

Write-Host "3) 컨테이너를 빌드하고 배포합니다 (처음에는 3~5분)..."
gcloud run deploy $Service --source $serverDir --region $Region --allow-unauthenticated `
    --min-instances $MinInstances --max-instances 3 --memory 512Mi --cpu 1 --timeout 30 --concurrency 20 `
    --set-env-vars "LLM_PROVIDER=anthropic,LLM_MODEL=$Model,HELP_MODE=true" `
    --set-secrets "APP_KEY=kl-app-key:latest,ANTHROPIC_API_KEY=kl-anthropic-key:latest" --quiet 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { Fail "배포" }

$url = (gcloud run services describe $Service --region $Region --format="value(status.url)" 2>$null).Trim()
if ([string]::IsNullOrWhiteSpace($url)) { throw "서비스 주소를 못 읽었습니다." }
Write-Host "4) 주소: $url"
# 주의: Cloud Run 앞단(구글 프런트엔드)이 /healthz 경로를 자체적으로 가로채서 404를 돌려준다 — 서버 문제가 아니다.
# 그래서 실제 API 경로에 "앱 키 없이" 요청해 401(인증 거절)이 오는지로 서버가 살아 있고 인증이 걸려 있는지 확인한다.
try {
    Invoke-WebRequest "$url/v1/guide/next" -Method Post -Body "{}" -ContentType "application/json" -UseBasicParsing -TimeoutSec 60 | Out-Null
    Write-Host "   서버가 키 없는 요청을 받아들였습니다 (인증이 꺼져 있는지 확인하세요)"
} catch {
    $code = [int]$_.Exception.Response.StatusCode
    if ($code -eq 401) { Write-Host "   서버 응답 확인: 401 (앱 키 없는 요청을 거절함) = 정상" }
    else { Write-Host "   서버 응답 코드 $code (잠시 뒤 다시 확인하세요)" }
}

Write-Host "5) local.properties(git에 안 올라감)에 서버 주소와 앱 키를 기록합니다."
$lp = Join-Path $repoDir "local.properties"
$lines = @(); if (Test-Path $lp) { $lines = Get-Content $lp | Where-Object { $_ -notmatch '^kl\.(serverUrl|appKey)=' } }
$lines += "kl.serverUrl=$url"
$lines += "kl.appKey=$appKey"
Set-Content -Path $lp -Value $lines -Encoding ascii
Write-Host ""
Write-Host "끝났습니다. 앱을 다시 설치하면 USB 없이 이 서버를 씁니다:"
Write-Host "   .\gradlew :app:installDebug"
