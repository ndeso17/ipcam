@echo off
setlocal enabledelayedexpansion
title IPCam2 Driver Installer

echo ========================================
echo   IPCam2 Driver - Windows Installer
echo ========================================
echo.

:: Find script directory
set "SCRIPT_DIR=%~dp0"
set "DRIVER_PS1=%SCRIPT_DIR%ipcam2-driver.ps1"

if not exist "%DRIVER_PS1%" (
    echo [ERROR] ipcam2-driver.ps1 not found at %DRIVER_PS1%
    pause
    exit /b 1
)

:: Check for PowerShell
where pwsh >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] PowerShell 7 (pwsh) not found in PATH.
    echo Install it from: https://github.com/PowerShell/PowerShell
    pause
    exit /b 1
)

:: Check for ADB
where adb >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARNING] adb not found in PATH. Driver won't work without ADB.
)

echo.
echo Choose action:
echo   1. Install scheduled task (auto-start at login)
echo   2. Uninstall scheduled task
echo   3. Exit
echo.
set /p CHOICE="Enter choice [1-3]: "

if "%CHOICE%"=="1" goto install
if "%CHOICE%"=="2" goto uninstall
if "%CHOICE%"=="3" goto end
echo Invalid choice.
goto end

:install
echo.
echo Creating scheduled task "IPCam2 Driver"...
pwsh -NoProfile -ExecutionPolicy Bypass -Command ^
    "$action = New-ScheduledTaskAction -Execute 'pwsh' -Argument '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"%DRIVER_PS1%\"';" ^
    "$trigger = New-ScheduledTaskTrigger -AtLogon -User ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name);" ^
    "$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RestartCount 9999 -RestartInterval (New-TimeSpan -Minutes 1);" ^
    "Register-ScheduledTask -TaskName 'IPCam2 Driver' -Action $action -Trigger $trigger -Settings $settings -Description 'Auto-start IPCam2 webcam bridge when phone is plugged in' -Force;"

if %errorlevel% equ 0 (
    echo [OK] Task installed. Will auto-start at next login.
    echo.
    echo Run now: schtasks /run /tn "IPCam2 Driver"
) else (
    echo [ERROR] Failed to create scheduled task.
)
goto end

:uninstall
echo.
echo Removing scheduled task "IPCam2 Driver"...
schtasks /delete /tn "IPCam2 Driver" /f 2>nul
if %errorlevel% equ 0 (
    echo [OK] Task removed.
) else (
    echo [INFO] Task not found or already removed.
)
goto end

:end
echo.
pause