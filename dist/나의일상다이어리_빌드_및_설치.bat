@echo off
chcp 65001 >nul
setlocal
title 나의일상다이어리 빌드 및 설치 도구

if /i "%~1"=="--admin" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build_and_deploy.ps1" -Elevate
    exit /b %ERRORLEVEL%
)

if "%~1"=="" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build_and_deploy.ps1" -Action Menu
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build_and_deploy.ps1" -Action %~1
)
endlocal
