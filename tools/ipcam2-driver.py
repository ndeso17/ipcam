#!/usr/bin/env python3
"""IPCam2 Driver — Auto-detect ADB device, launch app, bridge to webcam.

Runs as a daemon. When Android device is plugged in via USB (ADB):
1. Auto-launches IPCam2 app on the device
2. Sets up ADB port forwarding
3. Creates v4l2loopback + snd-aloop virtual devices
4. Pipes MJPEG + AAC into ffmpeg → /dev/video2 + ALSA loopback
5. When device disconnects: cleans up, waits for reconnection

Usage:
    python3 tools/ipcam2-driver.py              # foreground
    python3 tools/ipcam2-driver.py --daemon     # background daemon
    python3 tools/ipcam2-driver.py --install    # install systemd service (Linux)
    python3 tools/ipcam2-driver.py --uninstall  # remove systemd service

OBS setup:
    Video: V4L2 Video Capture → /dev/video2
    Audio: Audio Input Capture (ALSA) → hw:Loopback,0,0
"""

from __future__ import annotations

import argparse
import os
import platform
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

V4L2_MODULE = "v4l2loopback"
ALSA_MODULE = "snd-aloop"
PROC_ASOUND = Path("/proc/asound/cards")
POLL_INTERVAL = 2.0
APP_START_DELAY = 3.0
SYSTEMD_SERVICE = """[Unit]
Description=IPCam2 Webcam Bridge Driver
After=network.target

[Service]
Type=simple
ExecStart={python} {script}
Restart=always
RestartSec=5
Environment=ANDROID_HOME={android_home}
Environment=JAVA_HOME={java_home}

[Install]
WantedBy=default.target
"""


def ts_print(msg: str) -> None:
    stamp = time.strftime("%H:%M:%S")
    print(f"[{stamp}] {msg}", flush=True)


def run(cmd: list[str], check: bool = True, timeout: int | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, check=check, capture_output=True, text=True, timeout=timeout)


def require_binary(name: str) -> None:
    if subprocess.run(["which", name], capture_output=True).returncode != 0:
        ts_print(f"ERROR: {name} not found in PATH.")
        sys.exit(1)


def get_adb_device() -> Optional[str]:
    r = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    for line in r.stdout.strip().split("\n")[1:]:
        if "\tdevice" in line:
            return line.split("\t")[0]
    return None


def is_module_loaded(module: str) -> bool:
    r = subprocess.run(["lsmod"], capture_output=True, text=True)
    return any(line.startswith(module) for line in r.stdout.splitlines())


def load_module(module: str, *args: str) -> bool:
    if is_module_loaded(module):
        return True
    ts_print(f"Loading {module}...")
    r = subprocess.run(["sudo", "modprobe", module, *args], capture_output=True, text=True)
    if r.returncode != 0:
        ts_print(f"WARNING: modprobe {module} failed: {r.stderr.strip()}")
    return is_module_loaded(module)


def unload_module(module: str) -> None:
    if is_module_loaded(module):
        subprocess.run(["sudo", "modprobe", "-r", module], check=False, capture_output=True)


