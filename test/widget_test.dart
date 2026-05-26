import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gaze/src/app/app.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:gaze/src/core/services/shared_preferences_provider.dart';

void main() {
  testWidgets('Smoke test for Gaze onboarding initialization', (WidgetTester tester) async {
    // Setup mock shared preferences values
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
        child: const App(),
      ),
    );

    // Verify OnboardingScreen loads with Gaze Control title
    expect(find.text('Gaze Control'), findsOneWidget);
    expect(find.text('Get Started'), findsOneWidget);
  });
}
