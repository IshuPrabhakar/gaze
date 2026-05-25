import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'settings_provider.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(settingsProvider);
    final settingsNotifier = ref.read(settingsProvider.notifier);

    return Scaffold(
      backgroundColor: const Color(0xFF0F0F12),
      appBar: AppBar(
        title: const Text('Calibration & Settings', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 8.0),
          child: ListView(
            children: [
              Text(
                'Tweak Gestures',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: Colors.grey[300],
                ),
              ),
              const SizedBox(height: 12),
              // Sliders inside cards
              _buildSettingCard(
                title: 'Gaze Sensitivity: ${(settings.sensitivity * 100).toInt()}%',
                subtitle: 'Determines facial threshold pitch angle needed to trigger look downward action.',
                child: Slider(
                  value: settings.sensitivity,
                  min: 0.1,
                  max: 1.0,
                  activeColor: const Color(0xFF6366F1),
                  inactiveColor: const Color(0xFF2E2E38),
                  onChanged: (val) => settingsNotifier.updateSensitivity(val),
                ),
              ),
              const SizedBox(height: 16),
              _buildSettingCard(
                title: 'Scroll Velocity: ${(settings.scrollSpeed * 100).toInt()}%',
                subtitle: 'Configures speed / swiping stroke duration for accessibility dispatching.',
                child: Slider(
                  value: settings.scrollSpeed,
                  min: 0.2,
                  max: 2.0,
                  activeColor: const Color(0xFF6366F1),
                  inactiveColor: const Color(0xFF2E2E38),
                  onChanged: (val) => settingsNotifier.updateScrollSpeed(val),
                ),
              ),
              const SizedBox(height: 16),
              _buildSettingCard(
                title: 'Trigger Hold Duration: ${settings.triggerDurationMs}ms',
                subtitle: 'Eye attention must continuously look downward for this duration before vertical swipes fire.',
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [500, 800, 1200].map((ms) {
                    final isSelected = settings.triggerDurationMs == ms;
                    return ChoiceChip(
                      label: Text('$ms ms', style: const TextStyle(color: Colors.white)),
                      selected: isSelected,
                      selectedColor: const Color(0xFF6366F1),
                      backgroundColor: const Color(0xFF1E1E24),
                      onSelected: (_) => settingsNotifier.updateTriggerDuration(ms),
                    );
                  }).toList(),
                ),
              ),
              const SizedBox(height: 16),
              _buildSettingCard(
                title: 'Pause on Look Away',
                subtitle: 'Automatically pause media playback when you look away from the screen, and resume when you look back.',
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      settings.pauseOnLookAway ? 'Enabled' : 'Disabled',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        color: settings.pauseOnLookAway ? const Color(0xFF22C55E) : Colors.grey[400],
                      ),
                    ),
                    Switch(
                      value: settings.pauseOnLookAway,
                      onChanged: (val) => settingsNotifier.updatePauseOnLookAway(val),
                      activeThumbColor: const Color(0xFF6366F1),
                      inactiveThumbColor: const Color(0xFF757575),
                      inactiveTrackColor: const Color(0xFF2E2E38),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              _buildSettingCard(
                title: 'System-Wide Mode',
                subtitle: 'When enabled, gaze scrolling works on all apps. When disabled, gestures are restricted to whitelisted apps below.',
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      settings.systemWide ? 'System-Wide' : 'Whitelisted Only',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        color: settings.systemWide ? const Color(0xFF22C55E) : Colors.grey[400],
                      ),
                    ),
                    Switch(
                      value: settings.systemWide,
                      onChanged: (val) => settingsNotifier.updateSystemWide(val),
                      activeThumbColor: const Color(0xFF6366F1),
                      inactiveThumbColor: const Color(0xFF757575),
                      inactiveTrackColor: const Color(0xFF2E2E38),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 24),
              Text(
                'Enabled Applications',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: Colors.grey[300],
                ),
              ),
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: const Color(0xFF1E1E24),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: const Color(0xFF2E2E38)),
                ),
                child: Column(
                  children: [
                    _buildAppTile(
                      packageName: 'com.instagram.android',
                      appName: 'Instagram Reels',
                      enabled: settings.enabledApps.contains('com.instagram.android'),
                      onChanged: () => settingsNotifier.toggleApp('com.instagram.android'),
                    ),
                    const Divider(color: Color(0xFF2E2E38), height: 16),
                    _buildAppTile(
                      packageName: 'com.zhiliaoapp.musically',
                      appName: 'TikTok Feed',
                      enabled: settings.enabledApps.contains('com.zhiliaoapp.musically'),
                      onChanged: () => settingsNotifier.toggleApp('com.zhiliaoapp.musically'),
                    ),
                    const Divider(color: Color(0xFF2E2E38), height: 16),
                    _buildAppTile(
                      packageName: 'com.google.android.youtube',
                      appName: 'YouTube Shorts',
                      enabled: settings.enabledApps.contains('com.google.android.youtube'),
                      onChanged: () => settingsNotifier.toggleApp('com.google.android.youtube'),
                    ),
                    const Divider(color: Color(0xFF2E2E38), height: 16),
                    _buildAppTile(
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

  Widget _buildSettingCard({
    required String title,
    required String subtitle,
    required Widget child,
  }) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: const Color(0xFF1E1E24),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFF2E2E38)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.bold,
              color: Colors.white,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            subtitle,
            style: TextStyle(
              fontSize: 12,
              color: Colors.grey[400],
              height: 1.3,
            ),
          ),
          const SizedBox(height: 16),
          child,
        ],
      ),
    );
  }

  Widget _buildAppTile({
    required String packageName,
    required String appName,
    required bool enabled,
    required VoidCallback onChanged,
  }) {
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
                style: const TextStyle(fontWeight: FontWeight.w600, color: Colors.white),
              ),
              const SizedBox(height: 2),
              Text(
                packageName,
                style: TextStyle(fontSize: 11, color: Colors.grey[500]),
              ),
            ],
          ),
          Switch(
            value: enabled,
            onChanged: (_) => onChanged(),
            activeThumbColor: const Color(0xFF6366F1),
            inactiveThumbColor: const Color(0xFF757575),
            inactiveTrackColor: const Color(0xFF2E2E38),
          ),
        ],
      ),
    );
  }
}
