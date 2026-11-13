@echo off
rem Extra llama.cpp Android variants for the Phase 1 speed follow-up:
rem   kleidi  = Tensor G4 CPU flags + Arm KleidiAI kernels
rem   vulkan  = Tensor G4 CPU flags + Vulkan GPU backend (Mali-G715)
rem Usage: scripts\build-llama-android-extra.cmd [kleidi|vulkan|all]
setlocal
cd /d "%~dp0.."
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "NDK=%SDK%\ndk\28.2.13676358"
set "CMAKE=%SDK%\cmake\3.22.1\bin\cmake.exe"
set "NINJA=%SDK%\cmake\3.22.1\bin\ninja.exe"
set "SRC=third_party\llama.cpp"
set "ARCH=armv8.6-a+dotprod+i8mm+fp16"
set "TARGETS=llama-bench llama-completion llama-speculative-simple"
set "VARIANT=%~1"
if "%VARIANT%"=="" set "VARIANT=all"

rem The Vulkan shader generator is a host program, so it needs MSVC on PATH.
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || goto :fail

if /i "%VARIANT%"=="kleidi" call :kleidi || goto :fail
if /i "%VARIANT%"=="vulkan" call :vulkan || goto :fail
if /i "%VARIANT%"=="all" (
  call :kleidi || goto :fail
  call :vulkan || goto :fail
)
echo == BUILD-EXTRA DONE
exit /b 0

:common
"%CMAKE%" -S "%SRC%" -B "%OUT%" -G Ninja -DCMAKE_MAKE_PROGRAM="%NINJA%" ^
  -DCMAKE_TOOLCHAIN_FILE="%NDK%\build\cmake\android.toolchain.cmake" ^
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release ^
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF ^
  -DBUILD_SHARED_LIBS=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF ^
  -DGGML_CPU_ARM_ARCH=%ARCH% %EXTRA% || exit /b 1
"%CMAKE%" --build "%OUT%" --config Release -j 12 --target %TARGETS% || exit /b 1
exit /b 0

:kleidi
echo == Building kleidi
set "OUT=build\llama-android-kleidi"
set "EXTRA=-DGGML_CPU_KLEIDIAI=ON "-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=%CD%\third_party\kleidiai""
call :common
exit /b %errorlevel%

:vulkan
echo == Building vulkan
rem The Vulkan shader generator's ExternalProject breaks on spaces in the build path
rem ("Local Phone Ai Assistant" becomes "Local;Phone;Ai;Assistant"), so build from a temporary
rem drive letter that maps to this folder, and remove the mapping afterwards.
if not exist build\tools\glslc.exe ( mkdir build\tools 2>nul & copy /y "%NDK%\shader-tools\windows-x86_64\*" build\tools\ >nul )
subst V: "%CD%" || exit /b 1
pushd V:\
call :vulkan_inner
set "RC=%errorlevel%"
popd
subst V: /d
exit /b %RC%

:vulkan_inner
set "OUT=build\llama-android-vulkan"
set "VKLIB=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\29\libvulkan.so"
set "EXTRA=-DGGML_VULKAN=ON "-DVulkan_INCLUDE_DIR=%CD%\third_party\Vulkan-Headers\include" "-DVulkan_LIBRARY=%VKLIB%" "-DVulkan_GLSLC_EXECUTABLE=%CD%\build\tools\glslc.exe" "-DSPIRV-Headers_DIR=%CD%\build\spirv-headers-install\share\cmake\SPIRV-Headers" "-DCMAKE_CXX_FLAGS=-I%CD:\=/%build/spirv-headers-install/include""
call :common
exit /b %errorlevel%

:fail
echo == BUILD-EXTRA FAILED
exit /b 1
