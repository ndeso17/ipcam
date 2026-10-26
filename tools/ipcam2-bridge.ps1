#Requires -Version 5.1
<#
.SYNOPSIS
    Bridges Android IPCam2 app to OBS via ADB port forwarding.

.DESCRIPTION
    Sets up ADB port forwarding for IPCam2 video (MJPEG) and audio (AAC) streams,
    opens the status page in a browser, and prints OBS Virtual Camera + VB-Cable
    setup instructions.

.PARAMETER VideoPort
    Local port for video stream forwarding. Default: 8085

.PARAMETER AudioPort
    Local port for audio stream forwarding. Default: 8081

.PARAMETER LaunchOBS
    If specified, attempts to launch OBS after setting up forwarding.

.EXAMPLE
    .\ipcam2-bridge.ps1
    .\ipcam2-bridge.ps1 -VideoPort 9090 -AudioPort 9091
    .\ipcam2-bridge.ps1 -LaunchOBS
#>

param(
    [int]$VideoPort = 8085,
    [int]$AudioPort = 8085,
    [switch]$LaunchOBS
)

# --- Validation ---

# Check for ADB
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    Write-Host "ERROR: adb not found in PATH." -ForegroundColor Red
    Write-Host "Install Android Platform Tools and add to PATH:" -ForegroundColor Yellow
    Write-Host "  https://developer.android.com/tools/releases/platform-tools" -ForegroundColor Yellow
    exit 1
}

# Check for connected device
$devices = adb devices 2>&1 | Select-String -Pattern "^\w+\s+device$"
if (-not $devices) {
    Write-Host "ERROR: No Android device connected." -ForegroundColor Red
    Write-Host "Connect device via USB and enable USB debugging." -ForegroundColor Yellow
    exit 1
}

Write-Host "=== IPCam2 Bridge ===" -ForegroundColor Cyan
Write-Host "Device: $($devices[0].ToString().Split()[0])" -ForegroundColor Gray

# --- Port Forwarding ---

try {
    Write-Host "Setting up port forwarding..." -ForegroundColor White
    
    $fwdVideo = adb forward tcp:$VideoPort tcp:$VideoPort 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Failed to forward video port $VideoPort" -ForegroundColor Red
        Write-Host $fwdVideo -ForegroundColor Red
        exit 1
    }
    
    $fwdAudio = adb forward tcp:$AudioPort tcp:$AudioPort 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Failed to forward audio port $AudioPort" -ForegroundColor Red
        Write-Host $fwdAudio -ForegroundColor Red
        exit 1
    }
    
    Write-Host "  Video: tcp:$VideoPort -> tcp:$VideoPort" -ForegroundColor Green
    Write-Host "  Audio: tcp:$AudioPort -> tcp:$AudioPort" -ForegroundColor Green
    
    # Open browser to status page
    Write-Host "Opening status page..." -ForegroundColor White
    Start-Process "http://localhost:$VideoPort"
    
    # --- OBS Setup Instructions ---
    
    Write-Host ""
    Write-Host "=== OBS Setup ===" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "1. Add Media Source:" -ForegroundColor White
    Write-Host "   - Sources > + > Media Source" -ForegroundColor Gray
    Write-Host "   - Uncheck 'Local File'" -ForegroundColor Gray
    Write-Host "   - Input: http://localhost:$VideoPort/video" -ForegroundColor Yellow
    Write-Host "   - Input Format: (leave blank)" -ForegroundColor Gray
    Write-Host ""
    Write-Host "2. Virtual Camera (OBS 28+):" -ForegroundColor White
    Write-Host "   - Tools > Virtual Camera > Start" -ForegroundColor Gray
    Write-Host ""
    Write-Host "3. Audio via VB-Cable:" -ForegroundColor White
    Write-Host "   - Install VB-Cable: https://vb-audio.com/Cable/" -ForegroundColor Yellow
    Write-Host "   - Windows Sound Settings > Output: CABLE Input" -ForegroundColor Gray
    Write-Host "   - OBS > Settings > Audio > Desktop Audio: CABLE Output" -ForegroundColor Gray
    Write-Host "   - Browser: http://localhost:$AudioPort/audio" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Press Ctrl+C to stop forwarding and exit." -ForegroundColor Gray
    Write-Host ""
    
    # --- Launch OBS (optional) ---
    
    if ($LaunchOBS) {
        Write-Host "Launching OBS..." -ForegroundColor White
        
        # Try PATH first
        $obs = Get-Command obs64 -ErrorAction SilentlyContinue
        if ($obs) {
            Start-Process $obs.Source
        }
        else {
            # Try common install locations
            $obsPaths = @(
                "$env:ProgramFiles\obs-studio\bin\64bit\obs64.exe",
                "${env:ProgramFiles(x86)}\obs-studio\bin\64bit\obs64.exe",
                "$env:LOCALAPPDATA\Programs\obs-studio\bin\64bit\obs64.exe"
            )
            
            $obsPath = $obsPaths | Where-Object { Test-Path $_ } | Select-Object -First 1
            
            if ($obsPath) {
                Start-Process $obsPath
            }
            else {
                Write-Host "WARNING: Could not find OBS installation." -ForegroundColor Yellow
                Write-Host "Launch OBS manually or install from: https://obsproject.com" -ForegroundColor Yellow
            }
        }
    }
    
    # --- Wait for exit ---
    
    # Keep running until Ctrl+C or process termination
    Write-Host "Bridge active. Waiting..." -ForegroundColor Green
    while ($true) {
        Start-Sleep -Seconds 1
    }
}
finally {
    # --- Cleanup ---
    
    Write-Host ""
    Write-Host "Cleaning up port forwarding..." -ForegroundColor White
    
    $removeVideo = adb forward --remove tcp:$VideoPort 2>&1
    $removeAudio = adb forward --remove tcp:$AudioPort 2>&1
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Removed video forward: tcp:$VideoPort" -ForegroundColor Gray
        Write-Host "  Removed audio forward: tcp:$AudioPort" -ForegroundColor Gray
    }
    
    Write-Host "Bridge stopped." -ForegroundColor Cyan
}