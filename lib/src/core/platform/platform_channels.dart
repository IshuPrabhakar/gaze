import 'dart:async';
import 'package:flutter/services.dart';

class GazeTelemetryState {
  final bool isFaceDetected;
  final bool isAttentive;
  final bool isLookingDown;
  final bool isLookingUp;
  final bool isLookingLeft;
  final bool isLookingRight;
  final bool isBlinking;
  final double yaw;
  final double pitch;
  final double eyeOpenness;
  final String activeApp;
  final bool isNodLeft;
  final bool isNodRight;
  final bool isNodUp;
  final bool isNodDown;
  final String detectedHandGesture;
  final bool isSwipeLeft;
  final bool isSwipeRight;
  final bool isSwipeUp;
  final bool isSwipeDown;
  final double rawConfidence;
  final String internalState;
 
  GazeTelemetryState({
    required this.isFaceDetected,
    required this.isAttentive,
    required this.isLookingDown,
    required this.isLookingUp,
    required this.isLookingLeft,
    required this.isLookingRight,
    required this.isBlinking,
    required this.yaw,
    required this.pitch,
    required this.eyeOpenness,
    required this.activeApp,
    required this.isNodLeft,
    required this.isNodRight,
    required this.isNodUp,
    required this.isNodDown,
    required this.detectedHandGesture,
    required this.isSwipeLeft,
    required this.isSwipeRight,
    required this.isSwipeUp,
    required this.isSwipeDown,
    required this.rawConfidence,
    required this.internalState,
  });

  factory GazeTelemetryState.empty() => GazeTelemetryState(
        isFaceDetected: false,
        isAttentive: false,
        isLookingDown: false,
        isLookingUp: false,
        isLookingLeft: false,
        isLookingRight: false,
        isBlinking: false,
        yaw: 0.0,
        pitch: 0.0,
        eyeOpenness: 0.0,
        activeApp: '',
        isNodLeft: false,
        isNodRight: false,
        isNodUp: false,
        isNodDown: false,
        detectedHandGesture: 'NONE',
        isSwipeLeft: false,
        isSwipeRight: false,
        isSwipeUp: false,
        isSwipeDown: false,
        rawConfidence: 1.0,
        internalState: 'TRACKING',
      );

  factory GazeTelemetryState.fromMap(Map<dynamic, dynamic> map) {
    return GazeTelemetryState(
      isFaceDetected: map['isFaceDetected'] as bool? ?? false,
      isAttentive: map['isAttentive'] as bool? ?? false,
      isLookingDown: map['isLookingDown'] as bool? ?? false,
      isLookingUp: map['isLookingUp'] as bool? ?? false,
      isLookingLeft: map['isLookingLeft'] as bool? ?? false,
      isLookingRight: map['isLookingRight'] as bool? ?? false,
      isBlinking: map['isBlinking'] as bool? ?? false,
      yaw: (map['yaw'] as num? ?? 0.0).toDouble(),
      pitch: (map['pitch'] as num? ?? 0.0).toDouble(),
      eyeOpenness: (map['eyeOpenness'] as num? ?? 0.0).toDouble(),
      activeApp: map['activeApp'] as String? ?? '',
      isNodLeft: map['isNodLeft'] as bool? ?? false,
      isNodRight: map['isNodRight'] as bool? ?? false,
      isNodUp: map['isNodUp'] as bool? ?? false,
      isNodDown: map['isNodDown'] as bool? ?? false,
      detectedHandGesture: map['detectedHandGesture'] as String? ?? 'NONE',
      isSwipeLeft: map['isSwipeLeft'] as bool? ?? false,
      isSwipeRight: map['isSwipeRight'] as bool? ?? false,
      isSwipeUp: map['isSwipeUp'] as bool? ?? false,
      isSwipeDown: map['isSwipeDown'] as bool? ?? false,
      rawConfidence: (map['rawConfidence'] as num? ?? 1.0).toDouble(),
      internalState: map['internalState'] as String? ?? 'TRACKING',
    );
  }
}

