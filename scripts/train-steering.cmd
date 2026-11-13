@echo off
rem Phase 3.5: trains a steering direction (control vector) on the PC.
rem Usage: scripts\train-steering.cmd swear
rem Needs scripts\patches\llama-cvector-all-outputs.patch applied to third_party\llama.cpp (Gemma 4 support).
setlocal
cd /d "%~dp0.."
set "AXIS=%~1"
if "%AXIS%"=="" set "AXIS=swear"
set "PY=tools-pc\.venv\Scripts\python.exe"
call scripts\build-llama-pc.cmd || goto :fail
"%PY%" eval\steering\make_pairs.py %AXIS% || goto :fail
build\llama-pc\bin\llama-cvector-generator.exe -m models-local\gemma-4-E2B-it-Q4_K_M.gguf ^
  --positive-file eval\steering\%AXIS%.pos.txt --negative-file eval\steering\%AXIS%.neg.txt ^
  --method mean -c 1024 -t 8 -o eval\steering\%AXIS%-gemma4e2b.gguf || goto :fail
echo == STEERING DONE: eval\steering\%AXIS%-gemma4e2b.gguf
exit /b 0
:fail
echo == STEERING FAILED
exit /b 1
