import 'package:riverpod_annotation/riverpod_annotation.dart';
import '../../../../core/platform/platform_channels.dart';

part 'telemetry_provider.g.dart';

@riverpod
class GazeTelemetry extends _$GazeTelemetry {
  @override
  GazeTelemetryState build() {
    final subscription = PlatformChannels.telemetryStream.listen((state) {
      this.state = state;
    });
    ref.onDispose(() => subscription.cancel());
    return GazeTelemetryState.empty();
  }
}
