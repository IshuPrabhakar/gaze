import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:forui/forui.dart';
import 'package:go_router/go_router.dart';
import 'provider/permissions_provider.dart';

class PermissionsScreen extends ConsumerStatefulWidget {
  const PermissionsScreen({super.key});

  static const String routePath = '/permissions';

  @override
  ConsumerState<PermissionsScreen> createState() => _PermissionsScreenState();
}

class _PermissionsScreenState extends ConsumerState<PermissionsScreen> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Initial check
    Future.microtask(() => ref.read(permissionsProvider.notifier).checkAll());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // Recheck when returning from system settings
      ref.read(permissionsProvider.notifier).checkAll();
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(permissionsProvider);
    final theme = context.theme;

    return Scaffold(
      backgroundColor: theme.colors.background,
      appBar: AppBar(
        title: Text(
          'Required Permissions',
          style: theme.typography.lg.copyWith(color: theme.colors.foreground, fontWeight: FontWeight.bold),
        ),
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: IconThemeData(color: theme.colors.foreground),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Setup Gaze scrolling',
                style: theme.typography.xl.copyWith(
                  fontWeight: FontWeight.w600,
                  color: theme.colors.foreground,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                'Configure your Android device permissions to enable hands-free tracking and gesture simulation.',
                style: theme.typography.sm.copyWith(color: theme.colors.mutedForeground),
              ),
              const SizedBox(height: 32),
              Expanded(
                child: ListView(
                  children: [
                    _buildPermissionCard(
                      context,
                      icon: Icons.camera_alt_rounded,
                      title: 'Camera Access',
                      description:
                          'Used to process face landmarks and vertical gaze movement in real-time.',
                      isGranted: state.isCameraGranted,
                      onGrant: () => ref.read(permissionsProvider.notifier).requestCamera(),
                    ),
                    const SizedBox(height: 16),
                    _buildPermissionCard(
                      context,
                      icon: Icons.accessibility_new_rounded,
                      title: 'Accessibility Service',
                      description:
                          'Allows simulation of real vertical scroll gestures inside external feed applications.',
                      isGranted: state.isAccessibilityEnabled,
                      onGrant: () => ref.read(permissionsProvider.notifier).openAccessibility(),
                    ),
                    const SizedBox(height: 16),
                    _buildPermissionCard(
                      context,
                      icon: Icons.filter_none_rounded,
                      title: 'Overlay Permission',
                      description:
                          'Enables real-time calibration indicators to be visible when minimised.',
                      isGranted: state.isOverlayGranted,
                      onGrant: () => ref.read(permissionsProvider.notifier).openOverlay(),
                    ),
                    const SizedBox(height: 16),
                    _buildPermissionCard(
                      context,
                      icon: Icons.battery_saver_rounded,
                      title: 'Battery Optimization Exemption',
                      description:
                          'Exempts Gaze foreground camera services from sudden background hibernation.',
                      isGranted: state.isBatteryExempted,
                      onGrant: () => ref.read(permissionsProvider.notifier).openBattery(),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              FButton(
                onPress: state.allGranted
                    ? () => context.pushReplacement('/dashboard')
                    : null, // Enabled only when all permissions are granted
                child: const Text('Proceed to Dashboard'),
              ),
              const SizedBox(height: 8),
              if (!state.allGranted)
                Text(
                  'Please grant all required permissions to continue.',
                  textAlign: TextAlign.center,
                  style: theme.typography.xs.copyWith(
                    color: theme.colors.primary,
                    fontWeight: FontWeight.w500,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPermissionCard(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String description,
    required bool isGranted,
    required VoidCallback onGrant,
  }) {
    final theme = context.theme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: theme.colors.card,
        borderRadius: theme.style.borderRadius.lg,
        border: Border.all(
          color: isGranted ? const Color(0xFF22C55E).withValues(alpha: 0.3) : theme.colors.border,
          width: theme.style.borderWidth,
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: isGranted
                  ? const Color(0xFF22C55E).withValues(alpha: 0.1)
                  : theme.colors.primary.withValues(alpha: 0.1),
              shape: BoxShape.circle,
            ),
            child: Icon(
              icon,
              color: isGranted ? const Color(0xFF22C55E) : theme.colors.primary,
              size: 20,
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        title,
                        style: theme.typography.sm.copyWith(
                          fontWeight: FontWeight.bold,
                          color: theme.colors.foreground,
                        ),
                      ),
                    ),
                    if (isGranted)
                      const Row(
                        children: [
                          Icon(Icons.check_circle_rounded, color: Color(0xFF22C55E), size: 16),
                          SizedBox(width: 4),
                          Text(
                            'Active',
                            style: TextStyle(
                              color: Color(0xFF22C55E),
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  description,
                  style: theme.typography.xs.copyWith(color: theme.colors.mutedForeground),
                ),
                if (!isGranted) ...[
                  const SizedBox(height: 12),
                  FButton(onPress: onGrant, child: const Text('Configure')),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}
