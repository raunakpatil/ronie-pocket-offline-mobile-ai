@echo off
rem Builds tools-pc\probe\probe_dump.exe (X-ray probe training: dumps per-layer activations).
setlocal
cd /d "%~dp0.."
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "CMAKE=%SDK%\cmake\3.22.1\bin\cmake.exe"
set "NINJA=%SDK%\cmake\3.22.1\bin\ninja.exe"
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || goto :fail
"%CMAKE%" -S tools-pc\probe -B build\probe -G Ninja -DCMAKE_MAKE_PROGRAM="%NINJA%" -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON || goto :fail
"%CMAKE%" --build build\probe -j 12 --target probe_dump || goto :fail
echo == PROBE TOOL DONE: build\probe\probe_dump.exe
exit /b 0
:fail
echo == PROBE TOOL FAILED
exit /b 1
