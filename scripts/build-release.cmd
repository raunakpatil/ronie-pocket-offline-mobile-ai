@echo off
rem Builds the signed release app: build\release\RoniePocket-v<version>.apk
rem The signing key is yours: it lives outside the repo and you type its password yourself.
rem   First time only, create it with:  scripts\build-release.cmd newkey
rem Keep a backup of the key file: every future update must be signed with the same key.
setlocal
cd /d "%~dp0.."
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "BT=%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0"
if not defined RONIE_KEYSTORE set "RONIE_KEYSTORE=%USERPROFILE%\.ronie\ronie-release.jks"

if /i "%~1"=="newkey" (
  if exist "%RONIE_KEYSTORE%" ( echo A key already exists at %RONIE_KEYSTORE% - not overwriting. & exit /b 1 )
  if not exist "%USERPROFILE%\.ronie" mkdir "%USERPROFILE%\.ronie"
  "%JAVA_HOME%\bin\keytool.exe" -genkeypair -v -keystore "%RONIE_KEYSTORE%" -alias ronie -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Ronie Pocket, O=Raunak Patil" || exit /b 1
  echo == Key created at %RONIE_KEYSTORE%. Back it up somewhere safe now.
  exit /b 0
)
if not exist "%RONIE_KEYSTORE%" ( echo No signing key yet. Run: scripts\build-release.cmd newkey & exit /b 1 )

for /f "tokens=3 delims= " %%V in ('findstr /c:"versionName = " app\build.gradle.kts') do set "VER=%%~V"
call "%~dp0make-persona-state.cmd"
call "%CD%\gradlew.bat" :app:assembleRelease || goto :fail
if not exist build\release mkdir build\release
set "UNSIGNED=app\build\outputs\apk\release\app-release-unsigned.apk"
set "ALIGNED=build\release\aligned.apk"
set "OUT=build\release\RoniePocket-v%VER%.apk"
"%BT%\zipalign.exe" -p -f 4 "%UNSIGNED%" "%ALIGNED%" || goto :fail
echo == Signing: enter your key password when asked.
call "%BT%\apksigner.bat" sign --ks "%RONIE_KEYSTORE%" --ks-key-alias ronie --out "%OUT%" "%ALIGNED%" || goto :fail
del "%ALIGNED%"
call "%BT%\apksigner.bat" verify --print-certs "%OUT%" | findstr /c:"Signer #1 certificate DN" /c:"SHA-256 digest"
certutil -hashfile "%OUT%" SHA256 | findstr /v "hash CertUtil"
echo == Release app: %OUT%
exit /b 0
:fail
echo == FAILED
exit /b 1
