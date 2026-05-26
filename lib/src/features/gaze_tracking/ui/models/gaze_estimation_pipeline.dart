import 'dart:math';

/// A 3D Point representing a face landmark in normalized camera space [0.0, 1.0].
class FaceLandmark {
  final double x;
  final double y;
  final double z;

  const FaceLandmark({required this.x, required this.y, required this.z});

  /// Calculates the Euclidean distance to another landmark.
  double distanceTo(FaceLandmark other) {
    return sqrt(pow(x - other.x, 2) + pow(y - other.y, 2) + pow(z - other.z, 2));
  }
}

/// The personalized profile computed after completing guided calibration.
class UserCalibrationProfile {
  final List<double> neutralVector; // [x, y]
  final double topThreshold;
  final double bottomThreshold;
  final double leftThreshold;
  final double rightThreshold;
  final double verticalBias;
  final double horizontalBias;
  final double deadzoneRadius;
  final double blinkThreshold;
  final double occlusionCompensation;

  const UserCalibrationProfile({
    required this.neutralVector,
    required this.topThreshold,
    required this.bottomThreshold,
    required this.leftThreshold,
    required this.rightThreshold,
    required this.verticalBias,
    required this.horizontalBias,
    required this.deadzoneRadius,
    required this.blinkThreshold,
    required this.occlusionCompensation,
  });

  Map<String, dynamic> toJson() {
    return {
      'neutral_vector': neutralVector,
      'top_threshold': topThreshold,
      'bottom_threshold': bottomThreshold,
      'left_threshold': leftThreshold,
      'right_threshold': rightThreshold,
      'vertical_bias': verticalBias,
      'horizontal_bias': horizontalBias,
      'deadzone_radius': deadzoneRadius,
      'blink_threshold': blinkThreshold,
      'occlusion_compensation': occlusionCompensation,
    };
  }

  factory UserCalibrationProfile.fromJson(Map<String, dynamic> json) {
    return UserCalibrationProfile(
      neutralVector: List<double>.from(json['neutral_vector'] ?? [0.0, 0.0]),
      topThreshold: (json['top_threshold'] ?? 0.60).toDouble(),
      bottomThreshold: (json['bottom_threshold'] ?? -0.60).toDouble(),
      leftThreshold: (json['left_threshold'] ?? -0.60).toDouble(),
      rightThreshold: (json['right_threshold'] ?? 0.60).toDouble(),
      verticalBias: (json['vertical_bias'] ?? 0.0).toDouble(),
      horizontalBias: (json['horizontal_bias'] ?? 0.0).toDouble(),
      deadzoneRadius: (json['deadzone_radius'] ?? 0.15).toDouble(),
      blinkThreshold: (json['blink_threshold'] ?? 0.18).toDouble(),
      occlusionCompensation: (json['occlusion_compensation'] ?? 0.0).toDouble(),
    );
  }
}

/// The result of the gaze estimation pipeline for a single frame.
class GazeResult {
  final String direction;
  final List<double> vector; // [X, Y] normalized between -1.0 and 1.0
  final double confidence;

  const GazeResult({required this.direction, required this.vector, required this.confidence});

  Map<String, dynamic> toJson() {
    return {'direction': direction, 'vector': vector, 'confidence': confidence};
  }
}

/// Implementation of the OneEuro Filter for smooth, low-latency signal filtering.
class OneEuroFilter {
  final double minCutoff;
  final double beta;
  final double dCutoff;

  bool _firstTime = true;
  double _xPrev = 0.0;
  double _dxPrev = 0.0;
  int _tPrev = 0;

  OneEuroFilter({this.minCutoff = 1.0, this.beta = 0.02, this.dCutoff = 1.0});

  void reset() {
    _firstTime = true;
    _xPrev = 0.0;
    _dxPrev = 0.0;
    _tPrev = 0;
  }

  double filter(double value, int timestampMs) {
    if (_firstTime) {
      _firstTime = false;
      _xPrev = value;
      _dxPrev = 0.0;
      _tPrev = timestampMs;
      return value;
    }

    final double dt = (timestampMs - _tPrev) / 1000.0;
    if (dt <= 0.0) return _xPrev;

    final double dx = (value - _xPrev) / dt;
    final double alphaD = _alpha(dt, dCutoff);
    final double dxSmooth = alphaD * dx + (1.0 - alphaD) * _dxPrev;

    final double cutoff = minCutoff + beta * dxSmooth.abs();
    final double alphaX = _alpha(dt, cutoff);
    final double xSmooth = alphaX * value + (1.0 - alphaX) * _xPrev;

    _xPrev = xSmooth;
    _dxPrev = dxSmooth;
    _tPrev = timestampMs;

    return xSmooth;
  }