@dataclass
class FFmpegManager:
    video_proc: Optional[subprocess.Popen[str]] = field(default=None, init=False)
    audio_proc: Optional[subprocess.Popen[str]] = field(default=None, init=False)

    def start_video(self, h264_port: int, video_device: str) -> None:
        cmd = [
            "ffmpeg", "-loglevel", "warning", "-re",
            "-f", "h264",
            "-i", f"tcp://localhost:{h264_port}",
            "-pix_fmt", "yuv420p", "-f", "v4l2", video_device,
        ]
        ts_print(f"Video ffmpeg: {' '.join(cmd)}")
        self.video_proc = subprocess.Popen(cmd)

    def start_audio(self, audio_port: int, audio_device: str) -> None:
        cmd = [
            "ffmpeg", "-loglevel", "warning", "-re",
            "-fflags", "+genpts+discardcorrupt",
            "-reconnect", "1", "-reconnect_streamed", "1",
            "-reconnect_delay_max", "2",
            "-i", f"http://localhost:{audio_port}/audio",
            "-c:a", "pcm_s16le", "-ar", "16000", "-ac", "1",
            "-f", "alsa", audio_device,
        ]
        ts_print(f"Audio ffmpeg: {' '.join(cmd)}")
        self.audio_proc = subprocess.Popen(cmd)

    def kill_all(self) -> None:
        for name, proc in [("video", self.video_proc), ("audio", self.audio_proc)]:
            if proc is None:
                continue
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
            else:
                ts_print(f"{name} ffmpeg already exited (rc={proc.returncode}).")
        self.video_proc = None
        self.audio_proc = None


class Driver:
    def __init__(self, video_port: int = 8085, h264_port: int = 8086, audio_port: int = 8085,
                 video_device: str = "/dev/video2", audio_device: str = "hw:Loopback,0,0"):
        self.video_port = video_port
        self.h264_port = h264_port
        self.audio_port = audio_port
        self.video_device = video_device
        self.audio_device = audio_device
        self.ffmpeg = FFmpegManager()
        self._running = True
        self._last_serial: Optional[str] = None

    def stop(self) -> None:
        self._running = False

    def _cleanup(self, serial: Optional[str] = None) -> None:
        self.ffmpeg.kill_all()
        if serial:
            for port in (self.video_port, self.audio_port, self.h264_port):
                subprocess.run(["adb", "-s", serial, "forward", "--remove", f"tcp:{port}"],
                               check=False, capture_output=True)

    def _setup(self, serial: str) -> None:
        ts_print(f"Device {serial} detected. Launching app...")
        subprocess.run(["adb", "-s", serial, "shell", "am", "start", "-n",
                        "com.example.ipcam/.MainActivity"],
                       check=False, capture_output=True)
        time.sleep(APP_START_DELAY)

        for port in (self.video_port, self.audio_port, self.h264_port):
            subprocess.run(["adb", "-s", serial, "forward", f"tcp:{port}", f"tcp:{port}"],
                           check=True, capture_output=True)
            ts_print(f"Forwarded localhost:{port} → device:{port}")

        if not is_module_loaded(V4L2_MODULE):
            ts_print("Loading v4l2loopback...")
            if not load_module(V4L2_MODULE, "video_nr=2", "card_label=IPCam2", "exclusive_caps=1"):
                ts_print("ERROR: v4l2loopback not available. Run:")
                ts_print("  sudo modprobe v4l2loopback video_nr=2 card_label=IPCam2 exclusive_caps=1")
                ts_print("  If module not found: sudo apt install v4l2loopback-dkms")
        if not is_module_loaded(ALSA_MODULE):
            ts_print("Loading snd-aloop...")
            if not load_module(ALSA_MODULE):
                ts_print("WARNING: snd-aloop not available. Audio may not work.")

        ts_print("Waiting for IPCam2 H.264 server...")
        deadline = time.monotonic() + 60
        server_ready = False
        while time.monotonic() < deadline:
            try:
                import socket
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(2)
                sock.connect(("localhost", self.h264_port))
                sock.close()
                server_ready = True
                break
            except Exception:
                time.sleep(2)
        if not server_ready:
            ts_print("ERROR: H.264 server not reachable after 60s. Aborting ffmpeg start.")
            ts_print("Make sure IPCam2 app is running and START is pressed.")
            return

        self.ffmpeg.start_video(self.h264_port, self.video_device)
        self.ffmpeg.start_audio(self.audio_port, self.audio_device)
        ts_print("Bridge active. Webcam ready at /dev/video2")

    def run_forever(self) -> None:
        signal.signal(signal.SIGINT, lambda *_: self.stop())
        signal.signal(signal.SIGTERM, lambda *_: self.stop())

        ts_print("IPCam2 Driver started. Waiting for ADB device...")
        ts_print("Plug in your phone via USB with ADB debugging enabled.")

        while self._running:
            serial = get_adb_device()

            if serial and serial != self._last_serial:
                self._last_serial = serial
                try:
                    self._setup(serial)
                except Exception as e:
                    ts_print(f"Setup failed: {e}")

            elif not serial and self._last_serial:
                ts_print(f"Device {self._last_serial} disconnected. Cleaning up...")
                self._cleanup(self._last_serial)
                self._last_serial = None
                ts_print("Waiting for device...")

            elif serial == self._last_serial:
                # Check if H.264 server became available and ffmpeg is not running
                server_ok = False
                try:
                    import socket
                    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    sock.settimeout(2)
                    sock.connect(("localhost", self.h264_port))
                    sock.close()
                    server_ok = True
                except Exception:
                    pass

                ffmpeg_dead = (self.ffmpeg.video_proc is not None and self.ffmpeg.video_proc.poll() is not None)

                if server_ok and ffmpeg_dead:
                    ts_print("Server recovered. Restarting bridge...")
                    self.ffmpeg.kill_all()
                    self.ffmpeg.start_video(self.h264_port, self.video_device)
                    self.ffmpeg.start_audio(self.audio_port, self.audio_device)
                elif server_ok and self.ffmpeg.video_proc is None:
                    ts_print("Server ready. Starting bridge...")
                    self.ffmpeg.start_video(self.h264_port, self.video_device)
                    self.ffmpeg.start_audio(self.audio_port, self.audio_device)

            time.sleep(POLL_INTERVAL)

        self._cleanup(self._last_serial)
        if platform.system() == "Linux":
            unload_module(V4L2_MODULE)
        ts_print("Driver stopped.")


