@echo off
rem Like build-install.cmd, but does NOT launch the app (so nothing pops over a call or another app).
setlocal
cd /d "%~dp0.."
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
rem Ship the persona state for a fast first launch (only does work when the prompt changed).
call "%~dp0make-persona-state.cmd"
call "%CD%\gradlew.bat" :app:assembleDebug || goto :fail
set "SERIAL="
for /f "usebackq tokens=1" %%S in (`call "%ADB%" devices -l ^| findstr /c:"model:Pixel_9_Pro"`) do if not defined SERIAL set "SERIAL=%%S"
if not defined SERIAL ( echo Pixel 9 Pro not found & goto :fail )
"%ADB%" -s %SERIAL% install -r app\build\outputs\apk\debug\app-debug.apk || goto :fail
echo == Installed (not launched).
exit /b 0
:fail
echo == FAILED
exit /b 1