class PlatformChannels {
  static const MethodChannel _serviceChannel = MethodChannel('com.example.gaze/background_service');
  static const MethodChannel _telemetryChannel = MethodChannel('com.example.gaze/telemetry');

  static final StreamController<GazeTelemetryState> _telemetryController =
      StreamController<GazeTelemetryState>.broadcast();

  static Stream<GazeTelemetryState> get telemetryStream => _telemetryController.stream;

  static void initialize() {
    _telemetryChannel.setMethodCallHandler((call) async {
      if (call.method == 'onGazeStateChanged') {
        final Map<dynamic, dynamic> data = call.arguments as Map<dynamic, dynamic>;
        _telemetryController.add(GazeTelemetryState.fromMap(data));
      }
    });
  }

  static Future<bool> startService({
    required double sensitivity,
    required double scrollSpeed,
    required int triggerDurationMs,
    required bool pauseOnLookAway,
    required bool systemWide,
    required List<String> enabledApps,
    required String swipeMode,
  }) async {
    try {
      final bool result = await _serviceChannel.invokeMethod<bool>('startService', {
        'sensitivity': sensitivity,
        'scrollSpeed': scrollSpeed,
        'triggerDurationMs': triggerDurationMs,
        'pauseOnLookAway': pauseOnLookAway,
        'systemWide': systemWide,
        'enabledApps': enabledApps,
        'swipeMode': swipeMode,
      }) ?? false;
      return result;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> stopService() async {
    try {
      final bool result = await _serviceChannel.invokeMethod<bool>('stopService') ?? false;
      return result;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> isServiceRunning() async {
    try {
      return await _serviceChannel.invokeMethod<bool>('isServiceRunning') ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> isAccessibilityServiceEnabled() async {
    try {
      return await _serviceChannel.invokeMethod<bool>('isAccessibilityServiceEnabled') ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> openAccessibilitySettings() async {
    try {
      return await _serviceChannel.invokeMethod<bool>('openAccessibilitySettings') ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> openOverlaySettings() async {
    try {
      return await _serviceChannel.invokeMethod<bool>('openOverlaySettings') ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> isOverlayPermissionGranted() async {
    try {
      return await _serviceChannel.invokeMethod<bool>('isOverlayPermissionGranted') ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> openBatteryOptimizationSettings() async {
    try {
      return await _serviceChannel.invokeMethod<bool>('openBatteryOptimizationSettings') ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> isBatteryExempted() async {
    try {
      return await _serviceChannel.invokeMethod<bool>('isBatteryExempted') ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<String> getActivePackageName() async {
    try {
      return await _serviceChannel.invokeMethod<String>('getActivePackageName') ?? '';
    } on PlatformException catch (_) {
      return '';
    }
  }

  static Future<bool> triggerScrollDown(double speed) async {
    try {
      return await _serviceChannel.invokeMethod<bool>('triggerScrollDown', {
        'scrollSpeed': speed,
      }) ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> triggerScrollUp(double speed) async {
    try {
      return await _serviceChannel.invokeMethod<bool>('triggerScrollUp', {
        'scrollSpeed': speed,
      }) ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> triggerScrollLeft(double speed) async {
    try {
      return await _serviceChannel.invokeMethod<bool>('triggerScrollLeft', {
        'scrollSpeed': speed,
      }) ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> triggerScrollRight(double speed) async {
    try {
      return await _serviceChannel.invokeMethod<bool>('triggerScrollRight', {
        'scrollSpeed': speed,
      }) ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> isCameraPermissionGranted() async {
    try {
      return await _serviceChannel.invokeMethod<bool>('isCameraPermissionGranted') ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }

  static Future<bool> requestCameraPermission() async {
    try {
      return await _serviceChannel.invokeMethod<bool>('requestCameraPermission') ?? false;
    } on PlatformException catch (_) {
      return false;
    }
  }
}
