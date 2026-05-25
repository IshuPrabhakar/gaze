import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:forui/forui.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'core/router/router.dart';
import 'core/services/shared_preferences_provider.dart';
import 'core/theme/app_theme.dart';
import 'core/platform/platform_channels.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize shared preferences
  final prefs = await SharedPreferences.getInstance();
  final isFirstLaunch = prefs.getBool('is_first_launch') ?? true;

  // Start telemetry channel listeners
  PlatformChannels.initialize();

  runApp(
    ProviderScope(
      overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      child: GazeApp(isFirstLaunch: isFirstLaunch),
    ),
  );
}

class GazeApp extends StatelessWidget {
  final bool isFirstLaunch;

  const GazeApp({super.key, required this.isFirstLaunch});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'Gaze',
      debugShowCheckedModeBanner: false,
      routerConfig: createRouter(isFirstLaunch ? '/onboarding' : '/dashboard'),
      builder: (context, child) =>
          FTheme(data: AppTheme.dark(), child: child ?? const SizedBox.shrink()),
    );
  }
}
