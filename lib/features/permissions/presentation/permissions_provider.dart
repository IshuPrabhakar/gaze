import 'package:riverpod_annotation/riverpod_annotation.dart';
import '../../../core/platform/platform_channels.dart';

import 'package:permission_handler/permission_handler.dart';

part 'permissions_provider.g.dart';

class PermissionsState {
  final bool isCameraGranted;
  final bool isAccessibilityEnabled;
  final bool isOverlayGranted;
  final bool isBatteryExempted;

  PermissionsState({
    required this.isCameraGranted,
    required this.isAccessibilityEnabled,
    required this.isOverlayGranted,
    required this.isBatteryExempted,
  });

  bool get allGranted =>
      isCameraGranted && isAccessibilityEnabled && isOverlayGranted && isBatteryExempted;

  PermissionsState copyWith({
    bool? isCameraGranted,
    bool? isAccessibilityEnabled,
    bool? isOverlayGranted,
    bool? isBatteryExempted,
  }) {
    return PermissionsState(
      isCameraGranted: isCameraGranted ?? this.isCameraGranted,
      isAccessibilityEnabled: isAccessibilityEnabled ?? this.isAccessibilityEnabled,
      isOverlayGranted: isOverlayGranted ?? this.isOverlayGranted,
      isBatteryExempted: isBatteryExempted ?? this.isBatteryExempted,
    );
  }

  factory PermissionsState.initial() => PermissionsState(
    isCameraGranted: false,
    isAccessibilityEnabled: false,
    isOverlayGranted: false,
    isBatteryExempted: false,
  );
}

@riverpod
class Permissions extends _$Permissions {
  @override
  PermissionsState build() {
    return PermissionsState.initial();
  }

  Future<void> checkAll() async {
    final isCam = await Permission.camera.isGranted;
    final isAccess = await PlatformChannels.isAccessibilityServiceEnabled();
    final isOverlay = await Permission.systemAlertWindow.isGranted;
    final isBat = await Permission.ignoreBatteryOptimizations.isGranted;

    state = PermissionsState(
      isCameraGranted: isCam,
      isAccessibilityEnabled: isAccess,
      isOverlayGranted: isOverlay,
      isBatteryExempted: isBat,
    );
  }

  Future<void> requestCamera() async {
    await Permission.camera.request();
    await checkAll();
  }

  Future<void> openAccessibility() async {
    await PlatformChannels.openAccessibilitySettings();
    await checkAll();
  }

  Future<void> openOverlay() async {
    await Permission.systemAlertWindow.request();
    await checkAll();
  }

  Future<void> openBattery() async {
    await Permission.ignoreBatteryOptimizations.request();
    await checkAll();
  }

  void setCameraGranted(bool granted) {
    state = state.copyWith(isCameraGranted: granted);
  }
}
