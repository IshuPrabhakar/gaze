# Contributing to Gaze Control

Thank you for your interest in contributing. This document covers everything you need to get started.

---

## Ways to Contribute

- **Bug reports** — Open an issue with reproduction steps, device model, Android version, and logs from `adb logcat`
- **Feature requests** — Open a GitHub Discussion before building anything significant so we can align on approach
- **Code** — Bug fixes, new features, performance improvements, platform ports
- **Documentation** — Architecture docs, calibration guides, translation
- **Testing** — Especially on device models and Android versions we don't have access to

---

## Development Setup

See [Getting Started in README.md](README.md#getting-started). The short version:

```bash
git clone https://github.com/Deep-SkyLabs/gaze-control.git
cd gaze-control
flutter pub get
flutter pub run build_runner build --delete-conflicting-outputs
flutter run --profile  # profile mode for realistic CV pipeline performance
```

---

## Pull Request Process

1. **Open an issue or discussion first** for anything beyond small bug fixes. This prevents duplicate work and ensures the approach fits the project direction.
2. **Fork the repository** and create a branch from `main`. Branch names: `fix/blink-detector-ear-threshold`, `feat/ios-port`, `docs/calibration-guide`.
3. **Write tests** — widget tests for UI changes, unit tests for new logic in `core/`.
4. **Run linting** — `flutter analyze` must pass with zero warnings.
5. **Open the PR** — fill out the template, link the related issue, and describe what changed and why.

---

## Code Standards

### Flutter / Dart

- **Design tokens only**: Every color, spacing value, and typography style must reference `context.theme`. No hardcoded hex values, no `Colors.blue`, no arbitrary `EdgeInsets` literals.
  ```dart
  // ✅ Correct
  color: context.theme.colors.primary
  padding: EdgeInsets.all(context.theme.spacing.md)

  // ❌ Incorrect
  color: Color(0xFF6366F1)
  padding: EdgeInsets.all(16)
  ```
- **Explicit NotifierProviders**: State lives in `NotifierProvider` classes. Business logic does not live in widgets.
- **Const constructors**: Use `const` wherever the analyzer allows (`prefer_const_constructors` is enforced).
- **Final locals**: Prefer `final` for all local variables that aren't reassigned (`prefer_final_locals` is enforced).

### Kotlin / Android

- **No blocking main thread**: Camera frame callbacks and ML inference always happen off the main thread.
- **Null safety**: Prefer non-nullable types. Avoid `!!` force-unwrap operators.
- **Resource cleanup**: Any `CameraDevice`, `ImageReader`, or `MediaPipe` resource opened in the service must be closed in `onDestroy`.

---

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add ambient dynamic theming for active foreground app
fix: prevent blink detector false positives during rapid eye movement  
perf: reduce camera frame processing latency by 18% via ROI pre-crop
docs: add calibration algorithm explanation to CALIBRATION.md
refactor: extract ConfidenceEngine into standalone testable class
```

---

## Code of Conduct

This project follows the [Contributor Covenant v2.1](https://www.contributor-covenant.org/version/2/1/code_of_conduct/). Be respectful. Be constructive. Help us build something good.
