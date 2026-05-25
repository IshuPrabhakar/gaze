import 'dart:async';
import 'package:flutter/services.dart';

class GazeTelemetryState {
  final bool isFaceDetected;
  final bool isAttentive;
  final bool isLookingDown;
  final bool isBlinking;
  final double yaw;
  final double pitch;
  final double eyeOpenness;
  final String activeApp;

  GazeTelemetryState({
    required this.isFaceDetected,
    required this.isAttentive,
    required this.isLookingDown,
    required this.isBlinking,
    required this.yaw,
    required this.pitch,
    required this.eyeOpenness,
    required this.activeApp,
  });

  factory GazeTelemetryState.empty() => GazeTelemetryState(
        isFaceDetected: false,
        isAttentive: false,
        isLookingDown: false,
        isBlinking: false,
        yaw: 0.0,
        pitch: 0.0,
        eyeOpenness: 0.0,
        activeApp: '',
      );

  factory GazeTelemetryState.fromMap(Map<dynamic, dynamic> map) {
    return GazeTelemetryState(
      isFaceDetected: map['isFaceDetected'] as bool? ?? false,
      isAttentive: map['isAttentive'] as bool? ?? false,
      isLookingDown: map['isLookingDown'] as bool? ?? false,
      isBlinking: map['isBlinking'] as bool? ?? false,
      yaw: (map['yaw'] as num? ?? 0.0).toDouble(),
      pitch: (map['pitch'] as num? ?? 0.0).toDouble(),
      eyeOpenness: (map['eyeOpenness'] as num? ?? 0.0).toDouble(),
      activeApp: map['activeApp'] as String? ?? '',
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
  }) async {
    try {
      final bool result = await _serviceChannel.invokeMethod<bool>('startService', {
        'sensitivity': sensitivity,
        'scrollSpeed': scrollSpeed,
        'triggerDurationMs': triggerDurationMs,
        'pauseOnLookAway': pauseOnLookAway,
        'systemWide': systemWide,
        'enabledApps': enabledApps,
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
