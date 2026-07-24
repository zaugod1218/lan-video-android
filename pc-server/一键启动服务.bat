@echo off
chcp 65001 >nul
title 局域网种子影院 - 电脑服务

where pwsh.exe >nul 2>nul
if %errorlevel% equ 0 (
    pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0one-click-server.ps1"
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0one-click-server.ps1"
)

if errorlevel 1 (
    echo.
    echo 启动失败，请查看上面的错误信息。
    pause
)
