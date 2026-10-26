#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME=/home/mrksvt/android-studio/jbr
export ANDROID_HOME=/home/mrksvt/Expo

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

echo "Building and installing debug APK..."
./gradlew installDebug

echo ""
echo "Installed successfully."
echo ""
echo "=== Driver Mode (auto-detect, plug & play) ==="
echo "  python3 tools/ipcam2-driver.py"
echo "  python3 tools/ipcam2-driver.py --daemon    (background)"
echo "  python3 tools/ipcam2-driver.py --install   (systemd auto-start)"
echo ""
echo "=== Manual Bridge (one-shot) ==="
echo "  python3 tools/ipcam2-bridge.py"
echo "  pwsh tools/ipcam2-bridge.ps1               (Windows)"
echo ""
echo "=== OBS ==="
echo "  Video: V4L2 Video Capture → /dev/video2"
echo "  Audio: Audio Input Capture (ALSA) → hw:Loopback,0,0"