@echo off
rem Builds the debug app without installing it (also refreshes the shipped persona state if the prompt changed).
setlocal
cd /d "%~dp0.."
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call "%~dp0make-persona-state.cmd"
call "%CD%\gradlew.bat" :app:assembleDebug --console=plain || ( echo == BUILD FAILED & exit /b 1 )
echo == BUILD OK: app/build/outputs/apk/debug/app-debug.apk
