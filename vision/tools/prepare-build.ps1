param([switch]$InstallSdk)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$visionRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path $visionRoot '.local'
New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
$jdkRoot = Join-Path $toolsRoot 'jdk'
if (-not (Test-Path $jdkRoot)) {
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $package = $assets[0].binary.package
    $zip = Join-Path $toolsRoot 'jdk17.zip'
    Invoke-WebRequest -UseBasicParsing $package.link -OutFile $zip
    if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne $package.checksum) { throw 'JDK checksum mismatch' }
    Expand-Archive -LiteralPath $zip -DestinationPath $jdkRoot
}
$jdk = Get-ChildItem -LiteralPath $jdkRoot -Directory | Select-Object -First 1
$env:JAVA_HOME = $jdk.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$sdkRoot = Join-Path $toolsRoot 'android-sdk'
if ($InstallSdk) {
    $manager = Join-Path $sdkRoot 'cmdline-tools/latest/bin/sdkmanager.bat'
    if (-not (Test-Path $manager)) {
        $zip = Join-Path $toolsRoot 'android-tools.zip'
        Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' -OutFile $zip
        if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a') { throw 'SDK tools checksum mismatch' }
        $unpack = Join-Path $toolsRoot 'android-tools'
        Expand-Archive -LiteralPath $zip -DestinationPath $unpack -Force
        New-Item -ItemType Directory -Force -Path (Join-Path $sdkRoot 'cmdline-tools') | Out-Null
        $sdkSource = [IO.Path]::GetFullPath((Join-Path $unpack 'cmdline-tools'))
        $sdkDestination = [IO.Path]::GetFullPath((Join-Path $sdkRoot 'cmdline-tools/latest'))
        $allowedRoot = [IO.Path]::GetFullPath($toolsRoot).TrimEnd('\') + '\'
        if (-not $sdkSource.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase) -or
            -not $sdkDestination.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'SDK extraction paths must remain inside vision/.local'
        }
        Move-Item -LiteralPath $sdkSource -Destination $sdkDestination
    }
    1..30 | ForEach-Object { 'y' } | & $manager "--sdk_root=$sdkRoot" --licenses
    & $manager "--sdk_root=$sdkRoot" 'platforms;android-37' 'build-tools;36.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
}
if (Test-Path (Join-Path $sdkRoot 'platforms/android-37/android.jar')) { $env:ANDROID_HOME = $sdkRoot }
Write-Output "JAVA_HOME=$env:JAVA_HOME"
Write-Output "ANDROID_HOME=$env:ANDROID_HOME"
