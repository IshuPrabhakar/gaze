class SettingsState {
  final double sensitivity;
  final double scrollSpeed;
  final int triggerDurationMs;
  final bool pauseOnLookAway;
  final bool systemWide;
  final List<String> enabledApps;
  final String swipeMode;

  SettingsState({
    required this.sensitivity,
    required this.scrollSpeed,
    required this.triggerDurationMs,
    required this.pauseOnLookAway,
    required this.systemWide,
    required this.enabledApps,
    required this.swipeMode,
  });

  SettingsState copyWith({
    double? sensitivity,
    double? scrollSpeed,
    int? triggerDurationMs,
    bool? pauseOnLookAway,
    bool? systemWide,
    List<String>? enabledApps,
    String? swipeMode,
  }) {
    return SettingsState(
      sensitivity: sensitivity ?? this.sensitivity,
      scrollSpeed: scrollSpeed ?? this.scrollSpeed,
      triggerDurationMs: triggerDurationMs ?? this.triggerDurationMs,
      pauseOnLookAway: pauseOnLookAway ?? this.pauseOnLookAway,
      systemWide: systemWide ?? this.systemWide,
      enabledApps: enabledApps ?? this.enabledApps,
      swipeMode: swipeMode ?? this.swipeMode,
    );
  }

  factory SettingsState.initial() => SettingsState(
        sensitivity: 0.5,
        scrollSpeed: 1.0,
        triggerDurationMs: 800,
        pauseOnLookAway: false,
        systemWide: true,
        enabledApps: ['com.instagram.android', 'com.zhiliaoapp.musically', 'com.google.android.youtube'],
        swipeMode: 'eyeTracking',
      );
}
