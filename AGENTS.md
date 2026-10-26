# PROJECT KNOWLEDGE BASE — IPCam2

Generated: 2026-07-19
Commit: current
Branch: main

## OVERVIEW

Android IP camera app that turns the phone into an MJPEG video/audio streaming server over WiFi. CameraX captures frames, custom GPU pipeline applies real-time filters (OpenGL), NanoHTTPD serves MJPEG stream + audio to any browser/client on the network. ML Kit provides face detection and selfie segmentation.

## STACK

- **Language**: Kotlin (JVM 11)
- **UI**: View-based (AppCompat + ViewBinding + Material + BottomSheet)
- **Camera**: CameraX 1.3.0 (camera2 + lifecycle + view + extensions)
- **GPU**: Custom OpenGL ES pipeline (gpuimage package)
- **AI/ML**: ML Kit (face-detection 16.1.7, segmentation-selfie 16.0.0-beta5)
- **Server**: NanoHTTPD 2.3.1 (MJPEG HTTP streaming)
- **Audio**: Android AudioRecord (MicManager)
- **Tools**: Python/PowerShell scripts for virtual camera driver install + OBS bridge

## STRUCTURE

```
IPCam2/
├── app/src/main/java/com/example/ipcam/
│   ├── MainActivity.kt          # Entry point — UI + camera + server orchestration (546 LOC ⚠️)
│   ├── MjpegHttpServer.kt       # NanoHTTPD MJPEG server (164 LOC)
│   ├── MJPEGServerInstance.kt   # Per-client streaming instance (68 LOC)
│   ├── MicManager.kt            # Audio recording + streaming (260 LOC)
│   ├── FrameBuffer.kt           # Frame buffer for streaming (5 LOC)
│   ├── EqualizerView.kt         # Custom audio visualizer widget (90 LOC)
│   ├── gpuimage/
│   │   ├── FilterPipeline.kt    # GPU filter pipeline — orchestrates GL rendering (356 LOC ⚠️)
│   │   ├── FilterManager.kt     # OpenGL filter management + shader loading (278 LOC ⚠️)
│   │   ├── GLTextureRenderer.kt # GL texture rendering (126 LOC)
│   │   ├── ShaderProgram.kt     # GLSL shader program wrapper (79 LOC)
│   │   └── FilterType.kt        # Filter type enum (22 LOC)
│   ├── ai/
│   │   ├── AiProcessor.kt       # AI processing entry point (27 LOC)
│   │   ├── FaceDetector.kt      # ML Kit face detection (43 LOC)
│   │   └── SelfieSegmenter.kt   # ML Kit selfie segmentation (33 LOC)
│   └── pipeline/
│       └── FilterPipeline.kt    # Image filter pipeline (356 LOC ⚠️)
├── tools/
│   ├── install-driver.*         # Virtual camera driver installers
│   ├── ipcam2-bridge.*          # OBS bridge scripts
│   ├── ipcam2-driver.*          # Virtual camera driver scripts
│   └── obs-ipcam2/              # OBS plugin profile
└── gradle/
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Camera setup | `MainActivity.kt` | CameraX initialization + preview |
| MJPEG streaming | `MjpegHttpServer.kt` + `MJPEGServerInstance.kt` | NanoHTTPD serving frames |
| Audio streaming | `MicManager.kt` | AudioRecord → PCM → stream |
| GPU filters | `gpuimage/FilterPipeline.kt` + `FilterManager.kt` | OpenGL ES rendering pipeline |
| AI features | `ai/` | ML Kit face detection + segmentation |
| UI layout | `res/layout/` | XML layouts (only 2 files) |
| Driver install | `tools/` | Python/PowerShell for virtual cam |
| Gradle config | `app/build.gradle.kts` | Dependencies + build config |

## CONVENTIONS

- **No Hilt/Dagger** — manual dependency management
- **No Room/DB** — stateless, no persistence
- **No Navigation Component** — single Activity architecture
- **No Coroutines/Flow** — uses ExecutorService + callbacks
- **ViewBinding** enabled but manual view references also used
- **minSdk 24** (Android 7.0), **targetSdk 34**, **compileSdk 36**
- **No proguard/R8** minification in release

## CODE MAP (Key Symbols)

| Symbol | Type | Location | Role |
|--------|------|----------|------|
| `MainActivity` | Activity | MainActivity.kt | Entry point, UI, camera, server lifecycle |
| `MjpegHttpServer` | Class | MjpegHttpServer.kt | NanoHTTPD server, serves MJPEG |
| `MJPEGServerInstance` | Class | MJPEGServerInstance.kt | Per-client frame streaming |
| `MicManager` | Class | MicManager.kt | Audio capture + streaming |
| `FilterPipeline` | Class | gpuimage/FilterPipeline.kt | GL filter orchestration |
| `FilterManager` | Class | gpuimage/FilterManager.kt | Shader loading + filter management |
| `FilterType` | Enum | gpuimage/FilterType.kt | Available filter types |
| `AiProcessor` | Class | ai/AiProcessor.kt | AI processing facade |
| `FaceDetector` | Class | ai/FaceDetector.kt | ML Kit face detection |
| `SelfieSegmenter` | Class | ai/SelfieSegmenter.kt | ML Kit background segmentation |
| `EqualizerView` | View | EqualizerView.kt | Audio visualizer widget |
| `ShaderProgram` | Class | gpuimage/ShaderProgram.kt | GLSL program wrapper |
| `GLTextureRenderer` | Class | gpuimage/GLTextureRenderer.kt | GL texture rendering |

## ANTI-PATTERNS (THIS PROJECT)

- **MainActivity is a god class** (546 LOC) — camera, UI, server, audio, permissions all in one file. Needs decomposition.
- **FilterPipeline is oversized** (356 LOC) — GL setup, rendering, frame capture all mixed.
- **No tests** — only placeholder ExampleUnitTest/ExampleInstrumentedTest exist.
- **No error recovery** — server/camera failures crash instead of graceful degradation.
- **Manual view references** alongside ViewBinding — inconsistent pattern.

## COMMANDS

```bash
# Build
./gradlew assembleDebug

# Install on device
./gradlew installDebug

# Run tests
./gradlew test

# Install virtual camera driver (Windows)
powershell -File tools/install-driver.ps1
```

## NOTES

- App requires CAMERA + INTERNET + RECORD_AUDIO permissions at runtime
- `usesCleartextTraffic=true` — HTTP streaming, no TLS
- Server binds to device's WiFi IP on configurable port
- GPU filters run on GL thread, frames piped to MJPEG server
- ML Kit models downloaded on-device (no custom model files in repo)
- `tools/` scripts are for Windows/Linux virtual camera driver setup (OBS integration)
