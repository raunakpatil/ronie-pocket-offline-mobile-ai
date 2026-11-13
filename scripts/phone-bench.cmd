@echo off
rem Pushes llama.cpp tools + GGUF models to the Pixel and runs speed benchmarks and a greedy sanity check.
rem Usage: scripts\phone-bench.cmd   (results land in build\phone-bench\)
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "DEV=/data/local/tmp/ronie"
set "OUTDIR=build\phone-bench"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

rem --- find the Pixel 9 Pro (never another adb device)
set "SERIAL="
for /f "usebackq tokens=1" %%S in (`call "%ADB%" devices -l ^| findstr /c:"model:Pixel_9_Pro"`) do if not defined SERIAL set "SERIAL=%%S"
if not defined SERIAL (
  for /f "usebackq tokens=3" %%A in (`call "%ADB%" mdns services ^| findstr /c:"_adb-tls-connect" ^| findstr /c:"%RONIE_PHONE_ID%"`) do if not defined SERIAL set "SERIAL=%%A"
  if defined SERIAL "%ADB%" connect !SERIAL!
)
if not defined SERIAL ( echo Pixel 9 Pro not found & exit /b 1 )
set "A="%ADB%" -s %SERIAL%"
echo == Using %SERIAL%

rem --- push binaries
for %%V in (g4 portable) do (
  if exist "build\llama-android-%%V\bin\llama-bench" (
    %A% shell mkdir -p %DEV%/%%V
    %A% push -q "build\llama-android-%%V\bin\llama-bench" %DEV%/%%V/
    %A% push -q "build\llama-android-%%V\bin\llama-completion" %DEV%/%%V/
    %A% push -q "build\llama-android-%%V\bin\llama-mtmd-cli" %DEV%/%%V/
    %A% shell chmod 755 %DEV%/%%V/llama-bench %DEV%/%%V/llama-completion %DEV%/%%V/llama-mtmd-cli
  )
)

rem --- push models (skips files already on the phone with the same size)
%A% shell mkdir -p %DEV%/models
for %%M in (Qwen3.5-0.8B-Q4_K_M.gguf Qwen3.5-0.8B-Q8_0.gguf Qwen3.5-2B-Q4_K_M.gguf mmproj-F16.gguf) do (
  if exist "models-local\%%M" (
    echo == pushing %%M
    %A% push --sync "models-local\%%M" %DEV%/models/ >nul
  )
)

rem --- speed: prompt processing (pp256) and generation (tg64), pinned to the big cores (cpus 4-7 = 3x A720 + 1x X4).
rem     Unpinned runs from adb shell land on little cores and vary 3-20 tok/s, so always pin.
for %%V in (g4 portable) do (
  for %%M in (Qwen3.5-0.8B-Q4_K_M Qwen3.5-0.8B-Q8_0 Qwen3.5-2B-Q4_K_M) do (
    echo == bench %%V %%M
    %A% shell "cd %DEV% && taskset f0 ./%%V/llama-bench -m models/%%M.gguf -p 256 -n 64 -t 3,4 -r 3 -o md" > "%OUTDIR%\bench-%%V-%%M.md" 2>&1
    type "%OUTDIR%\bench-%%V-%%M.md"
  )
)

rem --- greedy sanity check (compare with desktop later)
for %%M in (Qwen3.5-0.8B-Q4_K_M Qwen3.5-2B-Q4_K_M) do (
  echo == sanity %%M
  %A% shell "cd %DEV% && taskset f0 ./g4/llama-completion -m models/%%M.gguf -p 'The three primary colors are' -n 40 --temp 0 -t 4 -no-cnv" > "%OUTDIR%\sanity-%%M.txt" 2>&1
)

rem --- thermals and memory after the run
%A% shell "dumpsys thermalservice | grep -E 'Current temperatures|Temperature\{' | head -12" > "%OUTDIR%\thermal.txt"
echo == PHONE-BENCH DONE
exit /b 0
