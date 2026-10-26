<#
.SYNOPSIS
IPCam2 Driver Installer for Windows - Scheduled Task

.DESCRIPTION
Installs or removes a scheduled task that auto-starts the IPCam2 driver
when the user logs in. The driver watches for ADB device connection
and bridges the phone camera to OBS Virtual Camera.

.PARAMETER Install
Install the scheduled task

.PARAMETER Uninstall
Remove the scheduled task

.EXAMPLE
.\install-driver.ps1 -Install
.\install-driver.ps1 -Uninstall
#>

param(
    [switch]$Install,
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"
$TaskName = "IPCam2 Driver"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$DriverScript = Join-Path $ScriptDir "ipcam2-driver.ps1"

function Test-Requirements {
    if (-not (Get-Command pwsh -ErrorAction SilentlyContinue)) {
        Write-Host "[ERROR] PowerShell 7 (pwsh) is required." -ForegroundColor Red
        Write-Host "Install: https://github.com/PowerShell/PowerShell" -ForegroundColor Yellow
        exit 1
    }
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        Write-Host "[WARNING] adb not found in PATH. Driver won't detect devices." -ForegroundColor Yellow
    }
    if (-not (Test-Path $DriverScript)) {
        Write-Host "[ERROR] $DriverScript not found." -ForegroundColor Red
        exit 1
    }
}

function Install-Task {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  IPCam2 Driver - Install Scheduled Task" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host ""

    Test-Requirements

    $user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    Write-Host "User: $user"
    Write-Host "Script: $DriverScript"

    $action = New-ScheduledTaskAction -Execute "pwsh" -Argument @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden",
        "-File", "`"$DriverScript`""
    )

    $trigger = New-ScheduledTaskTrigger -AtLogon -User $user

    $settings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -StartWhenAvailable `
        -RestartCount 9999 `
        -RestartInterval (New-TimeSpan -Minutes 1) `
        -MultipleInstances IgnoreNew

    try {
        Register-ScheduledTask -TaskName $TaskName `
            -Action $action `
            -Trigger $trigger `
            -Settings $settings `
            -Description "Auto-start IPCam2 webcam bridge when phone is plugged in via USB" `
            -Force | Out-Null

        Write-Host ""
        Write-Host "[OK] Scheduled task 'IPCam2 Driver' installed." -ForegroundColor Green
        Write-Host ""
        Write-Host "The driver will auto-start when you log in."
        Write-Host "It watches for ADB devices and bridges the phone camera to OBS."
        Write-Host ""
        Write-Host "To start now: schtasks /run /tn `"IPCam2 Driver`"" -ForegroundColor Yellow
        Write-Host "To uninstall:  .\install-driver.ps1 -Uninstall" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "OBS Setup:" -ForegroundColor Cyan
        Write-Host "  1. Add Media Source (uncheck Local File)" 
        Write-Host "     URL: http://localhost:8085/video"
        Write-Host "     Network Caching: 1000ms"
        Write-Host "  2. Tools > Virtual Camera > Start"
        Write-Host "  3. Install VB-Cable for audio: https://vb-audio.com/Cable/"
        Write-Host ""
        Write-Host "Optional: Import OBS scene collection" -ForegroundColor Yellow
        Write-Host "  OBS > Scene Collection > Import > Select tools/obs-ipcam2/IPCam2.json"
    } catch {
        Write-Host "[ERROR] Failed to create task: $_" -ForegroundColor Red
        exit 1
    }
}

function Uninstall-Task {
    Write-Host ""
    Write-Host "Removing scheduled task 'IPCam2 Driver'..."
    try {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop
        Write-Host "[OK] Task removed." -ForegroundColor Green
    } catch {
        Write-Host "[INFO] Task not found or already removed." -ForegroundColor Yellow
    }
}

# Main
if ($Install) {
    Install-Task
} elseif ($Uninstall) {
    Uninstall-Task
} else {
    Write-Host "IPCam2 Driver Installer" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Usage:"
    Write-Host "  .\install-driver.ps1 -Install      Install scheduled task"
    Write-Host "  .\install-driver.ps1 -Uninstall    Remove scheduled task"
    Write-Host ""
    Write-Host "Or double-click install-driver.bat"
}