import 'package:riverpod_annotation/riverpod_annotation.dart';
import '../../../core/platform/platform_channels.dart';
import '../../settings/presentation/settings_provider.dart';

part 'service_provider.g.dart';

@riverpod
class ServiceControl extends _$ServiceControl {
  @override
  bool build() {
    // Initial check
    checkRunning();
    return false;
  }

  Future<void> checkRunning() async {
    final running = await PlatformChannels.isServiceRunning();
    state = running;
  }

  Future<bool> start() async {
    final settings = ref.read(settingsProvider);
    final success = await PlatformChannels.startService(
      sensitivity: settings.sensitivity,
      scrollSpeed: settings.scrollSpeed,
      triggerDurationMs: settings.triggerDurationMs,
      pauseOnLookAway: settings.pauseOnLookAway,
      systemWide: settings.systemWide,
      enabledApps: settings.enabledApps,
    );
    if (success) {
      state = true;
    }
    return success;
  }

  Future<bool> stop() async {
    final success = await PlatformChannels.stopService();
    if (success) {
      state = false;
    }
    return success;
  }
}
