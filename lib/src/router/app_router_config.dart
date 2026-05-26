import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../features/onboarding/ui/onboarding_screen.dart';
import '../features/permissions/ui/permissions_screen.dart';
import '../features/dashboard/ui/dashboard_screen.dart';
import '../features/settings/ui/settings_screen.dart';
import '../features/gaze_tracking/ui/debug_screen.dart';
import '../features/gaze_tracking/ui/calibration_screen.dart';

import '../core/services/shared_preferences_provider.dart';

final goRouterProvider = Provider<GoRouter>((ref) {
  final sharedPrefs = ref.read(sharedPreferencesProvider);
  final isFirstLaunch = sharedPrefs.getBool('is_first_launch') ?? true;

  return GoRouter(
    initialLocation: isFirstLaunch ? OnboardingScreen.routePath : DashboardScreen.routePath,
    routes: [
      GoRoute(
        path: OnboardingScreen.routePath,
        builder: (context, state) => const OnboardingScreen(),
      ),
      GoRoute(
        path: DashboardScreen.routePath,
        builder: (context, state) => const DashboardScreen(),
      ),
      GoRoute(
        path: PermissionsScreen.routePath,
        builder: (context, state) => const PermissionsScreen(),
      ),
      GoRoute(
        path: DashboardScreen.routePath,
        builder: (context, state) => const DashboardScreen(),
      ),
      GoRoute(path: SettingsScreen.routePath, builder: (context, state) => const SettingsScreen()),
      GoRoute(path: DebugScreen.routePath, builder: (context, state) => const DebugScreen()),
      GoRoute(
        path: CalibrationScreen.routePath,
        builder: (context, state) => const CalibrationScreen(),
      ),
    ],
  );
});
