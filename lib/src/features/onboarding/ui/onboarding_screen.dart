import 'package:flutter/material.dart';
import 'package:forui/forui.dart';
import 'package:gaze/src/features/permissions/ui/permissions_screen.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/services/shared_preferences_provider.dart';

class OnboardingScreen extends ConsumerWidget {
  const OnboardingScreen({super.key});

  static const String routePath = '/onboarding';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = context.theme;
    return Scaffold(
      backgroundColor: theme.colors.background,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Spacer(),
              // Icon/Illustration placeholder or actual beautiful gradient shape
              Center(
                child: Container(
                  width: 100,
                  height: 100,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: LinearGradient(
                      colors: [theme.colors.primary, theme.colors.primary.withValues(alpha: 0.7)],
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: theme.colors.primary.withValues(alpha: 0.4),
                        blurRadius: 30,
                        spreadRadius: 5,
                      ),
                    ],
                  ),
                  child: Icon(
                    Icons.remove_red_eye_rounded,
                    size: 48,
                    color: theme.colors.primaryForeground,
                  ),
                ),
              ),
              const SizedBox(height: 40),
              // Premium typography
              Text(
                'Gaze Control',
                textAlign: TextAlign.center,
                style: theme.typography.xl3.copyWith(
                  fontWeight: FontWeight.bold,
                  color: theme.colors.foreground,
                ),
              ),
              const SizedBox(height: 12),
              Text(
                'Hands-free vertically scrolling across all feeds. Instagram, TikTok, YouTube, X, Reddit—perfectly navigated using eye movement tracking.',
                textAlign: TextAlign.center,
                style: theme.typography.xs.copyWith(color: theme.colors.mutedForeground),
              ),
              const Spacer(),
              // forui styling lists/cards
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: theme.colors.card,
                  borderRadius: theme.style.borderRadius.lg,
                  border: Border.all(color: theme.colors.border, width: theme.style.borderWidth),
                ),
                child: Column(
                  children: [
                    _buildFeatureRow(
                      context,
                      Icons.camera_front_rounded,
                      'Front-camera Attention Detection',
                      'Calculated in high-frequency low-res native frames to minimize battery drain.',
                    ),
                    Divider(color: theme.colors.border, height: 24),
                    _buildFeatureRow(
                      context,
                      Icons.gesture_rounded,
                      'System-Wide Swiping Service',
                      'Triggers actual vertical swipes inside your favorite media apps.',
                    ),
                  ],
                ),
              ),
              const Spacer(),
              // Proceed button
              FButton(
                onPress: () async {
                  await ref.read(sharedPreferencesProvider).setBool('is_first_launch', false);
                  if (context.mounted) {
                    context.push(PermissionsScreen.routePath);
                  }
                },
                child: const Text('Get Started'),
              ),
              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildFeatureRow(BuildContext context, IconData icon, String title, String subtitle) {
    final theme = context.theme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: theme.colors.primary, size: 24),
        const SizedBox(width: 16),
        Expanded(
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
              const SizedBox(height: 4),
              Text(
                subtitle,
                style: theme.typography.xs.copyWith(color: theme.colors.mutedForeground),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
