@echo off
powershell.exe -NoProfile -File "%~dp0tools\gradle.ps1" %*
exit /b %errorlevel%
