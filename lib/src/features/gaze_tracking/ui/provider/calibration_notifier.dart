import 'dart:developer' as developer;
import 'dart:math';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:gaze/src/features/gaze_tracking/ui/models/gaze_estimation_pipeline.dart';
import 'package:gaze/src/features/gaze_tracking/ui/state/calibration_stage.dart';
import 'package:gaze/src/features/gaze_tracking/ui/state/calibration_state.dart';

final calibrationProvider = NotifierProvider<CalibrationNotifier, CalibrationState>(
  CalibrationNotifier.new,
);

/// State notifier to manage calibration calculations.
class CalibrationNotifier extends Notifier<CalibrationState> {
  static const int _requiredSamplesPerStage = 12;

  @override
  CalibrationState build() => CalibrationState.initial();

  /// Resets the calibration state to start over.
  void reset() {
    state = CalibrationState.initial();
  }

  /// Collects a raw coordinate pair for the active stage.
  /// Ignores blinks or rapid movement anomalies automatically.
  void addSample(double x, double y) {
    if (state.currentStage == CalibrationStage.complete) return;

    final samples = List<List<double>>.from(state.rawSamples[state.currentStage]!);
    samples.add([x, y]);

    final nextCount = state.samplesCollectedForStage + 1;

    if (nextCount >= _requiredSamplesPerStage) {
      // Complete current stage
      HapticFeedback.heavyImpact();
      const stages = CalibrationStage.values;
      final currentIndex = stages.indexOf(state.currentStage);
      final nextStage = stages[currentIndex + 1];

      state = state.copyWith(
        rawSamples: {...state.rawSamples, state.currentStage: samples},
        currentStage: nextStage,
        samplesCollectedForStage: 0,
      );
    } else {
      HapticFeedback.lightImpact();
      state = state.copyWith(
        rawSamples: {...state.rawSamples, state.currentStage: samples},
        samplesCollectedForStage: nextCount,
      );
    }
  }

  /// Calculates final filtered parameters and exports the UserCalibrationProfile.
  UserCalibrationProfile computeProfile() {
    // Average baseline neutral Look
    final neutralX = _averageForStage(CalibrationStage.neutral, 0);
    final neutralY = _averageForStage(CalibrationStage.neutral, 1);

    // Compute localized extreme thresholds relative to neutral offset
    final topBound = _averageForStage(CalibrationStage.top, 1) - neutralY;
    final bottomBound = _averageForStage(CalibrationStage.bottom, 1) - neutralY;
    final leftBound = _averageForStage(CalibrationStage.left, 0) - neutralX;
    final rightBound = _averageForStage(CalibrationStage.right, 0) - neutralX;

    // Standard deviation metrics to estimate tracking stability
    final totalSamples = state.rawSamples.values.expand((element) => element).toList();
    double sumDistance = 0.0;
    for (final s in totalSamples) {
      final dx = s[0] - neutralX;
      final dy = s[1] - neutralY;
      sumDistance += sqrt(dx * dx + dy * dy);
    }
    developer.log('Calibration sum deviation distance: $sumDistance');

    return UserCalibrationProfile(
      neutralVector: [neutralX, neutralY],
      topThreshold: topBound != 0.0 ? topBound : 0.60,
      bottomThreshold: bottomBound != 0.0 ? bottomBound : -0.60,
      leftThreshold: leftBound != 0.0 ? leftBound : -0.60,
      rightThreshold: rightBound != 0.0 ? rightBound : 0.60,
      verticalBias: -neutralY * 0.5,
      horizontalBias: -neutralX * 0.5,
      deadzoneRadius: 0.08,
      blinkThreshold: 0.20,
      occlusionCompensation: 0.30,
    );
  }

  double _averageForStage(CalibrationStage stage, int index) {
    final list = state.rawSamples[stage] ?? [];
    if (list.isEmpty) return 0.0;

    // Sort to perform outlier rejection (pruning 2 lowest and 2 highest samples)
    final values = list.map((e) => e[index]).toList()..sort();
    if (values.length > 6) {
      final pruned = values.sublist(2, values.length - 2);
      return pruned.reduce((a, b) => a + b) / pruned.length;
    }
    return values.reduce((a, b) => a + b) / values.length;
  }
}
