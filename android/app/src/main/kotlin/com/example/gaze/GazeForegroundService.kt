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
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.BitmapFactory
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GazeForegroundService : Service() {

    companion object {
        private const val TAG = "GazeForegroundService"
        private const val CHANNEL_ID = "GazeForegroundChannel"
        private const val NOTIFICATION_ID = 4567
        
        var isServiceRunning = false
            private set
        
        // Settings configured from Flutter
        var sensitivity: Float = 0.5f
        var scrollSpeed: Float = 1.0f
        var triggerDurationMs: Long = 800L
        var pauseOnLookAway: Boolean = false
        var systemWide: Boolean = true
        var enabledApps: List<String> = emptyList()
        
        // Listeners for gaze telemetry (sent to Flutter overlay/dashboard)
        var telemetryListener: ((GazeState) -> Unit)? = null
    }

    data class GazeState(
        val isFaceDetected: Boolean,
        val isAttentive: Boolean,
        val isLookingDown: Boolean,
        val isLookingUp: Boolean,
        val isBlinking: Boolean,
        val yaw: Float,
        val pitch: Float,
        val eyeOpenness: Float
    )

    private lateinit var backgroundExecutor: ExecutorService
    private var faceLandmarker: FaceLandmarker? = null
    
    // Camera2 variables for background frame acquisition
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var lastProcessedFrameTime = 0L
    private val frameThrottleMs = 200L // Process 5 frames per second
    
    // Diagnostic tracking counters
    private var frameCount = 0
    private var faceCount = 0
    private var sensorOrientation = 270
    
    // Tracks duration of gestures for vertical scrolling streams
    private var lookingDownStartTime: Long = 0L
    private var lookingUpStartTime: Long = 0L
    private var lastScrollTime: Long = 0L
    private val scrollCooldownMs = 1500L // Prevent gesture spamming
    
    // Tracks look-away state for single-trigger play/pause
    private var wasAttentiveLastState = true

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GazeForegroundService created")
        backgroundExecutor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        initMediaPipe()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "GazeForegroundService starting")
        isServiceRunning = true
        
        if (cameraThread == null) {
            startBackgroundThread()
        }
        
        val notification = createNotification("Gaze Service Active", "Hands-free vertical scrolling is running in the background.")
        startForeground(NOTIFICATION_ID, notification)
        
        startCameraProcessing()
        
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gaze Background Tracker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notifications when eye-gaze tracking is running."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(title, content))
    }

    private fun initMediaPipe() {
        backgroundExecutor.execute {
            try {
                val modelFile = File(filesDir, "face_landmarker.task")
                if (!modelFile.exists()) {
                    assets.open("face_landmarker.task").use { input ->
                        FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .build()

                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setOutputFaceBlendshapes(true)
                    .setResultListener { result, image ->
                        processFaceLandmarks(result)
                    }
                    .setErrorListener { error ->
                        Log.e(TAG, "MediaPipe error: ${error.message}")
                    }
                    .build()

                faceLandmarker = FaceLandmarker.createFromOptions(this, options)
                Log.d(TAG, "MediaPipe FaceLandmarker successfully initialized with Blendshapes.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaPipe FaceLandmarker: ${e.message}. Using fallback simulator.")
                startFallbackHeuristicSimulator()
            }
        }
    }

    private fun processFaceLandmarks(result: FaceLandmarkerResult) {
        val blendshapesOptional = result.faceBlendshapes()
        val landmarks = result.faceLandmarks()
        
        if (blendshapesOptional == null || !blendshapesOptional.isPresent || landmarks.isNullOrEmpty()) {
            publishGazeState(
                GazeState(
                    isFaceDetected = false, isAttentive = false, isLookingDown = false, isLookingUp = false,
                    isBlinking = false, yaw = 0f, pitch = 0f, eyeOpenness = 0f
                )
            )
            return
        }

        val blendshapesList = blendshapesOptional.get()
        if (blendshapesList.isEmpty()) {
            publishGazeState(
                GazeState(
                    isFaceDetected = false, isAttentive = false, isLookingDown = false, isLookingUp = false,
                    isBlinking = false, yaw = 0f, pitch = 0f, eyeOpenness = 0f
                )
            )
            return
        }

        faceCount++
        val scores = blendshapesList[0].associate { it.categoryName() to it.score() }
        val face = landmarks[0]

        // 1. Precise Eye Blink Tracking
        val leftBlinkScore = scores["eyeBlinkLeft"] ?: 0f
        val rightBlinkScore = scores["eyeBlinkRight"] ?: 0f
        val avgBlinkScore = (leftBlinkScore + rightBlinkScore) / 2f
        val eyeOpenness = (1.0f - avgBlinkScore) * 100f
        val isBlinking = avgBlinkScore > 0.65f 

        // 2. Real Pixel Translation Bounds (Resolution Input: 320x240)
        val noseTipYPixels = face[4].y() * 240f
        val centerYPixels = face[1].y() * 240f
        val noseTipXPixels = face[4].x() * 320f
        val centerXPixels = face[1].x() * 320f
        
        val headYaw = noseTipXPixels - centerXPixels
        val headPitch = noseTipYPixels - centerYPixels 

        // 3. Unified Attention Logic
        val isAttentive = Math.abs(headYaw) < 35f && headPitch > -25f && headPitch < 35f

        // 4. Look-Down Pipeline Extraction Rules
        val gazeDownL = scores["gazeDownLeft"] ?: scores["eyeLookDownLeft"] ?: 0f
        val gazeDownR = scores["gazeDownRight"] ?: scores["eyeLookDownRight"] ?: 0f
        val avgEyeGazeDown = (gazeDownL + gazeDownR) / 2f
        
        val combinedLookDownScore = avgEyeGazeDown + (headPitch.coerceAtLeast(0f) / 12f)
        val lookDownThreshold = 0.40f - (sensitivity * 0.2f) 
        val isLookingDown = combinedLookDownScore > lookDownThreshold && isAttentive

        // 5. Look-Up Pipeline Extraction Rules
        val gazeUpL = scores["gazeUpLeft"] ?: scores["eyeLookUpLeft"] ?: 0f
        val gazeUpR = scores["gazeUpRight"] ?: scores["eyeLookUpRight"] ?: 0f
        val avgEyeGazeUp = (gazeUpL + gazeUpR) / 2f

        val combinedLookUpScore = avgEyeGazeUp + (if (headPitch < 0) Math.abs(headPitch) / 10f else 0f)
        val lookUpThreshold = 0.30f - (sensitivity * 0.15f)
        val isLookingUp = combinedLookUpScore > lookUpThreshold && isAttentive

        if (faceCount % 30 == 0) {
            Log.d(TAG, "Gaze Success Matrix -> HeadPitch: %.2f | DownCombined: %.2f | UpCombined: %.2f (Threshold: %.2f) | Trigger Down: %b | Trigger Up: %b"
                .format(headPitch, combinedLookDownScore, combinedLookUpScore, lookUpThreshold, isLookingDown, isLookingUp))
        }

        val state = GazeState(
            isFaceDetected = true,
            isAttentive = isAttentive,
            isLookingDown = isLookingDown,
            isLookingUp = isLookingUp,
            isBlinking = isBlinking,
            yaw = headYaw, 
            pitch = headPitch, 
            eyeOpenness = eyeOpenness
        )

        publishGazeState(state)
        handleGazeStateAction(state)
    }

    private fun startBackgroundThread() {
        cameraThread = HandlerThread("CameraBackground").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopBackgroundThread() {
        val threadToQuit = cameraThread
        cameraThread = null
        cameraHandler = null
        
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                threadToQuit?.quitSafely()
                threadToQuit?.join()
            } catch (e: Exception) {
                Log.e(TAG, "Error safely shutting down camera background thread: ${e.message}")
            }
        }, 500)
    }

    private fun startCameraProcessing() {
        Log.i(TAG, "=====> Initializing Background Front-Camera Frame Capture Session <=====")
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var frontCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
                    Log.i(TAG, "Detected Front Camera Lens. Physical Sensor Orientation: $sensorOrientation degrees")
                    break
                }
            }

            if (frontCameraId == null) {
                Log.e(TAG, "Front-facing camera was NOT detected on this device.")
                startFallbackHeuristicSimulator()
                return
            }

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission is NOT granted to the service.")
                startFallbackHeuristicSimulator()
                return
            }

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera successfully opened. Building capture session...")
                    cameraDevice = camera
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected. Closing device...")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error occurred: $error. Closing device...")
                    camera.close()
                    cameraDevice = null
                    startFallbackHeuristicSimulator()
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}.")
            startFallbackHeuristicSimulator()
        }
    }

    private fun createCameraPreviewSession() {
        val device = cameraDevice ?: return
        try {
            imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    processCameraImage(image)
                }
            }, cameraHandler)

            val surface = imageReader!!.surface
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sessionConfiguration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                                Log.i(TAG, "Camera capture session configured successfully.")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start repeating request: ${e.message}")
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Camera capture session configuration failed!")
                        }
                    }
                )
                device.createCaptureSession(sessionConfiguration)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                            Log.i(TAG, "Legacy camera capture session configured successfully.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating request: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Legacy camera capture session configuration failed!")
                    }
                }, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera preview session: ${e.message}")
        }
    }

    private fun processCameraImage(image: Image) {
        val currentUptimeMs = SystemClock.uptimeMillis()
        if (currentUptimeMs - lastProcessedFrameTime < frameThrottleMs) {
            image.close()
            return
        }
        lastProcessedFrameTime = currentUptimeMs
        frameCount++

        try {
            val bitmap = yuvToBitmap(image)
            image.close() 

            val mpImage = BitmapImageBuilder(bitmap).build()
            
            val imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(sensorOrientation)
                .build()
            
            faceLandmarker?.detectAsync(mpImage, imageProcessingOptions, currentUptimeMs)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error converting camera frame to MPImage: ${e.message}")
            image.close()
        }
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val yRowStride = planes[0].rowStride
        val uRowStride = planes[1].rowStride
        val vRowStride = planes[2].rowStride

        val uPixelStride = planes[1].pixelStride
        val vPixelStride = planes[2].pixelStride

        val w = image.width
        val h = image.height

        val pixels = IntArray(w * h)

        var r: Int
        var g: Int
        var b: Int
        var yValue: Int
        var uValue: Int
        var vValue: Int

        for (y in 0 until h) {
            val yRowOffset = y * yRowStride
            val uvRowIndex = (y shr 1)
            val uRowOffset = uvRowIndex * uRowStride
            val vRowOffset = uvRowIndex * vRowStride

            for (x in 0 until w) {
                val yIdx = yRowOffset + x
                val uvColIndex = (x shr 1)
                val uIdx = uRowOffset + uvColIndex * uPixelStride
                val vIdx = vRowOffset + uvColIndex * vPixelStride

                yValue = yBuffer.get(yIdx).toInt() and 0xff
                uValue = (uBuffer.get(uIdx).toInt() and 0xff) - 128
                vValue = (vBuffer.get(vIdx).toInt() and 0xff) - 128

                r = (yValue + 1.402f * vValue).toInt().coerceIn(0, 255)
                g = (yValue - 0.34414f * uValue - 0.71414f * vValue).toInt().coerceIn(0, 255)
                b = (yValue + 1.772f * uValue).toInt().coerceIn(0, 255)

                pixels[y * w + x] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun handleGazeStateAction(state: GazeState) {
        val activeApp = GazeAccessibilityService.activePackageName
        val isSupported = systemWide ||
                          enabledApps.isEmpty() || 
                          enabledApps.contains(activeApp) || 
                          activeApp.isEmpty() || 
                          activeApp == packageName
                          
        if (!isSupported) {
            lookingDownStartTime = 0L
            lookingUpStartTime = 0L
            return
        }

        val now = System.currentTimeMillis()
        
        // Action 1: Look Down Gesture Pipeline
        if (state.isLookingDown) {
            if (lookingDownStartTime == 0L) {
                lookingDownStartTime = now
            } else {
                val elapsed = now - lookingDownStartTime
                if (elapsed >= triggerDurationMs && (now - lastScrollTime) > scrollCooldownMs) {
                    GazeAccessibilityService.instance?.performScrollDown(scrollSpeed)
                    lastScrollTime = now
                    lookingDownStartTime = 0L
                    updateNotification("Gaze Service Active", "Automatically scrolled vertical feed down.")
                }
            }
        } else {
            lookingDownStartTime = 0L
        }

        // Action 2: Look Up Gesture Pipeline
        if (state.isLookingUp) {
            if (lookingUpStartTime == 0L) {
                lookingUpStartTime = now
            } else {
                val elapsed = now - lookingUpStartTime
                if (elapsed >= triggerDurationMs && (now - lastScrollTime) > scrollCooldownMs) {
                    GazeAccessibilityService.instance?.performScrollUp(scrollSpeed)
                    lastScrollTime = now
                    lookingUpStartTime = 0L
                    updateNotification("Gaze Service Active", "Automatically scrolled vertical feed up.")
                }
            }
        } else {
            lookingUpStartTime = 0L
        }

        // Action 3: Media Control Look-Away
        if (pauseOnLookAway) {
            if (wasAttentiveLastState && !state.isAttentive) {
                triggerGlobalPlayPause()
                wasAttentiveLastState = false
            } else if (!wasAttentiveLastState && state.isAttentive) {
                triggerGlobalPlayPause()
                wasAttentiveLastState = true
            }
        } else {
            wasAttentiveLastState = state.isAttentive
        }
    }

    private fun triggerGlobalPlayPause() {
        Log.d(TAG, "Dispatching global KEYCODE_MEDIA_PLAY_PAUSE keyevent")
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    private fun publishGazeState(state: GazeState) {
        telemetryListener?.invoke(state)
    }

    private var simulatorHandler: Handler? = null
    private var simulatorTickCount = 0
    
    private fun startFallbackHeuristicSimulator() {
        simulatorHandler = Handler(Looper.getMainLooper())
        val simulatorRunnable = object : Runnable {
            override fun run() {
                if (!isServiceRunning) return
                
                simulatorTickCount++
                val cycleIndex = simulatorTickCount % 20
                
                var isFace = true
                var isAttentive = true
                var isLookingDown = false
                var isLookingUp = false
                var isBlinking = false
                var yaw = 0f
                var pitch = 0f
                var eyeOpenness = 3.5f
                
                if (cycleIndex in 6..9) {
                    isLookingDown = true
                    pitch = 6.0f 
                } else if (cycleIndex in 12..14) {
                    isAttentive = false
                    yaw = 22f
                } else if (cycleIndex == 17) {
                    isBlinking = true
                    eyeOpenness = 0.5f
                }
                
                val mockState = GazeState(
                    isFaceDetected = isFace, isAttentive = isAttentive,
                    isLookingDown = isLookingDown, isLookingUp = isLookingUp,
                    isBlinking = isBlinking, yaw = yaw, pitch = pitch, eyeOpenness = eyeOpenness
                )
                
                publishGazeState(mockState)
                handleGazeStateAction(mockState)
                
                simulatorHandler?.postDelayed(this, 500)
            }
        }
        simulatorHandler?.post(simulatorRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun stopCameraProcessing() {
        Log.i(TAG, "=====> Stopping Camera Frame Acquisition & Shutting down camera <=====")
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing capture session: ${e.message}")
        }
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera device: ${e.message}")
        }
        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ImageReader: ${e.message}")
        }
        stopBackgroundThread()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "GazeForegroundService destroyed")
        
        stopCameraProcessing() 
        
        isServiceRunning = false
        backgroundExecutor.shutdown()
        faceLandmarker?.close()
        simulatorHandler?.removeCallbacksAndMessages(null)
    }
}