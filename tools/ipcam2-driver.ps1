#!/usr/bin/env pwsh
<#
.SYNOPSIS
IPCam2 Driver — Auto-detect ADB device, launch app, bridge to OBS.

.DESCRIPTION
Runs as a background watcher. When Android device is plugged in via USB (ADB):
1. Auto-launches IPCam2 app
2. Sets up ADB port forwarding
3. Opens browser to status page
4. When device disconnects: cleans up, waits for reconnection

.PARAMETER VideoPort
TCP port for MJPEG video stream (default: 8085)

.PARAMETER AudioPort
TCP port for AAC audio stream (default: 8085)

.PARAMETER Daemon
Run as background watcher

.EXAMPLE
.\ipcam2-driver.ps1
.\ipcam2-driver.ps1 -Daemon
#>

param(
    [int]$VideoPort = 8085,
    [int]$AudioPort = 8085,
    [switch]$Daemon
)

$ErrorActionPreference = "Stop"
$script:Running = $true
$script:LastSerial = $null

function Write-Timestamp {
    param([string]$Message)
    $stamp = Get-Date -Format "HH:mm:ss"
    Write-Host "[$stamp] $Message"
}

function Get-AdbDevice {
    $output = adb devices 2>$null | Select-Object -Skip 1
    foreach ($line in $output) {
        if ($line -match "^\s*(\S+)\s+device") {
            return $Matches[1]
        }
    }
    return $null
}

function Start-App {
    param([string]$Serial)
    Write-Timestamp "Launching IPCam2 app..."
    adb -s $Serial shell am start -n com.example.ipcam/.MainActivity 2>$null
    Start-Sleep -Seconds 3
}

function Setup-Forward {
    param([string]$Serial)
    adb -s $Serial forward tcp:$VideoPort tcp:$VideoPort 2>$null
    adb -s $Serial forward tcp:$AudioPort tcp:$AudioPort 2>$null
    Write-Timestamp "Forwarded localhost:$VideoPort → device:$VideoPort"
}

function Remove-Forward {
    param([string]$Serial)
    adb -s $Serial forward --remove tcp:$VideoPort 2>$null
    adb -s $Serial forward --remove tcp:$AudioPort 2>$null
}

function Test-Server {
    try {
        $req = [System.Net.WebRequest]::Create("http://localhost:$VideoPort/")
        $req.Timeout = 2000
        $req.GetResponse().Close()
        return $true
    } catch {
        return $false
    }
}

function Watch-Device {
    Write-Timestamp "IPCam2 Driver started. Waiting for ADB device..."
    Write-Timestamp "Plug in your phone via USB with ADB debugging enabled."
    Write-Timestamp ""

    while ($script:Running) {
        $serial = Get-AdbDevice

        if ($serial -and $serial -ne $script:LastSerial) {
            $script:LastSerial = $serial
            try {
                Write-Timestamp "Device $serial detected."
                Start-App -Serial $serial
                Setup-Forward -Serial $serial

                Write-Timestamp "Waiting for server..."
                $deadline = (Get-Date).AddSeconds(15)
                $ready = $false
                while ((Get-Date) -lt $deadline) {
                    if (Test-Server) { $ready = $true; break }
                    Start-Sleep -Seconds 1
                }

                if ($ready) {
                    Write-Timestamp "Server ready."
                    Write-Timestamp "Bridge active."
                    Write-Timestamp ""
                    Write-Timestamp "OBS Setup:"
                    Write-Timestamp "  1. Add Media Source (uncheck Local File)"
                    Write-Timestamp "     URL: http://localhost:$VideoPort/video"
                    Write-Timestamp "     Network Caching: 1000ms"
                    Write-Timestamp "  2. Tools > Virtual Camera > Start"
                    Write-Timestamp " "
                    Write-Timestamp "If stream stops, press START in app again."
                    # Open browser as visual indicator
                    Start-Process "http://localhost:$VideoPort"
                } else {
                    Write-Timestamp "WARNING: Server not reachable after 15s."
                    Write-Timestamp "Make sure IPCam2 app is running and START is pressed."
                }
            } catch {
                Write-Timestamp "Setup failed: $_"
            }
        }
        elseif (-not $serial -and $script:LastSerial) {
            Write-Timestamp "Device $($script:LastSerial) disconnected. Cleaning up..."
            Remove-Forward -Serial $script:LastSerial
            $script:LastSerial = $null
            Write-Timestamp "Waiting for device..."
        }

        Start-Sleep -Seconds 2
    }

    if ($script:LastSerial) {
        Remove-Forward -Serial $script:LastSerial
    }
    Write-Timestamp "Driver stopped."
}

try {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        Write-Host "ERROR: adb not found in PATH." -ForegroundColor Red
        exit 1
    }

    $consoleHandler = [Console]::TreatControlCAsInput = $false
    [Console]::CancelKeyPress += { $script:Running = $false }

    Watch-Device
} finally {
    Write-Timestamp "Goodbye."
}