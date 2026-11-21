@echo off
setlocal
cd /d "%~dp0"
if not exist "app\serve.mjs" (
  echo Extract the complete prism Windows controller release to start the web app.
  pause
  exit /b 1
)
"runtime\node.exe" "app\serve.mjs" --open
if errorlevel 1 pause
