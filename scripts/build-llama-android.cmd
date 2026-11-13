@echo off
rem Cross-compiles llama.cpp's CLI tools (llama-bench, llama-completion, llama-mtmd-cli) for arm64 Android.
rem Two variants: "portable" (baseline ARMv8) and "g4" (tuned for Tensor G4: dotprod + i8mm + fp16).
rem Usage: scripts\build-llama-android.cmd [portable|g4|all]
setlocal
cd /d "%~dp0.."
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "NDK=%SDK%\ndk\28.2.13676358"
set "CMAKE=%SDK%\cmake\3.22.1\bin\cmake.exe"
set "NINJA=%SDK%\cmake\3.22.1\bin\ninja.exe"
set "SRC=third_party\llama.cpp"
set "VARIANT=%~1"
if "%VARIANT%"=="" set "VARIANT=all"

if /i "%VARIANT%"=="portable" call :build portable "" || goto :fail
if /i "%VARIANT%"=="g4"       call :build g4 "armv8.6-a+dotprod+i8mm+fp16" || goto :fail
if /i "%VARIANT%"=="all" (
  call :build g4 "armv8.6-a+dotprod+i8mm+fp16" || goto :fail
  call :build portable "" || goto :fail
)
echo == BUILD-LLAMA DONE
exit /b 0

:build
set "OUT=build\llama-android-%~1"
echo == Configuring %~1 (arch: %~2)
set "ARCHFLAG="
if not "%~2"=="" set "ARCHFLAG=-DGGML_CPU_ARM_ARCH=%~2"
"%CMAKE%" -S "%SRC%" -B "%OUT%" -G Ninja -DCMAKE_MAKE_PROGRAM="%NINJA%" ^
  -DCMAKE_TOOLCHAIN_FILE="%NDK%\build\cmake\android.toolchain.cmake" ^
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release ^
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF ^
  -DBUILD_SHARED_LIBS=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF %ARCHFLAG% || exit /b 1
echo == Building %~1
"%CMAKE%" --build "%OUT%" --config Release -j 12 --target llama-bench llama-completion llama-mtmd-cli || exit /b 1
exit /b 0

:fail
echo == BUILD-LLAMA FAILED
exit /b 1
