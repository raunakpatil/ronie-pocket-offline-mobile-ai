@echo off
rem Builds stable-diffusion.cpp as an Android shared library for the app's imagegen module:
rem   - arm64-v8a ABI
rem   - Vulkan GPU backend (Mali-G715) with CPU fallback
rem Installs libs to core\imagegen\src\main\jniLibs\arm64-v8a and headers to core\imagegen\prebuilt\include.
rem Usage: scripts\build-sd-libs.cmd
setlocal
cd /d "%~dp0.."
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "NDK=%SDK%\ndk\28.2.13676358"
set "CMAKE=%SDK%\cmake\3.22.1\bin\cmake.exe"
set "NINJA=%SDK%\cmake\3.22.1\bin\ninja.exe"
set "ROOT=%CD%"

call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || goto :fail
if not exist build\tools\glslc.exe ( mkdir build\tools 2>nul & copy /y "%NDK%\shader-tools\windows-x86_64\*" build\tools\ >nul )
if not exist build\spirv-headers-install\share\cmake\SPIRV-Headers ( echo SPIRV-Headers missing & goto :fail )

rem The Vulkan shader generator breaks on spaces in paths, so build from a temporary drive letter.
subst V: "%ROOT%" || goto :fail
pushd V:\
set "VKLIB=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\29\libvulkan.so"

"%CMAKE%" -S third_party\stable-diffusion.cpp -B build\sd-libs -G Ninja -DCMAKE_MAKE_PROGRAM="%NINJA%" ^
  -DCMAKE_TOOLCHAIN_FILE="%NDK%\build\cmake\android.toolchain.cmake" ^
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release ^
  -DSD_BUILD_SHARED_LIBS=ON -DSD_BUILD_EXAMPLES=OFF -DSD_WEBP=OFF -DSD_WEBM=OFF ^
  -DSD_VULKAN=ON "-DVulkan_INCLUDE_DIR=V:/third_party/Vulkan-Headers/include" "-DVulkan_LIBRARY=%VKLIB%" ^
  "-DVulkan_GLSLC_EXECUTABLE=V:/build/tools/glslc.exe" "-DSPIRV-Headers_DIR=V:/build/spirv-headers-install/share/cmake/SPIRV-Headers" ^
  "-DCMAKE_CXX_FLAGS=-IV:/build/spirv-headers-install/include" ^
  "-DCMAKE_INSTALL_PREFIX=V:/build/sd-libs-install"
if errorlevel 1 goto :unsubst_fail
"%CMAKE%" --build build\sd-libs --config Release -j 12
if errorlevel 1 goto :unsubst_fail
"%CMAKE%" --install build\sd-libs --config Release >nul
if errorlevel 1 goto :unsubst_fail
popd
subst V: /d

set "STRIP=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
set "OUT=core\imagegen\prebuilt"
set "LIBS=core\imagegen\src\main\jniLibs\arm64-v8a"
if exist "%OUT%" rmdir /s /q "%OUT%"
if exist "%LIBS%" rmdir /s /q "%LIBS%"
mkdir "%OUT%\include" "%LIBS%"
for /r build\sd-libs-install %%L in (*.so) do (
  copy /y "%%L" "%LIBS%\" >nul
  "%STRIP%" --strip-unneeded "%LIBS%\%%~nxL"
)
for /r build\sd-libs\bin %%L in (*.so) do if not exist "%LIBS%\%%~nxL" (
  copy /y "%%L" "%LIBS%\" >nul
  "%STRIP%" --strip-unneeded "%LIBS%\%%~nxL"
)
for /r "%NDK%\toolchains\llvm\prebuilt\windows-x86_64\lib\clang" %%F in (libomp.so) do (
  echo %%F | findstr /i "aarch64" >nul && (
    copy /y "%%F" "%LIBS%\" >nul
    "%STRIP%" --strip-unneeded "%LIBS%\libomp.so"
  )
)
xcopy /y /q third_party\stable-diffusion.cpp\include\*.h "%OUT%\include\" >nul
dir /b "%LIBS%"
echo == SD-LIBS DONE
exit /b 0

:unsubst_fail
popd
subst V: /d
:fail
echo == SD-LIBS FAILED
exit /b 1