def cmd_install() -> None:
    if platform.system() != "Linux":
        ts_print("systemd install only supported on Linux.")
        sys.exit(1)

    script = os.path.abspath(__file__)
    python = sys.executable
    android_home = os.environ.get("ANDROID_HOME", "")
    java_home = os.environ.get("JAVA_HOME", "")

    svc = SYSTEMD_SERVICE.format(
        python=python, script=script,
        android_home=android_home, java_home=java_home,
    )

    path = Path.home() / ".config" / "systemd" / "user" / "ipcam2-driver.service"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(svc)
    ts_print(f"Service installed: {path}")
    ts_print("Run: systemctl --user enable --now ipcam2-driver.service")


def cmd_uninstall() -> None:
    path = Path.home() / ".config" / "systemd" / "user" / "ipcam2-driver.service"
    if path.exists():
        path.unlink()
        ts_print(f"Service removed: {path}")
    ts_print("Run: systemctl --user disable --now ipcam2-driver.service")


def main() -> int:
    parser = argparse.ArgumentParser(description="IPCam2 Driver — auto-detect ADB, bridge to webcam")
    parser.add_argument("--video-port", type=int, default=8085)
    parser.add_argument("--h264-port", type=int, default=8086)
    parser.add_argument("--audio-port", type=int, default=8085)
    parser.add_argument("--video-device", default="/dev/video2")
    parser.add_argument("--audio-device", default="hw:Loopback,0,0")
    parser.add_argument("--install", action="store_true", help="Install systemd user service")
    parser.add_argument("--uninstall", action="store_true", help="Remove systemd user service")
    args = parser.parse_args()

    if args.install:
        cmd_install()
        return 0
    if args.uninstall:
        cmd_uninstall()
        return 0

    require_binary("adb")
    if platform.system() == "Linux":
        require_binary("ffmpeg")

    driver = Driver(
        video_port=args.video_port,
        h264_port=args.h264_port,
        audio_port=args.audio_port,
        video_device=args.video_device,
        audio_device=args.audio_device,
    )
    driver.run_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())