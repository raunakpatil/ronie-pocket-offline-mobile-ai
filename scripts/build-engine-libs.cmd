@echo off
rem Builds llama.cpp as Android shared libraries for the app's engine module:
rem   - every Android CPU variant (armv8.0 ... armv9.2+SME), chosen at runtime (GGML_BACKEND_DL)
rem   - Arm KleidiAI kernels, Vulkan GPU backend
rem Installs libs to core\engine\prebuilt\arm64-v8a and headers to core\engine\prebuilt\include.
rem Usage: scripts\build-engine-libs.cmd
setlocal
cd /d "%~dp0.."
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "NDK=%SDK%\ndk\28.2.13676358"
set "CMAKE=%SDK%\cmake\3.22.1\bin\cmake.exe"
set "NINJA=%SDK%\cmake\3.22.1\bin\ninja.exe"
set "ROOT=%CD%"

call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || goto :fail
if not exist build\tools\glslc.exe ( mkdir build\tools 2>nul & copy /y "%NDK%\shader-tools\windows-x86_64\*" build\tools\ >nul )
if not exist build\spirv-headers-install\share\cmake\SPIRV-Headers ( echo SPIRV-Headers missing; see build-llama-android-extra.cmd & goto :fail )

rem The Vulkan shader generator breaks on spaces in paths, so build from a temporary drive letter.
subst V: "%ROOT%" || goto :fail
pushd V:\
set "VKLIB=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\29\libvulkan.so"
"%CMAKE%" -S third_party\llama.cpp -B build\engine-libs -G Ninja -DCMAKE_MAKE_PROGRAM="%NINJA%" ^
  -DCMAKE_TOOLCHAIN_FILE="%NDK%\build\cmake\android.toolchain.cmake" ^
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release ^
  -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_CPU_KLEIDIAI=ON ^
  "-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=V:/third_party/kleidiai" ^
  -DGGML_VULKAN=ON "-DVulkan_INCLUDE_DIR=V:/third_party/Vulkan-Headers/include" "-DVulkan_LIBRARY=%VKLIB%" ^
  "-DVulkan_GLSLC_EXECUTABLE=V:/build/tools/glslc.exe" "-DSPIRV-Headers_DIR=V:/build/spirv-headers-install/share/cmake/SPIRV-Headers" ^
  "-DCMAKE_CXX_FLAGS=-IV:/build/spirv-headers-install/include" ^
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF ^
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF ^
  -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_COMMON=OFF ^
  -DLLAMA_BUILD_MTMD=ON -DMTMD_VIDEO=OFF ^
  "-DCMAKE_INSTALL_PREFIX=V:/build/engine-libs-install"
if errorlevel 1 goto :unsubst_fail
"%CMAKE%" --build build\engine-libs --config Release -j 12
if errorlevel 1 goto :unsubst_fail
"%CMAKE%" --install build\engine-libs --config Release >nul
if errorlevel 1 goto :unsubst_fail
popd
subst V: /d

rem Copy into the engine module (strip debug symbols to keep the APK small).
set "STRIP=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
set "OUT=core\engine\prebuilt"
set "LIBS=core\engine\src\main\jniLibs\arm64-v8a"
if exist "%OUT%" rmdir /s /q "%OUT%"
if exist "%LIBS%" rmdir /s /q "%LIBS%"
mkdir "%OUT%\include" "%LIBS%"
for /r build\engine-libs-install %%L in (*.so) do (
  copy /y "%%L" "%LIBS%\" >nul
  "%STRIP%" --strip-unneeded "%LIBS%\%%~nxL"
)
for /r build\engine-libs\bin %%L in (*.so) do if not exist "%LIBS%\%%~nxL" (
  copy /y "%%L" "%LIBS%\" >nul
  "%STRIP%" --strip-unneeded "%LIBS%\%%~nxL"
)
xcopy /y /q build\engine-libs-install\include\*.h "%OUT%\include\" >nul
dir /b "%LIBS%"
echo == ENGINE-LIBS DONE
exit /b 0

:unsubst_fail
popd
subst V: /d
:fail
echo == ENGINE-LIBS FAILED
exit /b 1
