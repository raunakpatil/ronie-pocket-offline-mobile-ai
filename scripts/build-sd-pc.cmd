@echo off
rem Builds stable-diffusion.cpp's command-line tool (CPU) for this PC, from the same source as the phone build.
rem Used to compare samplers and schedulers on the PC with the exact model the phone runs. Output: build\sd-pc\bin\
setlocal
cd /d "%~dp0.."
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "CMAKE=%SDK%\cmake\3.22.1\bin\cmake.exe"
set "NINJA=%SDK%\cmake\3.22.1\bin\ninja.exe"
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || goto :fail
"%CMAKE%" -S third_party\stable-diffusion.cpp -B build\sd-pc -G Ninja -DCMAKE_MAKE_PROGRAM="%NINJA%" ^
  -DCMAKE_BUILD_TYPE=Release -DSD_BUILD_EXAMPLES=ON -DSD_WEBP=OFF -DSD_WEBM=OFF -DBUILD_SHARED_LIBS=OFF || goto :fail
"%CMAKE%" --build build\sd-pc --config Release -j 12 || goto :fail
echo == SD-PC DONE
exit /b 0
:fail
echo == SD-PC FAILED
exit /b 1
