# OBS Profile for IPCam2

## Quick Start (Manual)
1. Connect phone via USB, enable ADB debugging
2. Run: `.\tools\ipcam2-bridge.ps1` (Windows) or `python3 tools/ipcam2-bridge.py` (Linux)
3. Press START in IPCam2 app on phone
4. In OBS: Add Media Source → uncheck "Local File" → URL: `http://localhost:8085/video`
5. Tools → Virtual Camera → Start

## Auto-Start (Scheduled Task)
```powershell
# Install (runs on login, watches for phone)
.\tools\install-driver.ps1 -Install

# Uninstall
.\tools\install-driver.ps1 -Uninstall
```

## Import OBS Scene Collection
1. OBS → Scene Collection → Import
2. Select `tools/obs-ipcam2/IPCam2.json`

## OBS Sources Setup

### Video
- Add **Media Source** → Uncheck "Local File"
- Input: `http://localhost:8085/video`
- Network Caching: `1000` ms

### Audio (via VB-Cable)
1. Install VB-Cable: https://vb-audio.com/Cable/
2. Windows Sound Settings → Output: **CABLE Input**
3. OBS → Add **Audio Input Capture** → Device: **CABLE Output**
4. Open `http://localhost:8085/audio` in browser (plays audio to CABLE Input)

### Virtual Camera
- Tools → Virtual Camera → Start
- Phone camera appears as webcam in all apps

## Troubleshooting
- **No device**: Check `adb devices` shows your phone
- **Stream offline**: Press START in IPCam2 app
- **No video in OBS**: Verify URL works in browser first (`http://localhost:8085/video`)
- **Audio not working**: Make sure VB-Cable is installed and browser is playing audio page

## Linux (v4l2loopback)
```bash
sudo modprobe v4l2loopback video_nr=2 card_label=IPCam2 exclusive_caps=1
sudo modprobe snd-aloop
python3 tools/ipcam2-driver.py
```
OBS: Add V4L2 Video Capture → `/dev/video2`
