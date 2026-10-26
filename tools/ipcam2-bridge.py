#!/usr/bin/env python3
"""Bridge Android IPCam2 app to virtual webcam + audio devices.

Cross-platform: Linux uses v4l2loopback + snd-aloop + ffmpeg; Windows uses
ADB forward + OBS Virtual Camera + VB-Cable.

Connects to an Android device running the IPCam2 app via ADB, forwards
the MJPEG video and AAC audio streams, and pipes them into virtual
devices so OBS (or any webcam consumer) can use the phone camera.
"""

from __future__ import annotations

import argparse
import platform
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

V4L2_MODULE: str = "v4l2loopback"
ALSA_MODULE: str = "snd-aloop"
PROC_ASOUND: Path = Path("/proc/asound/cards")
FFMPEG_LOGLEVEL: str = "-loglevel"
FFMPEG_QUIET: str = "warning"

# ---------------------------------------------------------------------------
# Timestamped printing
# ---------------------------------------------------------------------------


def ts_print(msg: str) -> None:
    """Print a message with an ISO-ish timestamp prefix."""
    stamp = time.strftime("%H:%M:%S")
    print(f"[{stamp}] {msg}", flush=True)


# ---------------------------------------------------------------------------
# Prerequisite checks
# ---------------------------------------------------------------------------


def require_binary(name: str) -> None:
    """Exit with an error if *name* is not found in PATH."""
    try:
        subprocess.run(
            ["which", name],
            check=True,
            capture_output=True,
        )
    except subprocess.CalledProcessError:
        print(f"Error: '{name}' not found in PATH. Please install it.", file=sys.stderr)
        sys.exit(1)


def detect_adb_device() -> str:
    """Return the first connected ADB device serial, or exit."""
    result = subprocess.run(
        ["adb", "devices"],
        check=True,
        capture_output=True,
        text=True,
    )
    lines = [
        line
        for line in result.stdout.strip().splitlines()[1:]  # skip header
        if line.strip() and "\tdevice" in line
    ]
    if not lines:
        print("Error: no ADB device detected. Connect a device and enable USB debugging.", file=sys.stderr)
        sys.exit(1)
    serial = lines[0].split("\t")[0]
    ts_print(f"Found ADB device: {serial}")
    return serial


# ---------------------------------------------------------------------------
# ADB port forwarding
# ---------------------------------------------------------------------------


def setup_port_forward(serial: str, video_port: int, audio_port: int, h264_port: int = 8086) -> None:
    """Forward video/audio/h264 ports from the device to localhost."""
    for port in dict.fromkeys((video_port, audio_port, h264_port)):
        subprocess.run(
            ["adb", "-s", serial, "forward", f"tcp:{port}", f"tcp:{port}"],
            check=True,
            capture_output=True,
        )
        ts_print(f"Forwarded localhost:{port} -> device:{port}")


def remove_port_forward(serial: str, video_port: int, audio_port: int, h264_port: int = 8086) -> None:
    """Remove ADB port forwards (best-effort)."""
    for port in dict.fromkeys((video_port, audio_port, h264_port)):
        subprocess.run(
            ["adb", "-s", serial, "forward", "--remove", f"tcp:{port}"],
            check=False,
            capture_output=True,
        )


# ---------------------------------------------------------------------------
# Kernel module helpers
# ---------------------------------------------------------------------------


def is_module_loaded(module: str) -> bool:
    """Check if a kernel module is currently loaded."""
    result = subprocess.run(
        ["lsmod"],
        capture_output=True,
        text=True,
    )
    return any(line.startswith(module) for line in result.stdout.splitlines())


