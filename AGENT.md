# Gaze

Production-ready Android Flutter application that enables gaze-controlled scrolling in third-party apps using Android Accessibility Services and front-camera attention detection.

---

# Product Vision

Gaze is a background-running Android utility that allows users to scroll short-form content hands-free using eye movement and attention detection.

This is NOT a reel player app.

The Flutter application acts as:
- control dashboard
- permissions manager
- calibration tool
- debugging console
- service manager
- settings interface

Actual scrolling occurs globally across other Android apps using:
- Accessibility Services
- Gesture dispatching
- Background camera processing

Target supported apps:
- Instagram Reels
- TikTok
- YouTube Shorts
- X/Twitter
- Reddit
- Any vertically scrollable feed

---

# Core Interaction Model

## Behavior

### Watching normally
- No action

### Looking away
- Optionally pause media

### Looking downward for ~800ms
- Trigger global scroll gesture

### Long blink
- Trigger configurable action

---

# Primary Technical Goals

The system must be:
- low latency
- battery efficient
- stable
- invisible during use
- production-grade
- modular
- maintainable
- testable

---

# Platform Target

## Primary Platform
- Android

## Required Android Capabilities
- Accessibility Service
- Foreground Service
- Overlay Windows
- Gesture Dispatching
- Background Camera Processing

---

# Mandatory Technical Constraints

## DO NOT BUILD
- fake gesture simulation
- demo-only scrolling
- in-app-only scrolling
- toy architecture
- UI-heavy MVPs
- experimental unmaintainable abstractions

## MUST SUPPORT
- real global scrolling
- background operation
- minimized app operation
- process/service recovery
- configurable gesture behavior
- active app detection

---

# Required Tech Stack

## Flutter
- Flutter latest stable
- Dart 3+

## State Management
- Riverpod 3.0
- flutter_riverpod
- riverpod_generator

## UI
- forui
- Material 3
- go_router

## Vision / Camera
- camera
- google_mlkit_face_detection

## Android Native
- Kotlin
- MethodChannel or Pigeon

## Code Quality
- very_good_analysis (can remain abstract initially)

---

# Forbidden Dependencies

DO NOT USE:
- freezed
- flutter_hooks
- hooks_riverpod
- bloc
- provider
- getx
- mutable global singletons

---

# Architecture Requirements

Use production-grade feature-first architecture.

## Required Structure

```txt
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
    permissions/
    settings/
    accessibility/
    background_service/
    gaze_tracking/
    overlays/
    analytics/

  shared/
    widgets/
    models/
```

---

# Layer Separation

Every feature should maintain separation between:

* presentation
* application/controllers
* infrastructure/services

Avoid mixing UI with business logic.

---

# State Management Rules

## Riverpod Requirements

Use:

* AsyncNotifier
* Notifier
* generated providers
* provider composition
* immutable state

## Avoid

* logic inside widgets
* manual dependency injection
* mutable shared state

---

# Android Native Integration

Native Android integration is mandatory.

## Required Native Modules

### Accessibility Service

Responsibilities:

* identify active app
* inspect accessibility tree
* detect scrollable nodes
* dispatch gestures
* support future custom gestures

### Foreground Service

Responsibilities:

* persistent background execution
* lifecycle management
* process recovery

### Overlay Manager

Responsibilities:

* debug overlays
* future floating controls

### Camera Background Handler

Responsibilities:

* low-resolution processing
* frame throttling
* lifecycle-safe operation

---

# Accessibility Service Rules

The accessibility layer must:

* perform REAL swipe gestures
* work across third-party apps
* support configurable speed/intensity
* remain responsive under load

Do not fake scrolling.

---

# Gaze Tracking Engine

Implement:

```txt
GazeTrackingEngine
```

## Detection Inputs

* face landmarks
* eye openness
* head orientation
* approximate vertical gaze

## Detection Outputs

* attentive
* distracted
* lookingDown
* blinking
* noFaceDetected

## Required Logic

* temporal smoothing
* debouncing
* jitter reduction
* cooldown management

## Example Thresholds

* looking down for 800ms → scroll
* distracted for 500ms → pause

---

# Performance Requirements

The app must:

* minimize battery drain
* minimize rebuilds
* avoid memory leaks
* throttle frame analysis
* process low-resolution frames
* remain responsive while backgrounded

## Optimization Strategies

* frame skipping
* throttled ML processing
* isolate-friendly computation
* efficient camera lifecycle handling

---

# Settings Dashboard

Flutter UI is primarily a control panel.

## Required Screens

### Home Dashboard

Display:

* service status
* accessibility status
* camera status
* active app
* current gaze state

### Settings

Include:

* sensitivity
* scroll speed
* trigger duration
* allowed apps
* pause-on-look-away
* battery optimization guidance

### Permissions Flow

Handle:

* accessibility permission
* camera permission
* overlay permission
* battery optimization exemption

### Debug Screen

Include:

* camera preview
* face landmarks
* gaze state
* trigger logs
* realtime diagnostics

---

# UI Requirements

Use forui consistently.

## Design Principles

* modern
* minimal
* dark-first
* premium feel
* responsive
* polished

## UI Expectations

* smooth animations
* clean spacing
* semantic colors
* proper loading states
* graceful error states

---

# Theme System

Implement:

* AppTheme
* dark/light themes
* typography system
* spacing system
* reusable design tokens

Use forui theming correctly.

---

# Error Handling

Must support:

* permission denial handling
* accessibility disabled handling
* service crash recovery
* structured logging
* native platform exception handling
* fallback states

Never silently fail.

---

# Testing Requirements

Include:

* unit tests
* provider tests
* widget tests
* service-layer tests
* integration-ready architecture

Code should be testable by design.

---

# Engineering Rules

## Code Quality

* production-quality only
* strongly typed everywhere
* immutable models preferred
* small focused files
* composable widgets
* maintainable architecture
* meaningful comments only

## Avoid

* giant files
* placeholder architecture
* fake implementations
* unnecessary abstractions
* overengineering

---

# Implementation Order

Build incrementally in this order:

1. architecture foundation
2. Android native integration
3. Riverpod foundation
4. foreground/background services
5. accessibility service
6. gaze tracking engine
7. permissions system
8. settings dashboard
9. overlays/debugging
10. performance optimization
11. testing

---

# Agent Expectations

When generating code:

* prioritize maintainability
* prioritize real Android integration
* avoid shortcuts
* avoid fake behavior
* optimize for scalability
* follow Dart 3 best practices
* keep architecture clean and modular

If unsure:

* choose simpler production-safe solutions
* prefer explicitness over magic
* optimize for long-term maintainability

