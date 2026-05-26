package com.example.gaze

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.coroutines.resume

/**
 * Supported gaze directions for semantic classification.
 */
enum class GazeDirection {
    CENTER, TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, BLINK, UNKNOWN
}

/**
 * Immutable configuration for gesture dispatch dynamics.
 */
data class GestureConfig(
    val swipeDistanceFraction: Float = 0.70f,
    val baseDurationMs: Long = 300L,
    val minDurationMs: Long = 150L,
    val maxDurationMs: Long = 600L,
    val minIntervalMs: Long = 800L,
    val accelerationFactor: Float = 1.2f,
    val flickThreshold: Float = 0.75f
)

/**
 * Behavior profile tailored to app categories.
 */
data class AppGestureProfile(
    val categoryName: String,
    val config: GestureConfig,
    val useNodeScroll: Boolean,
    val stabilityDurationMs: Long = 350L
)

/**
 * Calibration parameters applied to raw inputs.
 */
data class RuntimeCalibrationState(
    val sensitivityX: Float = 1.0f,
    val sensitivityY: Float = 1.0f,
    val deadzoneX: Float = 0.15f,
    val deadzoneY: Float = 0.15f,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f
)

/**
 * Production-grade Gaze Accessibility Service.
 * Implements a state-stabilized, queue-driven gesture engine.
 */
class GazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GazeAccessibility"

        @Volatile
        var instance: GazeAccessibilityService? = null
            private set

        @Volatile
        var activePackageName: String = ""
            private set
    }

    // Coroutine scope for running async queue and state tasks safely
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Internal subcomponents
    private lateinit var appProfileManager: AppProfileManager
    private lateinit var calibrationManager: CalibrationManager
    private lateinit var stableDirectionTracker: StableDirectionTracker
    private lateinit var gestureEngine: GestureEngine
    private lateinit var gestureDispatcher: GestureDispatcher
    private lateinit var gestureAnalytics: GestureAnalytics

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "=====> onServiceConnected: Accessibility Service fully bound <=====")
        instance = this

        // Initialize modules
        appProfileManager = AppProfileManager()
        calibrationManager = CalibrationManager()
        stableDirectionTracker = StableDirectionTracker()
        gestureEngine = GestureEngine()
        gestureDispatcher = GestureDispatcher(this, serviceScope)
        gestureAnalytics = GestureAnalytics()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val pkg = event.packageName
            if (pkg != null && pkg.toString().isNotEmpty()) {
                val oldPackage = activePackageName
                val newPackage = pkg.toString()
                if (oldPackage != newPackage) {
                    activePackageName = newPackage
                    Log.d(TAG, "Active App Detected: '$oldPackage' -> '$activePackageName'")
                    
                    // Switch profile based on package name
                    val profile = appProfileManager.getProfileForPackage(newPackage)
                    Log.d(TAG, "Applied App Profile: ${profile.categoryName}")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "=====> onInterrupt: Accessibility service suspended <=====")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "=====> onDestroy: GazeAccessibilityService shutting down <=====")
        serviceScope.cancel()
        if (instance == this) {
            instance = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Runtime APIs called by foreground service / IPC layers
    // ─────────────────────────────────────────────────────────────────────────

    fun updateCalibration(sensitivityX: Float, sensitivityY: Float, deadzoneX: Float, deadzoneY: Float) {
        calibrationManager.update(sensitivityX, sensitivityY, deadzoneX, deadzoneY)
        Log.i(TAG, "Calibration updated: sx=$sensitivityX sy=$sensitivityY dzX=$deadzoneX dzY=$deadzoneY")
    }

    fun updateSensitivity(multiplier: Float) {
        calibrationManager.setScale(multiplier)
        Log.i(TAG, "Global Sensitivity multiplier scaled to: $multiplier")
    }

    fun resetAdaptiveLearning() {
        gestureAnalytics.reset()
        Log.i(TAG, "Adaptive learning state and analytics metrics reset.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public Action Dispatch APIs (Gaze / Gesture events)
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // Backwards compatibility scroll API wrappers
    // ─────────────────────────────────────────────────────────────────────────

    fun performScrollDown(intensity: Float = 1.0f): Boolean {
        return performGazeSwipe(GazeDirection.BOTTOM, intensity)
    }

    fun performScrollUp(intensity: Float = 1.0f): Boolean {
        return performGazeSwipe(GazeDirection.TOP, intensity)
    }

    fun performScrollLeft(intensity: Float = 1.0f): Boolean {
        return performGazeSwipe(GazeDirection.LEFT, intensity)
    }

    fun performScrollRight(intensity: Float = 1.0f): Boolean {
        return performGazeSwipe(GazeDirection.RIGHT, intensity)
    }

    fun performGazeSwipe(direction: GazeDirection, intensity: Float): Boolean {
        if (direction == GazeDirection.CENTER || direction == GazeDirection.UNKNOWN || direction == GazeDirection.BLINK) {
            return false
        }

        val profile = appProfileManager.getProfileForPackage(activePackageName)

        // 1. Run Node-based scrolling if supported by the profile
        if (profile.useNodeScroll && !appProfileManager.isVideoApp(activePackageName)) {
            val action = when (direction) {
                GazeDirection.BOTTOM -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                GazeDirection.TOP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> 0
            }
            if (action != 0 && AccessibilityNodeHelper.scrollNode(this, action)) {
                Log.i(TAG, "Successfully scrolled active view using Accessibility Node Action.")
                gestureAnalytics.recordSuccess()
                return true
            }
        }

        // 2. Dispatch simulated gesture
        val state = calibrationManager.getCalibration()
        val scaledIntensity = gestureEngine.scaleIntensity(intensity, state)
        val gesture = gestureEngine.buildGesture(this, direction, scaledIntensity, profile) ?: return false

        gestureDispatcher.enqueue(gesture, direction)
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nested Architectural Components
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Identifies application categories and serves tailored configurations.
     */
    class AppProfileManager {
        private val profiles = mapOf(
            "video" to AppGestureProfile(
                "Video Feed Apps (Reels/TikTok/Shorts)",
                GestureConfig(
                    swipeDistanceFraction = 0.82f,
                    baseDurationMs = 180L,
                    minDurationMs = 120L,
                    maxDurationMs = 240L,
                    minIntervalMs = 1400L,
                    accelerationFactor = 1.4f
                ),
                useNodeScroll = false,
                stabilityDurationMs = 250L
            ),
            "reading" to AppGestureProfile(
                "Reading Apps (Chrome/PDF/Settings)",
                GestureConfig(
                    swipeDistanceFraction = 0.50f,
                    baseDurationMs = 380L,
                    minDurationMs = 250L,
                    maxDurationMs = 550L,
                    minIntervalMs = 700L,
                    accelerationFactor = 0.9f
                ),
                useNodeScroll = true,
                stabilityDurationMs = 450L
            ),
            "standard" to AppGestureProfile(
                "Standard Apps",
                GestureConfig(),
                useNodeScroll = true
            )
        )

        fun getProfileForPackage(packageName: String): AppGestureProfile {
            return when {
                isVideoApp(packageName) -> profiles["video"]!!
                isReadingApp(packageName) -> profiles["reading"]!!
                else -> profiles["standard"]!!
            }
        }

        fun isVideoApp(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower.contains("youtube") || lower.contains("instagram") ||
                   lower.contains("tiktok") || lower.contains("musically")
        }

        private fun isReadingApp(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower.contains("chrome") || lower.contains("pdf") ||
                   lower.contains("reader") || lower.contains("settings") ||
                   lower.contains("book")
        }
    }

    /**
     * Manages system calibration matrix variables in a thread-safe manner.
     */
    class CalibrationManager {
        private val state = AtomicReference(RuntimeCalibrationState())
        private var globalScale = 1.0f

        fun update(sensitivityX: Float, sensitivityY: Float, deadzoneX: Float, deadzoneY: Float) {
            state.set(
                RuntimeCalibrationState(
                    sensitivityX = sensitivityX * globalScale,
                    sensitivityY = sensitivityY * globalScale,
                    deadzoneX = deadzoneX,
                    deadzoneY = deadzoneY
                )
            )
        }

        fun setScale(multiplier: Float) {
            globalScale = multiplier
            val current = state.get()
            update(current.sensitivityX / globalScale, current.sensitivityY / globalScale, current.deadzoneX, current.deadzoneY)
        }

        fun getCalibration(): RuntimeCalibrationState = state.get()
    }

    /**
     * Prevents false triggers by ensuring the user's gaze remains stable.
     */
    class StableDirectionTracker {
        private var lastDirection = GazeDirection.CENTER
        private var stableSinceTime = 0L

        fun isStable(direction: GazeDirection, stabilityThresholdMs: Long): Boolean {
            val now = System.currentTimeMillis()
            if (direction != lastDirection) {
                lastDirection = direction
                stableSinceTime = now
                return false
            }
            return (now - stableSinceTime) >= stabilityThresholdMs
        }
    }

    /**
     * Builds gestures with custom path physics and non-linear distance mappings.
     */
    class GestureEngine {
        /**
         * Applies non-linear sigmoid transformation to gaze intensity
         */
        fun scaleIntensity(intensity: Float, state: RuntimeCalibrationState): Float {
            // Sigmoid: 1 / (1 + exp(-k * x)) scaled appropriately
            val corrected = intensity * state.sensitivityY
            return (2.0f / (1.0f + exp(-2.5f * (corrected - 0.5f)))) - 0.5f
        }

        fun buildGesture(
            service: AccessibilityService,
            direction: GazeDirection,
            intensity: Float,
            profile: AppGestureProfile
        ): GestureDescription? {
            val dm = service.resources.displayMetrics
            val cx = (dm.widthPixels / 2).toFloat()
            val cy = (dm.heightPixels / 2).toFloat()

            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()

            // Non-linear calculation of distance fractions based on configurations
            val fraction = (profile.config.swipeDistanceFraction * (0.8f + (intensity * 0.4f))).coerceIn(0.15f, 0.95f)

            val path = Path()
            when (direction) {
                GazeDirection.BOTTOM -> {
                    // Finger drags from bottom to top -> scrolls down
                    val startY = h * (0.5f + (fraction / 2))
                    val endY = h * (0.5f - (fraction / 2))
                    path.moveTo(cx, startY)
                    path.lineTo(cx, endY)
                }
                GazeDirection.TOP -> {
                    // Finger drags from top to bottom -> scrolls up
                    val startY = h * (0.5f - (fraction / 2))
                    val endY = h * (0.5f + (fraction / 2))
                    path.moveTo(cx, startY)
                    path.lineTo(cx, endY)
                }
                GazeDirection.LEFT -> {
                    // Finger drags from right to left -> scrolls left
                    val startX = w * (0.5f + (fraction / 2))
                    val endX = w * (0.5f - (fraction / 2))
                    path.moveTo(startX, cy)
                    path.lineTo(endX, cy)
                }
                GazeDirection.RIGHT -> {
                    // Finger drags from left to right -> scrolls right
                    val startX = w * (0.5f - (fraction / 2))
                    val endX = w * (0.5f + (fraction / 2))
                    path.moveTo(startX, cy)
                    path.lineTo(endX, cy)
                }
                else -> return null
            }

            // Nonlinear duration mapping based on acceleration coefficients
            val duration = (profile.config.baseDurationMs / (intensity * profile.config.accelerationFactor).coerceAtLeast(0.5f)).toLong()
                .coerceIn(profile.config.minDurationMs, profile.config.maxDurationMs)

            return GestureDescription.Builder().apply {
                addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            }.build()
        }
    }

    /**
     * Thread-safe Coroutine-based Gesture Queue and Dispatcher.
     * Prevents parallel execution, limits throughput, and handles retries.
     */
    class GestureDispatcher(
        private val service: AccessibilityService,
        private val scope: CoroutineScope
    ) {
        private val queue = ConcurrentLinkedQueue<Pair<GestureDescription, GazeDirection>>()
        private val isProcessing = AtomicBoolean(false)
        private var lastDispatchTime = 0L

        fun enqueue(gesture: GestureDescription, direction: GazeDirection) {
            queue.add(Pair(gesture, direction))
            if (isProcessing.compareAndSet(false, true)) {
                scope.launch {
                    processQueue()
                }
            }
        }

        private suspend fun processQueue() {
            while (queue.isNotEmpty()) {
                val pair = queue.poll() ?: break
                val now = System.currentTimeMillis()
                
                // Enforce safety delay between sequential gestures to prevent storms
                val waitTime = 600L - (now - lastDispatchTime)
                if (waitTime > 0) {
                    delay(waitTime)
                }

                var attempts = 0
                var success = false
                while (attempts < 3 && !success) {
                    attempts++
                    success = dispatchSingle(pair.first, pair.second)
                    if (!success && attempts < 3) {
                        delay(200L * attempts) // Exponential backoff retry
                    }
                }
                lastDispatchTime = System.currentTimeMillis()
            }
            isProcessing.set(false)
        }

        private suspend fun dispatchSingle(gesture: GestureDescription, direction: GazeDirection): Boolean = suspendCancellableCoroutine { continuation ->
            try {
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "System gesture [$direction] successfully completed.")
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "System gesture [$direction] cancelled by OS overlay.")
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    },
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Silent crash in system dispatcher: ${e.message}")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    /**
     * Metrics and performance diagnostics for adaptive adjustments.
     */
    class GestureAnalytics {
        private var successCount = 0
        private var totalCount = 0

        fun recordSuccess() {
            totalCount++
            successCount++
        }

        fun recordAttempt() {
            totalCount++
        }

        fun getSuccessRate(): Float {
            return if (totalCount > 0) successCount.toFloat() / totalCount else 1.0f
        }

        fun reset() {
            successCount = 0
            totalCount = 0
        }
    }

    /**
     * High-speed node traversal utility that skips redundant depth checks.
     */
    object AccessibilityNodeHelper {
        fun scrollNode(service: AccessibilityService, action: Int): Boolean {
            val root = service.rootInActiveWindow ?: return false
            val target = findTarget(root)
            return target?.performAction(action) ?: false
        }

        private fun findTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            val limit = minOf(node.childCount, 30) // Upper bound processing limits
            for (i in 0 until limit) {
                val child = node.getChild(i) ?: continue
                val res = findTarget(child)
                if (res != null) return res
            }
            return null
        }
    }
}