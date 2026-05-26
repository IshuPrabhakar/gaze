import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:forui/forui.dart';
import 'provider/settings_provider.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  static const String routePath = '/settings';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(settingsProvider);
    final settingsNotifier = ref.read(settingsProvider.notifier);
    final theme = context.theme;

    return Scaffold(
      backgroundColor: theme.colors.background,
      appBar: AppBar(
        title: Text(
          'Calibration & Settings',
          style: theme.typography.lg.copyWith(color: theme.colors.foreground, fontWeight: FontWeight.bold),
        ),
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: IconThemeData(color: theme.colors.foreground),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 8.0),
          child: ListView(
            children: [
              _buildSettingCard(
                context,
                title: 'Personalized Eye Calibration',
                subtitle: 'Train the eye-tracking system to recognize your unique range of eye motion and posture.',
                child: Row(
                  children: [
                    Expanded(
                      child: FButton(
                        onPress: () => context.push('/calibration'),
                        child: const Text('Start Active Calibration'),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              Text(
                'Tweak Gestures',
                style: theme.typography.md.copyWith(
                  fontWeight: FontWeight.bold,
                  color: theme.colors.foreground,
                ),
              ),
              const SizedBox(height: 12),
              // Sliders inside cards
              _buildSettingCard(
                context,
                title: 'Gaze Sensitivity: ${(settings.sensitivity * 100).toInt()}%',
                subtitle: 'Determines facial threshold pitch angle needed to trigger look downward action.',
                child: Slider(
                  value: settings.sensitivity,
                  min: 0.1,
                  max: 1.0,
                  activeColor: theme.colors.primary,
                  inactiveColor: theme.colors.border,
                  onChanged: (val) => settingsNotifier.updateSensitivity(val),
                ),
              ),
              const SizedBox(height: 16),
              _buildSettingCard(
                context,
                title: 'Scroll Velocity: ${(settings.scrollSpeed * 100).toInt()}%',
                subtitle: 'Configures speed / swiping stroke duration for accessibility dispatching.',
                child: Slider(
                  value: settings.scrollSpeed,
                  min: 0.2,
                  max: 2.0,
                  activeColor: theme.colors.primary,
                  inactiveColor: theme.colors.border,
                  onChanged: (val) => settingsNotifier.updateScrollSpeed(val),
                ),
              ),
              const SizedBox(height: 16),
              _buildSettingCard(
                context,
                title: 'Interaction Control Mode',
                subtitle: 'Choose how vertical swipes are triggered. Horizontal swipes always respond to left/right nods.',
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: ChoiceChip(
                            label: const Center(
                              child: Text(
                                'Eye Gaze',
                                style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 10),
                              ),
                            ),
                            selected: settings.swipeMode == 'eyeTracking',
                            selectedColor: theme.colors.primary,
                            backgroundColor: theme.colors.card,
                            showCheckmark: false,
                            onSelected: (selected) {
                              if (selected) {
                                settingsNotifier.updateSwipeMode('eyeTracking');
                              }
                            },
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: ChoiceChip(
                            label: const Center(
                              child: Text(
                                'Head Nod',
                                style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 10),
                              ),
                            ),
                            selected: settings.swipeMode == 'headNod',
                            selectedColor: theme.colors.primary,
                            backgroundColor: theme.colors.card,
                            showCheckmark: false,
                            onSelected: (selected) {
                              if (selected) {
                                settingsNotifier.updateSwipeMode('headNod');
                              }
                            },
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: ChoiceChip(
                            label: const Center(
                              child: Text(
                                'Hand Gesture',
                                style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 10),
                              ),
                            ),
                            selected: settings.swipeMode == 'handGesture',
                            selectedColor: theme.colors.primary,
                            backgroundColor: theme.colors.card,
                            showCheckmark: false,
                            onSelected: (selected) {
                              if (selected) {
                                settingsNotifier.updateSwipeMode('handGesture');
                              }
                            },
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Text(
                      settings.swipeMode == 'eyeTracking'
                          ? '👉 Eye Gaze Mode: Look up/down and hold your gaze for the trigger duration to scroll vertically.'
                          : settings.swipeMode == 'headNod'
                              ? '👉 Head Nodding Mode: Tilt/nod your head up or down to scroll vertically instantly.'
                              : '👉 Hand Gesture Mode: Swipe your hand in front of the camera to scroll, or use Palm Gestures (Open Palm to Play/Pause, Fist to Stop, Thumbs Up to Select).',
                      style: theme.typography.xs.copyWith(
                        color: theme.colors.primary,
                        fontStyle: FontStyle.italic,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              _buildSettingCard(
                context,
                title: 'Trigger Hold Duration: ${settings.triggerDurationMs}ms',
                subtitle: 'Eye attention must continuously look downward for this duration before vertical swipes fire.',
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [500, 800, 1200].map((ms) {
                    final isSelected = settings.triggerDurationMs == ms;
                    return ChoiceChip(
                      label: Text('$ms ms', style: const TextStyle(color: Colors.white)),
                      selected: isSelected,
                      selectedColor: theme.colors.primary,
                      backgroundColor: theme.colors.card,
                      onSelected: (_) => settingsNotifier.updateTriggerDuration(ms),
                    );
                  }).toList(),
                ),
              ),
              const SizedBox(height: 16),
              _buildSettingCard(
                context,
                title: 'Pause on Look Away',
                subtitle: 'Automatically pause media playback when you look away from the screen, and resume when you look back.',
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      settings.pauseOnLookAway ? 'Enabled' : 'Disabled',
                      style: theme.typography.sm.copyWith(
                        fontWeight: FontWeight.bold,
                        color: settings.pauseOnLookAway ? const Color(0xFF22C55E) : theme.colors.mutedForeground,
                      ),
                    ),
                    FSwitch(
                      value: settings.pauseOnLookAway,
                      onChange: (val) => settingsNotifier.updatePauseOnLookAway(val),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              _buildSettingCard(
                context,
                title: 'System-Wide Mode',
                subtitle: 'When enabled, gaze scrolling works on all apps. When disabled, gestures are restricted to whitelisted apps below.',
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      settings.systemWide ? 'System-Wide' : 'Whitelisted Only',
                      style: theme.typography.sm.copyWith(
                        fontWeight: FontWeight.bold,
                        color: settings.systemWide ? const Color(0xFF22C55E) : theme.colors.mutedForeground,
                      ),
                    ),
                    FSwitch(
                      value: settings.systemWide,
                      onChange: (val) => settingsNotifier.updateSystemWide(val),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 24),
              Text(
                'Enabled Applications',
                style: theme.typography.md.copyWith(
                  fontWeight: FontWeight.bold,
                  color: theme.colors.foreground,
                ),
              ),
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: theme.colors.card,
                  borderRadius: theme.style.borderRadius.lg,
                  border: Border.all(color: theme.colors.border, width: theme.style.borderWidth),
                ),
                child: Column(
                  children: [
                    _buildAppTile(
                      context,
                      packageName: 'com.instagram.android',
                      appName: 'Instagram Reels',
                      enabled: settings.enabledApps.contains('com.instagram.android'),
                      onChanged: () => settingsNotifier.toggleApp('com.instagram.android'),
                    ),
                    Divider(color: theme.colors.border, height: 16),
                    _buildAppTile(
                      context,
                      packageName: 'com.zhiliaoapp.musically',
                      appName: 'TikTok Feed',
                      enabled: settings.enabledApps.contains('com.zhiliaoapp.musically'),
                      onChanged: () => settingsNotifier.toggleApp('com.zhiliaoapp.musically'),
                    ),
                    Divider(color: theme.colors.border, height: 16),
                    _buildAppTile(
                      context,
                      packageName: 'com.google.android.youtube',
                      appName: 'YouTube Shorts',
                      enabled: settings.enabledApps.contains('com.google.android.youtube'),
                      onChanged: () => settingsNotifier.toggleApp('com.google.android.youtube'),
                    ),
                    Divider(color: theme.colors.border, height: 16),
                    _buildAppTile(
                      context,
                      packageName: 'com.twitter.android',
                      appName: 'X / Twitter Feed',
                      enabled: settings.enabledApps.contains('com.twitter.android'),
                      onChanged: () => settingsNotifier.toggleApp('com.twitter.android'),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 32),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSettingCard(
    BuildContext context, {
    required String title,
    required String subtitle,
    required Widget child,
  }) {
    final theme = context.theme;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: theme.colors.card,
        borderRadius: theme.style.borderRadius.lg,
        border: Border.all(color: theme.colors.border, width: theme.style.borderWidth),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: theme.typography.sm.copyWith(
              fontWeight: FontWeight.bold,
              color: theme.colors.foreground,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            subtitle,
            style: theme.typography.xs.copyWith(
              color: theme.colors.mutedForeground,
            ),
          ),
          const SizedBox(height: 16),
          child,
        ],
      ),
    );
  }

  Widget _buildAppTile(
    BuildContext context, {
    required String packageName,
    required String appName,
    required bool enabled,
    required VoidCallback onChanged,
  }) {
    final theme = context.theme;
    return InkWell(
      onTap: onChanged,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                appName,
                style: theme.typography.sm.copyWith(fontWeight: FontWeight.w600, color: theme.colors.foreground),
              ),
              const SizedBox(height: 2),
              Text(
                packageName,
                style: theme.typography.xs.copyWith(color: theme.colors.mutedForeground),
              ),
            ],
          ),
          FSwitch(
            value: enabled,
            onChange: (_) => onChanged(),
          ),
        ],
      ),
    );
  }
}
