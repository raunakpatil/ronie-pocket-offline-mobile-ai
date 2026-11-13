# Builds the debug app and installs it on the Pixel 9 Pro (over wireless debugging), then launches it.
# Usage:  & .\scripts\build-install.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'

# 1. Gradle wrapper (generated once from the Gradle 9.8.0 already cached on this PC)
if (-not (Test-Path (Join-Path $root 'gradlew.bat'))) {
    $gradle = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-9.8.0-bin') -Recurse -Filter gradle.bat |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $gradle) { throw 'Gradle 9.8.0 not found in the Gradle cache.' }
    Write-Host "== Generating Gradle wrapper with $gradle"
    & $gradle wrapper --gradle-version 9.8.0 --no-configuration-cache
    if ($LASTEXITCODE -ne 0) { throw 'Wrapper generation failed.' }
}

# 2. Build (first run downloads the NDK and CMake)
Write-Host '== Building debug APK'
& (Join-Path $root 'gradlew.bat') ':app:assembleDebug'
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }

# 3. Find the Pixel 9 Pro (never install on other adb devices on the network)
& $adb start-server | Out-Null
$serial = & $adb devices -l | Where-Object { $_ -match 'model:Pixel_9_Pro' } |
    ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
if (-not $serial) {
    Write-Host '== Pixel not connected; looking it up over mDNS'
    $svc = & $adb mdns services | Where-Object { $_ -match '_adb-tls-connect' -and $_ -match "$env:RONIE_PHONE_ID" } | Select-Object -First 1
    if ($svc) { $ipPort = ($svc -split '\s+')[-1]; & $adb connect $ipPort | Out-Host; $serial = $ipPort }
}
if (-not $serial) { throw 'Pixel 9 Pro not found. Check that Wireless debugging is on.' }
Write-Host "== Installing on $serial"

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }

# 4. Launch
& $adb -s $serial shell am start -n 'com.raunakpatil.roniepocket/.MainActivity' | Out-Host
Write-Host '== Done. Check the phone.'
