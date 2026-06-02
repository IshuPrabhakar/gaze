# Changelog

All notable changes to Gaze Control are documented here. This project follows [Semantic Versioning](https://semver.org/) and [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) conventions.

---

## [Unreleased]

### Planned
- Smart App Detection: auto-wake camera service on supported app foreground
- Context-Aware Smoothing: dynamic One Euro Filter coefficients per scroll velocity
- Ambient Dynamic Theming: mirror active app color palette in overlay

---

## [1.0.0] — 2025-01-15

### Added
- Initial open source release
- Eye gaze tracking via MediaPipe Face Landmarker + L2CS-Net ONNX inference
- Head nod state machine with gyroscope/gravity sensor fusion decoupling
- Palm gesture recognition (Open Palm, Closed Fist, Thumbs Up) via MediaPipe Hand Landmarker
- 9-stage guided calibration flow with JSON profile persistence
- `GazeStateMachine` with full `NO_FACE → SEARCHING → TRACKING → STABLE_LOCK → ACTION_READY → COOLDOWN` cycle
- `ConfidenceEngine` combining landmark visibility, eyelid blendshapes, and device motion
- `BlinkDetector` using Eye Aspect Ratio (EAR) + blendshape fusion
- `DirectionHysteresis` entry/exit threshold system
- `GravityRollDecoupler` for head rotation vs. gaze intent separation
- `ThermalPerformanceManager` for dynamic frame throttling
- `InteractionProfileManager` for per-application scroll tuning
- `DriftCorrectionManager` for long-session calibration stability
- NNAPI hardware acceleration with mathematical fallback for L2CS-Net inference
- Real-time telemetry dashboard (confidence score, internal state, gesture events)
- Onboarding carousel for first-launch setup
- Accessibility + overlay permission dependency checker
- Sensitivity scaling preference (`[0.1 – 1.0]`)
- Full on-device privacy: no data transmitted or stored beyond local calibration profile

[Unreleased]: https://github.com/your-org/gaze-control/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/your-org/gaze-control/releases/tag/v1.0.0
