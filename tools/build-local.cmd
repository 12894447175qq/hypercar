@echo off
rem HyperCar 本地出包（双击即可）：调用 tools\build-local.ps1
rem 需要先装好 D:\devtools 下的 JDK / Android SDK / Rust（见 tools\build-local.ps1 注释）
setlocal
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-local.ps1" %*
if errorlevel 1 (
  echo.
  echo [FAILED] 构建失败，详见上面的输出。
)
pause
