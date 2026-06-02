<div align="center">

<img src="docs/assets/banner.png" alt="Gaze Control Banner" width="100%" />

# 👁️ Gaze Control

**Hands-free scrolling for Android — powered by real-time on-device eye tracking**

[![Flutter](https://img.shields.io/badge/Flutter-3.11.5+-02569B?logo=flutter&logoColor=white&style=flat-square)](https://flutter.dev)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white&style=flat-square)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-green?style=flat-square)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-Welcome-brightgreen?style=flat-square)](CONTRIBUTING.md)
[![Platform](https://img.shields.io/badge/Platform-Android%2010+-3DDC84?logo=android&logoColor=white&style=flat-square)](https://android.com)
[![MediaPipe](https://img.shields.io/badge/MediaPipe-Google-4285F4?style=flat-square)](https://developers.google.com/mediapipe)

[**Read the Full Blog Post →**](https://www.deepskylabs.in/portfolio/were-open-sourcing-gaze-control-hands-free-accessibility-for-everyone) · [Report a Bug](https://github.com/Deep-SkyLabs/gaze-control/issues) · [Request a Feature](https://github.com/Deep-SkyLabs/gaze-control/discussions)

<br />

<img src="docs/assets/demo.gif" alt="Gaze Control Demo" width="320" />

</div>

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Running in Development](#running-in-development)
  - [Building a Release APK](#building-a-release-apk)
- [Project Structure](#project-structure)
- [Configuration Reference](#configuration-reference)
- [Contributing](#contributing)
- [Roadmap](#roadmap)
- [Privacy](#privacy)
- [License](#license)

---

## Overview

Gaze Control is an Android accessibility utility that lets users scroll social media feeds — Instagram, TikTok, YouTube Shorts, X/Twitter, Reddit — using only their eyes, head movements, or palm gestures.

It runs as an Android Foreground Service, processes all camera frames locally on-device via a [Google MediaPipe](https://developers.google.com/mediapipe) + L2CS-Net ONNX pipeline, and dispatches physical scroll gestures through the Android Accessibility Service API. No data ever leaves the device.

Built for people who want hands-free mobile interaction: those recovering from surgery, living with motor disabilities, multitasking, or simply curious about what gaze-driven interfaces feel like.

---

## Features

| Capability | Description |
|:---|:---|
| 👁️ **Eye Gaze Tracking** | Look up/down past a configurable dwell threshold to trigger scrolls |
| 🤙 **Head Nod State Machine** | Subtle vertical/horizontal nods fire instant scroll actions via gyroscope fusion |
| 🖐️ **Palm Gesture Control** | Open Palm = Play/Pause · Closed Fist = Stop · Thumbs Up = Select |
| 📐 **9-Stage Calibration** | Personalized neutral anchors, gaze extents, deadzones, and directional biases |
| 🔒 **Full On-Device Privacy** | Camera frames processed in native memory, never stored or transmitted |
| 🔋 **Thermal-Aware Performance** | Dynamic frame throttling based on device temperature and tracking confidence |
| ⚡ **NNAPI Acceleration** | L2CS-Net inference runs with hardware NPU acceleration + math fallback |
| 🛡️ **Blink-Safe Tracking** | Eye Aspect Ratio + blendshape fusion prevents involuntary blinks from triggering actions |

---

## Architecture

The system is split into two primary layers communicating via Flutter platform method channels.

```
┌─────────────────────────── Flutter (Dart) ─────────────────────────────┐
│                                                                          │
│   GoRouter ──► Dashboard ──► Calibration ──► Settings ──► Onboarding   │
│                    │               │              │                      │
│              Riverpod NotifierProviders ◄── Platform Channel Adapters   │
│                                                                          │
└──────────────────────────────────┬───────────────────────────────────── ┘
                                   │ MethodChannel / EventChannel
┌──────────────────────────── Kotlin (Android) ──────────────────────────┐
│                                                                          │
│   GazeForegroundService                                                  │
│    ├── Camera2 API ──► MediaPipe Face/Hand Landmarker                   │
│    │                        └──► L2CS-Net ONNX (gaze pitch/yaw)        │
│    ├── SensorEventListener ──► GravityRollDecoupler                     │
│    │                              (head rotation ≠ gaze intent)        │
│    ├── CropStabilizer (EMA + affine alignment)                          │
│    ├── ConfidenceEngine (visibility + eyelid + gyro)                   │
│    ├── BlinkDetector (EAR + blendshapes)                                │
│    ├── DirectionHysteresis (entry/exit thresholds)                      │
│    ├── GazeStateMachine                                                  │
│    │    NO_FACE → SEARCHING → TRACKING → STABLE_LOCK → ACTION_READY    │
│    ├── InteractionProfileManager (per-app scroll tuning)                │
│    ├── ThermalPerformanceManager (dynamic frame throttling)              │
│    └── AccessibilitySafetyEngine                                        │
│                    └──► GazeAccessibilityService ──► System Gestures    │
│                                                                          │
└──────────────────────────────────────────────────────────────────────── ┘
```

### Key Subsystems

**`GazeForegroundService.kt`** — The system heartbeat. Sticky foreground service that coordinates the full perception pipeline from frame capture to gesture dispatch.

**L2CS-Net ONNX** — Predicts high-resolution pitch/yaw gaze angles from MediaPipe eye ROI crops. Runs via `OnnxReflectiveRunner` with NNAPI hardware acceleration.

**`GravityRollDecoupler`** — Fuses accelerometer and gyroscope vectors to separate head rotation from genuine gaze intent. Essential for real-world usability when the device is not held perfectly upright.

**`GazeStateMachine`** — Prevents false positive gesture dispatch by enforcing the full state cycle: `NO_FACE → SEARCHING → TRACKING → UNSTABLE → STABLE_LOCK → ACTION_READY → COOLDOWN`. Actions only fire from `ACTION_READY`.

**`ConfidenceEngine`** — Combines landmark visibility scores, eyelid closure blendshapes, and device motion to generate a real-time 0.0–1.0 tracking quality score. Suppresses the filter chain during low-confidence periods.

---

## Getting Started

### Prerequisites

| Requirement | Version |
|:---|:---|
| Flutter SDK | `^3.11.5` |
| Dart SDK | `^3.11.5` |
| Android Studio | Latest stable |
| Android SDK Platform Tools | `33+` |
| Physical Android Device | API 29+ with front camera |

> **⚠️ Emulator Note**: Camera2 is unavailable on emulators. The app will launch but fall back to a degraded heuristic engine. Physical device testing is strongly recommended.

### Installation

```bash
# 1. Clone the repository
git clone (https://github.com/Deep-SkyLabs/gaze-control.git
cd gaze-control

# 2. Install Flutter dependencies
flutter pub get

# 3. Run code generators (Riverpod, GoRouter)
flutter pub run build_runner build --delete-conflicting-outputs
```

### Running in Development

```bash
# Connect your Android device and verify it's visible
flutter devices

# Run in debug mode (note: CV pipeline is noticeably slower in debug)
flutter run

# Run in profile mode for realistic performance with debugging
flutter run --profile
```

### Building a Release APK

```bash
# Build optimized release APK
flutter build apk --release

# The output APK will be at:
# build/app/outputs/flutter-apk/app-release.apk
```

For App Bundle (recommended for Play Store distribution):

```bash
flutter build appbundle --release
```

---

## Project Structure

```
gaze-control/
├── android/
│   └── app/src/main/kotlin/com/example/gaze/
│       ├── MainActivity.kt                  # Flutter ↔ Native MethodChannel bindings
│       ├── GazeAccessibilityService.kt      # Accessibility Service gesture dispatcher
│       └── GazeForegroundService.kt         # Full CV engine: camera, sensors, ML, state machine
├── lib/
│   ├── main.dart                            # App entry point
│   └── src/
│       ├── app/
│       │   └── app.dart                     # Root widget, theme injection
│       ├── core/
│       │   ├── platform/                    # MethodChannel / EventChannel adapters
│       │   ├── services/                    # Foreground service lifecycle controllers
│       │   └── theme/                       # Forui design token customization
│       ├── routing/
│       │   └── app_router_config.dart       # GoRouter declarations
│       └── features/
│           ├── dashboard/                   # Real-time telemetry & state visualization
│           ├── gaze_tracking/               # Calibration wizard & diagnostic overlays
│           ├── onboarding/                  # First-launch welcome flow
│           ├── permissions/                 # Accessibility + overlay permission checks
│           └── settings/                   # Threshold configuration & app whitelisting
├── docs/
│   ├── assets/                             # Banner, demo GIF, screenshots
│   ├── ARCHITECTURE.md                     # Deep-dive architecture documentation
│   └── CALIBRATION.md                      # Calibration system explained
├── test/
│   ├── unit/                               # Dart unit tests
│   └── widget/                             # Flutter widget tests
├── CHANGELOG.md
├── CONTRIBUTING.md
├── LICENSE
└── pubspec.yaml
```

---

## Configuration Reference

All user configuration persists in Android SharedPreferences:

| Key | Type | Default | Description |
|:---|:---|:---|:---|
| `flutter.user_calibration_profile` | `String (JSON)` | `null` | Neutral gaze vectors, directional bounds, deadzones, and biases from the 9-stage calibration flow |
| `is_first_launch` | `bool` | `true` | Controls whether the onboarding carousel renders on next launch |
| `sensitivity` | `double` | `0.5` | Global gesture sensitivity multiplier `[0.1 – 1.0]` |

Calibration profiles use the following JSON schema:

```json
{
  "neutralPitch": 0.0,
  "neutralYaw": 0.0,
  "topThreshold": 0.18,
  "bottomThreshold": -0.18,
  "leftThreshold": -0.22,
  "rightThreshold": 0.22,
  "deadzonePitch": 0.06,
  "deadzoneYaw": 0.06,
  "verticalBias": 1.0,
  "horizontalBias": 1.0
}
```

---

## Contributing

Contributions are welcome. Please read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request.

### Quick Contribution Guidelines

- **Design tokens only**: All colors, spacing, and typography must use `context.theme` properties. No raw hex values.
- **Explicit state**: Use `NotifierProvider` implementations. Avoid implicit provider reads in business logic.
- **Lint compliance**: Run `flutter analyze` before submitting. The project uses strict linting rules in `analysis_options.yaml`.
- **Tests**: Widget tests for new feature screens; unit tests for any new business logic in the `core/` layer.

### Good First Issues

Look for issues tagged [`good first issue`](https://github.com/Deep-SkyLabs/gaze-control/labels/good%20first%20issue) — these are scoped, well-documented, and don't require deep knowledge of the native CV pipeline.

---

## Roadmap

- [ ] **Smart App Detection** — Auto-wake camera service when a supported app enters foreground
- [ ] **Context-Aware Smoothing** — Dynamic One Euro Filter coefficients based on active scroll velocity
- [ ] **Ambient Dynamic Theming** — Mirror the active app's color palette in the Gaze Control overlay
- [ ] **iOS Port** — ARKit-based gaze engine for iPhone
- [ ] **Passive Calibration Refinement** — Incremental profile tuning over time without explicit recalibration sessions
- [ ] **Additional Gesture Vocabulary** — Tongue detection, expression triggers, breath sensing

Track progress and vote on priorities in [Discussions → Roadmap](https://github.com/Deep-SkyLabs/gaze-control/discussions).

---

## Privacy

Gaze Control is designed privacy-first by architecture:

- Camera frames are processed in native memory and immediately discarded — they are never written to disk
- No gaze coordinates, usage events, or identifiers are transmitted off-device
- The only persistent data is your calibration profile and settings, stored locally in Android SharedPreferences
- The app requests no internet permission

---

## License

Apache License 2.0 — see [`LICENSE`](LICENSE) for full terms.

---

<div align="center">

Built with care by **[Deep Skylabs](https://www.deepskylabs.in)**

[Website](https://www.deepskylabs.in) · [Blog](https://www.deepskylabs.in/blog) 

*If Gaze Control has been useful to you, consider starring the repo — it helps others find it.*

</div>

> ⚠️ **Early Development** — This project is actively in development. Some features may be incomplete, unstable, or subject to breaking changes. Use in production at your own discretion.