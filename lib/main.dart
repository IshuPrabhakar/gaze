import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:gaze/src/app/app.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'src/core/platform/platform_channels.dart';
import 'src/core/services/shared_preferences_provider.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize shared preferences
  final prefs = await SharedPreferences.getInstance();

  // Start telemetry channel listeners
  PlatformChannels.initialize();

  runApp(
    ProviderScope(
      overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      child: const App(),
    ),
  );
}
