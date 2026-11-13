@echo off
rem Downloads llama.cpp's official prebuilt Windows CUDA 12.4 build (release b12202) into tools-pc\bin\llama-cuda.
setlocal
cd /d "%~dp0.."
set "TAG=b12202"
set "BASE=https://github.com/ggml-org/llama.cpp/releases/download/%TAG%"
set "DL=tools-pc\bin\downloads"
if not exist "%DL%" mkdir "%DL%"
for %%F in (llama-%TAG%-bin-win-cuda-12.4-x64.zip cudart-llama-bin-win-cuda-12.4-x64.zip) do (
  echo == downloading %%F
  curl.exe -fL --retry 3 --retry-delay 5 -o "%DL%\%%F" "%BASE%/%%F" || goto :fail
)
echo == unpacking
powershell -NoProfile -Command "Get-ChildItem '%DL%' -Filter *.zip | ForEach-Object { Expand-Archive -Force $_.FullName -DestinationPath 'tools-pc\bin\llama-cuda' }" || goto :fail
if not exist tools-pc\bin\llama-cuda\llama-server.exe goto :fail
echo == LLAMA-CUDA READY
exit /b 0
:fail
echo == LLAMA-CUDA FAILED
exit /b 1
