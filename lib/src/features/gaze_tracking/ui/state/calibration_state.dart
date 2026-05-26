import 'calibration_stage.dart';

/// The calibration state to hold raw collected coordinates.
class CalibrationState {
  final Map<CalibrationStage, List<List<double>>> rawSamples;
  final CalibrationStage currentStage;
  final int samplesCollectedForStage;

  CalibrationState({
    required this.rawSamples,
    required this.currentStage,
    required this.samplesCollectedForStage,
  });

  factory CalibrationState.initial() => CalibrationState(
    rawSamples: {for (final stage in CalibrationStage.values) stage: []},
    currentStage: CalibrationStage.neutral,
    samplesCollectedForStage: 0,
  );

  CalibrationState copyWith({
    Map<CalibrationStage, List<List<double>>>? rawSamples,
    CalibrationStage? currentStage,
    int? samplesCollectedForStage,
  }) {
    return CalibrationState(
      rawSamples: rawSamples ?? this.rawSamples,
      currentStage: currentStage ?? this.currentStage,
      samplesCollectedForStage: samplesCollectedForStage ?? this.samplesCollectedForStage,
    );
  }
}
