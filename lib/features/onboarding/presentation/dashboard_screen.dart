import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:forui/forui.dart';
import 'package:go_router/go_router.dart';
import '../../background_service/presentation/service_provider.dart';
import '../../gaze_tracking/presentation/telemetry_provider.dart';

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isServiceActive = ref.watch(serviceControlProvider);
    final telemetry = ref.watch(gazeTelemetryProvider);

    return Scaffold(
      backgroundColor: const Color(0xFF0F0F12),
      appBar: AppBar(
        title: const Text('Gaze Cockpit', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
        backgroundColor: Colors.transparent,
        elevation: 0,
        automaticallyImplyLeading: false,
        actions: [
          IconButton(
            icon: const Icon(Icons.bug_report_rounded, color: Color(0xFFA855F7)),
            onPressed: () => context.push('/debug'),
          ),
          IconButton(
            icon: const Icon(Icons.settings_rounded, color: Colors.grey),
            onPressed: () => context.push('/settings'),
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
                        : [const Color(0xFF6366F1).withValues(alpha: 0.15), const Color(0xFF4338CA).withValues(alpha: 0.05)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(24),
                  border: Border.all(
                    color: isServiceActive
                        ? const Color(0xFF22C55E).withValues(alpha: 0.4)
                        : const Color(0xFF6366F1).withValues(alpha: 0.3),
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
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w900,
                                color: isServiceActive ? const Color(0xFF22C55E) : const Color(0xFF6366F1),
                                letterSpacing: 1.5,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              isServiceActive ? 'Monitoring gaze inputs' : 'Service is paused',
                              style: const TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                              ),
                            ),
                          ],
                        ),
                        Container(
                          width: 16,
                          height: 16,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: isServiceActive ? const Color(0xFF22C55E) : const Color(0xFFE2E8F0).withValues(alpha: 0.3),
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
                    const Divider(color: Color(0xFF2E2E38), height: 32),
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
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                  color: Colors.grey[300],
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: _buildTelemetryUI(telemetry, isServiceActive),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTelemetryUI(dynamic telemetry, bool isServiceActive) {
    final isFace = telemetry?.isFaceDetected ?? false;
    final isLookingDown = telemetry?.isLookingDown ?? false;
    final isBlinking = telemetry?.isBlinking ?? false;
    final activeApp = telemetry?.activeApp ?? 'None';

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
          icon: Icons.remove_red_eye_rounded,
          title: 'Eye State',
          value: gazeStatus,
          color: gazeStatus == 'Looking Down'
              ? const Color(0xFFA855F7)
              : (gazeStatus == 'Attentive' ? const Color(0xFF22C55E) : Colors.grey[400]!),
        ),
        _buildStatCard(
          icon: Icons.phone_android_rounded,
          title: 'Foreground App',
          value: activeApp.split('.').last.toUpperCase(),
          color: const Color(0xFF6366F1),
        ),
        _buildStatCard(
          icon: Icons.face_rounded,
          title: 'Face Detector',
          value: isFace ? 'Acquired' : 'Lost',
          color: isFace ? const Color(0xFF22C55E) : Colors.red[400]!,
        ),
        _buildStatCard(
          icon: Icons.flash_on_rounded,
          title: 'Action Trigger',
          value: isLookingDown ? 'Triggering' : 'Normal',
          color: isLookingDown ? const Color(0xFF22C55E) : Colors.grey[400]!,
        ),
      ],
    );
  }

  Widget _buildStatCard({
    required IconData icon,
    required String title,
    required String value,
    required Color color,
  }) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF1E1E24),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFF2E2E38)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: color, size: 20),
          const Spacer(),
          Text(
            title,
            style: TextStyle(
              fontSize: 12,
              color: Colors.grey[500],
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.bold,
              color: Colors.white,
            ),
          ),
        ],
      ),
    );
  }
}
