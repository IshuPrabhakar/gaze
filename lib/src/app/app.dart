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
    final baseTheme = AppTheme.dark();
    final themeData = baseTheme.copyWith(
      colors: baseTheme.colors.copyWith(primary: const Color(0xFF39A7FF)),
    );
    return MaterialApp.router(
      title: 'Gaze Control',
      debugShowCheckedModeBanner: false,
      routeInformationParser: router.routeInformationParser,
      routeInformationProvider: router.routeInformationProvider,
      routerDelegate: router.routerDelegate,
      theme: themeData.toApproximateMaterialTheme().copyWith(
        pageTransitionsTheme: const PageTransitionsTheme(
          builders: {TargetPlatform.android: CupertinoPageTransitionsBuilder()},
        ),
      ),
      builder: (context, child) {
        return FTheme(data: themeData, child: child ?? const SizedBox.shrink());
      },
    );
  }
}
