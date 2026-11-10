$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'prepare-build.ps1')
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $repoRoot
try {
    & .\gradlew.bat :vision:testDebugUnitTest :vision:lintDebug :app:assembleDebug --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Verification failed' }
} finally {
    Pop-Location
}
