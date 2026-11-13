@echo off
rem Builds llama.cpp (CPU) for this Windows PC with MSVC, from the same source as the phone build.
rem Used by the Phase 2 bake-off (llama-server) and Phase 3.5 steering (llama-cvector-generator). Output: build\llama-pc\bin\
setlocal
cd /d "%~dp0.."
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "CMAKE=%SDK%\cmake\3.22.1\bin\cmake.exe"
set "NINJA=%SDK%\cmake\3.22.1\bin\ninja.exe"
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || goto :fail
"%CMAKE%" -S third_party\llama.cpp -B build\llama-pc -G Ninja -DCMAKE_MAKE_PROGRAM="%NINJA%" ^
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF ^
  -DLLAMA_BUILD_TESTS=OFF -DBUILD_SHARED_LIBS=OFF || goto :fail
"%CMAKE%" --build build\llama-pc --config Release -j 12 --target llama-server llama-completion llama-cvector-generator || goto :fail
echo == BUILD-PC DONE
exit /b 0
:fail
echo == BUILD-PC FAILED
exit /b 1