def load_module_sudo(module: str, *extra_args: str) -> None:
    """Load a kernel module via sudo modprobe."""
    cmd = ["sudo", "modprobe", module, *extra_args]
    ts_print(f"Loading kernel module: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        ts_print(f"Warning: modprobe {module} failed (rc={result.returncode}).")
        ts_print("  You may need to run this script with sudo or configure sudoers.")
        if result.stderr.strip():
            ts_print(f"  stderr: {result.stderr.strip()}")


def unload_module_sudo(module: str) -> None:
    """Unload a kernel module via sudo modprobe -r (best-effort)."""
    cmd = ["sudo", "modprobe", "-r", module]
    ts_print(f"Unloading kernel module: {module}")
    subprocess.run(cmd, check=False, capture_output=True)


def ensure_v4l2loopback(video_device: str) -> None:
    """Load v4l2loopback if the target device does not exist."""
    if Path(video_device).exists():
        ts_print(f"Video device {video_device} already exists, skipping modprobe.")
        return
    # Derive video_nr from device path (e.g. /dev/video2 -> 2)
    nr = Path(video_device).name.removeprefix("video")
    load_module_sudo(
        V4L2_MODULE,
        f"video_nr={nr}",
        "card_label=IPCam2",
        "exclusive_caps=1",
    )
    # Brief pause for udev to create the device node
    time.sleep(0.5)


def ensure_snd_aloop() -> None:
    """Load snd-aloop if not already present."""
    if is_module_loaded(ALSA_MODULE):
        ts_print("snd-aloop already loaded.")
        return
    load_module_sudo(ALSA_MODULE)


# ---------------------------------------------------------------------------
# ffmpeg process management
# ---------------------------------------------------------------------------


@dataclass
class FFmpegManager:
    """Spawn and track ffmpeg child processes."""

    video_proc: subprocess.Popen[str] | None = field(default=None, init=False)
    audio_proc: subprocess.Popen[str] | None = field(default=None, init=False)

    def start_video(self, h264_port: int, video_device: str) -> None:
        cmd = [
            "ffmpeg",
            FFMPEG_LOGLEVEL, FFMPEG_QUIET,
            "-re",
            "-f", "h264",
            "-i", f"tcp://localhost:{h264_port}",
            "-pix_fmt", "yuv420p",
            "-f", "v4l2",
            video_device,
        ]
        ts_print(f"Starting video ffmpeg: {' '.join(cmd)}")
        self.video_proc = subprocess.Popen(cmd)

    def start_audio(self, audio_port: int, audio_device: str) -> None:
        cmd = [
            "ffmpeg",
            FFMPEG_LOGLEVEL, FFMPEG_QUIET,
            "-re",
            "-fflags", "+genpts+discardcorrupt",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "2",
            "-i", f"http://localhost:{audio_port}/audio",
            "-c:a", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            "-f", "alsa",
            audio_device,
        ]
        ts_print(f"Starting audio ffmpeg: {' '.join(cmd)}")
        self.audio_proc = subprocess.Popen(cmd)

    def kill_all(self) -> None:
        """Terminate (then kill) both ffmpeg processes."""
        for name, proc in [("video", self.video_proc), ("audio", self.audio_proc)]:
            if proc is None:
                continue
            if proc.poll() is None:
                ts_print(f"Terminating {name} ffmpeg (pid={proc.pid})...")
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    ts_print(f"Force-killing {name} ffmpeg (pid={proc.pid})...")
                    proc.kill()
                    proc.wait()
            else:
                ts_print(f"{name} ffmpeg already exited (rc={proc.returncode}).")
        self.video_proc = None
        self.audio_proc = None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Bridge IPCam2 (Android) to virtual webcam + audio (cross-platform).",
    )
    parser.add_argument(
        "--video-port", type=int, default=8085,
        help="TCP port for MJPEG video stream (default: 8085)",
    )
    parser.add_argument(
        "--h264-port", type=int, default=8086,
        help="TCP port for H.264 video stream (default: 8086)",
    )
    parser.add_argument(
        "--audio-port", type=int, default=8085,
        help="TCP port for AAC audio stream (default: 8085, same as video)",
    )
    parser.add_argument(
        "--video-device", default="/dev/video2",
        help="V4L2 loopback device path (default: /dev/video2)",
    )
    parser.add_argument(
        "--audio-device", default="hw:Loopback,0,0",
        help="ALSA loopback device (default: hw:Loopback,0,0)",
    )
    return parser.parse_args()


def wait_for_server(h264_port: int, timeout: int = 30) -> None:
    import socket
    ts_print(f"Waiting for IPCam2 H.264 server on port {h264_port}...")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(2)
            sock.connect(("localhost", h264_port))
            sock.close()
            ts_print("H.264 server is ready.")
            return
        except (ConnectionRefusedError, OSError):
            time.sleep(1)
    ts_print("WARNING: H.264 server not reachable. Continuing anyway (press START in app).")


