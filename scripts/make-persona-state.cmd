@echo off
rem Fast first launch: computes Ronie's persona state on the PC and puts it in the app's assets, so the
rem phone restores it (~60 ms) instead of computing it (~49 s). Only does work when the prompt changed.
rem 1. The PersonaPrefixExport unit test writes the exact fixed prompt text + the state file name.
rem 2. tools-pc\persona_state (same llama.cpp as the phone) computes the state, if the app doesn't have it yet.
setlocal
cd /d "%~dp0.."
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call "%CD%\gradlew.bat" :app:testDebugUnitTest --tests com.raunakpatil.roniepocket.PersonaPrefixExport --console=plain -q || goto :fail
if not exist build\probe\persona_state.exe call "%~dp0build-persona-tool.cmd" || goto :fail
set /p NAME=<app\build\persona\FULL.name
if exist "app\src\main\assets\state\%NAME%" (
  echo == persona state up to date: %NAME%
  exit /b 0
)
if not exist app\src\main\assets\state mkdir app\src\main\assets\state
del /q app\src\main\assets\state\FULL-*.bin 2>nul
build\probe\persona_state.exe models-local\gemma-4-E2B-it-Q4_K_M.gguf app\build\persona\FULL.txt "app\src\main\assets\state\%NAME%" 8 || goto :fail
echo == persona state made: %NAME%
exit /b 0
:fail
echo == PERSONA STATE FAILED (the app will compute it on first launch instead)
exit /b 0
