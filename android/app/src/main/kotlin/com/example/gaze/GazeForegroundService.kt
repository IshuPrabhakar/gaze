package com.example.gaze

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.view.KeyEvent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.ImageFormat
import android.graphics.RectF
import android.graphics.Matrix
import android.accessibilityservice.GestureDescription
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GazeForegroundService : Service() {

    companion object {
        private const val TAG = "GazeForegroundService"
        private const val CHANNEL_ID = "GazeForegroundChannel"
        private const val NOTIFICATION_ID = 4567

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var sensitivity: Float = 0.5f
        @Volatile
        var scrollSpeed: Float = 1.0f
        @Volatile
        var triggerDurationMs: Long = 800L
        @Volatile
        var pauseOnLookAway: Boolean = false
        @Volatile
        var systemWide: Boolean = true
        @Volatile
        var enabledApps: List<String> = emptyList()
        @Volatile
        var swipeMode: String = "eyeTracking"

        @Volatile
        var devicePitchDeg: Float = 0f
        @Volatile
        var deviceRollDeg: Float = 0f
        @Volatile
        var deviceYawDeg: Float = 0f

        @Volatile
        var telemetryListener: ((GazeState) -> Unit)? = null
    }

    data class GazeState(
        val isFaceDetected: Boolean,
        val isAttentive: Boolean,
        val isLookingDown: Boolean,
        val isLookingUp: Boolean,
        val isLookingLeft: Boolean = false,
        val isLookingRight: Boolean = false,
        val isBlinking: Boolean,
        val yaw: Float,
        val pitch: Float,
        val eyeOpenness: Float,
        val isNodLeft: Boolean = false,
        val isNodRight: Boolean = false,
        val isNodUp: Boolean = false,
        val isNodDown: Boolean = false,
        val detectedHandGesture: String = "NONE",
        val isSwipeLeft: Boolean = false,
        val isSwipeRight: Boolean = false,
        val isSwipeUp: Boolean = false,
        val isSwipeDown: Boolean = false,
        val rawConfidence: Float = 1.0f,
        val internalState: String = "TRACKING"
    )

    enum class GazeStateEnum { NO_FACE, SEARCHING, TRACKING, UNSTABLE, STABLE_LOCK, ACTION_READY, COOLDOWN }

    // Outer data classes to resolve inner-class nested declaration restrictions in Kotlin
    data class BlinkResult(val isBlinking: Boolean, val blinkScore: Float, val eyeOpenness: Float)
    data class HysteresisResult(val isLookingDown: Boolean, val isLookingUp: Boolean, val isLookingLeft: Boolean, val isLookingRight: Boolean)
    data class AppGestureConfig(val swipeDistanceFraction: Float, val minIntervalMs: Long)
    data class AppInteractionProfile(val config: AppGestureConfig, val stabilityDurationMs: Long)

    // Instance fields for state processing
    private var scrollCooldownMs     = 1500L
    private var adaptiveFrameThrottleMs = 33L
    private var lastScrollTime       = 0L

    private var topThreshold: Float = 0.60f
    private var bottomThreshold: Float = -0.60f
    private var leftThreshold: Float = -0.60f
    private var rightThreshold: Float = 0.60f
    private var verticalBias: Float = 0.0f
    private var horizontalBias: Float = 0.0f
    private var deadzoneRadius: Float = 0.08f


    // Modular components instances
    private lateinit var cropStabilizer: CropStabilizer
    private lateinit var confidenceEngine: ConfidenceEngine
    private lateinit var gazeStateMachine: GazeStateMachine
    private lateinit var blinkDetector: BlinkDetector
    private lateinit var directionHysteresis: DirectionHysteresis
    private lateinit var safetyEngine: AccessibilitySafetyEngine
    private lateinit var thermalManager: ThermalPerformanceManager
    private lateinit var driftManager: DriftCorrectionManager
    private lateinit var profileManager: InteractionProfileManager

    private var yawNodPhase     = NodPhase.IDLE
    private var yawNodDirection = 0
    private var yawNodPeak      = 0f
    private var yawNodStartTime = 0L

    private var pitchNodPhase     = NodPhase.IDLE
    private var pitchNodDirection = 0
    private var pitchNodPeak      = 0f
    private var pitchNodStartTime = 0L

    private val nodMaxDurationMs  = 600L
    private val nodReturnFraction = 0.4f

    private var yawBaseline   = 0f
    private var pitchBaseline = 0f
    private var baselineInitialized   = false
    private var baselineSampleCount   = 0
    private val baselineWarmupFrames  = 15
    private val baselineDriftRate = 0.995f

    private var reflectiveRunner: OnnxReflectiveRunner? = null
    private val inputSize = 96 

    private var calibrationMatrix = floatArrayOf(
        1f, 0f, 0f, 
        0f, 1f, 0f  
    )

    private var smoothedYaw = 0f
    private var smoothedPitch = 0f

    private var lookingDownStartTime = 0L
    private var lookingUpStartTime   = 0L
    private var lookingLeftStartTime = 0L
    private var lookingRightStartTime = 0L

    private var handLandmarker: HandLandmarker? = null
    private val swipeHistory = java.util.ArrayList<Pair<Long, android.graphics.PointF>>()
    private val swipeCooldownMs = 1200L
    private var lastSwipeTriggeredTime = 0L

    private var lastStabilityGesture = "NONE"
    private var gestureStabilityStartTime = 0L
    private val stabilityDurationMs = 400L
    private var lastTriggeredPalmGesture = "NONE"
    private var lastPalmGestureTriggerTime = 0L
    private val palmGestureCooldownMs = 1500L

    private var isUserAttentive = true
    private var attentionStateTransitionTime = 0L
    private val attentionLostThresholdMs = 1500L
    private val attentionRegainThresholdMs = 500L

    private var currentHandGesture = "NONE"
    private var flagSwipeLeft = false
    private var flagSwipeRight = false
    private var flagSwipeUp = false
    private var flagSwipeDown = false

    private lateinit var backgroundExecutor: ExecutorService
    private var faceLandmarker: FaceLandmarker? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var lastProcessedFrameTime = 0L
    private var cachedPixels: IntArray? = null
    private var frameCount       = 0
    private var faceCount        = 0
    private var sensorOrientation = 270

    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var gyroMagnitude = 0f

    private var filterGazeDown: OneEuroFilter? = null
    private var filterGazeUp: OneEuroFilter? = null
    private var filterHeadYaw: OneEuroFilter? = null
    private var filterHeadPitch: OneEuroFilter? = null

    private enum class NodPhase { IDLE, ARMED, FIRED }

    class OneEuroFilter(
        val minCutoff: Double,
        val beta: Double,
        val dCutoff: Double
    ) {
        private var firstTime = true
        private var xPrev = 0.0
        private var dxPrev = 0.0
        private var tPrev = 0L

        fun filter(value: Double, timestampMs: Long): Double {
            if (firstTime) {
                firstTime = false
                xPrev = value
                dxPrev = 0.0
                tPrev = timestampMs
                return value
            }

            val dt = (timestampMs - tPrev) / 1000.0
            if (dt <= 0.0) return xPrev

            val dx = (value - xPrev) / dt
            val alphaD = alpha(dt, dCutoff)
            val dxSmooth = alphaD * dx + (1.0 - alphaD) * dxPrev

            val cutoff = minCutoff + beta * Math.abs(dxSmooth)
            val alphaX = alpha(dt, cutoff)

            val xSmooth = alphaX * value + (1.0 - alphaX) * xPrev

            xPrev = xSmooth
            dxPrev = dxSmooth
            tPrev = timestampMs

            return xSmooth
        }

        private fun alpha(dt: Double, cutoff: Double): Double {
            val tau = 1.0 / (2.0 * Math.PI * cutoff)
            return dt / (dt + tau)
        }
    }

    private val sensorListener = object : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private val orientationAngles = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    deviceYawDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    devicePitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                    deviceRollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    gyroMagnitude = Math.sqrt((x*x + y*y + z*z).toDouble()).toFloat()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GazeForegroundService created")
        backgroundExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize modular components
        cropStabilizer = CropStabilizer()
        confidenceEngine = ConfidenceEngine()
        gazeStateMachine = GazeStateMachine()
        blinkDetector = BlinkDetector()
        directionHysteresis = DirectionHysteresis()
        safetyEngine = AccessibilitySafetyEngine()
        thermalManager = ThermalPerformanceManager()
        driftManager = DriftCorrectionManager()
        profileManager = InteractionProfileManager()

        filterGazeDown = OneEuroFilter(1.0, 0.01, 1.0)
        filterGazeUp = OneEuroFilter(1.0, 0.01, 1.0)
        filterHeadYaw = OneEuroFilter(1.2, 0.015, 1.0)
        filterHeadPitch = OneEuroFilter(1.2, 0.015, 1.0)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        createNotificationChannel()
        initMediaPipe()
        initOnnx()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "GazeForegroundService starting")
        isServiceRunning = true
        loadPersonalizedProfile(this)

        if (cameraThread == null) startBackgroundThread()

        rotationVectorSensor?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroSensor?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }

        val notification = createNotification(
            "Gaze Service Active",
            "Hands-free control is running in the background with L2CS-Net."
        )
        startForeground(NOTIFICATION_ID, notification)
        startCameraProcessing()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "GazeForegroundService destroyed")

        stopCameraProcessing()
        sensorManager?.unregisterListener(sensorListener)

        isServiceRunning = false
        backgroundExecutor.shutdown()
        faceLandmarker?.close()
        handLandmarker?.close()
        reflectiveRunner?.close()
        simulatorHandler?.removeCallbacksAndMessages(null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gaze Background Tracker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when L2CS-Net eye-gaze tracking is running."
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification(title, content))
    }

    private fun initMediaPipe() {
        backgroundExecutor.execute {
            try {
                val modelFile = File(filesDir, "face_landmarker.task")
                if (!modelFile.exists()) {
                    assets.open("face_landmarker.task").use { input ->
                        FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                    }
                }

                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .build()

                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setOutputFaceBlendshapes(true)
                    .setResultListener { result, mpImage ->
                        val inputBitmap = BitmapExtractor.extract(mpImage)
                        processFaceLandmarksWithBitmap(result, inputBitmap)
                    }
                    .setErrorListener { error ->
                        Log.e(TAG, "MediaPipe error: ${error.message}")
                    }
                    .build()

                faceLandmarker = FaceLandmarker.createFromOptions(this, options)
                Log.d(TAG, "MediaPipe FaceLandmarker initialized.")

                val handModelFile = File(filesDir, "hand_landmarker.task")
                if (!handModelFile.exists()) {
                    assets.open("hand_landmarker.task").use { input ->
                        FileOutputStream(handModelFile).use { output -> input.copyTo(output) }
                    }
                }

                val handBaseOptions = BaseOptions.builder()
                    .setModelAssetPath(handModelFile.absolutePath)
                    .build()

                val handOptions = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(handBaseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener { result, _ -> processHandLandmarks(result) }
                    .setErrorListener { error ->
                        Log.e(TAG, "MediaPipe Hand error: ${error.message}")
                    }
                    .build()

                handLandmarker = HandLandmarker.createFromOptions(this, handOptions)
                Log.d(TAG, "MediaPipe HandLandmarker initialized.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaPipe: ${e.message}. Starting fallback simulator.")
                startFallbackHeuristicSimulator()
            }
        }
    }

    private fun initOnnx() {
        backgroundExecutor.execute {
            try {
                val expectedOnnxSize = 235557L
                val expectedDataSize = 95420416L

                val modelFile = File(filesDir, "l2cs.onnx")
                modelFile.parentFile?.mkdirs()
                if (!modelFile.exists() || modelFile.length() != expectedOnnxSize) {
                    Log.i(TAG, "Copying l2cs.onnx from assets to ${modelFile.absolutePath} (expected size: $expectedOnnxSize)...")
                    assets.open("l2cs.onnx").use { input ->
                        FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                    }
                }
                
                val modelDataFile = File(filesDir, "l2cs.onnx.data")
                modelDataFile.parentFile?.mkdirs()
                if (!modelDataFile.exists() || modelDataFile.length() != expectedDataSize) {
                    try {
                        Log.i(TAG, "Copying l2cs.onnx.data from assets to ${modelDataFile.absolutePath} (expected size: $expectedDataSize)...")
                        assets.open("l2cs.onnx.data").use { input ->
                            FileOutputStream(modelDataFile).use { output -> input.copyTo(output) }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "External weights data file copy failed or not required", e)
                    }
                }

                reflectiveRunner = OnnxReflectiveRunner(this, modelFile.absolutePath)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load ONNX model. Fallback will execute inside pipeline.", e)
            }
        }
    }

    private fun processFaceLandmarks(result: FaceLandmarkerResult) {
        // Obsolete signature.
    }

    private fun processFaceLandmarksWithBitmap(result: FaceLandmarkerResult, frameBitmap: Bitmap) {
        val blendshapesOptional = result.faceBlendshapes()
        val landmarks           = result.faceLandmarks()

        if (blendshapesOptional == null
            || !blendshapesOptional.isPresent
            || landmarks.isNullOrEmpty()
            || landmarks[0].size <= 263
        ) {
            baselineInitialized   = false
            baselineSampleCount   = 0
            yawNodPhase           = NodPhase.IDLE
            pitchNodPhase         = NodPhase.IDLE
            gazeStateMachine.updateState(GazeStateEnum.NO_FACE)
            publishGazeState(emptyGazeState())
            return
        }

        faceCount++
        val scores = blendshapesOptional.get()[0].associate { it.categoryName() to it.score() }
        val face   = landmarks[0]
        val now = System.currentTimeMillis()

        // 1. ADVANCED BLINK & WINK DETECTOR
        val blinkResult = blinkDetector.detectBlink(face, scores)
        val isBlinking = blinkResult.isBlinking
        val avgBlinkScore = blinkResult.blinkScore
        val eyeOpenness = blinkResult.eyeOpenness

        // 2. Head Decoupling/Pose Baseline calculations
        val lEyeLm = face[33]
        val rEyeLm = face[263]
        val iodNormDx = rEyeLm.x() - lEyeLm.x()
        val iodNormDy = rEyeLm.y() - lEyeLm.y()
        val iodNorm = Math.sqrt((iodNormDx * iodNormDx + iodNormDy * iodNormDy).toDouble()).toFloat().coerceAtLeast(0.01f)
        val zCenter = (280f * 70f) / (iodNorm * 320f)

        fun get3DPoint(idx: Int): FloatArray {
            val lm = face[idx]
            val zPhysical = zCenter + lm.z() * 70f
            val xPhysical = (lm.x() - 0.5f) * 320f * zPhysical / 280f
            val yPhysical = (lm.y() - 0.5f) * 240f * zPhysical / 280f
            return floatArrayOf(xPhysical, yPhysical, zPhysical)
        }

        val p33 = get3DPoint(33)
        val p263 = get3DPoint(263)
        val p10 = get3DPoint(10)
        val p152 = get3DPoint(152)

        val uxDx = p263[0] - p33[0]
        val uxDy = p263[1] - p33[1]
        val uxDz = p263[2] - p33[2]
        val uxLen = Math.sqrt((uxDx * uxDx + uxDy * uxDy + uxDz * uxDz).toDouble()).toFloat().coerceAtLeast(0.001f)
        val ux = floatArrayOf(uxDx / uxLen, uxDy / uxLen, uxDz / uxLen)

        val uyRawDx = p10[0] - p152[0]
        val uyRawDy = p10[1] - p152[1]
        val uyRawDz = p10[2] - p152[2]
        val uyRawLen = Math.sqrt((uyRawDx * uyRawDx + uyRawDy * uyRawDy + uyRawDz * uyRawDz).toDouble()).toFloat().coerceAtLeast(0.001f)
        val uyRaw = floatArrayOf(uyRawDx / uyRawLen, uyRawDy / uyRawLen, uyRawDz / uyRawLen)

        val uz = floatArrayOf(
            ux[1]*uyRaw[2] - ux[2]*uyRaw[1],
            ux[2]*uyRaw[0] - ux[0]*uyRaw[2],
            ux[0]*uyRaw[1] - ux[1]*uyRaw[0]
        )
        val lenZ = Math.sqrt((uz[0]*uz[0] + uz[1]*uz[1] + uz[2]*uz[2]).toDouble()).toFloat().coerceAtLeast(0.001f)
        uz[0] /= lenZ; uz[1] /= lenZ; uz[2] /= lenZ

        val uy = floatArrayOf(
            uz[1]*ux[2] - uz[2]*ux[1],
            uz[2]*ux[0] - uz[0]*ux[2],
            uz[0]*ux[1] - uz[1]*ux[0]
        )

        val p1 = get3DPoint(1)
        val eyeMid = floatArrayOf((p33[0] + p263[0]) / 2f, (p33[1] + p263[1]) / 2f, (p33[2] + p263[2]) / 2f)
        val dNoseX = p1[0] - eyeMid[0]
        val dNoseY = p1[1] - eyeMid[1]
        val dNoseZ = p1[2] - eyeMid[2]

        val iodMetric = Math.sqrt(((p263[0]-p33[0])*(p263[0]-p33[0]) + (p263[1]-p33[1])*(p263[1]-p33[1]) + (p263[2]-p33[2])*(p263[2]-p33[2])).toDouble()).toFloat().coerceAtLeast(1f)
        val headYaw   = (dNoseX * ux[0] + dNoseY * ux[1] + dNoseZ * ux[2]) / iodMetric
        val headPitch = (dNoseX * uy[0] + dNoseY * uy[1] + dNoseZ * uy[2]) / iodMetric

        if (!baselineInitialized) {
            val n = baselineSampleCount.toFloat()
            yawBaseline   = yawBaseline   * (n / (n + 1f)) + headYaw   * (1f / (n + 1f))
            pitchBaseline = pitchBaseline * (n / (n + 1f)) + headPitch * (1f / (n + 1f))
            baselineSampleCount++

            if (baselineSampleCount >= baselineWarmupFrames) {
                baselineInitialized = true
            }

            gazeStateMachine.updateState(GazeStateEnum.SEARCHING)
            publishGazeState(emptyGazeState().copy(internalState = "SEARCHING"))
            return
        } else {
            driftManager.applyDriftCorrection(headYaw, headPitch)
            yawBaseline   = yawBaseline   * baselineDriftRate + headYaw   * (1f - baselineDriftRate)
            pitchBaseline = pitchBaseline * baselineDriftRate + headPitch * (1f - baselineDriftRate)
        }

        val yawDev   = headYaw   - yawBaseline
        val pitchDev = headPitch - pitchBaseline

        val confidence = confidenceEngine.calculateConfidence(face, isBlinking, gyroMagnitude, headYaw, headPitch)

        adaptiveFrameThrottleMs = thermalManager.getThrottleMs(confidence, gazeStateMachine.currentState)

        var rawPitch = 0f
        var rawYaw = 0f

        if (confidence > 0.40f && !isBlinking) {
            val leftEyeRect = cropStabilizer.getStabilizedEyeBoundingBox(face, true, frameBitmap.width, frameBitmap.height)
            val rightEyeRect = cropStabilizer.getStabilizedEyeBoundingBox(face, false, frameBitmap.width, frameBitmap.height)

            val leftCrop = cropBitmapSafe(frameBitmap, leftEyeRect)
            val rightCrop = cropBitmapSafe(frameBitmap, rightEyeRect)

            if (leftCrop != null && rightCrop != null) {
                val resizedLeft = Bitmap.createScaledBitmap(leftCrop, inputSize, inputSize, true)
                val resizedRight = Bitmap.createScaledBitmap(rightCrop, inputSize, inputSize, true)

                val output = runL2CSNetInference(resizedLeft, resizedRight)
                rawPitch = output.first
                rawYaw = output.second
            } else {
                // Heuristic fallback: Add baselines to align with subtraction below
                rawYaw = yawDev * 0.7f + yawBaseline
                rawPitch = pitchDev * 0.7f + pitchBaseline
            }
        } else {
            // Un-tracked or blinking: default to baseline to maintain zero-centered output
            rawYaw = yawBaseline
            rawPitch = pitchBaseline
        }

        // Apply active calibration biases and thresholds to map raw gaze into [-1.0, 1.0] range
        val offsetX = rawYaw - yawBaseline
        val offsetY = rawPitch - pitchBaseline

        val dx = offsetX + horizontalBias
        val dy = offsetY + verticalBias

        var calibX = if (dx > 0) {
            dx / if (leftThreshold != 0f) Math.abs(leftThreshold) else 0.1f
        } else {
            dx / if (rightThreshold != 0f) Math.abs(rightThreshold) else 0.1f
        }

        var calibY = if (dy > 0) {
            dy / if (topThreshold != 0f) Math.abs(topThreshold) else 0.1f
        } else {
            dy / if (bottomThreshold != 0f) Math.abs(bottomThreshold) else 0.1f
        }

        calibX = calibX.coerceIn(-1.0f, 1.0f)
        calibY = calibY.coerceIn(-1.0f, 1.0f)

        val finalX = calibrationMatrix[0] * calibX + calibrationMatrix[1] * calibY + calibrationMatrix[2]
        val finalY = calibrationMatrix[3] * calibX + calibrationMatrix[4] * calibY + calibrationMatrix[5]

        val smoothed = safetyEngine.applyAdaptiveSmoothing(finalX, finalY, confidence, now)
        smoothedYaw = smoothed.first
        smoothedPitch = smoothed.second

        val hysteresisResult = directionHysteresis.applyHysteresis(smoothedYaw, smoothedPitch, sensitivity)
        val isLookingDown = hysteresisResult.isLookingDown
        val isLookingUp = hysteresisResult.isLookingUp
        val isLookingLeft = hysteresisResult.isLookingLeft
        val isLookingRight = hysteresisResult.isLookingRight

        gazeStateMachine.evaluateState(confidence, isLookingDown || isLookingUp || isLookingLeft || isLookingRight, isBlinking)

        val yawNodThreshold   = 0.07f - (sensitivity * 0.025f)
        val pitchNodThreshold = 0.035f - (sensitivity * 0.012f)

        var isNodLeft  = false
        var isNodRight = false
        var isNodUp    = false
        var isNodDown  = false

        if (gazeStateMachine.currentState == GazeStateEnum.STABLE_LOCK && (now - lastScrollTime) > scrollCooldownMs) {
            when (yawNodPhase) {
                NodPhase.IDLE -> {
                    if (Math.abs(yawDev) > yawNodThreshold) {
                        yawNodPhase     = NodPhase.ARMED
                        yawNodDirection = if (yawDev > 0) 1 else -1
                        yawNodPeak      = yawDev
                        yawNodStartTime = now
                    }
                }
                NodPhase.ARMED -> {
                    if (Math.abs(yawDev) > Math.abs(yawNodPeak)) yawNodPeak = yawDev
                    val hasReturned = Math.abs(yawDev) < Math.abs(yawNodPeak) * nodReturnFraction
                    val timedOut    = (now - yawNodStartTime) > nodMaxDurationMs

                    when {
                        hasReturned -> {
                            yawNodPhase = NodPhase.FIRED
                            if (yawNodDirection > 0) isNodRight = true else isNodLeft = true
                        }
                        timedOut -> {
                            yawNodPhase = NodPhase.IDLE
                        }
                    }
                }
                NodPhase.FIRED -> {
                    if (Math.abs(yawDev) < yawNodThreshold * 0.5f) {
                        yawNodPhase = NodPhase.IDLE
                    }
                }
            }

            when (pitchNodPhase) {
                NodPhase.IDLE -> {
                    if (Math.abs(pitchDev) > pitchNodThreshold) {
                        pitchNodPhase     = NodPhase.ARMED
                        pitchNodDirection = if (pitchDev > 0) 1 else -1
                        pitchNodPeak      = pitchDev
                        pitchNodStartTime = now
                    }
                }
                NodPhase.ARMED -> {
                    if (Math.abs(pitchDev) > Math.abs(pitchNodPeak)) pitchNodPeak = pitchDev
                    val hasReturned = Math.abs(pitchDev) < Math.abs(pitchNodPeak) * nodReturnFraction
                    val timedOut    = (now - pitchNodStartTime) > nodMaxDurationMs

                    when {
                        hasReturned -> {
                            pitchNodPhase = NodPhase.FIRED
                            if (pitchNodDirection > 0) isNodDown = true else isNodUp = true
                        }
                        timedOut -> {
                            pitchNodPhase = NodPhase.IDLE
                        }
                    }
                }
                NodPhase.FIRED -> {
                    if (Math.abs(pitchDev) < pitchNodThreshold * 0.5f) {
                        pitchNodPhase = NodPhase.IDLE
                    }
                }
            }
        }

        // Diagnostics
        if (faceCount % 15 == 0) {
            Log.d(TAG, "Gaze System | State=${gazeStateMachine.currentState} Confidence=${"%.2f".format(confidence)} | SmoothedYaw=${"%.3f".format(smoothedYaw)} SmoothedPitch=${"%.3f".format(smoothedPitch)} | LookDown=$isLookingDown LookUp=$isLookingUp")
        }

        val state = GazeState(
            isFaceDetected = true,
            isAttentive    = (gazeStateMachine.currentState == GazeStateEnum.STABLE_LOCK || gazeStateMachine.currentState == GazeStateEnum.ACTION_READY),
            isLookingDown  = isLookingDown,
            isLookingUp    = isLookingUp,
            isLookingLeft  = isLookingLeft,
            isLookingRight = isLookingRight,
            isBlinking     = isBlinking,
            yaw            = smoothedYaw,
            pitch          = smoothedPitch,
            eyeOpenness    = eyeOpenness,
            isNodLeft      = isNodLeft,
            isNodRight     = isNodRight,
            isNodUp        = isNodUp,
            isNodDown      = isNodDown,
            rawConfidence  = confidence,
            internalState  = gazeStateMachine.currentState.name
        )

        publishGazeState(state)
        handleGazeStateAction(state)
    }

    private fun getEyeBoundingBox(face: List<NormalizedLandmark>?, isLeft: Boolean, width: Int, height: Int): RectF {
        val indices = if (isLeft) {
            intArrayOf(33, 133, 159, 145, 160, 158, 153, 144)
        } else {
            intArrayOf(263, 362, 386, 374, 385, 387, 373, 380)
        }

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        if (face != null) {
            for (idx in indices) {
                if (idx < face.size) {
                    val lm = face[idx]
                    val x = lm.x() * width
                    val y = lm.y() * height
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val paddingX = (maxX - minX) * 0.25f
        val paddingY = (maxY - minY) * 0.25f

        return RectF(
            (minX - paddingX).coerceAtLeast(0f),
            (minY - paddingY).coerceAtLeast(0f),
            (maxX + paddingX).coerceAtMost(width.toFloat()),
            (maxY + paddingY).coerceAtMost(height.toFloat())
        )
    }

    private fun cropBitmapSafe(src: Bitmap, rect: RectF): Bitmap? {
        val x = rect.left.toInt()
        val y = rect.top.toInt()
        val w = (rect.right - rect.left).toInt()
        val h = (rect.bottom - rect.top).toInt()

        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > src.width || y + h > src.height) {
            return null
        }
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    private fun runL2CSNetInference(leftEye: Bitmap, rightEye: Bitmap): Pair<Float, Float> {
        val runner = reflectiveRunner
        if (runner == null) {
            return Pair(devicePitchDeg * 0.01f, deviceYawDeg * 0.01f)
        }

        try {
            val numChannels = 3
            val numElements = numChannels * inputSize * inputSize
            val floatBuffer = FloatBuffer.allocate(numElements)

            val leftPixels = IntArray(inputSize * inputSize)
            leftEye.getPixels(leftPixels, 0, inputSize, 0, 0, inputSize, inputSize)

            for (c in 0 until numChannels) {
                for (i in 0 until inputSize * inputSize) {
                    val pix = leftPixels[i]
                    val channelValue = when (c) {
                        0 -> ((pix shr 16) and 0xFF) / 255.0f
                        1 -> ((pix shr 8) and 0xFF) / 255.0f
                        else -> (pix and 0xFF) / 255.0f
                    }
                    floatBuffer.put(channelValue)
                }
            }
            floatBuffer.rewind()

            val shape = longArrayOf(1, numChannels.toLong(), inputSize.toLong(), inputSize.toLong())
            val output = runner.runInference(floatBuffer, shape)
            if (output != null) {
                return output
            }
        } catch (e: Exception) {
            Log.e(TAG, "ONNX Inference execution error: ${e.message}")
        }
        return Pair(devicePitchDeg * 0.01f, deviceYawDeg * 0.01f)
    }

    private fun handleGazeStateAction(state: GazeState) {
        val activeApp = GazeAccessibilityService.activePackageName
        val profile = profileManager.getProfile(activeApp)

        val isSupported = systemWide
                || enabledApps.isEmpty()
                || enabledApps.contains(activeApp)
                || activeApp.isEmpty()
                || activeApp == packageName

        if (!isSupported) {
            lookingDownStartTime = 0L
            lookingUpStartTime   = 0L
            return
        }

        val now = System.currentTimeMillis()

        if (!safetyEngine.allowAction(now, profile.config.minIntervalMs)) {
            return
        }

        if ((now - lastScrollTime) > scrollCooldownMs) {
            if (state.isNodLeft) {
                GazeAccessibilityService.instance?.performScrollLeft(scrollSpeed)
                lastScrollTime = now
                safetyEngine.recordAction(now)
                updateNotification("Gaze Service Active", "Swiped left via head nod.")
                return
            }
            if (state.isNodRight) {
                GazeAccessibilityService.instance?.performScrollRight(scrollSpeed)
                lastScrollTime = now
                safetyEngine.recordAction(now)
                updateNotification("Gaze Service Active", "Swiped right via head nod.")
                return
            }
        }

        if (swipeMode == "headNod") {
            if ((now - lastScrollTime) > scrollCooldownMs) {
                when {
                    state.isNodDown -> {
                        GazeAccessibilityService.instance?.performScrollDown(scrollSpeed)
                        lastScrollTime = now
                        safetyEngine.recordAction(now)
                        updateNotification("Gaze Service Active", "Scrolled down via head nod.")
                    }
                    state.isNodUp -> {
                        GazeAccessibilityService.instance?.performScrollUp(scrollSpeed)
                        lastScrollTime = now
                        safetyEngine.recordAction(now)
                        updateNotification("Gaze Service Active", "Scrolled up via head nod.")
                    }
                }
            }
        } else if (swipeMode == "eyeTracking") {
            val activeDwell = profile.stabilityDurationMs

            if (state.isLookingDown) {
                if (lookingDownStartTime == 0L) {
                    lookingDownStartTime = now
                } else {
                    val elapsed = now - lookingDownStartTime
                    if (elapsed >= activeDwell && (now - lastScrollTime) > scrollCooldownMs) {
                        GazeAccessibilityService.instance?.performScrollDown(scrollSpeed)
                        lastScrollTime       = now
                        lookingDownStartTime = 0L
                        safetyEngine.recordAction(now)
                        updateNotification("Gaze Service Active", "Scrolled down via eye gaze.")
                    }
                }
            } else {
                lookingDownStartTime = 0L
            }

            if (state.isLookingUp) {
                if (lookingUpStartTime == 0L) {
                    lookingUpStartTime = now
                } else {
                    val elapsed = now - lookingUpStartTime
                    if (elapsed >= activeDwell && (now - lastScrollTime) > scrollCooldownMs) {
                        GazeAccessibilityService.instance?.performScrollUp(scrollSpeed)
                        lastScrollTime     = now
                        lookingUpStartTime = 0L
                        safetyEngine.recordAction(now)
                        updateNotification("Gaze Service Active", "Scrolled up via eye gaze.")
                    }
                }
            } else {
                lookingUpStartTime = 0L
            }

            if (state.isLookingLeft) {
                if (lookingLeftStartTime == 0L) {
                    lookingLeftStartTime = now
                } else {
                    val elapsed = now - lookingLeftStartTime
                    if (elapsed >= activeDwell && (now - lastScrollTime) > scrollCooldownMs) {
                        GazeAccessibilityService.instance?.performScrollLeft(scrollSpeed)
                        lastScrollTime       = now
                        lookingLeftStartTime = 0L
                        safetyEngine.recordAction(now)
                        updateNotification("Gaze Service Active", "Scrolled left via eye gaze.")
                    }
                }
            } else {
                lookingLeftStartTime = 0L
            }

            if (state.isLookingRight) {
                if (lookingRightStartTime == 0L) {
                    lookingRightStartTime = now
                } else {
                    val elapsed = now - lookingRightStartTime
                    if (elapsed >= activeDwell && (now - lastScrollTime) > scrollCooldownMs) {
                        GazeAccessibilityService.instance?.performScrollRight(scrollSpeed)
                        lastScrollTime        = now
                        lookingRightStartTime = 0L
                        safetyEngine.recordAction(now)
                        updateNotification("Gaze Service Active", "Scrolled right via eye gaze.")
                    }
                }
            } else {
                lookingRightStartTime = 0L
            }
        }

        if (pauseOnLookAway) {
            val isAttentionLost = Math.abs(state.yaw) > 0.35f || Math.abs(state.pitch) > 0.30f
            val isAttentionRegained = Math.abs(state.yaw) < 0.20f && Math.abs(state.pitch) < 0.15f
            
            if (isUserAttentive) {
                if (isAttentionLost) {
                    if (attentionStateTransitionTime == 0L) {
                        attentionStateTransitionTime = now
                    } else if (now - attentionStateTransitionTime >= attentionLostThresholdMs) {
                        isUserAttentive = false
                        attentionStateTransitionTime = now
                        triggerGlobalPlayPause()
                    }
                } else {
                    attentionStateTransitionTime = 0L
                }
            } else {
                if (isAttentionRegained) {
                    if (attentionStateTransitionTime == 0L) {
                        attentionStateTransitionTime = now
                    } else if (now - attentionStateTransitionTime >= attentionRegainThresholdMs) {
                        isUserAttentive = true
                        attentionStateTransitionTime = now
                        triggerGlobalPlayPause()
                    }
                } else {
                    attentionStateTransitionTime = 0L
                }
            }
        }
    }

    private fun triggerGlobalPlayPause() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    private fun processHandLandmarks(result: HandLandmarkerResult) {
        val landmarks = result.landmarks()
        if (landmarks.isNullOrEmpty() || landmarks[0].size <= 20) {
            swipeHistory.clear()
            currentHandGesture = "NONE"
            lastStabilityGesture = "NONE"
            lastTriggeredPalmGesture = "NONE"
            return
        }

        val hand = landmarks[0]
        val now = SystemClock.uptimeMillis()
        val wrist = hand[0]
        val wristPoint = android.graphics.PointF(wrist.x(), wrist.y())

        swipeHistory.add(Pair(now, wristPoint))
        while (swipeHistory.isNotEmpty() && now - swipeHistory[0].first > 800L) {
            swipeHistory.removeAt(0)
        }

        if (now - lastSwipeTriggeredTime > swipeCooldownMs && swipeHistory.size >= 2) {
            val oldest = swipeHistory[0].second
            val newest = wristPoint
            val dx = newest.x - oldest.x
            val dy = newest.y - oldest.y
            val dt = (now - swipeHistory[0].first) / 1000f

            val travelDistance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (travelDistance > 0.22f && dt > 0.1f) {
                val vx = dx / dt
                val vy = dy / dt

                if (Math.abs(dx) > Math.abs(dy)) {
                    if (Math.abs(vx) > 0.4f) {
                        if (dx > 0) {
                            flagSwipeRight = true
                            GazeAccessibilityService.instance?.performScrollRight(scrollSpeed)
                        } else {
                            flagSwipeLeft = true
                            GazeAccessibilityService.instance?.performScrollLeft(scrollSpeed)
                        }
                        lastSwipeTriggeredTime = now
                        swipeHistory.clear()
                    }
                } else {
                    if (Math.abs(vy) > 0.4f) {
                        if (dy > 0) {
                            flagSwipeDown = true
                            GazeAccessibilityService.instance?.performScrollDown(scrollSpeed)
                        } else {
                            flagSwipeUp = true
                            GazeAccessibilityService.instance?.performScrollUp(scrollSpeed)
                        }
                        lastSwipeTriggeredTime = now
                        swipeHistory.clear()
                    }
                }
            }
        }

        val dist = { p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark ->
            val dx = p1.x() - p2.x()
            val dy = p1.y() - p2.y()
            val dz = p1.z() - p2.z()
            Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        }

        val indexExt  = dist(hand[8], wrist) > dist(hand[5], wrist) * 1.25f
        val middleExt = dist(hand[12], wrist) > dist(hand[9], wrist) * 1.25f
        val ringExt   = dist(hand[16], wrist) > dist(hand[13], wrist) * 1.25f
        val pinkyExt  = dist(hand[20], wrist) > dist(hand[17], wrist) * 1.25f
        val thumbExt  = dist(hand[4], hand[5]) > dist(hand[3], hand[5]) * 1.2f

        var currentGesture = "NONE"
        if (indexExt && middleExt && ringExt && pinkyExt) {
            currentGesture = "OPEN_PALM"
        } else if (!indexExt && !middleExt && !ringExt && !pinkyExt && !thumbExt) {
            currentGesture = "CLOSED_FIST"
        } else if (thumbExt && !indexExt && !middleExt && !ringExt && !pinkyExt) {
            if (hand[4].y() < hand[3].y() && hand[4].y() < hand[5].y()) {
                currentGesture = "THUMBS_UP"
            }
        }

        currentHandGesture = currentGesture

        if (currentGesture == lastStabilityGesture && currentGesture != "NONE") {
            val elapsed = now - gestureStabilityStartTime
            if (elapsed >= stabilityDurationMs && currentGesture != lastTriggeredPalmGesture && (now - lastPalmGestureTriggerTime > palmGestureCooldownMs)) {
                when (currentGesture) {
                    "OPEN_PALM" -> triggerGlobalPlayPause()
                }
                lastTriggeredPalmGesture = currentGesture
                lastPalmGestureTriggerTime = now
            }
        } else {
            lastStabilityGesture = currentGesture
            gestureStabilityStartTime = now
            if (currentGesture == "NONE") {
                lastTriggeredPalmGesture = "NONE"
            }
        }
    }

    private fun publishGazeState(state: GazeState) {
        val mergedState = state.copy(
            detectedHandGesture = currentHandGesture,
            isSwipeLeft = flagSwipeLeft,
            isSwipeRight = flagSwipeRight,
            isSwipeUp = flagSwipeUp,
            isSwipeDown = flagSwipeDown
        )
        telemetryListener?.invoke(mergedState)
        
        flagSwipeLeft = false
        flagSwipeRight = false
        flagSwipeUp = false
        flagSwipeDown = false
    }

    private fun emptyGazeState() = GazeState(
        isFaceDetected = false, isAttentive = false,
        isLookingDown  = false, isLookingUp  = false,
        isLookingLeft  = false, isLookingRight = false,
        isBlinking     = false, yaw = 0f, pitch = 0f, eyeOpenness = 0f,
        isNodLeft = false, isNodRight = false, isNodUp = false, isNodDown = false,
        detectedHandGesture = "NONE", isSwipeLeft = false, isSwipeRight = false, isSwipeUp = false, isSwipeDown = false
    )

    private fun startBackgroundThread() {
        cameraThread  = HandlerThread("CameraBackground").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopBackgroundThread() {
        val threadToQuit = cameraThread
        cameraThread  = null
        cameraHandler = null

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                threadToQuit?.quitSafely()
                threadToQuit?.join()
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down camera thread: ${e.message}")
            }
        }, 500)
    }

    private fun startCameraProcessing() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var frontCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars  = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId     = id
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
                    break
                }
            }

            if (frontCameraId == null) {
                startFallbackHeuristicSimulator()
                return
            }

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                startFallbackHeuristicSimulator()
                return
            }

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    startFallbackHeuristicSimulator()
                }
            }, cameraHandler)

        } catch (e: Exception) {
            startFallbackHeuristicSimulator()
        }
    }

    private fun createCameraPreviewSession() {
        val device = cameraDevice ?: return
        try {
            imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) processCameraImage(image)
            }, cameraHandler)

            val surface        = imageReader!!.surface
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply { addTarget(surface) }

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start repeating request: ${e.message}")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed.")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(surface)),
                        Executors.newSingleThreadExecutor(),
                        sessionCallback
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface), sessionCallback, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create preview session: ${e.message}")
        }
    }

    private fun processCameraImage(image: Image) {
        val now = SystemClock.uptimeMillis()
        if (now - lastProcessedFrameTime < adaptiveFrameThrottleMs) {
            image.close(); return
        }
        lastProcessedFrameTime = now
        frameCount++

        try {
            val bitmap  = yuvToBitmap(image)
            image.close()

            val mpImage = BitmapImageBuilder(bitmap).build()
            val opts    = ImageProcessingOptions.builder()
                .setRotationDegrees(sensorOrientation)
                .build()

            faceLandmarker?.detectAsync(mpImage, opts, now)
            handLandmarker?.detectAsync(mpImage, opts, now)
        } catch (e: Exception) {
            image.close()
        }
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        val planes      = image.planes
        val yBuffer     = planes[0].buffer
        val uBuffer     = planes[1].buffer
        val vBuffer     = planes[2].buffer
        val yRowStride  = planes[0].rowStride
        val uRowStride  = planes[1].rowStride
        val vRowStride  = planes[2].rowStride
        val uPixStride  = planes[1].pixelStride
        val vPixStride  = planes[2].pixelStride
        val w = image.width
        val h = image.height

        val size = w * h
        if (cachedPixels == null || cachedPixels!!.size != size) {
            cachedPixels = IntArray(size)
        }
        val pixels = cachedPixels!!

        for (y in 0 until h) {
            val yRowOff  = y * yRowStride
            val uvRowIdx = y shr 1
            val uRowOff  = uvRowIdx * uRowStride
            val vRowOff  = uvRowIdx * vRowStride
            val pixelRowOff = y * w

            for (x in 0 until w) {
                val uvColIdx = x shr 1
                val uOffset = uRowOff + uvColIdx * uPixStride
                val vOffset = vRowOff + uvColIdx * vPixStride
                val yv = yBuffer.get(yRowOff + x).toInt() and 0xFF
                val uv = (uBuffer.get(uOffset).toInt() and 0xFF) - 128
                val vv = (vBuffer.get(vOffset).toInt() and 0xFF) - 128

                val r = (yv + 1.402f  * vv).toInt().coerceIn(0, 255)
                val g = (yv - 0.34414f * uv - 0.71414f * vv).toInt().coerceIn(0, 255)
                val b = (yv + 1.772f  * uv).toInt().coerceIn(0, 255)

                pixels[pixelRowOff + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun stopCameraProcessing() {
        try { captureSession?.stopRepeating(); captureSession?.close() }
        catch (e: Exception) { }
        captureSession = null

        try { cameraDevice?.close() }
        catch (e: Exception) { }
        cameraDevice = null

        try { imageReader?.close() }
        catch (e: Exception) { }
        imageReader = null

        stopBackgroundThread()
    }

    private var simulatorHandler: Handler? = null
    private var simulatorTickCount = 0

    private fun startFallbackHeuristicSimulator() {
        simulatorHandler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                if (!isServiceRunning) return

                simulatorTickCount++
                val cycle = simulatorTickCount % 30

                var isLookingDown = false
                var isLookingUp   = false
                var isLookingLeft = false
                var isLookingRight = false
                var isBlinking    = false
                var isNodLeft     = false
                var isNodRight    = false
                var isNodUp       = false
                var isNodDown     = false
                var isAttentive   = true
                var yaw           = 0f
                var pitch         = 0f
                var eyeOpenness   = 100f

                when (cycle) {
                    in 4..7  -> { isLookingDown = true; pitch =  0.25f }
                    in 10..13 -> { isLookingUp   = true; pitch = -0.05f }
                    in 14..15 -> { isLookingLeft = true; yaw = -0.25f }
                    16       -> { isNodLeft  = true; yaw =  0.15f }
                    18       -> { isNodRight = true; yaw = -0.15f }
                    in 19..20 -> { isLookingRight = true; yaw = 0.25f }
                    21       -> { isNodDown  = true; pitch =  0.10f }
                    23       -> { isNodUp    = true; pitch = -0.10f }
                    25       -> { isBlinking = true; eyeOpenness = 0f }
                    in 27..29 -> { isAttentive = false; yaw = 0.35f }
                }

                val mockState = GazeState(
                    isFaceDetected = true, isAttentive = isAttentive,
                    isLookingDown  = isLookingDown, isLookingUp = isLookingUp,
                    isLookingLeft  = isLookingLeft, isLookingRight = isLookingRight,
                    isBlinking     = isBlinking,
                    yaw = yaw, pitch = pitch, eyeOpenness = eyeOpenness,
                    isNodLeft  = isNodLeft,  isNodRight = isNodRight,
                    isNodUp    = isNodUp,    isNodDown  = isNodDown
                )

                publishGazeState(mockState)
                handleGazeStateAction(mockState)

                simulatorHandler?.postDelayed(this, 500)
            }
        }

        simulatorHandler?.post(runnable)
    }

    private fun loadPersonalizedProfile(context: Context) {
        try {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val profileJson = prefs.getString("flutter.user_calibration_profile", null)
            if (profileJson != null) {
                val json = org.json.JSONObject(profileJson)
                val neutralVec = json.getJSONArray("neutral_vector")
                val neutralX = neutralVec.getDouble(0).toFloat()
                val neutralY = neutralVec.getDouble(1).toFloat()

                yawBaseline = neutralX
                pitchBaseline = neutralY
                baselineInitialized = true

                if (json.has("top_threshold")) topThreshold = json.getDouble("top_threshold").toFloat()
                if (json.has("bottom_threshold")) bottomThreshold = json.getDouble("bottom_threshold").toFloat()
                if (json.has("left_threshold")) leftThreshold = json.getDouble("left_threshold").toFloat()
                if (json.has("right_threshold")) rightThreshold = json.getDouble("right_threshold").toFloat()
                if (json.has("vertical_bias")) verticalBias = json.getDouble("vertical_bias").toFloat()
                if (json.has("horizontal_bias")) horizontalBias = json.getDouble("horizontal_bias").toFloat()
                if (json.has("deadzone_radius")) deadzoneRadius = json.getDouble("deadzone_radius").toFloat()

                if (json.has("calibration_matrix")) {
                    val mat = json.getJSONArray("calibration_matrix")
                    for (i in 0 until 6) {
                        calibrationMatrix[i] = mat.getDouble(i).toFloat()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load personalized calibration baseline: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. EYE CROP STABILIZER (Affine Alignment & Temporal Box Smoothing)
    // ─────────────────────────────────────────────────────────────────────────
    inner class CropStabilizer {
        private var prevRectLeft = RectF()
        private var prevRectRight = RectF()
        private val smoothingAlpha = 0.25f 

        fun getStabilizedEyeBoundingBox(face: List<NormalizedLandmark>, isLeft: Boolean, width: Int, height: Int): RectF {
            val indices = if (isLeft) {
                intArrayOf(33, 133, 159, 145, 160, 158, 153, 144)
            } else {
                intArrayOf(263, 362, 386, 374, 385, 387, 373, 380)
            }

            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE

            for (idx in indices) {
                if (idx < face.size) {
                    val lm = face[idx]
                    val x = lm.x() * width
                    val y = lm.y() * height
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }

            val rawW = maxX - minX
            val rawH = maxY - minY
            val paddingX = rawW * 0.30f
            val paddingY = rawH * 0.30f

            val targetRect = RectF(
                (minX - paddingX).coerceAtLeast(0f),
                (minY - paddingY).coerceAtLeast(0f),
                (maxX + paddingX).coerceAtMost(width.toFloat()),
                (maxY + paddingY).coerceAtMost(height.toFloat())
            )

            val prevRect = if (isLeft) prevRectLeft else prevRectRight
            if (prevRect.isEmpty) {
                if (isLeft) prevRectLeft = targetRect else prevRectRight = targetRect
                return targetRect
            }

            val stableRect = RectF(
                prevRect.left * (1f - smoothingAlpha) + targetRect.left * smoothingAlpha,
                prevRect.top * (1f - smoothingAlpha) + targetRect.top * smoothingAlpha,
                prevRect.right * (1f - smoothingAlpha) + targetRect.right * smoothingAlpha,
                prevRect.bottom * (1f - smoothingAlpha) + targetRect.bottom * smoothingAlpha
            )

            if (isLeft) prevRectLeft = stableRect else prevRectRight = stableRect
            return stableRect
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. CONFIDENCE ESTIMATION SYSTEM
    // ─────────────────────────────────────────────────────────────────────────
    inner class ConfidenceEngine {
        fun calculateConfidence(
            face: List<NormalizedLandmark>,
            isBlinking: Boolean,
            gyroMagnitude: Float,
            headYaw: Float,
            headPitch: Float
        ): Float {
            if (isBlinking) return 0.05f

            var landmarkScore = 1.0f
            val criticalIndices = intArrayOf(33, 133, 263, 362, 1, 10, 152)
            var validPoints = 0
            for (idx in criticalIndices) {
                if (idx < face.size) {
                    val lm = face[idx]
                    if (lm.x() in 0.0f..1.0f && lm.y() in 0.0f..1.0f) {
                        validPoints++
                    }
                }
            }
            landmarkScore = validPoints.toFloat() / criticalIndices.size.toFloat()

            val tiltPenalty = (Math.abs(headYaw) * 0.4f + Math.abs(headPitch) * 0.4f).coerceAtMost(0.3f)
            val motionPenalty = (gyroMagnitude * 0.07f).coerceAtMost(0.4f)

            return (landmarkScore - tiltPenalty - motionPenalty).coerceIn(0f, 1f)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. GAZE STATE MACHINE
    // ─────────────────────────────────────────────────────────────────────────
    inner class GazeStateMachine {
        var currentState = GazeStateEnum.SEARCHING
            private set
        private var stableLockStartTime = 0L

        fun updateState(newState: GazeStateEnum) {
            if (currentState != newState) {
                currentState = newState
            }
        }

        fun evaluateState(confidence: Float, isLookingAway: Boolean, isBlinking: Boolean) {
            val now = System.currentTimeMillis()
            when (currentState) {
                GazeStateEnum.NO_FACE -> {
                    if (confidence > 0.45f) currentState = GazeStateEnum.SEARCHING
                }
                GazeStateEnum.SEARCHING -> {
                    if (confidence > 0.65f && !isLookingAway && !isBlinking) {
                        currentState = GazeStateEnum.TRACKING
                    } else if (confidence <= 0.35f) {
                        currentState = GazeStateEnum.NO_FACE
                    }
                }
                GazeStateEnum.TRACKING -> {
                    if (isLookingAway && !isBlinking) {
                        stableLockStartTime = now
                        currentState = GazeStateEnum.UNSTABLE
                    } else if (confidence < 0.50f) {
                        currentState = GazeStateEnum.UNSTABLE
                    } else if (confidence > 0.75f && !isLookingAway && !isBlinking) {
                        currentState = GazeStateEnum.STABLE_LOCK
                    }
                }
                GazeStateEnum.UNSTABLE -> {
                    if (confidence > 0.65f && !isLookingAway) {
                        currentState = GazeStateEnum.TRACKING
                    } else if (confidence <= 0.30f) {
                        currentState = GazeStateEnum.SEARCHING
                    }
                }
                GazeStateEnum.STABLE_LOCK -> {
                    if (isLookingAway) {
                        currentState = GazeStateEnum.ACTION_READY
                    } else if (confidence < 0.55f) {
                        currentState = GazeStateEnum.UNSTABLE
                    }
                }
                GazeStateEnum.ACTION_READY -> {
                    currentState = GazeStateEnum.COOLDOWN
                    stableLockStartTime = now
                }
                GazeStateEnum.COOLDOWN -> {
                    if (now - stableLockStartTime > 800L) {
                        currentState = GazeStateEnum.TRACKING
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. BLINK DETECTION + REJECTION
    // ─────────────────────────────────────────────────────────────────────────
    inner class BlinkDetector {
        fun detectBlink(face: List<NormalizedLandmark>, scores: Map<String, Float>): BlinkResult {
            val lEAR = if (face.size > 160) {
                val h1 = Math.sqrt(((face[160].x()-face[144].x()).let { it*it } + (face[160].y()-face[144].y()).let { it*it } + (face[160].z()-face[144].z()).let { it*it }).toDouble()).toFloat()
                val h2 = Math.sqrt(((face[158].x()-face[153].x()).let { it*it } + (face[158].y()-face[153].y()).let { it*it } + (face[158].z()-face[153].z()).let { it*it }).toDouble()).toFloat()
                val w  = Math.sqrt(((face[33].x()-face[133].x()).let { it*it } + (face[33].y()-face[133].y()).let { it*it } + (face[33].z()-face[133].z()).let { it*it }).toDouble()).toFloat()
                (h1 + h2) / (2f * w.coerceAtLeast(0.001f))
            } else 0.25f

            val rEAR = if (face.size > 387) {
                val h1 = Math.sqrt(((face[385].x()-face[380].x()).let { it*it } + (face[385].y()-face[380].y()).let { it*it } + (face[385].z()-face[380].z()).let { it*it }).toDouble()).toFloat()
                val h2 = Math.sqrt(((face[387].x()-face[373].x()).let { it*it } + (face[387].y()-face[373].y()).let { it*it } + (face[387].z()-face[373].z()).let { it*it }).toDouble()).toFloat()
                val w  = Math.sqrt(((face[263].x()-face[362].x()).let { it*it } + (face[263].y()-face[362].y()).let { it*it } + (face[263].z()-face[362].z()).let { it*it }).toDouble()).toFloat()
                (h1 + h2) / (2f * w.coerceAtLeast(0.001f))
            } else 0.25f

            val avgEAR = (lEAR + rEAR) / 2f
            val leftBlinkScore  = scores["eyeBlinkLeft"]  ?: 0f
            val rightBlinkScore = scores["eyeBlinkRight"] ?: 0f
            val avgBlinkScore   = (leftBlinkScore + rightBlinkScore) / 2f
            val eyeOpenness     = (1f - avgBlinkScore) * 100f
            
            val isBlinking      = (avgBlinkScore * 0.75f + (if (avgEAR < 0.16f) 1.0f else 0.0f) * 0.25f) > 0.72f

            return BlinkResult(isBlinking, avgBlinkScore, eyeOpenness)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. DIRECTION HYSTERESIS
    // ─────────────────────────────────────────────────────────────────────────
    inner class DirectionHysteresis {
        private var wasLookingDown = false
        private var wasLookingUp = false
        private var wasLookingLeft = false
        private var wasLookingRight = false

        fun applyHysteresis(yaw: Float, pitch: Float, sensitivity: Float): HysteresisResult {
            val baseThreshold = 0.40f - (sensitivity * 0.18f)
            
            val enterThreshold = baseThreshold * 1.15f
            val exitThreshold = baseThreshold * 0.80f

            val isLookingDown = if (wasLookingDown) {
                pitch < -exitThreshold
            } else {
                pitch < -enterThreshold
            }

            val isLookingUp = if (wasLookingUp) {
                pitch > exitThreshold
            } else {
                pitch > enterThreshold
            }

            val isLookingLeft = if (wasLookingLeft) {
                yaw > exitThreshold
            } else {
                yaw > enterThreshold
            }

            val isLookingRight = if (wasLookingRight) {
                yaw < -exitThreshold
            } else {
                yaw < -enterThreshold
            }

            wasLookingDown = isLookingDown
            wasLookingUp = isLookingUp
            wasLookingLeft = isLookingLeft
            wasLookingRight = isLookingRight

            return HysteresisResult(isLookingDown, isLookingUp, isLookingLeft, isLookingRight)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. ACCESSIBILITY SAFETY LAYER & ADAPTIVE SMOOTHING
    // ─────────────────────────────────────────────────────────────────────────
    inner class AccessibilitySafetyEngine {
        private var lastActionTime = 0L
        private var smoothedYaw = 0f
        private var smoothedPitch = 0f

        fun allowAction(now: Long, minIntervalMs: Long): Boolean {
            if (now - lastActionTime < minIntervalMs) {
                return false
            }
            return true
        }

        fun recordAction(now: Long) {
            lastActionTime = now
        }

        fun applyAdaptiveSmoothing(rawX: Float, rawY: Float, confidence: Float, now: Long): Pair<Float, Float> {
            val baseAlpha = 0.35f
            val confidenceWeight = confidence * 0.40f
            val alpha = (baseAlpha + confidenceWeight).coerceIn(0.12f, 0.85f)

            smoothedYaw = smoothedYaw * (1f - alpha) + rawX * alpha
            smoothedPitch = smoothedPitch * (1f - alpha) + rawY * alpha

            return Pair(smoothedYaw, smoothedPitch)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. THERMAL & PERFORMANCE MANAGER (Dynamic FPS and Throttling)
    // ─────────────────────────────────────────────────────────────────────────
    inner class ThermalPerformanceManager {
        fun getThrottleMs(confidence: Float, state: GazeStateEnum): Long {
            return when {
                state == GazeStateEnum.NO_FACE -> 200L 
                state == GazeStateEnum.UNSTABLE -> 100L 
                confidence < 0.50f -> 66L 
                else -> 33L 
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. LONG-SESSION DRIFT CORRECTION
    // ─────────────────────────────────────────────────────────────────────────
    inner class DriftCorrectionManager {
        private var accumulatedDriftYaw = 0f
        private var accumulatedDriftPitch = 0f
        private val driftDamping = 0.002f 

        fun applyDriftCorrection(rawYaw: Float, rawPitch: Float) {
            accumulatedDriftYaw = accumulatedDriftYaw * (1f - driftDamping) + rawYaw * driftDamping
            accumulatedDriftPitch = accumulatedDriftPitch * (1f - driftDamping) + rawPitch * driftDamping
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. PER-APP INTERACTION PROFILES
    // ─────────────────────────────────────────────────────────────────────────
    inner class InteractionProfileManager {
        fun getProfile(packageName: String): AppInteractionProfile {
            val lower = packageName.lowercase()
            return when {
                lower.contains("youtube") || lower.contains("instagram") || lower.contains("tiktok") -> {
                    AppInteractionProfile(AppGestureConfig(0.82f, 1400L), 250L)
                }
                lower.contains("chrome") || lower.contains("pdf") || lower.contains("reader") -> {
                    AppInteractionProfile(AppGestureConfig(0.50f, 700L), 450L)
                }
                else -> {
                    AppInteractionProfile(AppGestureConfig(0.70f, 800L), 350L)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ONNX Reflective Runner
    // ─────────────────────────────────────────────────────────────────────────
    class OnnxReflectiveRunner(private val context: Context, private val modelPath: String) {
        companion object {
            private val dummyEnv: Class<*> = ai.onnxruntime.OrtEnvironment::class.java
            private val dummySession: Class<*> = ai.onnxruntime.OrtSession::class.java
            private val dummyTensor: Class<*> = ai.onnxruntime.OnnxTensor::class.java
        }
        private var env: Any? = null
        private var session: Any? = null
        private var isInitialized = false

        private fun getReflectiveClass(className: String): Class<*> {
            val classLoaders = listOfNotNull(
                Thread.currentThread().contextClassLoader,
                context.classLoader,
                OnnxReflectiveRunner::class.java.classLoader,
                GazeForegroundService::class.java.classLoader
            )
            for (cl in classLoaders) {
                try {
                    return Class.forName(className, true, cl)
                } catch (ignored: Throwable) {
                    // Try next classloader
                }
            }
            // Final fallback to default classloader
            return Class.forName(className)
        }

        init {
            try {
                Log.i("OnnxReflective", "Initializing OrtEnvironment reflectively...")
                val envClass = getReflectiveClass("ai.onnxruntime.OrtEnvironment")
                val getEnvMethod = envClass.getMethod("getEnvironment")
                env = getEnvMethod.invoke(null)
                Log.i("OnnxReflective", "OrtEnvironment initialized successfully.")

                val sessionOptionsClass = getReflectiveClass("ai.onnxruntime.OrtSession\$SessionOptions")
                val sessionOptions = sessionOptionsClass.getDeclaredConstructor().newInstance()
                
                try {
                    val addNnapiMethod = sessionOptionsClass.getMethod("addNnapi")
                    addNnapiMethod.invoke(sessionOptions)
                    Log.i("OnnxReflective", "NNAPI enabled reflectively.")
                } catch (e: Throwable) {
                    Log.w("OnnxReflective", "NNAPI not available reflectively or failed to bind. Defaulting to CPU.", e)
                }

                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    throw java.io.FileNotFoundException("Model file does not exist at path: $modelPath")
                }
                if (!modelFile.canRead()) {
                    throw java.io.IOException("Model file is not readable at path: $modelPath")
                }
                Log.i("OnnxReflective", "Model file validated: Path=${modelFile.absolutePath}, Size=${modelFile.length()} bytes")

                val envClassInstance = envClass.cast(env)
                val createSessionMethod = envClass.getMethod("createSession", String::class.java, sessionOptionsClass)
                
                Log.i("OnnxReflective", "Creating OrtSession with model: $modelPath...")
                session = createSessionMethod.invoke(envClassInstance, modelPath, sessionOptions)
                isInitialized = true
                Log.i("OnnxReflective", "Reflective ONNX Session successfully created.")
            } catch (e: Throwable) {
                Log.e("OnnxReflective", "Reflective ONNX initialization failed", e)
            }
        }

        private fun calculateExpectation(logits: FloatBuffer): Float {
            val size = 90
            val raw = FloatArray(size)
            logits.rewind()
            logits.get(raw)

            var maxVal = Float.NEGATIVE_INFINITY
            for (v in raw) {
                if (v > maxVal) maxVal = v
            }

            var sumExp = 0.0f
            val expValues = FloatArray(size)
            for (i in 0 until size) {
                expValues[i] = Math.exp((raw[i] - maxVal).toDouble()).toFloat()
                sumExp += expValues[i]
            }

            var expectation = 0.0f
            for (i in 0 until size) {
                val prob = expValues[i] / (if (sumExp == 0f) 1f else sumExp)
                expectation += prob * i
            }

            val angleDeg = expectation * 4.0f - 180.0f
            val angleRad = angleDeg * (Math.PI.toFloat() / 180.0f)
            return angleRad
        }

        fun runInference(floatBuffer: FloatBuffer, shape: LongArray): Pair<Float, Float>? {
            if (!isInitialized || env == null || session == null) {
                Log.w("OnnxReflective", "Cannot run inference: OnnxReflectiveRunner is not fully initialized.")
                return null
            }
            try {
                val onnxTensorClass = getReflectiveClass("ai.onnxruntime.OnnxTensor")
                val ortEnvClass = getReflectiveClass("ai.onnxruntime.OrtEnvironment")
                val createTensorMethod = onnxTensorClass.getMethod(
                    "createTensor", 
                    ortEnvClass, 
                    FloatBuffer::class.java, 
                    LongArray::class.java
                )
                
                // Create Tensor and map it
                val inputTensor = createTensorMethod.invoke(null, env, floatBuffer, shape)
                
                // Get input name dynamically
                val sessionClass = getReflectiveClass("ai.onnxruntime.OrtSession")
                val getInputNamesMethod = sessionClass.getMethod("getInputNames")
                val inputNames = getInputNamesMethod.invoke(session) as Set<*>
                val inputName = (inputNames.firstOrNull() as? String) ?: "input"
                
                val inputMap = mapOf(inputName to inputTensor)
                val runMethod = sessionClass.getMethod("run", Map::class.java)
                
                val outputs = runMethod.invoke(session, inputMap)

                val resultClass = getReflectiveClass("ai.onnxruntime.OrtSession\$Result")
                val sizeMethod = resultClass.getMethod("size")
                val getMethod = resultClass.getMethod("get", Int::class.java)

                val size = sizeMethod.invoke(outputs) as Int
                if (size >= 2) {
                    val outValYaw = getMethod.invoke(outputs, 0)
                    val outValPitch = getMethod.invoke(outputs, 1)

                    if (onnxTensorClass.isInstance(outValYaw) && onnxTensorClass.isInstance(outValPitch)) {
                        val getFloatBufferMethod = onnxTensorClass.getMethod("getFloatBuffer")
                        
                        val outputDataYaw = getFloatBufferMethod.invoke(outValYaw) as FloatBuffer
                        val outputDataPitch = getFloatBufferMethod.invoke(outValPitch) as FloatBuffer
                        
                        val yaw = calculateExpectation(outputDataYaw)
                        val pitch = calculateExpectation(outputDataPitch)

                        val closeResultMethod = resultClass.getMethod("close")
                        closeResultMethod.invoke(outputs)

                        val closeTensorMethod = onnxTensorClass.getMethod("close")
                        closeTensorMethod.invoke(inputTensor)

                        return Pair(pitch, yaw)
                    } else {
                        Log.e("OnnxReflective", "Output elements are not instances of OnnxTensor")
                    }
                } else if (size == 1) {
                    val outVal = getMethod.invoke(outputs, 0)
                    if (onnxTensorClass.isInstance(outVal)) {
                        val getFloatBufferMethod = onnxTensorClass.getMethod("getFloatBuffer")
                        val outputData = getFloatBufferMethod.invoke(outVal) as FloatBuffer
                        outputData.rewind()
                        val remaining = outputData.remaining()

                        if (remaining >= 90) {
                            val yaw = calculateExpectation(outputData)
                            val pitch = if (outputData.remaining() >= 90) calculateExpectation(outputData) else 0f
                            
                            val closeResultMethod = resultClass.getMethod("close")
                            closeResultMethod.invoke(outputs)

                            val closeTensorMethod = onnxTensorClass.getMethod("close")
                            closeTensorMethod.invoke(inputTensor)

                            return Pair(pitch, yaw)
                        } else {
                            // Fallback to legacy single-output raw values if not 90-bin logits
                            val pitch = if (remaining > 0) outputData.get() else 0f
                            val yaw = if (remaining > 1) outputData.get() else 0f

                            val closeResultMethod = resultClass.getMethod("close")
                            closeResultMethod.invoke(outputs)

                            val closeTensorMethod = onnxTensorClass.getMethod("close")
                            closeTensorMethod.invoke(inputTensor)

                            return Pair(pitch, yaw)
                        }
                    }
                } else {
                    Log.e("OnnxReflective", "Session returned empty outputs")
                }
                
                val closeTensorMethod = onnxTensorClass.getMethod("close")
                closeTensorMethod.invoke(inputTensor)
            } catch (e: Throwable) {
                Log.e("OnnxReflective", "Reflective ONNX runtime run failed", e)
            }
            return null
        }

        fun close() {
            try {
                if (session != null) {
                    val sessionClass = getReflectiveClass("ai.onnxruntime.OrtSession")
                    sessionClass.getMethod("close").invoke(session)
                    session = null
                }
                if (env != null) {
                    val envClass = getReflectiveClass("ai.onnxruntime.OrtEnvironment")
                    envClass.getMethod("close").invoke(env)
                    env = null
                }
                isInitialized = false
                Log.i("OnnxReflective", "Reflective runner resources closed successfully.")
            } catch (e: Throwable) {
                Log.e("OnnxReflective", "Error closing reflective runner", e)
            }
        }
    }
}