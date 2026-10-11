@echo off
setlocal
title MyDesk Official Release
powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0scripts\Build-OfficialReleaseWizard.ps1"
set "MYDESK_BUILD_EXIT=%ERRORLEVEL%"
echo.
pause
exit /b %MYDESK_BUILD_EXIT%
