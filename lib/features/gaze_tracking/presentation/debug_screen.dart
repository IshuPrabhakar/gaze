import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:forui/forui.dart';
import '../../../core/platform/platform_channels.dart';
import '../presentation/telemetry_provider.dart';

class DebugScreen extends ConsumerStatefulWidget {
  const DebugScreen({super.key});

  @override
  ConsumerState<DebugScreen> createState() => _DebugScreenState();
}

class _DebugScreenState extends ConsumerState<DebugScreen> {
  final List<String> _logs = [];

  @override
  void initState() {
    super.initState();
    _logs.add('Gaze diagnostics initialized.');
  }

  void _addLog(String msg) {
    if (!mounted) return;
    setState(() {
      final timeStr = TimeOfDay.now().format(context);
      _logs.insert(0, '[$timeStr] $msg');
      if (_logs.length > 50) _logs.removeLast();
    });
  }

  @override
  Widget build(BuildContext context) {
    final telemetry = ref.watch(gazeTelemetryProvider);

    ref.listen<GazeTelemetryState>(
      gazeTelemetryProvider,
      (prev, next) {
        if (next.isLookingDown && !(prev?.isLookingDown ?? false)) {
          _addLog('LOOKING DOWN TRIGGER');
        }
        if (next.isBlinking && !(prev?.isBlinking ?? false)) {
          _addLog('BLINK DETECTED');
        }
        if (next.isFaceDetected && !(prev?.isFaceDetected ?? false)) {
          _addLog('FACE ACQUIRED');
        }
        if (!next.isFaceDetected && (prev?.isFaceDetected ?? true)) {
          _addLog('FACE LOST');
        }
      },
    );

    return Scaffold(
      backgroundColor: const Color(0xFF0F0F12),
      appBar: AppBar(
        title: const Text('Realtime Telemetry', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
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
              // Realtime visual face tracker map!
              Expanded(
                flex: 4,
                child: Container(
                  decoration: BoxDecoration(
                    color: const Color(0xFF1E1E24),
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: const Color(0xFF2E2E38)),
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(24),
                    child: Stack(
                      children: [
                        Center(
                          child: CustomPaint(
                            painter: FaceTrackerPainter(
                              yaw: telemetry.yaw,
                              pitch: telemetry.pitch,
                              eyeOpenness: telemetry.eyeOpenness,
                              isFace: telemetry.isFaceDetected,
                            ),
                            size: const Size(200, 200),
                          ),
                        ),
                        Positioned(
                          top: 16,
                          left: 16,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                            decoration: BoxDecoration(
                              color: Colors.black.withValues(alpha: 0.5),
                              borderRadius: BorderRadius.circular(20),
                            ),
                            child: Text(
                              'SIMULATED CAMERA CORRIDOR',
                              style: context.theme.typography.xs.copyWith(
                                color: context.theme.colors.primary,
                                fontWeight: FontWeight.bold,
                                letterSpacing: 1.0,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              // Manual Trigger Bar
              Row(
                children: [
                  Expanded(
                    child: FButton(
                      onPress: () async {
                        _addLog('Manual Scroll Down gesture fired');
                        await PlatformChannels.triggerScrollDown(1.0);
                      },
                      child: const Text('Scroll Down'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FButton(
                      onPress: () async {
                        _addLog('Manual Scroll Up gesture fired');
                        await PlatformChannels.triggerScrollUp(1.0);
                      },
                      child: const Text('Scroll Up'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              // Diagnostic Console Logs
              Expanded(
                flex: 3,
                child: Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: const Color(0xFF070709),
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: const Color(0xFF2E2E38)),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'DIAGNOSTICS LOGS',
                        style: TextStyle(
                          color: Color(0xFF6366F1),
                          fontSize: 11,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 1.2,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Expanded(
                        child: ListView.builder(
                          itemCount: _logs.length,
                          itemBuilder: (context, idx) {
                            return Padding(
                              padding: const EdgeInsets.symmetric(vertical: 2.0),
                              child: Text(
                                _logs[idx],
                                style: const TextStyle(
                                  fontFamily: 'Courier',
                                  fontSize: 12,
                                  color: Color(0xFF22C55E),
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class FaceTrackerPainter extends CustomPainter {
  final double yaw;
  final double pitch;
  final double eyeOpenness;
  final bool isFace;

  FaceTrackerPainter({
    required this.yaw,
    required this.pitch,
    required this.eyeOpenness,
    required this.isFace,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = isFace ? const Color(0xFF6366F1) : Colors.grey[700]!
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3.0;

    final centerX = size.width / 2;
    final centerY = size.height / 2;

    // Shift coordinates slightly based on face rotation yaw & pitch
    final faceShiftX = yaw * 1.5;
    final faceShiftY = pitch * 1.5;

    // Draw main face oval boundary
    canvas.drawOval(
      Rect.fromCenter(
        center: Offset(centerX + faceShiftX, centerY + faceShiftY),
        width: 120,
        height: 150,
      ),
      paint,
    );

    // If face is found, draw eyes and crosshairs
    if (isFace) {
      final pupilPaint = Paint()
        ..color = const Color(0xFF22C55E)
        ..style = PaintingStyle.fill;

      // Draw pupils shifting according to look pitch
      final leftPupilX = centerX - 25 + faceShiftX;
      final rightPupilX = centerX + 25 + faceShiftX;
      final pupilsY = centerY - 15 + faceShiftY;

      // Draw left & right pupils
      canvas.drawCircle(Offset(leftPupilX, pupilsY), 6.0, pupilPaint);
      canvas.drawCircle(Offset(rightPupilX, pupilsY), 6.0, pupilPaint);

      // Draw horizontal crosshairs to represent tracking reference
      final linePaint = Paint()
        ..color = const Color(0xFF2E2E38)
        ..strokeWidth = 1.0;
      canvas.drawLine(Offset(0, centerY), Offset(size.width, centerY), linePaint);
      canvas.drawLine(Offset(centerX, 0), Offset(centerX, size.height), linePaint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => true;
}
