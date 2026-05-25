Build a production-ready Flutter application called “Gaze”.

IMPORTANT:
This is NOT a reel player app.

This app runs in the background and controls scrolling behavior in OTHER APPS using gaze/attention detection from the front camera.

The app itself only contains:
- onboarding
- permissions
- settings
- calibration/debug tools
- service controls
- analytics/debugging

The actual scrolling happens globally across the Android system using Accessibility Services.

====================================================
CORE PRODUCT IDEA
====================================================

The app uses the front camera to detect:
- whether the user is looking at the screen
- whether the user looks away
- whether the user intentionally looks downward
- blink detection

When enabled:

1. User watching content
   → do nothing

2. User looks away
   → optionally auto pause media

3. User looks downward continuously for ~800ms
   → automatically scroll to next reel/post/video
   → works inside Instagram, TikTok, YouTube Shorts, X, etc.

4. Long blink
   → configurable action

The app should feel:
- low latency
- stable
- invisible
- battery efficient
- production quality

====================================================
PLATFORM REQUIREMENTS
====================================================

Primary target:
- Android

The app MUST use:
- Android Accessibility Service
- foreground service
- overlay support where necessary
- camera background processing
- lifecycle-safe architecture

The app should be architected so that:
- gaze detection works while app is backgrounded
- scrolling works in other apps
- service survives app minimization
- service restarts gracefully

====================================================
IMPORTANT TECHNICAL CONSTRAINTS
====================================================

DO NOT build:
- fake simulation
- demo-only architecture
- in-app scrolling only

The architecture must support REAL global scrolling behavior using Android accessibility APIs.

Accessibility service should:
- detect active app
- detect scrollable views
- perform swipe gestures
- support configurable scroll intensity/speed

====================================================
TECH STACK
====================================================

Use:

- Flutter latest stable
- Riverpod 3.0
- forui UI library
- go_router
- camera
- google_mlkit_face_detection
- accessibility service platform channels
- flutter_riverpod
- very_good_analysis (can be leave as abstract for now later implemented)

DO NOT USE:
- freezed
- flutter_hooks
- hooks_riverpod
- bloc
- provider
- getx

====================================================
ARCHITECTURE
====================================================

Use production-grade feature-first architecture.

Example:

lib/
  core/
    constants/
    theme/
    services/
    errors/
    router/
    utils/
    extensions/
    platform/

  features/
    onboarding/
    settings/
    permissions/
    accessibility/
    gaze_tracking/
    background_service/
    overlays/
    analytics/

  shared/
    widgets/
    models/

Architecture requirements:
- scalable
- testable
- modular
- strongly typed
- clean separation of concerns

Separate:
- presentation
- application/controller layer
- infrastructure/services

====================================================
RIVERPOD REQUIREMENTS
====================================================

Use Riverpod 3.0 properly.

Requirements:
- generated providers
- AsyncNotifier where appropriate
- Notifier for local state
- provider composition
- no mutable globals
- no business logic in widgets
- dependency injection via providers only

====================================================
ANDROID NATIVE INTEGRATION
====================================================

Implement native Android integration for:

1. Accessibility Service
2. Foreground Service
3. Overlay windows
4. Gesture dispatching
5. Background camera handling

Use:
- Kotlin
- method channels or pigeon

Accessibility service should:
- identify active app
- dispatch swipe gestures
- support future gesture customization

====================================================
BACKGROUND SERVICE
====================================================

Implement a robust background service architecture.

Requirements:
- persistent foreground notification
- survive app minimization
- restart after process death where possible
- optimized battery usage
- avoid memory leaks
- proper lifecycle cleanup

====================================================
GAZE DETECTION SYSTEM
====================================================

Implement lightweight heuristic-based gaze detection.

DO NOT train custom ML models.

Use:
- face landmarks
- eye openness
- head orientation
- approximate vertical gaze

Create:

GazeTrackingEngine

Outputs:
- attentive
- distracted
- lookingDown
- blinking
- noFaceDetected

Must implement:
- debouncing
- temporal smoothing
- jitter reduction

Example:
- sustained downward gaze for 800ms before trigger
- sustained distraction for 500ms before pause

====================================================
SETTINGS SYSTEM
====================================================

The Flutter UI primarily acts as a control dashboard.

Implement:

1. Home Dashboard
   - service status
   - accessibility status
   - camera status
   - current active app
   - realtime gaze state

2. Settings
   - sensitivity
   - scroll speed
   - trigger duration
   - enable/disable apps
   - battery optimization guide
   - pause-on-look-away toggle

3. Permissions Flow
   - accessibility permission
   - camera permission
   - overlay permission
   - battery optimization exemption

4. Debug Screen
   - camera preview
   - face landmarks
   - gaze direction state
   - trigger logs

====================================================
UI REQUIREMENTS
====================================================

Use forui consistently.

Design language:
- modern
- minimal
- premium
- dark mode first

Use:
- smooth animations
- polished settings UI
- clean cards
- responsive layouts
- proper loading/error states

====================================================
PERFORMANCE REQUIREMENTS
====================================================

The app must:
- minimize battery drain
- throttle frame processing
- avoid excessive rebuilds
- process camera frames efficiently
- maintain responsive accessibility gestures
- run smoothly in background

Implement:
- frame skipping
- low-resolution camera analysis
- isolate-friendly processing where useful

====================================================
ERROR HANDLING
====================================================

Implement:
- robust permission handling
- accessibility disabled states
- service crash recovery
- fallback states
- structured logging
- platform exception handling

====================================================
TESTING
====================================================

Include:
- unit tests
- provider tests
- widget tests
- platform integration structure
- service-layer testing support

Architecture should be testable by design.

====================================================
THEME SYSTEM
====================================================

Create:
- AppTheme
- dark/light themes
- spacing system
- typography system
- semantic colors
- reusable design tokens

Use forui theming properly.

====================================================
DELIVERABLES
====================================================

Generate:

1. Full folder structure
2. Riverpod architecture setup
3. Android native integration structure
4. Accessibility service implementation
5. Background service implementation
6. Gaze tracking engine
7. Permission flows
8. Settings dashboard
9. Overlay/debug system
10. Production-grade routing
11. App theme system
12. README with setup instructions
13. Platform channel architecture
14. Battery optimization guidance flow

====================================================
ENGINEERING RULES
====================================================

- Production-quality code only
- No giant files
- No business logic inside widgets
- Strong typing everywhere
- Prefer immutable models
- Use Dart 3 patterns
- Keep widgets composable
- Optimize for maintainability
- Add meaningful comments only where necessary
- No placeholder architecture
- No fake implementations unless explicitly marked
- Prioritize real Android integration patterns

Generate incrementally starting with:
1. architecture
2. native Android integration
3. Riverpod foundation
4. background services
5. gaze engine
6. UI/settings dashboard

