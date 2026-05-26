import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:forui/forui.dart';
import 'package:gaze/src/features/gaze_tracking/ui/debug_screen.dart';
import 'package:gaze/src/features/settings/ui/settings_screen.dart';
import 'package:go_router/go_router.dart';
import '../../../core/services/background_service/service_provider.dart';
import '../../gaze_tracking/ui/provider/telemetry_provider.dart';

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  static const String routePath = '/dashboard';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isServiceActive = ref.watch(serviceControlProvider);
    final telemetry = ref.watch(gazeTelemetryProvider);
    final theme = context.theme;

    return Scaffold(
      backgroundColor: theme.colors.background,
      appBar: AppBar(
        title: Text(
          'Gaze Cockpit',
          style: theme.typography.lg.copyWith(color: theme.colors.foreground, fontWeight: FontWeight.bold),
        ),
        backgroundColor: Colors.transparent,
        elevation: 0,
        automaticallyImplyLeading: false,
        actions: [
          IconButton(
            icon: Icon(Icons.bug_report_rounded, color: theme.colors.primary),
            onPressed: () => context.push(DebugScreen.routePath),
          ),
          IconButton(
            icon: Icon(Icons.settings_rounded, color: theme.colors.mutedForeground),
            onPressed: () => context.push(SettingsScreen.routePath),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 8.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 1. Service Status Card
              Container(
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: isServiceActive
                        ? [const Color(0xFF22C55E).withValues(alpha: 0.15), const Color(0xFF15803D).withValues(alpha: 0.05)]
                        : [theme.colors.primary.withValues(alpha: 0.15), theme.colors.primary.withValues(alpha: 0.05)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: theme.style.borderRadius.lg,
                  border: Border.all(
                    color: isServiceActive
                        ? const Color(0xFF22C55E).withValues(alpha: 0.4)
                        : theme.colors.primary.withValues(alpha: 0.3),
                    width: 1.5,
                  ),
                ),
                child: Column(
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              isServiceActive ? 'TRACKING ACTIVE' : 'SYSTEM IDLE',
                              style: theme.typography.xs.copyWith(
                                fontWeight: FontWeight.w900,
                                color: isServiceActive ? const Color(0xFF22C55E) : theme.colors.primary,
                                letterSpacing: 1.5,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              isServiceActive ? 'Monitoring gaze inputs' : 'Service is paused',
                              style: theme.typography.lg.copyWith(
                                fontWeight: FontWeight.bold,
                                color: theme.colors.foreground,
                              ),
                            ),
                          ],
                        ),
                        Container(
                          width: 16,
                          height: 16,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: isServiceActive ? const Color(0xFF22C55E) : theme.colors.muted.withValues(alpha: 0.3),
                            boxShadow: isServiceActive
                                ? [
                                    BoxShadow(
                                      color: const Color(0xFF22C55E).withValues(alpha: 0.6),
                                      blurRadius: 10,
                                      spreadRadius: 2,
                                    )
                                  ]
                                : null,
                          ),
                        ),
                      ],
                    ),
                    Divider(color: theme.colors.border, height: 32),
                    FButton(
                      onPress: () async {
                        if (isServiceActive) {
                          await ref.read(serviceControlProvider.notifier).stop();
                        } else {
                          await ref.read(serviceControlProvider.notifier).start();
                        }
                      },
                      child: Text(isServiceActive ? 'Stop Service' : 'Start Tracking Service'),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 24),
              // 2. Active Session Realtime Stats
              Text(
                'Telemetry Feed',
                style: theme.typography.md.copyWith(
                  fontWeight: FontWeight.bold,
                  color: theme.colors.foreground,
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: _buildTelemetryUI(context, telemetry, isServiceActive),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTelemetryUI(BuildContext context, dynamic telemetry, bool isServiceActive) {
    final isFace = telemetry?.isFaceDetected ?? false;
    final isLookingDown = telemetry?.isLookingDown ?? false;
    final isBlinking = telemetry?.isBlinking ?? false;
    final activeApp = telemetry?.activeApp ?? 'None';
    final theme = context.theme;

    String gazeStatus = 'Idle';
    if (!isServiceActive) {
      gazeStatus = 'Inactive';
    } else if (!isFace) {
      gazeStatus = 'No Face';
    } else if (isBlinking) {
      gazeStatus = 'Blinking';
    } else if (isLookingDown) {
      gazeStatus = 'Looking Down';
    } else {
      gazeStatus = 'Attentive';
    }

    return GridView.count(
      crossAxisCount: 2,
      crossAxisSpacing: 16,
      mainAxisSpacing: 16,
      childAspectRatio: 1.35,
      children: [
        _buildStatCard(
          context,
          icon: Icons.remove_red_eye_rounded,
          title: 'Eye State',
          value: gazeStatus,
          color: gazeStatus == 'Looking Down'
              ? theme.colors.primary
              : (gazeStatus == 'Attentive' ? const Color(0xFF22C55E) : theme.colors.mutedForeground),
        ),
        _buildStatCard(
          context,
          icon: Icons.phone_android_rounded,
          title: 'Foreground App',
          value: activeApp.split('.').last.toUpperCase(),
          color: theme.colors.primary,
        ),
        _buildStatCard(
          context,
          icon: Icons.face_rounded,
          title: 'Face Detector',
          value: isFace ? 'Acquired' : 'Lost',
          color: isFace ? const Color(0xFF22C55E) : theme.colors.destructive,
        ),
        _buildStatCard(
          context,
          icon: Icons.flash_on_rounded,
          title: 'Action Trigger',
          value: isLookingDown ? 'Triggering' : 'Normal',
          color: isLookingDown ? const Color(0xFF22C55E) : theme.colors.mutedForeground,
        ),
      ],
    );
  }

  Widget _buildStatCard(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String value,
    required Color color,
  }) {
    final theme = context.theme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: theme.colors.card,
        borderRadius: theme.style.borderRadius.lg,
        border: Border.all(color: theme.colors.border, width: theme.style.borderWidth),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: color, size: 20),
          const Spacer(),
          Text(
            title,
            style: theme.typography.xs.copyWith(
              color: theme.colors.mutedForeground,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: theme.typography.sm.copyWith(
              fontSize: 14,
              fontWeight: FontWeight.bold,
              color: theme.colors.foreground,
            ),
          ),
        ],
      ),
    );
  }
}
