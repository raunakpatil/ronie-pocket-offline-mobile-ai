@echo off
rem Builds the debug app and installs it on the Pixel 9 Pro (wireless debugging), then launches it.
rem Usage: scripts\build-install.cmd
setlocal
cd /d "%~dp0.."
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

rem 1. Gradle wrapper, generated once from the Gradle 9.8.0 already cached on this PC
if not exist gradlew.bat (
  set "GRADLE="
  for /r "%USERPROFILE%\.gradle\wrapper\dists\gradle-9.8.0-bin" %%G in (gradle.bat) do if exist "%%G" set "GRADLE=%%G"
  call :wrapper || goto :fail
)

rem 2. Build (the first run downloads the NDK and CMake)
echo == Building debug APK
rem Ship the persona state for a fast first launch (only does work when the prompt changed).
call "%~dp0make-persona-state.cmd"
call "%CD%\gradlew.bat" :app:assembleDebug || goto :fail

rem 3. Find the Pixel 9 Pro and never install on other adb devices on the network
"%ADB%" start-server >nul
set "SERIAL="
for /f "usebackq tokens=1" %%S in (`call "%ADB%" devices -l ^| findstr /c:"model:Pixel_9_Pro"`) do if not defined SERIAL set "SERIAL=%%S"
if not defined SERIAL (
  echo == Pixel not connected, looking it up over mDNS
  for /f "usebackq tokens=3" %%A in (`call "%ADB%" mdns services ^| findstr /c:"_adb-tls-connect" ^| findstr /c:"%RONIE_PHONE_ID%"`) do if not defined SERIAL set "SERIAL=%%A"
  if defined SERIAL "%ADB%" connect %SERIAL%
)
if not defined SERIAL (
  echo Pixel 9 Pro not found. Check that Wireless debugging is on.
  goto :fail
)

echo == Installing on %SERIAL%
"%ADB%" -s %SERIAL% install -r app\build\outputs\apk\debug\app-debug.apk || goto :fail

rem 4. Launch
"%ADB%" -s %SERIAL% shell am start -n com.raunakpatil.roniepocket/.MainActivity
echo == Done. Check the phone.
exit /b 0

:wrapper
if not defined GRADLE (
  echo Gradle 9.8.0 not found in the Gradle cache.
  exit /b 1
)
echo == Generating Gradle wrapper
call "%GRADLE%" wrapper --gradle-version 9.8.0 --no-configuration-cache
exit /b %errorlevel%

:fail
echo == FAILED
exit /b 1