  double _alpha(double dt, double cutoff) {
    final double tau = 1.0 / (2.0 * pi * cutoff);
    return dt / (dt + tau);
  }
}

/// A high-performance calibration and estimation pipeline for eye-tracking
/// using MediaPipe Face Mesh landmarks.
class GazeEstimationPipeline {
  // Personalized profile parameters
  UserCalibrationProfile? _activeProfile;

  // Baseline calibration offsets [X, Y] captured during calibration.
  List<double>? _baselineOffset;
  bool _isCalibrated = false;

  // Calibration warm-up window accumulator
  int _calibrationSamples = 0;
  final int _requiredCalibrationSamples = 15;
  double _calibrationSumX = 0.0;
  double _calibrationSumY = 0.0;

  // Maximum historical bounds for dynamic scaling of offsets to [-1, 1].
  double _minObservedX = -0.10;
  double _maxObservedX = 0.10;
  double _minObservedY = -0.10;
  double _maxObservedY = 0.10;

  // Noise reduction filters
  final OneEuroFilter _filterX = OneEuroFilter(minCutoff: 0.8, beta: 0.03);
  final OneEuroFilter _filterY = OneEuroFilter(minCutoff: 0.8, beta: 0.03);

  // Slow, confidence-aware drift correction parameters
  final double _driftRate = 0.998;

  // Track physical device tilt (Roll in degrees, default 0.0)
  double _deviceRoll = 0.0;

  /// Sets the active calibration profile to personalize estimation values.
  void applyProfile(UserCalibrationProfile profile) {
    _activeProfile = profile;
    _baselineOffset = [profile.neutralVector[0], profile.neutralVector[1]];
    _isCalibrated = true;
  }

  /// Sets the device tilt context to make the coordinates orientation independent.
  void setDeviceTilt(double rollDegrees) {
    _deviceRoll = rollDegrees;
  }

  /// Resets the calibration and dynamic bounds.
  void resetCalibration() {
    _activeProfile = null;
    _baselineOffset = null;
    _isCalibrated = false;
    _calibrationSamples = 0;
    _calibrationSumX = 0.0;
    _calibrationSumY = 0.0;
    _minObservedX = -0.10;
    _maxObservedX = 0.10;
    _minObservedY = -0.10;
    _maxObservedY = 0.10;
    _filterX.reset();
    _filterY.reset();
  }

  /// Triggers or adds to the baseline calibration using the current frame's raw features.
  /// Accumulates across frames for highly stable [0,0] neutral center gaze detection.
  void calibrate(List<FaceLandmark> landmarks) {
    if (landmarks.length < 478) return;
    final raw = _calculateRawGazeFeatures(landmarks);

    _calibrationSumX += raw[0];
    _calibrationSumY += raw[1];
    _calibrationSamples++;

    if (_calibrationSamples >= _requiredCalibrationSamples) {
      _baselineOffset = [
        _calibrationSumX / _calibrationSamples,
        _calibrationSumY / _calibrationSamples,
      ];
      _isCalibrated = true;
    }
  }

