import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:forui/forui.dart';
import 'package:gaze/src/core/theme/app_theme.dart';

import '../router/app_router_config.dart';

class App extends ConsumerWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(goRouterProvider);

    return MaterialApp.router(
      title: 'Gaze',
      debugShowCheckedModeBanner: false,
      routerConfig: router,
      builder: (context, child) {
        return FTheme(data: AppTheme.dark(), child: child ?? const SizedBox.shrink());
      },
    );
  }
}