def main() -> int:
    args = parse_args()
    system = platform.system()

    # --- prerequisites ---
    require_binary("adb")

    # --- detect device ---
    serial = detect_adb_device()

    # --- port forwarding (works on both platforms) ---
    setup_port_forward(serial, args.video_port, args.audio_port, args.h264_port)

    # ==================================================================
    # Windows: keep running, auto-retry, print OBS instructions
    # ==================================================================
    if system == "Windows":
        print("=== Windows Setup ===")
        print("1. Install OBS Studio: https://obsproject.com/")
        print("2. OBS Virtual Camera is built-in (OBS 28+)")
        print("3. Install VB-Cable for audio: https://vb-audio.com/Cable/")
        print()
        print("4. In OBS, add 'Media Source':")
        print(f"   For H.264 (recommended): tcp://localhost:{args.h264_port}")
        print(f"   For MJPEG (legacy): http://localhost:{args.video_port}/video")
        print("   Uncheck 'Local File', set network caching to 1000ms")
        print()
        print("5. For audio, add 'Audio Input Capture':")
        print(f"   Device: CABLE Output (VB-Audio Virtual Cable)")
        print(f"   OR open http://localhost:{args.audio_port}/audio in browser")
        print()
        print("6. Tools > Virtual Camera > Start")
        print("7. Other apps see phone as 'OBS Virtual Camera'")
        print()
        print("Bridge running. Press Ctrl+C to stop.")
        print("=" * 60)

        # Keep running and monitor connection
        import socket
        while True:
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(5)
                sock.connect(("localhost", args.h264_port))
                sock.close()
            except (ConnectionRefusedError, OSError):
                ts_print("WARNING: H.264 server unreachable. Waiting for reconnection...")
                try:
                    setup_port_forward(serial, args.video_port, args.audio_port, args.h264_port)
                except Exception:
                    pass
            time.sleep(5)
        return 0

    # ==================================================================
    # Linux: v4l2loopback + snd-aloop + ffmpeg
    # ==================================================================
    require_binary("ffmpeg")

    # --- wait for server ---
    wait_for_server(args.h264_port)

    # --- kernel modules ---
    ensure_v4l2loopback(args.video_device)
    ensure_snd_aloop()

    # --- ffmpeg ---
    ffmpeg = FFmpegManager()
    ffmpeg.start_video(args.h264_port, args.video_device)
    ffmpeg.start_audio(args.audio_port, args.audio_device)

    # --- signal handling ---
    def cleanup(signum: int, _frame: object) -> None:
        sig_name = signal.Signals(signum).name
        ts_print(f"\nReceived {sig_name}, shutting down...")
        ffmpeg.kill_all()
        remove_port_forward(serial, args.video_port, args.audio_port, args.h264_port)
        unload_module_sudo(V4L2_MODULE)
        ts_print("Cleanup complete. Goodbye.")
        sys.exit(0)

    signal.signal(signal.SIGINT, cleanup)
    signal.signal(signal.SIGTERM, cleanup)

    # --- print OBS instructions ---
    ts_print("Bridge is running. Press Ctrl+C to stop.")
    print()
    print("=" * 60)
    print("  OBS Setup Instructions")
    print("=" * 60)
    print()
    print("  Video source (H.264 — recommended):")
    print("    1. Add a 'V4L2 Video Capture' source")
    print(f"    2. Set device to: {args.video_device}")
    print("    3. Resolution/FPS: auto-detected from stream")
    print()
    print(f"  Video source (MJPEG — legacy):")
    print(f"    1. Add a 'Media Source' → http://localhost:{args.video_port}/video")
    print()
    print("  Audio source:")
    print("    1. Add an 'Audio Input Capture (PulseAudio)' source")
    print(f"       or use ALSA directly with device: {args.audio_device}")
    print("    2. Alternatively, use 'Monitor of Loopback' in PulseAudio")
    print("=" * 60)
    print()

    # --- wait for ffmpeg processes ---
    while True:
        rc_v = ffmpeg.video_proc.poll() if ffmpeg.video_proc else None
        rc_a = ffmpeg.audio_proc.poll() if ffmpeg.audio_proc else None
        if rc_v is not None:
            ts_print(f"Video ffmpeg exited (rc={rc_v}).")
            break
        if rc_a is not None:
            ts_print(f"Audio ffmpeg exited (rc={rc_a}).")
            break
        time.sleep(1)

    # If we get here, one of the processes died unexpectedly — clean up.
    cleanup(signal.SIGINT, None)
    return 1


if __name__ == "__main__":
    sys.exit(main())
