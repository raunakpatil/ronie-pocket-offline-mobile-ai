@echo off
rem Runs the JVM unit tests (engine helpers like LoopGuard).
setlocal
cd /d "%~dp0.."
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call "%CD%\gradlew.bat" :core:engine:testDebugUnitTest :core:imagegen:testDebugUnitTest :app:testDebugUnitTest --console=plain || ( echo == TESTS FAILED & exit /b 1 )
echo == TESTS PASSED
