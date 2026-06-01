import 'dart:convert';
import 'dart:developer' as developer;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:forui/forui.dart';
import 'package:gaze/src/features/gaze_tracking/ui/provider/calibration_notifier.dart';
import 'package:gaze/src/features/gaze_tracking/ui/state/calibration_stage.dart';
import 'package:go_router/go_router.dart';

import '../../../core/platform/platform_channels.dart';
import '../../../core/services/shared_preferences_provider.dart';
import 'provider/telemetry_provider.dart';

class CalibrationScreen extends ConsumerStatefulWidget {
  const CalibrationScreen({super.key});

  static const String routePath = '/calibration';

  @override
  ConsumerState<CalibrationScreen> createState() => _CalibrationScreenState();
}

class _CalibrationScreenState extends ConsumerState<CalibrationScreen> {
  bool _saving = false;
  int _lastSampleTime = 0;

  @override
  Widget build(BuildContext context) {
    final telemetry = ref.watch(gazeTelemetryProvider);
    final calibration = ref.watch(calibrationProvider);
    final notifier = ref.read(calibrationProvider.notifier);
    final theme = context.theme;

    // Listen to telemetry stream updates and capture samples at throttled rate (150ms)
    ref.listen<GazeTelemetryState>(gazeTelemetryProvider, (previous, next) {
      if (calibration.currentStage == CalibrationStage.complete) return;

      final isFace = next.isFaceDetected;
      final isBlinking = next.isBlinking;

      if (isFace && !isBlinking) {
        final now = DateTime.now().millisecondsSinceEpoch;
        if (now - _lastSampleTime >= 150) {
          _lastSampleTime = now;
          notifier.addSample(next.yaw, next.pitch);
        }
      }
    });

    final double progress = (calibration.samplesCollectedForStage / 12.0).clamp(0.0, 1.0);

    return Scaffold(
      backgroundColor: theme.colors.background,
      body: SafeArea(
        child: Stack(
          children: [
            // Stage Complete View
            if (calibration.currentStage == CalibrationStage.complete)
              _buildCompleteView(context, notifier)
            else
              // Progressive Guided Overlay Dot
              Stack(
                children: [
                  _buildHeaderInstruction(context, calibration.currentStage),
                  _buildVisualTarget(context, calibration.currentStage, progress),
                  _buildTelemetryFeedback(context, telemetry),
                ],
              ),

            // Top exit button
            Positioned(
              top: 16,
              left: 16,
              child: IconButton(
                icon: Icon(Icons.close_rounded, color: theme.colors.foreground, size: 24),
                onPressed: () => context.pop(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeaderInstruction(BuildContext context, CalibrationStage stage) {
    final theme = context.theme;
    String message = 'Center your gaze directly at the dot.';
    String sub = 'Keep your head totally static and relaxed.';

    switch (stage) {
      case CalibrationStage.top:
        message = 'Look UP at the target dot.';
        break;
      case CalibrationStage.bottom:
        message = 'Look DOWN at the target dot.';
        sub = 'Slightly droop eyelids naturally as you look down.';
        break;
      case CalibrationStage.left:
        message = 'Look LEFT at the target dot.';
        break;
      case CalibrationStage.right:
        message = 'Look RIGHT at the target dot.';
        break;
      case CalibrationStage.topLeft:
        message = 'Look toward the TOP-LEFT target.';
        break;
      case CalibrationStage.topRight:
        message = 'Look toward the TOP-RIGHT target.';
        break;
      case CalibrationStage.bottomLeft:
        message = 'Look toward the BOTTOM-LEFT target.';
        break;
      case CalibrationStage.bottomRight:
        message = 'Look toward the BOTTOM-RIGHT target.';
        break;
      default:
        break;
    }

    return Positioned(
      top: 60,
      left: 24,
      right: 24,
      child: Column(
        children: [
          Text(
            message,
            textAlign: TextAlign.center,
            style: theme.typography.xl2.copyWith(
              fontWeight: FontWeight.bold,
              color: theme.colors.foreground,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            sub,
            textAlign: TextAlign.center,
            style: theme.typography.sm.copyWith(color: theme.colors.mutedForeground),
          ),
        ],
      ),
    );
  }

  Widget _buildVisualTarget(BuildContext context, CalibrationStage stage, double progress) {
    final theme = context.theme;
    Alignment alignment = Alignment.center;

    switch (stage) {
      case CalibrationStage.top:
        alignment = Alignment.topCenter;
        break;
      case CalibrationStage.bottom:
        alignment = Alignment.bottomCenter;
        break;
      case CalibrationStage.left:
        alignment = Alignment.centerLeft;
        break;
      case CalibrationStage.right:
        alignment = Alignment.centerRight;
        break;
      case CalibrationStage.topLeft:
        alignment = Alignment.topLeft;
        break;
      case CalibrationStage.topRight:
        alignment = Alignment.topRight;
        break;
      case CalibrationStage.bottomLeft:
        alignment = Alignment.bottomLeft;
        break;
      case CalibrationStage.bottomRight:
        alignment = Alignment.bottomRight;
        break;
      default:
        alignment = Alignment.center;
    }

    return Align(
      alignment: alignment,
      child: Padding(
        padding: const EdgeInsets.all(72.0),
        child: SizedBox(
          width: 80,
          height: 80,
          child: Stack(
            alignment: Alignment.center,
            children: [
              // Outer progressive indicator ring
              CircularProgressIndicator(
                value: progress,
                strokeWidth: 3.5,
                valueColor: AlwaysStoppedAnimation<Color>(theme.colors.primary),
                backgroundColor: theme.colors.muted,
              ),
              // Inner pulsing solid anchor dot
              Container(
                width: 32,
                height: 32,
                decoration: const BoxDecoration(
                  shape: BoxShape.circle,
                  color: Colors.white,
                  boxShadow: [BoxShadow(color: Colors.white24, blurRadius: 16, spreadRadius: 4)],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildTelemetryFeedback(BuildContext context, GazeTelemetryState telemetry) {
    final isFace = telemetry.isFaceDetected;
    final isAttentive = telemetry.isAttentive;
    final theme = context.theme;

    return Positioned(
      bottom: 24,
      left: 24,
      right: 24,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: theme.colors.card.withValues(alpha: 0.7),
          borderRadius: theme.style.borderRadius.lg,
          border: Border.all(color: theme.colors.border, width: theme.style.borderWidth),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Row(
              children: [
                Icon(
                  isFace ? Icons.check_circle_outline_rounded : Icons.warning_amber_rounded,
                  color: isFace ? const Color(0xFF22C55E) : Colors.amber[600],
                  size: 18,
                ),
                const SizedBox(width: 8),
                Text(
                  isFace ? 'Face Captured' : 'Acquiring Face Mesh…',
                  style: theme.typography.xs.copyWith(
                    fontWeight: FontWeight.bold,
                    color: isFace ? const Color(0xFF22C55E) : Colors.amber[600],
                  ),
                ),
              ],
            ),
            Text(
              isAttentive ? 'Stable Gaze' : 'Analyzing Alignment',
              style: theme.typography.xs.copyWith(
                color: isAttentive ? theme.colors.primary : theme.colors.mutedForeground,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCompleteView(BuildContext context, CalibrationNotifier notifier) {
    final theme = context.theme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Icon(Icons.stars_rounded, color: Color(0xFF22C55E), size: 72),
            const SizedBox(height: 24),
            Text(
              'Calibration Complete!',
              textAlign: TextAlign.center,
              style: theme.typography.xl2.copyWith(
                fontWeight: FontWeight.bold,
                color: theme.colors.foreground,
              ),
            ),
            const SizedBox(height: 12),
            Text(
              'Your eye movements have been successfully customized. Personalized thresholds are active.',
              textAlign: TextAlign.center,
              style: theme.typography.sm.copyWith(color: theme.colors.mutedForeground),
            ),
            const SizedBox(height: 48),
            FButton(
              onPress: _saving
                  ? null
                  : () async {
                      setState(() => _saving = true);

                      final profile = notifier.computeProfile();

                      // Persist locally using SharedPreferences
                      final prefs = ref.read(sharedPreferencesProvider);
                      final jsonString = jsonEncode(profile.toJson());
                      await prefs.setString('user_calibration_profile', jsonString);
                      developer.log('Saved User Calibration Profile: $jsonString');

                      if (context.mounted) {
                        context.pop(); // Return home
                      }
                    },
              child: _saving
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black),
                    )
                  : const Text('Save and Apply Settings'),
            ),
            const SizedBox(height: 16),
            FButton(
              onPress: _saving
                  ? null
                  : () {
                      ref.read(calibrationProvider.notifier).reset();
                    },
              child: const Text('Recalibrate / Try Again'),
            ),
          ],
        ),
      ),
    );
  }
}
