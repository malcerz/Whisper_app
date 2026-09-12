$ErrorActionPreference = "Stop"

$PinnedCommit = "52a939a2a762224e255d366c1182b2af4dd1a032"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Whisper = Join-Path $Root "whisper.cpp"
$AndroidProject = Join-Path $Whisper "examples\whisper.android.java"
$Overlay = Join-Path $Root "overlay"
$RootPatches = Join-Path $Root "root-patches"
$Out = Join-Path $Root "out"

Write-Host "=== Whisper Files Android v0.3 build ===" -ForegroundColor Cyan
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

$WhisperGit = Join-Path $Whisper ".git"
if (Test-Path $WhisperGit) {
    Push-Location $Whisper
    try {
        git fetch --all --tags
        git reset --hard $PinnedCommit
        git clean -fdx
    } finally {
        Pop-Location
    }
} else {
    Write-Host "Uzywam dolaczonego zrodla whisper.cpp (bez metadanych .git)." -ForegroundColor DarkGray
}

if (-not (Test-Path $AndroidProject)) {
    throw "Brak oficjalnego projektu Android w whisper.cpp."
}

Write-Host "Nakładanie Whisper Files v0.3..."
$JavaDir = Join-Path $AndroidProject "app\src\main\java"
if (Test-Path $JavaDir) { Remove-Item $JavaDir -Recurse -Force }
New-Item $JavaDir -ItemType Directory -Force | Out-Null
Copy-Item (Join-Path $Overlay "app\*") (Join-Path $AndroidProject "app") -Recurse -Force

Copy-Item (Join-Path $RootPatches "build.gradle") (Join-Path $AndroidProject "build.gradle") -Force
Copy-Item (Join-Path $RootPatches "gradle.properties") (Join-Path $AndroidProject "gradle.properties") -Force
Copy-Item (Join-Path $RootPatches "gradle-wrapper.properties") `
    (Join-Path $AndroidProject "gradle\wrapper\gradle-wrapper.properties") -Force

$Sdk = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($Sdk)) { $Sdk = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($Sdk)) {
    $Candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $Candidate) { $Sdk = $Candidate }
}
if ([string]::IsNullOrWhiteSpace($Sdk) -or -not (Test-Path $Sdk)) {
    throw "Nie znaleziono Android SDK. Ustaw ANDROID_SDK_ROOT albo zainstaluj Android Studio/SDK."
}

$SdkGradle = $Sdk.Replace('\','/').Replace(':','\:')
"sdk.dir=$SdkGradle" | Set-Content -Path (Join-Path $AndroidProject "local.properties") -Encoding ASCII

function Find-SdkManager([string]$SdkRoot) {
    $candidates = @(
        (Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"),
        (Join-Path $SdkRoot "tools\bin\sdkmanager.bat")
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $all = Get-ChildItem -Path (Join-Path $SdkRoot "cmdline-tools") -Filter "sdkmanager.bat" -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($all) { return $all.FullName }
    return $null
}

$SdkManager = Find-SdkManager $Sdk
$Platform = Join-Path $Sdk "platforms\android-36"
if (-not (Test-Path $Platform)) {
    if (-not $SdkManager) { throw "Brak Android SDK Platform 36 i sdkmanager do automatycznej instalacji." }
    Write-Host "Instalowanie Android SDK Platform 36..." -ForegroundColor Cyan
    & $SdkManager "platforms;android-36" "build-tools;36.0.0"
    if ($LASTEXITCODE -ne 0) { throw "Nie udało się zainstalować Android SDK 36." }
}

$Ndk = Join-Path $Sdk "ndk\25.2.9519653"
if (-not (Test-Path $Ndk)) {
    if (-not $SdkManager) { throw "Brak NDK 25.2.9519653 i sdkmanager do automatycznej instalacji." }
    Write-Host "Instalowanie NDK 25.2.9519653..." -ForegroundColor Cyan
    & $SdkManager "ndk;25.2.9519653"
    if ($LASTEXITCODE -ne 0) { throw "Nie udało się zainstalować NDK 25.2.9519653." }
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
$Dest = Join-Path $Out "WhisperFiles-v0.3-arm64-release.apk"
Copy-Item $Apk $Dest -Force

Write-Host ""
Write-Host "GOTOWE: $Dest" -ForegroundColor Green
