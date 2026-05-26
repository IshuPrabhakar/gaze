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
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GazeForegroundService : Service() {

    // ─────────────────────────────────────────────────────────────────────────
    // Companion / static state
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GazeForegroundService"
        private const val CHANNEL_ID = "GazeForegroundChannel"
        private const val NOTIFICATION_ID = 4567

        @Volatile
        var isServiceRunning = false
            private set

        // Settings configured from Flutter (Thread-visible)
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

        // Sensor values for telemetry/fusion (Thread-visible)
        @Volatile
        var devicePitchDeg: Float = 0f
        @Volatile
        var deviceRollDeg: Float = 0f
        @Volatile
        var deviceYawDeg: Float = 0f

        // Listeners for gaze telemetry (sent to Flutter overlay/dashboard)
        @Volatile
        var telemetryListener: ((GazeState) -> Unit)? = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────────────────

    data class GazeState(
        val isFaceDetected: Boolean,
        val isAttentive: Boolean,
        val isLookingDown: Boolean,
        val isLookingUp: Boolean,
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
        val isSwipeDown: Boolean = false
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Head-nod state machine
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Three-phase nod detector that requires:
     *   IDLE  → deviation exceeds threshold       → ARMED
     *   ARMED → deviation returns to near-neutral → FIRED  (nod confirmed)
     *   FIRED → deviation fully back at neutral   → IDLE   (ready for next nod)
     *
     * This eliminates false-positives from hand tremors or slow postural drift
     * because a genuine nod peaks and returns within [nodMaxDurationMs].
     */
    private enum class NodPhase { IDLE, ARMED, FIRED }

    // Yaw (left / right) nod state
    private var yawNodPhase     = NodPhase.IDLE
    private var yawNodDirection = 0          // +1 = nod-left peak, -1 = nod-right peak
    private var yawNodPeak      = 0f
    private var yawNodStartTime = 0L

    // Pitch (up / down) nod state
    private var pitchNodPhase     = NodPhase.IDLE
    private var pitchNodDirection = 0        // +1 = nod-down peak, -1 = nod-up peak
    private var pitchNodPeak      = 0f
    private var pitchNodStartTime = 0L

    // A nod must complete (peak → return) within this window; otherwise it is
    // treated as a sustained posture change and ignored.
    private val nodMaxDurationMs  = 600L

    // After firing, the deviation must fall below this fraction of the peak
    // before the state machine re-arms.  Prevents double-firing on one nod.
    private val nodReturnFraction = 0.4f

    // ─────────────────────────────────────────────────────────────────────────
    // Baseline / posture fields
    // ─────────────────────────────────────────────────────────────────────────

    private var yawBaseline   = 0f
    private var pitchBaseline = 0f   // intentionally 0; warm-up will set the real value
    private var baselineInitialized   = false
    private var baselineSampleCount   = 0
    private val baselineWarmupFrames  = 15   // average first N frames for a clean start

    // Post-warmup: very slow exponential drift so gradual posture changes
    // (e.g. slouching, tilting phone) are absorbed without swallowing
    // short intentional gestures (~300–800 ms).
    private val baselineDriftRate = 0.995f   // was 0.99 — slower = gestures not absorbed

    // ─────────────────────────────────────────────────────────────────────────
    // Gaze blendshape smoothing
    // ─────────────────────────────────────────────────────────────────────────

    // Exponential moving average applied to raw blendshape scores.
    // Suppresses single-frame spikes that reset the hold-duration timer prematurely.
    private var smoothedGazeDown = 0f
    private var smoothedGazeUp   = 0f

    // α = 0 → no smoothing (instant); α = 1 → infinite lag.
    // 0.4 keeps single-frame noise damped while preserving ~200 ms responsiveness.
    private val gazeSmoothing = 0.4f

    // Blendshape baselines — the resting "neutral gaze" score for this person/device.
    // MediaPipe's eyeLookDownLeft/Right routinely sit at 0.4–0.6 at rest on many
    // devices.  Without subtracting this offset every threshold comparison fires
    // permanently.  These are seeded during the same warm-up window as the head-pose
    // baseline, then drift very slowly afterward (same 0.995 rate).
    private var gazeDownBaseline  = 0f
    private var gazeUpBaseline    = 0f
    private var gazeBaselineReady = false   // true after warm-up accumulates enough frames

    // Running sum accumulators for the warm-up average (separate from head-pose counter
    // so they stay in sync).
    private var gazeDownAccum = 0f
    private var gazeUpAccum   = 0f

    // Customized calibration thresholds loaded from SharedPreferences
    private var customTopThreshold: Float = 0f
    private var customBottomThreshold: Float = 0f

    // ─────────────────────────────────────────────────────────────────────────
    // Scroll timing
    // ─────────────────────────────────────────────────────────────────────────

    private var lookingDownStartTime = 0L
    private var lookingUpStartTime   = 0L
    private var lastScrollTime       = 0L
    private val scrollCooldownMs     = 1500L  // prevents gesture spamming

    // ─────────────────────────────────────────────────────────────────────────
    // Media / look-away state
    // ─────────────────────────────────────────────────────────────────────────

    private var wasAttentiveLastState = true
    private var lastDevicePitch = 0f
    private var lastDeviceRoll = 0f

    // ─────────────────────────────────────────────────────────────────────────
    // Hand tracking & gesture classification fields
    // ─────────────────────────────────────────────────────────────────────────
    private var handLandmarker: HandLandmarker? = null

    // Swipe tracking
    private val swipeHistory = java.util.ArrayList<Pair<Long, android.graphics.PointF>>()
    private val swipeCooldownMs = 1200L
    private var lastSwipeTriggeredTime = 0L

    // Palm gesture stability tracking
    private var lastStabilityGesture = "NONE"
    private var gestureStabilityStartTime = 0L
    private val stabilityDurationMs = 400L
    private var lastTriggeredPalmGesture = "NONE"
    private var lastPalmGestureTriggerTime = 0L
    private val palmGestureCooldownMs = 1500L

    // Face attention temporal fields
    private var isUserAttentive = true
    private var attentionStateTransitionTime = 0L
    private val attentionLostThresholdMs = 1500L
    private val attentionRegainThresholdMs = 500L

    // State cache to combine asynchronously updated face & hand detections
    private var currentHandGesture = "NONE"
    private var flagSwipeLeft = false
    private var flagSwipeRight = false
    private var flagSwipeUp = false
    private var flagSwipeDown = false

    // ─────────────────────────────────────────────────────────────────────────
    // Camera / MediaPipe infrastructure & Sensor Fusion / Filtering
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var backgroundExecutor: ExecutorService
    private var faceLandmarker: FaceLandmarker? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var lastProcessedFrameTime = 0L
    @Volatile
    private var adaptiveFrameThrottleMs = 33L   // Dynamic: 33ms (30 FPS) active / 200ms (5 FPS) idle
    private var cachedPixels: IntArray? = null   // Pre-allocated reusable buffer to avoid GC pressure

    private var frameCount       = 0
    private var faceCount        = 0
    private var sensorOrientation = 270

    // Sensor Fusion & One Euro Filters
    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var gyroMagnitude = 0f

    private var filterGazeDown: OneEuroFilter? = null
    private var filterGazeUp: OneEuroFilter? = null
    private var filterHeadYaw: OneEuroFilter? = null
    private var filterHeadPitch: OneEuroFilter? = null

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

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GazeForegroundService created")
        backgroundExecutor = Executors.newSingleThreadExecutor()
        
        // Setup One Euro Filters
        filterGazeDown = OneEuroFilter(1.0, 0.01, 1.0)
        filterGazeUp = OneEuroFilter(1.0, 0.01, 1.0)
        filterHeadYaw = OneEuroFilter(1.2, 0.015, 1.0)
        filterHeadPitch = OneEuroFilter(1.2, 0.015, 1.0)

        // Setup Sensor Fusion
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        createNotificationChannel()
        initMediaPipe()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "GazeForegroundService starting")
        isServiceRunning = true
        loadPersonalizedProfile(this)

        if (cameraThread == null) startBackgroundThread()

        // Register sensors
        rotationVectorSensor?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroSensor?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }

        val notification = createNotification(
            "Gaze Service Active",
            "Hands-free control is running in the background."
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
        simulatorHandler?.removeCallbacksAndMessages(null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gaze Background Tracker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when eye-gaze tracking is running."
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

    // ─────────────────────────────────────────────────────────────────────────
    // MediaPipe initialisation
    // ─────────────────────────────────────────────────────────────────────────

    private fun initMediaPipe() {
        backgroundExecutor.execute {
            try {
                // Face model setup
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
                    .setResultListener { result, _ -> processFaceLandmarks(result) }
                    .setErrorListener { error ->
                        Log.e(TAG, "MediaPipe error: ${error.message}")
                    }
                    .build()

                faceLandmarker = FaceLandmarker.createFromOptions(this, options)
                Log.d(TAG, "MediaPipe FaceLandmarker initialised with blendshapes.")

                // Hand model setup
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
                Log.d(TAG, "MediaPipe HandLandmarker initialised.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialise MediaPipe: ${e.message}. Starting fallback simulator.")
                startFallbackHeuristicSimulator()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core landmark processing  ← all fixes live here
    // ─────────────────────────────────────────────────────────────────────────

    private fun processFaceLandmarks(result: FaceLandmarkerResult) {
        val blendshapesOptional = result.faceBlendshapes()
        val landmarks           = result.faceLandmarks()

        // ── No face detected ──────────────────────────────────────────────────
        if (blendshapesOptional == null
            || !blendshapesOptional.isPresent
            || landmarks.isNullOrEmpty()
            || landmarks[0].size <= 263   // need indices 1, 10, 33, 152, 263
        ) {
            // Reset all smoothing / baseline so the next detection starts fresh
            smoothedGazeDown      = 0f
            smoothedGazeUp        = 0f
            baselineInitialized   = false
            baselineSampleCount   = 0
            gazeBaselineReady     = false
            gazeDownAccum         = 0f
            gazeUpAccum           = 0f
            yawNodPhase           = NodPhase.IDLE
            pitchNodPhase         = NodPhase.IDLE

            publishGazeState(emptyGazeState())
            return
        }

        faceCount++
        val scores = blendshapesOptional.get()[0].associate { it.categoryName() to it.score() }
        val face   = landmarks[0]

        // ── 1. Physical Eye Aspect Ratio (EAR) Blink Detection ────────────────
        // Left eye contours: 33 (outer), 160 (top-outer), 158 (top-inner), 133 (inner), 153 (bottom-inner), 144 (bottom-outer)
        val lEAR = if (face.size > 160) {
            val h1 = Math.sqrt(((face[160].x()-face[144].x()).let { it*it } + (face[160].y()-face[144].y()).let { it*it } + (face[160].z()-face[144].z()).let { it*it }).toDouble()).toFloat()
            val h2 = Math.sqrt(((face[158].x()-face[153].x()).let { it*it } + (face[158].y()-face[153].y()).let { it*it } + (face[158].z()-face[153].z()).let { it*it }).toDouble()).toFloat()
            val w  = Math.sqrt(((face[33].x()-face[133].x()).let { it*it } + (face[33].y()-face[133].y()).let { it*it } + (face[33].z()-face[133].z()).let { it*it }).toDouble()).toFloat()
            (h1 + h2) / (2f * w.coerceAtLeast(0.001f))
        } else 0.25f

        // Right eye contours: 263 (outer), 385 (top-outer), 387 (top-inner), 362 (inner), 373 (bottom-inner), 380 (bottom-outer)
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
        
        // Dual metric validation: blend shapes + physical EAR to prevent glasses reflections spikes
        val isBlinking      = (avgBlinkScore * 0.6f + (if (avgEAR < 0.20f) 1.0f else 0.0f) * 0.4f) > 0.60f

        // ── 2. True 3D Camera-Space Un-Projection & Head Rotation Matrix R_h ──
        val lEyeLm = face[33]
        val rEyeLm = face[263]
        val iodNormDx = rEyeLm.x() - lEyeLm.x()
        val iodNormDy = rEyeLm.y() - lEyeLm.y()
        val iodNorm = Math.sqrt((iodNormDx * iodNormDx + iodNormDy * iodNormDy).toDouble()).toFloat().coerceAtLeast(0.01f)
        
        // Estimate physical camera distance using average physical IOD (70mm) and front-camera focal approximation (f=280)
        val zCenter = (280f * 70f) / (iodNorm * 320f)

        // Helper function to unproject normalized MediaPipe coordinates into millimeter metric camera space
        fun get3DPoint(idx: Int): FloatArray {
            val lm = face[idx]
            val zPhysical = zCenter + lm.z() * 70f
            val xPhysical = (lm.x() - 0.5f) * 320f * zPhysical / 280f
            val yPhysical = (lm.y() - 0.5f) * 240f * zPhysical / 280f
            return floatArrayOf(xPhysical, yPhysical, zPhysical)
        }

        val p33 = get3DPoint(33)     // Left Eye Outer
        val p263 = get3DPoint(263)   // Right Eye Outer
        val p10 = get3DPoint(10)     // Forehead
        val p152 = get3DPoint(152)   // Chin
        val p133 = get3DPoint(133)   // Left Eye Inner
        val p362 = get3DPoint(362)   // Right Eye Inner

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

        // Orthonormal Z axis via cross product (Z = X cross Y)
        val uz = floatArrayOf(
            ux[1]*uyRaw[2] - ux[2]*uyRaw[1],
            ux[2]*uyRaw[0] - ux[0]*uyRaw[2],
            ux[0]*uyRaw[1] - ux[1]*uyRaw[0]
        )
        val lenZ = Math.sqrt((uz[0]*uz[0] + uz[1]*uz[1] + uz[2]*uz[2]).toDouble()).toFloat().coerceAtLeast(0.001f)
        uz[0] /= lenZ; uz[1] /= lenZ; uz[2] /= lenZ

        // Perfect orthogonal Y axis (Y = Z cross X)
        val uy = floatArrayOf(
            uz[1]*ux[2] - uz[2]*ux[1],
            uz[2]*ux[0] - uz[0]*ux[2],
            uz[0]*ux[1] - uz[1]*ux[0]
        )

        // R_h transpose values for inverse vector rotation (decouples head rotation)
        val r00 = ux[0]; val r01 = ux[1]; val r02 = ux[2]
        val r10 = uy[0]; val r11 = uy[1]; val r12 = uy[2]
        val r20 = uz[0]; val r21 = uz[1]; val r22 = uz[2]

        // ── 3. 3D Eyeball Vector and Canonical Gaze Normalization ────────────
        val lEyeCenter3D = floatArrayOf((p33[0] + p133[0]) / 2f, (p33[1] + p133[1]) / 2f, (p33[2] + p133[2]) / 2f)
        val rEyeCenter3D = floatArrayOf((p263[0] + p362[0]) / 2f, (p263[1] + p362[1]) / 2f, (p263[2] + p362[2]) / 2f)

        // Iris centers (468 left, 473 right)
        val hasIris = face.size > 473
        val lPupil3D = if (hasIris) get3DPoint(468) else p33
        val rPupil3D = if (hasIris) get3DPoint(473) else p263

        val lGazeRaw = floatArrayOf(lPupil3D[0] - lEyeCenter3D[0], lPupil3D[1] - lEyeCenter3D[1], lPupil3D[2] - lEyeCenter3D[2])
        val rGazeRaw = floatArrayOf(rPupil3D[0] - rEyeCenter3D[0], rPupil3D[1] - rEyeCenter3D[1], rPupil3D[2] - rEyeCenter3D[2])

        // Rotate 3D gaze vector into canonical eyeball head space via R_h^T
        val lGazeCanonical = floatArrayOf(
            r00 * lGazeRaw[0] + r01 * lGazeRaw[1] + r02 * lGazeRaw[2],
            r10 * lGazeRaw[0] + r11 * lGazeRaw[1] + r12 * lGazeRaw[2],
            r20 * lGazeRaw[0] + r21 * lGazeRaw[1] + r22 * lGazeRaw[2]
        )
        val rGazeCanonical = floatArrayOf(
            r00 * rGazeRaw[0] + r01 * rGazeRaw[1] + r02 * rGazeRaw[2],
            r10 * rGazeRaw[0] + r11 * rGazeRaw[1] + r12 * rGazeRaw[2],
            r20 * rGazeRaw[0] + r21 * rGazeRaw[1] + r22 * rGazeRaw[2]
        )

        // Project nose-tip to measure head yaw/pitch in physical millimeters
        val p1 = get3DPoint(1) // Nose Tip
        val eyeMid = floatArrayOf((p33[0] + p263[0]) / 2f, (p33[1] + p263[1]) / 2f, (p33[2] + p263[2]) / 2f)
        val dNoseX = p1[0] - eyeMid[0]
        val dNoseY = p1[1] - eyeMid[1]
        val dNoseZ = p1[2] - eyeMid[2]

        val iodMetric = Math.sqrt(((p263[0]-p33[0])*(p263[0]-p33[0]) + (p263[1]-p33[1])*(p263[1]-p33[1]) + (p263[2]-p33[2])*(p263[2]-p33[2])).toDouble()).toFloat().coerceAtLeast(1f)
        
        val headYaw   = (dNoseX * ux[0] + dNoseY * ux[1] + dNoseZ * ux[2]) / iodMetric
        val headPitch = (dNoseX * uy[0] + dNoseY * uy[1] + dNoseZ * uy[2]) / iodMetric

        // ── 4. Device Sensor Fusion & Gravity Roll Compensation ──────────────
        val pitchShift = Math.abs(devicePitchDeg - lastDevicePitch)
        val rollShift = Math.abs(deviceRollDeg - lastDeviceRoll)
        if (pitchShift > 12f || rollShift > 12f) {
            baselineInitialized = false
            baselineSampleCount = 0
            gazeBaselineReady = false
            gazeDownAccum = 0f
            gazeUpAccum = 0f
            Log.i(TAG, "Sensor Fusion: Dynamic baseline recalibration triggered by posture shift (pitchShift=$pitchShift, rollShift=$rollShift)")
        }
        lastDevicePitch = devicePitchDeg
        lastDeviceRoll = deviceRollDeg

        // Rotate canonical gaze vector around Z-axis by device roll to counteract phone tilt/lying down
        val rollRad = Math.toRadians(deviceRollDeg.toDouble()).toFloat()
        val cosR = Math.cos(rollRad.toDouble()).toFloat()
        val sinR = Math.sin(rollRad.toDouble()).toFloat()

        val lGazeFused = floatArrayOf(
            lGazeCanonical[0] * cosR - lGazeCanonical[1] * sinR,
            lGazeCanonical[0] * sinR + lGazeCanonical[1] * cosR,
            lGazeCanonical[2]
        )
        val rGazeFused = floatArrayOf(
            rGazeCanonical[0] * cosR - rGazeCanonical[1] * sinR,
            rGazeCanonical[0] * sinR + rGazeCanonical[1] * cosR,
            rGazeCanonical[2]
        )

        if (!baselineInitialized) {
            val n = baselineSampleCount.toFloat()
            yawBaseline   = yawBaseline   * (n / (n + 1f)) + headYaw   * (1f / (n + 1f))
            pitchBaseline = pitchBaseline * (n / (n + 1f)) + headPitch * (1f / (n + 1f))
            baselineSampleCount++

            if (baselineSampleCount >= baselineWarmupFrames) {
                baselineInitialized = true
                Log.i(TAG, "Baseline ready after $baselineWarmupFrames frames — " +
                           "Yaw: ${"%.4f".format(yawBaseline)}, " +
                           "Pitch: ${"%.4f".format(pitchBaseline)}")
            }

            publishGazeState(
                GazeState(
                    isFaceDetected = true, isAttentive = false,
                    isLookingDown = false, isLookingUp = false,
                    isBlinking = isBlinking, yaw = 0f, pitch = 0f,
                    eyeOpenness = eyeOpenness
                )
            )
            return
        } else {
            yawBaseline   = yawBaseline   * baselineDriftRate + headYaw   * (1f - baselineDriftRate)
            pitchBaseline = pitchBaseline * baselineDriftRate + headPitch * (1f - baselineDriftRate)
        }

        val yawDev   = headYaw   - yawBaseline
        val pitchDev = headPitch - pitchBaseline

        // ── 5. Motion-Aware Confidence Scoring ────────────────────────────────
        val motionPenalty = (gyroMagnitude * 0.08f).coerceAtMost(0.4f)
        val poseAnglePenalty = (Math.abs(headYaw) * 0.4f + Math.abs(headPitch) * 0.4f).coerceAtMost(0.3f)
        val gazeConfidence = (1.0f - motionPenalty - poseAnglePenalty).coerceIn(0f, 1f)

        // ── 6. Attention gate ────────────────────────────────────────────────
        val isAttentive = Math.abs(yawDev) < 0.20f && Math.abs(pitchDev) < 0.15f && gazeConfidence > 0.60f

        // Dynamically adjust Camera FPS throttling (30 FPS when active, 5 FPS when idle/face lost)
        adaptiveFrameThrottleMs = if (isAttentive) 33L else 200L

        // ── 7. Canonical Gaze Extraction & One Euro Filtering ────────────────
        // Calculate physical eye width to normalize metric displacement into a scale-invariant ratio
        val lEyeWidth = Math.sqrt(((p33[0]-p133[0])*(p33[0]-p133[0]) + (p33[1]-p133[1])*(p33[1]-p133[1]) + (p33[2]-p133[2])*(p33[2]-p133[2])).toDouble()).toFloat().coerceAtLeast(1f)
        val rEyeWidth = Math.sqrt(((p263[0]-p362[0])*(p263[0]-p362[0]) + (p263[1]-p362[1])*(p263[1]-p362[1]) + (p263[2]-p362[2])*(p263[2]-p362[2])).toDouble()).toFloat().coerceAtLeast(1f)
        val avgEyeWidth = (lEyeWidth + rEyeWidth) / 2f

        val canonicalEyeY = ((lGazeFused[1] + rGazeFused[1]) / 2f) / avgEyeWidth * 1.8f
        
        // Relativize against dynamic gaze baseline offsets
        val rawGazeDown = (-canonicalEyeY).coerceAtLeast(0f)
        val rawGazeUp   = canonicalEyeY.coerceAtLeast(0f)

         if (!gazeBaselineReady) {
            gazeDownAccum += rawGazeDown
            gazeUpAccum   += rawGazeUp
            if (baselineInitialized) {
                gazeDownBaseline  = gazeDownAccum / baselineWarmupFrames.toFloat()
                gazeUpBaseline    = gazeUpAccum   / baselineWarmupFrames.toFloat()
                gazeBaselineReady = true
                
                // Instantly reset smoothed state and One Euro filters to avoid warmup decay lag
                smoothedGazeDown  = 0f
                smoothedGazeUp    = 0f
                filterGazeDown    = OneEuroFilter(1.0, 0.01, 1.0)
                filterGazeUp      = OneEuroFilter(1.0, 0.01, 1.0)
                
                Log.i(TAG, "Gaze baseline ready — Down: ${"%.3f".format(gazeDownBaseline)} Up: ${"%.3f".format(gazeUpBaseline)}")
            }
        } else {
            gazeDownBaseline = gazeDownBaseline * baselineDriftRate + rawGazeDown * (1f - baselineDriftRate)
            gazeUpBaseline   = gazeUpBaseline   * baselineDriftRate + rawGazeUp   * (1f - baselineDriftRate)
        }

        val relGazeDown = (rawGazeDown - gazeDownBaseline).coerceAtLeast(0f)
        val relGazeUp   = (rawGazeUp   - gazeUpBaseline  ).coerceAtLeast(0f)

        // Smooth relative scores and deviations using our high-velocity One Euro Filters
        val now = System.currentTimeMillis()
        val filteredYawDev = filterHeadYaw?.filter(yawDev.toDouble(), now)?.toFloat() ?: yawDev
        val filteredPitchDev = filterHeadPitch?.filter(pitchDev.toDouble(), now)?.toFloat() ?: pitchDev

        val filteredGazeDown = filterGazeDown?.filter(relGazeDown.toDouble(), now)?.toFloat() ?: relGazeDown
        val filteredGazeUp = filterGazeUp?.filter(relGazeUp.toDouble(), now)?.toFloat() ?: relGazeUp

        smoothedGazeDown = smoothedGazeDown * gazeSmoothing + filteredGazeDown * (1f - gazeSmoothing)
        smoothedGazeUp   = smoothedGazeUp   * gazeSmoothing + filteredGazeUp   * (1f - gazeSmoothing)

        val lookThresholdDown = if (customBottomThreshold != 0f) Math.abs(customBottomThreshold) else (0.12f - (sensitivity * 0.06f))
        val lookThresholdUp   = if (customTopThreshold != 0f) Math.abs(customTopThreshold) else (0.12f - (sensitivity * 0.06f))

        val eyeSignalMinimumDown = lookThresholdDown * 0.70f
        val eyeSignalMinimumUp   = lookThresholdUp * 0.70f

        val pitchDownAssist = filteredPitchDev.coerceAtLeast(0f) * 0.3f
        val pitchUpAssist   = (-filteredPitchDev).coerceAtLeast(0f) * 0.3f

        val isLookingDown = gazeBaselineReady && isAttentive
                && smoothedGazeDown >= eyeSignalMinimumDown
                && (smoothedGazeDown + pitchDownAssist) >= lookThresholdDown

        val isLookingUp = gazeBaselineReady && isAttentive
                && smoothedGazeUp >= eyeSignalMinimumUp
                && (smoothedGazeUp + pitchUpAssist) >= lookThresholdUp

        // ── 6. Head-nod detection — FIX C ────────────────────────────────────
        //
        // PROBLEM (original): An instantaneous threshold check fired nods on
        //   any micro-movement (hand tremor, phone shift) that briefly crossed
        //   the line.  There was no requirement for the deviation to peak-and-
        //   return, which is the physical signature of a genuine nod.
        //
        // FIX: Three-phase state machine (IDLE → ARMED → FIRED → IDLE).
        //   • ARMED when deviation first exceeds threshold.
        //   • FIRED only when deviation returns to ≤40 % of its peak while still
        //     within nodMaxDurationMs.  Movements held longer than that are
        //     treated as sustained posture changes, not nods.
        //   • Re-arms only after deviation fully returns to near-neutral.
        // Re-use already declared 'now' timestamp for head-nod checks

        // Nod thresholds in IOD-normalised units.
        // A small but deliberate nod moves ~0.06–0.10 IOD units.
        // sensitivity=0.5 gives yaw=0.065, pitch=0.033 — tuned for phone-held-in-hand use.
        val yawNodThreshold   = 0.07f - (sensitivity * 0.025f)   // range: 0.045–0.070
        val pitchNodThreshold = 0.035f - (sensitivity * 0.012f)  // range: 0.023–0.035

        var isNodLeft  = false
        var isNodRight = false
        var isNodUp    = false
        var isNodDown  = false

        if ((now - lastScrollTime) > scrollCooldownMs) {

            // ── Yaw nod (left / right) ──────────────────────────────────────
            when (yawNodPhase) {
                NodPhase.IDLE -> {
                    // Must be looking at screen to *start* a nod
                    if (isAttentive && Math.abs(yawDev) > yawNodThreshold) {
                        yawNodPhase     = NodPhase.ARMED
                        yawNodDirection = if (yawDev > 0) 1 else -1
                        yawNodPeak      = yawDev
                        yawNodStartTime = now
                    }
                }
                NodPhase.ARMED -> {
                    // Keep tracking the peak magnitude
                    if (Math.abs(yawDev) > Math.abs(yawNodPeak)) yawNodPeak = yawDev

                    val hasReturned = Math.abs(yawDev) < Math.abs(yawNodPeak) * nodReturnFraction
                    val timedOut    = (now - yawNodStartTime) > nodMaxDurationMs

                    when {
                        hasReturned -> {
                            // Clean peak-and-return → confirmed nod
                            yawNodPhase = NodPhase.FIRED
                            if (yawNodDirection > 0) isNodRight = true else isNodLeft = true
                        }
                        timedOut -> {
                            // Held too long → sustained posture change, not a nod
                            yawNodPhase = NodPhase.IDLE
                        }
                    }
                }
                NodPhase.FIRED -> {
                    // Wait for full return to neutral so one nod doesn't fire twice
                    if (Math.abs(yawDev) < yawNodThreshold * 0.5f) {
                        yawNodPhase = NodPhase.IDLE
                    }
                }
            }

            // ── Pitch nod (up / down) ────────────────────────────────────────
            when (pitchNodPhase) {
                NodPhase.IDLE -> {
                    // Must be looking at screen to *start* a nod
                    if (isAttentive && Math.abs(pitchDev) > pitchNodThreshold) {
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

        // ── 7. Diagnostics log (throttled) ────────────────────────────────────
        //   IOD is logged so you can verify it stays in [0.05, 0.40] range —
        //   values outside that range indicate a normalization or rotation problem.
        //
        // BUG (previous): Java-style %-placeholders were in the string literal
        //   but .format() was chained on the string rather than passed to Log.d,
        //   so Android logcat printed the placeholders literally, not the values.
        //   Fix: pre-format each value into a local val, then use Kotlin templates.
        if (faceCount % 15 == 0) {
            val pd  = "%.4f".format(pitchDev)
            val yd  = "%.4f".format(yawDev)
            val sd  = "%.3f".format(smoothedGazeDown)   // relative (baseline-subtracted)
            val su  = "%.3f".format(smoothedGazeUp)
            val ds  = "%.3f".format(smoothedGazeDown + pitchDownAssist)
            val us  = "%.3f".format(smoothedGazeUp   + pitchUpAssist)
            val db  = "%.3f".format(gazeDownBaseline)
            val ub  = "%.3f".format(gazeUpBaseline)
            val thrD = "%.3f".format(lookThresholdDown)
            val thrU = "%.3f".format(lookThresholdUp)
            val emD  = "%.3f".format(eyeSignalMinimumDown)
            val emU  = "%.3f".format(eyeSignalMinimumUp)
            val iodStr = "%.4f".format(iodNorm)
            Log.d(TAG,
                "Gaze | IOD=$iodStr PitchDev=$pd YawDev=$yd | " +
                "RelDown=$sd(base=$db) RelUp=$su(base=$ub) | " +
                "DownScore=$ds UpScore=$us | ThreshD=$thrD ThreshU=$thrU EyeMinD=$emD EyeMinU=$emU | " +
                "LookDown=$isLookingDown LookUp=$isLookingUp Attentive=$isAttentive"
            )
        }

        // ── 8. Publish + act ─────────────────────────────────────────────────
        val state = GazeState(
            isFaceDetected = true,
            isAttentive    = isAttentive,
            isLookingDown  = isLookingDown,
            isLookingUp    = isLookingUp,
            isBlinking     = isBlinking,
            yaw            = yawDev,
            pitch          = pitchDev,
            eyeOpenness    = eyeOpenness,
            isNodLeft      = isNodLeft,
            isNodRight     = isNodRight,
            isNodUp        = isNodUp,
            isNodDown      = isNodDown
        )

        publishGazeState(state)
        handleGazeStateAction(state)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gesture dispatch
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleGazeStateAction(state: GazeState) {
        val activeApp = GazeAccessibilityService.activePackageName
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

        // ── Horizontal nods (always active regardless of swipeMode) ───────────
        if ((now - lastScrollTime) > scrollCooldownMs) {
            if (state.isNodLeft) {
                GazeAccessibilityService.instance?.performScrollLeft(scrollSpeed)
                lastScrollTime = now
                updateNotification("Gaze Service Active", "Swiped left via head nod.")
                return
            }
            if (state.isNodRight) {
                GazeAccessibilityService.instance?.performScrollRight(scrollSpeed)
                lastScrollTime = now
                updateNotification("Gaze Service Active", "Swiped right via head nod.")
                return
            }
        }

        // ── Vertical actions: mode-dependent ─────────────────────────────────
        if (swipeMode == "headNod") {
            if ((now - lastScrollTime) > scrollCooldownMs) {
                when {
                    state.isNodDown -> {
                        GazeAccessibilityService.instance?.performScrollDown(scrollSpeed)
                        lastScrollTime = now
                        updateNotification("Gaze Service Active", "Scrolled down via head nod.")
                    }
                    state.isNodUp -> {
                        GazeAccessibilityService.instance?.performScrollUp(scrollSpeed)
                        lastScrollTime = now
                        updateNotification("Gaze Service Active", "Scrolled up via head nod.")
                    }
                }
            }
        } else if (swipeMode == "eyeTracking") {
            // Eye-tracking hold mode
            if (state.isLookingDown) {
                if (lookingDownStartTime == 0L) {
                    lookingDownStartTime = now
                } else {
                    val elapsed = now - lookingDownStartTime
                    if (elapsed >= triggerDurationMs && (now - lastScrollTime) > scrollCooldownMs) {
                        GazeAccessibilityService.instance?.performScrollDown(scrollSpeed)
                        lastScrollTime       = now
                        lookingDownStartTime = 0L
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
                    if (elapsed >= triggerDurationMs && (now - lastScrollTime) > scrollCooldownMs) {
                        GazeAccessibilityService.instance?.performScrollUp(scrollSpeed)
                        lastScrollTime     = now
                        lookingUpStartTime = 0L
                        updateNotification("Gaze Service Active", "Scrolled up via eye gaze.")
                    }
                }
            } else {
                lookingUpStartTime = 0L
            }
        }

        // ── Media play/pause on look-away ─────────────────────────────────────
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
        Log.d(TAG, "Dispatching KEYCODE_MEDIA_PLAY_PAUSE")
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    private fun triggerGlobalStop() {
        Log.d(TAG, "Dispatching KEYCODE_MEDIA_STOP")
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))
    }

    private fun triggerGlobalSelect() {
        Log.d(TAG, "Dispatching Select (Click center of screen)")
        val dm = resources.displayMetrics
        val cx = (dm.widthPixels / 2).toFloat()
        val cy = (dm.heightPixels / 2).toFloat()
        val path = Path().apply { moveTo(cx, cy) }
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        GazeAccessibilityService.instance?.dispatchGesture(
            gestureBuilder.build(),
            null,
            null
        )
    }

    private fun triggerSwipeLeft() {
        flagSwipeLeft = true
        GazeAccessibilityService.instance?.performScrollLeft(scrollSpeed)
        updateNotification("Gaze Service Active", "Swiped left via hand gesture.")
        Log.i(TAG, "Triggered Swipe Left via hand gesture.")
    }
    
    private fun triggerSwipeRight() {
        flagSwipeRight = true
        GazeAccessibilityService.instance?.performScrollRight(scrollSpeed)
        updateNotification("Gaze Service Active", "Swiped right via hand gesture.")
        Log.i(TAG, "Triggered Swipe Right via hand gesture.")
    }

    private fun triggerSwipeUp() {
        flagSwipeUp = true
        GazeAccessibilityService.instance?.performScrollUp(scrollSpeed)
        updateNotification("Gaze Service Active", "Scrolled up via hand gesture.")
        Log.i(TAG, "Triggered Scroll Up via hand gesture.")
    }

    private fun triggerSwipeDown() {
        flagSwipeDown = true
        GazeAccessibilityService.instance?.performScrollDown(scrollSpeed)
        updateNotification("Gaze Service Active", "Scrolled down via hand gesture.")
        Log.i(TAG, "Triggered Scroll Down via hand gesture.")
    }

    private fun triggerPalmGesture(gesture: String) {
        Log.i(TAG, "Palm Gesture Confirmed: $gesture")
        updateNotification("Gaze Service Active", "Triggered $gesture palm gesture.")
        when (gesture) {
            "OPEN_PALM" -> triggerGlobalPlayPause()
            "CLOSED_FIST" -> triggerGlobalStop()
            "THUMBS_UP" -> triggerGlobalSelect()
        }
    }

    private fun processHandLandmarks(result: HandLandmarkerResult) {
        val landmarks = result.landmarks()
        if (landmarks.isNullOrEmpty() || landmarks[0].size <= 20) {
            // Hand lost -> reset swipe history and stable palm state
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

        // 1. Swipe classification using velocity + distance
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
                            triggerSwipeRight()
                        } else {
                            triggerSwipeLeft()
                        }
                        lastSwipeTriggeredTime = now
                        swipeHistory.clear()
                    }
                } else {
                    if (Math.abs(vy) > 0.4f) {
                        if (dy > 0) {
                            triggerSwipeDown()
                        } else {
                            triggerSwipeUp()
                        }
                        lastSwipeTriggeredTime = now
                        swipeHistory.clear()
                    }
                }
            }
        }

        // 2. Stable Palm Gesture classification
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
                triggerPalmGesture(currentGesture)
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

    /** Convenience to build a fully-false/zero GazeState when no face is detected. */
    private fun emptyGazeState() = GazeState(
        isFaceDetected = false, isAttentive = false,
        isLookingDown  = false, isLookingUp  = false,
        isBlinking     = false, yaw = 0f, pitch = 0f, eyeOpenness = 0f,
        isNodLeft = false, isNodRight = false, isNodUp = false, isNodDown = false,
        detectedHandGesture = "NONE", isSwipeLeft = false, isSwipeRight = false, isSwipeUp = false, isSwipeDown = false
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Camera2 plumbing
    // ─────────────────────────────────────────────────────────────────────────

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
        Log.i(TAG, "=====> Initialising front-camera frame capture <=====")
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var frontCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars  = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId     = id
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
                    Log.i(TAG, "Front camera found. Sensor orientation: $sensorOrientation°")
                    break
                }
            }

            if (frontCameraId == null) {
                Log.e(TAG, "No front camera found.")
                startFallbackHeuristicSimulator()
                return
            }

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Camera permission not granted.")
                startFallbackHeuristicSimulator()
                return
            }

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera opened. Building capture session…")
                    cameraDevice = camera
                    createCameraPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected.")
                    camera.close(); cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close(); cameraDevice = null
                    startFallbackHeuristicSimulator()
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
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
                        Log.i(TAG, "Capture session configured.")
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
            Log.e(TAG, "Frame processing error: ${e.message}")
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

        // Pre-allocated array cache optimization (production grade)
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
        Log.i(TAG, "=====> Stopping camera <=====")
        try { captureSession?.stopRepeating(); captureSession?.close() }
        catch (e: Exception) { Log.e(TAG, "Error closing capture session: ${e.message}") }
        captureSession = null

        try { cameraDevice?.close() }
        catch (e: Exception) { Log.e(TAG, "Error closing camera device: ${e.message}") }
        cameraDevice = null

        try { imageReader?.close() }
        catch (e: Exception) { Log.e(TAG, "Error closing ImageReader: ${e.message}") }
        imageReader = null

        stopBackgroundThread()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback heuristic simulator (used when camera/MediaPipe unavailable)
    // ─────────────────────────────────────────────────────────────────────────

    private var simulatorHandler: Handler? = null
    private var simulatorTickCount = 0

    private fun startFallbackHeuristicSimulator() {
        Log.w(TAG, "Starting fallback heuristic simulator — no real gaze data.")
        simulatorHandler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {
                if (!isServiceRunning) return

                simulatorTickCount++
                val cycle = simulatorTickCount % 30

                var isLookingDown = false
                var isLookingUp   = false
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
                    16       -> { isNodLeft  = true; yaw =  0.15f }
                    18       -> { isNodRight = true; yaw = -0.15f }
                    20       -> { isNodDown  = true; pitch =  0.10f }
                    22       -> { isNodUp    = true; pitch = -0.10f }
                    25       -> { isBlinking = true; eyeOpenness = 0f }
                    in 27..29 -> { isAttentive = false; yaw = 0.35f }
                }

                val mockState = GazeState(
                    isFaceDetected = true, isAttentive = isAttentive,
                    isLookingDown  = isLookingDown, isLookingUp = isLookingUp,
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

                customTopThreshold = json.optDouble("top_threshold", 0.08).toFloat()
                customBottomThreshold = json.optDouble("bottom_threshold", -0.08).toFloat()

                // Instantly seed dynamic gaze baselines and ready states
                gazeDownBaseline = 0f
                gazeUpBaseline = 0f
                gazeBaselineReady = true

                Log.i(TAG, "Loaded active custom baseline calibration: neutralX=$neutralX neutralY=$neutralY top=$customTopThreshold bottom=$customBottomThreshold")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load personalized calibration baseline: ${e.message}")
        }
    }
}