  /// Evaluates a single frame of raw Face Mesh landmarks.
  /// Returns a calibrated, normalized GazeResult [X, Y] in range [-1.0, 1.0].
  GazeResult processFrame(List<FaceLandmark> landmarks) {
    // MediaPipe Face Mesh has 468 face landmarks + 10 iris landmarks = 478 total.
    if (landmarks.length < 478) {
      return const GazeResult(direction: 'UNKNOWN', vector: [0.0, 0.0], confidence: 0.0);
    }

    // 1. Physical Eye Aspect Ratio (EAR) Blink Rejection
    final isBlinking = _detectBlink(landmarks);
    final double confidence = isBlinking ? 0.05 : _computeConfidence(landmarks);

    // 2. Extract Raw Normalized Gaze Ratios (Iris relative position to Eye Socket & Eyelid bounds)
    final rawFeatures = _calculateRawGazeFeatures(landmarks);
    double rawX = rawFeatures[0];
    double rawY = rawFeatures[1];

    // Compensate for device tilt/roll
    if (_deviceRoll != 0.0) {
      final double rad = _deviceRoll * pi / 180.0;
      final double cosR = cos(rad);
      final double sinR = sin(rad);
      final double rx = rawX * cosR - rawY * sinR;
      final double ry = rawX * sinR + rawY * cosR;
      rawX = rx;
      rawY = ry;
    }

    // 3. Dynamic Calibration Baseline & Auto-Initialization
    if (!_isCalibrated || _baselineOffset == null) {
      _baselineOffset = [rawX, rawY];
      _isCalibrated = true;
    }

    // Compute calibration-offset values relative to baseline
    double offsetX = rawX - _baselineOffset![0];
    double offsetY = rawY - _baselineOffset![1];

    // Slow, confidence-aware drift correction to absorb gradual posture/slouching shifts
    if (!isBlinking && confidence > 0.70) {
      _baselineOffset![0] = _baselineOffset![0] * _driftRate + rawX * (1.0 - _driftRate);
      _baselineOffset![1] = _baselineOffset![1] * _driftRate + rawY * (1.0 - _driftRate);
    }

    // 4. Smooth using high-performance OneEuro Filters
    final int nowMs = DateTime.now().millisecondsSinceEpoch;
    offsetX = _filterX.filter(offsetX, nowMs);
    offsetY = _filterY.filter(offsetY, nowMs);

    // 5. Normalize vectors based on active calibration profile or observed running bounds
    double normalizedX = 0.0;
    double normalizedY = 0.0;

    final profile = _activeProfile;
    if (profile != null) {
      // Map based on personalized calibration thresholds
      final double dx = offsetX + profile.horizontalBias;
      final double dy = offsetY + profile.verticalBias;

      // Check deadzone
      final distance = sqrt(dx * dx + dy * dy);
      if (distance < profile.deadzoneRadius) {
        return const GazeResult(direction: 'CENTER', vector: [0.0, 0.0], confidence: 0.95);
      }

      // Personalized normalization mapping relative to extreme points
      normalizedX = dx > 0
          ? (dx / (profile.rightThreshold != 0.0 ? profile.rightThreshold : 0.1))
          : (dx / (profile.leftThreshold != 0.0 ? profile.leftThreshold.abs() : 0.1));

      normalizedY = dy > 0
          ? (dy / (profile.topThreshold != 0.0 ? profile.topThreshold : 0.1))
          : (dy / (profile.bottomThreshold != 0.0 ? profile.bottomThreshold.abs() : 0.1));

      // Apply eyelid occlusion compensation factor to down-gaze normalization
      if (dy < 0 && profile.occlusionCompensation != 0.0) {
        normalizedY *= (1.0 + profile.occlusionCompensation);
      }
    } else {
      // Fallback: Dynamically update outer bounds
      if (offsetX < _minObservedX) _minObservedX = offsetX;
      if (offsetX > _maxObservedX) _maxObservedX = offsetX;
      if (offsetY < _minObservedY) _minObservedY = offsetY;
      if (offsetY > _maxObservedY) _maxObservedY = offsetY;

      normalizedX = _normalize(offsetX, _minObservedX, _maxObservedX);
      normalizedY = _normalize(offsetY, _minObservedY, _maxObservedY);
    }

    // Bound the values strictly within [-1.0, 1.0]
    normalizedX = normalizedX.clamp(-1.0, 1.0);
    normalizedY = normalizedY.clamp(-1.0, 1.0);

    // 6. If blink is detected, return baseline/last vector to prevent target drifting/jitter
    if (isBlinking) {
      return const GazeResult(direction: 'BLINK', vector: [0.0, 0.0], confidence: 0.05);
    }

    // Determine high-resolution directional classification
    final direction = _determineDirection(normalizedX, normalizedY);

    return GazeResult(
      direction: direction,
      vector: [
        double.parse(normalizedX.toStringAsFixed(2)),
        double.parse(normalizedY.toStringAsFixed(2)),
      ],
      confidence: double.parse(confidence.toStringAsFixed(2)),
    );
  }

