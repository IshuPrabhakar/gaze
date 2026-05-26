# 👁️ Gaze Control

[![Flutter](https://img.shields.io/badge/Flutter-3.11.5+-02569B?logo=flutter&logoColor=white&style=flat-square)](https://flutter.dev)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white&style=flat-square)](https://android.com)
[![UI Library](https://img.shields.io/badge/UI-Forui-6366F1?style=flat-square)](https://forui.dev)
[![License](https://img.shields.io/badge/License-Proprietary-red?style=flat-square)](#)

Gaze Control is a cutting-edge, hands-free accessibility and navigation utility that allows users to seamlessly scroll feeds across all social media and video applications (Instagram, TikTok, YouTube Shorts, X/Twitter, Reddit) using real-time eye-gaze tracking, head nodding, and palm gestures.

Calculated inside high-frequency, battery-optimized native foreground services, Gaze Control translates face mesh landmarks and pupil movements into physical, system-wide scroll gestures via the Android Accessibility Service API.

---

## ⚡ Key Capabilities & Features

*   **✨ Multi-Modal Interaction Control**:
    *   **Eye Gaze Tracking**: Scroll feeds by looking up or down and holding your gaze for a configurable hold duration.
    *   **Head Nodding State Machine**: Fire instant scroll actions with natural, subtle vertical or horizontal head nods.
    *   **Hand Gestures & Palm Controls**: Perform quick swipes or trigger media actions (`Open Palm` to Play/Pause, `Closed Fist` to Stop, `Thumbs Up` to Select).
*   **📐 Active 3D Personalized Calibration**: Custom calibration guides target eye anchors to tailor threshold configurations for distinct user postures and eye movement ranges.
*   **🔋 Hardware-Optimized Native Engine**: Front-camera processing using Google MediaPipe and native Sensor Fusion (Gravity + Gyroscope) Decoupling head rotation from eyeball rotation.
*   **🔒 Complete User Privacy**: Low-resolution camera frames are processed purely on-device in high-velocity local loops. Images are never uploaded, serialized, or stored.
*   **🛡️ Pause on Look Away**: Automatically triggers media play/pause state transitions by monitoring user attentiveness relative to the screen.

---

## 🛠️ Technology Stack

| Category | Technology | Purpose |
| :--- | :--- | :--- |
| **Core Framework** | [Flutter (Dart)](https://flutter.dev) | High-performance cross-platform application shell |
| **Native Integration** | [Kotlin (Android)](https://kotlinlang.org) | Low-level camera pipelines, sensor fusion, and Accessibility Services |
| **Computer Vision** | [Google MediaPipe](https://developers.google.com/mediapipe) | High-fidelity Face Landmarker & Hand Landmarker models |
| **UI Component System**| [Forui](https://forui.dev) | Premium, minimalist visual design system inspired by shadcn/ui |
| **State Management** | [Flutter Riverpod](https://riverpod.dev) | Declarative state propagation and reactive logic pipelines |
| **Navigation** | [GoRouter](https://pub.dev/packages/go_router) | Robust path-based routing configuration |
| **Local Storage** | [SharedPreferences](https://pub.dev/packages/shared_preferences) | Fast persistent caching of user calibration profiles & custom settings |

---

## 📐 Architecture Overview

Gaze Control is split into two primary layers connected via high-speed native-to-Dart communication channels:

```mermaid
graph TD
    subgraph Flutter App Layer (Dart)
        A[App Entry / app.dart] --> B[GoRouter Config]
        B --> C[Dashboard Screen]
        B --> D[Settings Screen]
        B --> E[Calibration Screen]
        
        F[Riverpod Notifiers] -->|State Binding| C
        F -->|Config Mapping| D
        F -->|Anchor Calculation| E
    end

    subgraph Native Android Layer (Kotlin)
        G[GazeForegroundService] <-->|Platform Method Channel| H[PlatformChannels API]
        G -->|Frame Capture| I[Camera2 API]
        G -->|Device Tilts| J[SensorEventListener]
        
        I -->|Low-Res Stream| K[MediaPipe Face & Hand Landmarker]
        K -->|Normalized Landmarks| L[3D Un-Projection Engine]
        J -->|Fusion Vectors| M[Gravity Roll Decoupler]
        
        L & M -->|Feature Vector Fusion| N[One Euro Filter Smoothing]
        N -->|Telemetry Event| O[Telemetry Listener Channel]
        N -->|Nod / Gaze Gesture Fired| P[Accessibility Swipe dispatcher]
        
        P -->|Scroll Actions| Q[System-Wide Windows / Third-Party Apps]
    end

    O -->|Reactive Updates| F
    H -->|Saves Profile JSON| R[FlutterSharedPreferences]
    R -->|Reads custom thresholds| G
```

### 1. The Native Gaze Engine (Kotlin)
*   **`GazeForegroundService.kt`**: The processing heartbeat. Runs as a sticky Android Foreground Service, capturing front-facing camera frames through `Camera2 API` at throttled rates (33ms active, 200ms idle) and piping them directly to **MediaPipe FaceLandmarker**.
*   **3D Camera-Space Un-Projection**: Landmark vectors are converted to millimeter-metric space using average physical Inter-Pupillary Distance (IOD) offsets to decouple depth variations.
*   **Gravity & Motion Decoupling**: Utilizes `TYPE_ROTATION_VECTOR` and `TYPE_GYROSCOPE` sensor inputs to continuously rotate 3D gaze vectors relative to the device's absolute roll/pitch space, preventing posture anomalies (like lying down) from corrupting the baseline.
*   **One Euro Filter Smoothing**: Telemetry is smoothed using high-velocity One Euro Filters to suppress jitter while maintaining ultra-low latency responsiveness.
*   **Nod State Machine**: Employs a robust three-phase transition model (`IDLE` -> `ARMED` -> `FIRED` -> `IDLE`) checking for peak-and-return criteria within a `600ms` window to prevent false gestures from minor hand tremors.

### 2. The Application Shell (Flutter)
*   **State Propagation**: Multi-threaded updates are captured via platform channels and exposed through declarative `NotifierProviders`.
*   **Accessibility Integration**: Accessibility swipes are dispatched dynamically via node coordinates using targeted, device-scaled stroke generators.

---

## 📁 Project Structure

```txt
gaze/
 ├── android/
 │    └── app/src/main/kotlin/com/example/gaze/
 │         ├── MainActivity.kt               # Method Channel bindings
 │         ├── GazeAccessibilityService.kt   # System-wide gesture dispatching
 │         └── GazeForegroundService.kt      # 3D un-projection, filters, & computer vision engine
 ├── lib/
 │    ├── main.dart                          # Application entry point
 │    └── src/
 │         ├── app/
 │         │    └── app.dart                 # Root widget with theme injections
 │         ├── core/
 │         │    ├── platform/                # Platform channel adapters
 │         │    ├── services/                # Background service controllers
 │         │    └── theme/                   # Forui customization rules
 │         ├── routing/
 │         │    └── app_router_config.dart   # GoRouter state declarations
 │         └── features/
 │              ├── dashboard/               # Real-time state visualizations
 │              ├── gaze_tracking/           # guided calibration UI & telemetry diagnostics
 │              ├── onboarding/              # Premium welcome carousel
 │              ├── permissions/             # Dependency check & overlay config
 │              └── settings/                # Threshold and whitelisting controllers
 └── pubspec.yaml                            # Dependency and asset manifests
```

---

## 🚦 Prerequisites & Installation

### Required Software
*   **Flutter SDK**: `^3.11.5`
*   **Dart SDK**: `^3.11.5`
*   **Android Studio / Android SDK**: Platform Tools level `33+`
*   **Physical Android Device**: Camera2 capability is required. Emulators will default to fallback heuristic engines.

### Installation Instructions
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/gaze.git
   cd gaze
   ```
2. Download packages and build code generators:
   ```bash
   flutter pub get
   flutter pub run build_runner build --delete-conflicting-outputs
   ```
3. Deploy to your physical Android device:
   ```bash
   flutter run --release
   ```

---

## 🔌 Environment & Storage Variables

All configuration files and calibration models are stored locally using Android SharedPreferences:

| Preference Key | Type | Description |
| :--- | :--- | :--- |
| `flutter.user_calibration_profile` | `String (JSON)` | Holds the customized neutral vectors and relative bottom/top bounds. |
| `is_first_launch` | `bool` | Toggles whether onboarding should render. |
| `sensitivity` | `double` | Scaling factor `[0.1 - 1.0]` for gesture detection. |

---

## ⚙️ Running & Building

### 🟢 Local Development
Run the application in debug mode on a connected device:
```bash
flutter run
```

### 🔨 Production Release
Generate a signed release APK optimized for production speeds:
```bash
flutter build apk --release
```

### 🧪 Run Unit/Widget Tests
Run tests locally to check onboarding flow or calibration state changes:
```bash
flutter test
```

---

## 🛡️ Coding Conventions & Standards

This project enforces strict performance and design system guidelines:

*   **Design Tokens Consistency**: All custom paddings, margins, colors, and typography rules are inherited dynamically via `context.theme` properties (`theme.colors`, `theme.typography`). Raw Hex values or arbitrary `Colors` are disallowed to maintain premium theme interoperability.
*   **Declarative Hooks**: Code utilizes explicit `NotifierProvider` implementations to isolate presentation states from domain-level telemetry streams.
*   **Linting Checks**: Configured with strict rules listed in `analysis_options.yaml` (requiring `prefer_const_constructors` and `prefer_final_locals`).

---

## 🔮 Future Improvements
*   **🤖 Smart App Detection**: Auto-detecting when a supported application enters the absolute screen foreground to wake up the camera service dynamically.
*   **📈 Context-Aware Smoothing**: Dynamically altering One Euro Filter coefficients based on the user's active scrolling speed inside specific feeds.
*   **🎨 Ambient Dynamic Theming**: Real-time styling shifts mimicking the visual colors of the active foreground app.
