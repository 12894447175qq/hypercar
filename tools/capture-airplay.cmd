@echo off
REM ============================================================
REM  Capture "box -> WinPlay" AirPlay traffic (double-click to run)
REM
REM  Why admin: pktmon talks to the PktMon kernel driver; without
REM  elevation it fails with "Access denied". This launcher asks
REM  for UAC once and starts the capture (all traffic, 120s).
REM
REM  Steps:
REM    1) Open WinPlay and click "start receiving" first
REM    2) Double-click this file, accept UAC
REM    3) Within 120s, make the box connect to WinPlay
REM    4) The script prints the resulting .pcapng path
REM ============================================================
setlocal
set SCRIPT=%~dp0capture-airplay.ps1

powershell -NoProfile -Command ^
  "Start-Process -FilePath powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','\"%SCRIPT%\"','-All','-Seconds','120'"

echo.
echo Admin capture requested (120 seconds).
echo Now: click "start receiving" in WinPlay, then connect the box to it.
echo Output: %LOCALAPPDATA%\hypercar-hub\captures\
echo.
pause