  /// Calculates scale-invariant relative horizontal & vertical ratios of iris centers
  List<double> _calculateRawGazeFeatures(List<FaceLandmark> landmarks) {
    // --- Horizontal Ratio Configuration ---
    // Left eye corner anchors: Outer = 33, Inner = 133
    // Right eye corner anchors: Inner = 362, Outer = 263
    final leftOuter = landmarks[33];
    final leftInner = landmarks[133];
    final rightInner = landmarks[362];
    final rightOuter = landmarks[263];

    // Iris centers: Left = 468, Right = 473
    final leftIris = landmarks[468];
    final rightIris = landmarks[473];

    final leftEyeWidth = leftOuter.x - leftInner.x;
    final rightEyeWidth = rightOuter.x - rightInner.x;

    // Horizontal position of iris center relative to eye width bounds
    final double leftHOffset = leftEyeWidth != 0 ? (leftIris.x - leftInner.x) / leftEyeWidth : 0.5;
    final double rightHOffset = rightEyeWidth != 0
        ? (rightIris.x - rightInner.x) / rightEyeWidth
        : 0.5;

    // Map to zero-centered scale: Left (-0.5) to Right (0.5)
    final double horizontalRaw = ((leftHOffset + rightHOffset) / 2.0) - 0.5;

    // --- Vertical Ratio Configuration ---
    // Downward gaze droop compensation uses the ratio of iris center relative to upper & lower eyelids
    // Left Eye upper eyelid: 159, lower eyelid: 145
    // Right Eye upper eyelid: 386, lower eyelid: 374
    final leftUpperEyelid = landmarks[159];
    final leftLowerEyelid = landmarks[145];
    final rightUpperEyelid = landmarks[386];
    final rightLowerEyelid = landmarks[374];

    final leftEyeHeight = leftLowerEyelid.y - leftUpperEyelid.y;
    final rightEyeHeight = rightLowerEyelid.y - rightUpperEyelid.y;

    final double leftVOffset = leftEyeHeight != 0
        ? (leftIris.y - leftUpperEyelid.y) / leftEyeHeight
        : 0.5;
    final double rightVOffset = rightEyeHeight != 0
        ? (rightIris.y - rightUpperEyelid.y) / rightEyeHeight
        : 0.5;

    // Map to zero-centered scale: Bottom (-0.5) to Top (0.5)
    final double verticalRaw = 0.5 - ((leftVOffset + rightVOffset) / 2.0);

    return [horizontalRaw, verticalRaw];
  }

  /// Detects blinks using Eye Aspect Ratio (EAR)
  bool _detectBlink(List<FaceLandmark> landmarks) {
    // Left eye contours: 160 (top), 144 (bottom), 33 (outer), 133 (inner)
    final lEAR = _calculateEAR(landmarks[160], landmarks[144], landmarks[33], landmarks[133]);
    // Right eye contours: 385 (top), 380 (bottom), 263 (outer), 362 (inner)
    final rEAR = _calculateEAR(landmarks[385], landmarks[380], landmarks[263], landmarks[362]);

    final threshold = _activeProfile?.blinkThreshold ?? 0.18;
    return (lEAR + rEAR) / 2.0 < threshold;
  }

  double _calculateEAR(
    FaceLandmark top,
    FaceLandmark bottom,
    FaceLandmark outer,
    FaceLandmark inner,
  ) {
    final double height = top.distanceTo(bottom);
    final double width = outer.distanceTo(inner);
    return width != 0.0 ? height / width : 0.0;
  }

  /// Normalizes raw offset to [-1.0, 1.0] range
  double _normalize(double val, double minBound, double maxBound) {
    if (maxBound == minBound) return 0.0;
    final double normalized = (val - minBound) / (maxBound - minBound);
    return (normalized * 2.0) - 1.0;
  }

  /// Computes a confidence score based on physical distance, scale and landmarks consistency
  double _computeConfidence(List<FaceLandmark> landmarks) {
    final importantLandmarks = [
      landmarks[33], landmarks[133], landmarks[362], landmarks[263], // Corners
      landmarks[468], landmarks[473], // Iris Centers
      landmarks[159], landmarks[145], landmarks[386], landmarks[374], // Eyelids
    ];

    int validPoints = 0;
    for (final pt in importantLandmarks) {
      if (!pt.x.isNaN && !pt.y.isNaN && pt.x != 0.0 && pt.y != 0.0) {
        validPoints++;
      }
    }

    double baseConfidence = validPoints / importantLandmarks.length;

    // Check physiological alignment symmetry between both eyes to detect occlusion or tracking issues
    final leftIris = landmarks[468];
    final rightIris = landmarks[473];
    final double irisDistanceDelta = (leftIris.y - rightIris.y).abs();
    if (irisDistanceDelta > 0.05) {
      baseConfidence *= 0.7; // Discrepancy penalty
    }

    return baseConfidence.clamp(0.0, 1.0);
  }

  /// Classifies the normalized coordinates into high-grade accessibility directional sectors
  String _determineDirection(double x, double y) {
    const double deadzone = 0.25;

    final absX = x.abs();
    final absY = y.abs();

    if (absX < deadzone && absY < deadzone) {
      return 'CENTER';
    }

    if (absX >= deadzone && absY < deadzone) {
      return x > 0 ? 'RIGHT' : 'LEFT';
    } else if (absY >= deadzone && absX < deadzone) {
      return y > 0 ? 'TOP' : 'BOTTOM';
    } else {
      // Diagonal sectors
      final String horizontal = x > 0 ? 'RIGHT' : 'LEFT';
      final String vertical = y > 0 ? 'TOP' : 'BOTTOM';
      return '${vertical}_$horizontal';
    }
  }
}
