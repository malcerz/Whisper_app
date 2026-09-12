$ErrorActionPreference = "Stop"

$PinnedCommit = "52a939a2a762224e255d366c1182b2af4dd1a032"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Whisper = Join-Path $Root "whisper.cpp"
$AndroidProject = Join-Path $Whisper "examples\whisper.android.java"
$Overlay = Join-Path $Root "overlay"
$Out = Join-Path $Root "out"

Write-Host "=== Whisper Files Android build ===" -ForegroundColor Cyan
Write-Host "whisper.cpp commit: $PinnedCommit"

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "Brak git w PATH."
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Brak Java w PATH. Wymagane JDK 17."
}

if (-not (Test-Path $Whisper)) {
    Write-Host "Pobieranie whisper.cpp..."
    git clone https://github.com/ggml-org/whisper.cpp.git $Whisper
}

Push-Location $Whisper
try {
    git fetch --all --tags
    git reset --hard $PinnedCommit
    git clean -fdx
} finally {
    Pop-Location
}

if (-not (Test-Path $AndroidProject)) {
    throw "Brak oficjalnego projektu Android w whisper.cpp."
}

Write-Host "Nakładanie aplikacji Whisper Files..."
$JavaDir = Join-Path $AndroidProject "app\src\main\java"
if (Test-Path $JavaDir) { Remove-Item $JavaDir -Recurse -Force }
New-Item $JavaDir -ItemType Directory -Force | Out-Null
Copy-Item (Join-Path $Overlay "app\*") (Join-Path $AndroidProject "app") -Recurse -Force

$Sdk = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($Sdk)) { $Sdk = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($Sdk)) {
    $Candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $Candidate) { $Sdk = $Candidate }
}
if ([string]::IsNullOrWhiteSpace($Sdk) -or -not (Test-Path $Sdk)) {
    throw "Nie znaleziono Android SDK. Ustaw ANDROID_SDK_ROOT albo zainstaluj Android Studio/SDK."
}

$SdkGradle = $Sdk.Replace('\','/')
"sdk.dir=$SdkGradle" | Set-Content -Path (Join-Path $AndroidProject "local.properties") -Encoding ASCII

$Ndk = Join-Path $Sdk "ndk\25.2.9519653"
if (-not (Test-Path $Ndk)) {
    throw "Brak NDK 25.2.9519653 w $Ndk. Zainstaluj tę wersję przez SDK Manager."
}

Write-Host "Budowanie release APK (arm64-v8a)..." -ForegroundColor Cyan
Push-Location $AndroidProject
try {
    .\gradlew.bat --no-daemon clean :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle zakończył się kodem $LASTEXITCODE" }
} finally {
    Pop-Location
}

$Apk = Join-Path $AndroidProject "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $Apk)) { throw "Build zakończony, ale nie znaleziono APK: $Apk" }
New-Item $Out -ItemType Directory -Force | Out-Null
$Dest = Join-Path $Out "WhisperFiles-v0.1-arm64-release.apk"
Copy-Item $Apk $Dest -Force

Write-Host ""
Write-Host "GOTOWE: $Dest" -ForegroundColor Green
