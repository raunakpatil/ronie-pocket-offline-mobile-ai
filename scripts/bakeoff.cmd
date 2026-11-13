@echo off
rem Runs the Phase 2 bake-off on this PC. Extra args are passed through, e.g.:
rem   scripts\bakeoff.cmd --models qwen08b --sets tools
setlocal
cd /d "%~dp0.."
tools-pc\.venv\Scripts\python.exe -u eval\run_bakeoff.py %*
echo == BAKEOFF EXIT %errorlevel%
