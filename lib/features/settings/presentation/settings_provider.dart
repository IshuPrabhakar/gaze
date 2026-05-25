import 'package:riverpod_annotation/riverpod_annotation.dart';
import '../../../core/services/shared_preferences_provider.dart';
import '../../../core/platform/platform_channels.dart';
import '../domain/settings_state.dart';

part 'settings_provider.g.dart';

@riverpod
class Settings extends _$Settings {
  static const String _keySensitivity = 'gaze_sensitivity';
  static const String _keyScrollSpeed = 'gaze_scroll_speed';
  static const String _keyDuration = 'gaze_duration';
  static const String _keyPauseLookAway = 'gaze_pause_look_away';
  static const String _keySystemWide = 'gaze_system_wide';
  static const String _keySwipeMode = 'gaze_swipe_mode';

  @override
  SettingsState build() {
    final prefs = ref.watch(sharedPreferencesProvider);
    
    return SettingsState(
      sensitivity: prefs.getDouble(_keySensitivity) ?? 0.5,
      scrollSpeed: prefs.getDouble(_keyScrollSpeed) ?? 1.0,
      triggerDurationMs: prefs.getInt(_keyDuration) ?? 800,
      pauseOnLookAway: prefs.getBool(_keyPauseLookAway) ?? false,
      systemWide: prefs.getBool(_keySystemWide) ?? true,
      swipeMode: prefs.getString(_keySwipeMode) ?? 'eyeTracking',
      enabledApps: prefs.getStringList('gaze_enabled_apps') ?? [
        'com.instagram.android',
        'com.zhiliaoapp.musically',
        'com.google.android.youtube',
        'com.twitter.android',
        'com.reddit.frontpage'
      ],
    );
  }

  Future<void> _applyIfRunning() async {
    final isRunning = await PlatformChannels.isServiceRunning();
    if (isRunning) {
      await PlatformChannels.startService(
        sensitivity: state.sensitivity,
        scrollSpeed: state.scrollSpeed,
        triggerDurationMs: state.triggerDurationMs,
        pauseOnLookAway: state.pauseOnLookAway,
        systemWide: state.systemWide,
        enabledApps: state.enabledApps,
        swipeMode: state.swipeMode,
      );
    }
  }

  Future<void> updateSensitivity(double value) async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setDouble(_keySensitivity, value);
    state = state.copyWith(sensitivity: value);
    await _applyIfRunning();
  }

  Future<void> updateScrollSpeed(double value) async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setDouble(_keyScrollSpeed, value);
    state = state.copyWith(scrollSpeed: value);
    await _applyIfRunning();
  }

  Future<void> updateTriggerDuration(int ms) async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setInt(_keyDuration, ms);
    state = state.copyWith(triggerDurationMs: ms);
    await _applyIfRunning();
  }

  Future<void> updatePauseOnLookAway(bool value) async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setBool(_keyPauseLookAway, value);
    state = state.copyWith(pauseOnLookAway: value);
    await _applyIfRunning();
  }

  Future<void> updateSystemWide(bool value) async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setBool(_keySystemWide, value);
    state = state.copyWith(systemWide: value);
    await _applyIfRunning();
  }

  Future<void> updateSwipeMode(String value) async {
    final prefs = ref.read(sharedPreferencesProvider);
    await prefs.setString(_keySwipeMode, value);
    state = state.copyWith(swipeMode: value);
    await _applyIfRunning();
  }

  Future<void> toggleApp(String packageName) async {
    final prefs = ref.read(sharedPreferencesProvider);
    final currentList = List<String>.from(state.enabledApps);
    if (currentList.contains(packageName)) {
      currentList.remove(packageName);
    } else {
      currentList.add(packageName);
    }
    await prefs.setStringList('gaze_enabled_apps', currentList);
    state = state.copyWith(enabledApps: currentList);
    await _applyIfRunning();
  }
}
