# Dual-Pipeline H.264 Streaming — Design Spec

**Date**: 2026-07-20
**Goal**: Real-time USB-only video streaming from Android phone to laptop (<80ms latency, 30fps)

## Architecture

### Stream Path (NEW)
```
CameraX VideoCapture → Surface → MediaCodec(H.264) → TcpStreamServer(port 8086)
                                                          ↓
                                                  adb reverse tcp:8086
                                                          ↓
                                                  ffmpeg → v4l2loopback → OBS
```

- Zero CPU copy: CameraX Surface feeds directly into MediaCodec input Surface
- Hardware H.264 encoding (Android MediaCodec, GPU-accelerated on all devices)
- Raw TCP stream of H.264 NAL units (no container overhead)
- 720p @ 30fps, ~2-5 Mbps

### Preview Path (EXISTING)
```
CameraX Preview → GLSurfaceView → filters + AI (unchanged)
CameraX ImageAnalysis → FilterPipeline → FrameBuffer (MJPEG fallback, can co-exist)
```

### Connection
- `adb reverse tcp:8086 tcp:8086` — maps laptop port 8086 to phone port 8086 over USB
- No WiFi needed. Pure USB cable connection.

## Files

### New: `StreamEncoder.kt`
- Wraps MediaCodec for H.264 encoding
- Takes CameraX Surface as input
- Outputs NAL units via callback
- Configurable: resolution, bitrate, framerate, I-frame interval

### New: `TcpStreamServer.kt`
- Binds to port 8086
- Accepts single client connection
- Streams H.264 NAL units from StreamEncoder
- SPS/PPS sent on new client connection

### Modified: `MainActivity.kt`
- Add VideoCapture use case alongside existing Preview + ImageAnalysis
- Bind VideoCapture Surface to StreamEncoder
- Start/stop TcpStreamServer with streaming toggle
- USB status display for port 8086

### Modified: `ipcam2-bridge.py`
- Replace `http://localhost:8085/video` (MJPEG) with `tcp://localhost:8086` (H.264)
- ffmpeg: `-f h264 -i tcp://localhost:8086` instead of MJPEG URL
- Remove MJPEG-specific demuxer options

### Modified: `ipcam2-driver.py`
- Same H.264 source changes as bridge

## Configuration
- Resolution: 1280x720 (720p) — balance of quality vs bandwidth
- Framerate: 30fps
- Bitrate: 3 Mbps (configurable)
- I-frame interval: 1 second (every 30 frames)
- Color format: COLOR_FormatSurface (direct from CameraX)

## Backward Compatibility
- MJPEG HTTP server on 8085 remains functional
- Users can choose between H.264 (TCP 8086) and MJPEG (HTTP 8085)
- Bridge tool defaults to H.264 but keeps MJPEG as fallback option

## Verification
- Build passes (`./gradlew assembleDebug`)
- App starts, preview shows, streaming toggles
- `adb reverse tcp:8086 tcp:8086` + `ffplay tcp://localhost:8086` shows video
- Bridge: `python3 tools/ipcam2-bridge.py` → v4l2loopback device shows video