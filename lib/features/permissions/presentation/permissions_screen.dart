import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:forui/forui.dart';
import 'package:go_router/go_router.dart';
import 'permissions_provider.dart';

class PermissionsScreen extends ConsumerStatefulWidget {
  const PermissionsScreen({super.key});

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

    return Scaffold(
      backgroundColor: const Color(0xFF0F0F12),
      appBar: AppBar(
        title: const Text(
          'Required Permissions',
          style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
        ),
        backgroundColor: Colors.transparent,
        elevation: 0,
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Setup Gaze scrolling',
                style: TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w600,
                  color: Colors.grey[300],
                ),
              ),
              const SizedBox(height: 8),
              Text(
                'Configure your Android device permissions to enable hands-free tracking and gesture simulation.',
                style: TextStyle(fontSize: 14, color: Colors.grey[400], height: 1.4),
              ),
              const SizedBox(height: 32),
              Expanded(
                child: ListView(
                  children: [
                    _buildPermissionCard(
                      icon: Icons.camera_alt_rounded,
                      title: 'Camera Access',
                      description:
                          'Used to process face landmarks and vertical gaze movement in real-time.',
                      isGranted: state.isCameraGranted,
                      onGrant: () => ref.read(permissionsProvider.notifier).requestCamera(),
                    ),
                    const SizedBox(height: 16),
                    _buildPermissionCard(
                      icon: Icons.accessibility_new_rounded,
                      title: 'Accessibility Service',
                      description:
                          'Allows simulation of real vertical scroll gestures inside external feed applications.',
                      isGranted: state.isAccessibilityEnabled,
                      onGrant: () => ref.read(permissionsProvider.notifier).openAccessibility(),
                    ),
                    const SizedBox(height: 16),
                    _buildPermissionCard(
                      icon: Icons.filter_none_rounded,
                      title: 'Overlay Permission',
                      description:
                          'Enables real-time calibration indicators to be visible when minimised.',
                      isGranted: state.isOverlayGranted,
                      onGrant: () => ref.read(permissionsProvider.notifier).openOverlay(),
                    ),
                    const SizedBox(height: 16),
                    _buildPermissionCard(
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
                const Text(
                  'Please grant all required permissions to continue.',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    color: Color(0xFFA855F7),
                    fontWeight: FontWeight.w500,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPermissionCard({
    required IconData icon,
    required String title,
    required String description,
    required bool isGranted,
    required VoidCallback onGrant,
  }) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF1E1E24),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isGranted ? const Color(0xFF22C55E).withValues(alpha: 0.3) : const Color(0xFF2E2E38),
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
                  : const Color(0xFF6366F1).withValues(alpha: 0.1),
              shape: BoxShape.circle,
            ),
            child: Icon(
              icon,
              color: isGranted ? const Color(0xFF22C55E) : const Color(0xFF6366F1),
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
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
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
                  style: TextStyle(fontSize: 13, color: Colors.grey[400], height: 1.3),
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
