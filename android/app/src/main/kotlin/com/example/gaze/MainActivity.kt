package com.example.gaze

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.util.Log

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.example.gaze/background_service"
    private var telemetryChannel: MethodChannel? = null
    private val CAMERA_PERMISSION_CODE = 1001

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        telemetryChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.example.gaze/telemetry")
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    // Check camera permission first to avoid Foreground Service SecurityException
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        Log.w("MainActivity", "FGS type camera requires CAMERA permission. Requesting now.")
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
                        result.error("PERMISSION_DENIED", "Camera permission is required to start the background gaze tracking service.", null)
                        return@setMethodCallHandler
                    }

                    val sens = call.argument<Double>("sensitivity")?.toFloat() ?: 0.5f
                    val speed = call.argument<Double>("scrollSpeed")?.toFloat() ?: 1.0f
                    val duration = call.argument<Int>("triggerDurationMs")?.toLong() ?: 800L
                    val pauseOnLook = call.argument<Boolean>("pauseOnLookAway") ?: false
                    val systemWide = call.argument<Boolean>("systemWide") ?: true
                    val apps = call.argument<List<String>>("enabledApps") ?: emptyList()
                    val swipeMode = call.argument<String>("swipeMode") ?: "eyeTracking"
                    
                    GazeForegroundService.sensitivity = sens
                    GazeForegroundService.scrollSpeed = speed
                    GazeForegroundService.triggerDurationMs = duration
                    GazeForegroundService.pauseOnLookAway = pauseOnLook
                    GazeForegroundService.systemWide = systemWide
                    GazeForegroundService.enabledApps = apps
                    GazeForegroundService.swipeMode = swipeMode
                    
                    try {
                        val serviceIntent = Intent(this, GazeForegroundService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                        
                        // Register telemetry pipe to Flutter
                        GazeForegroundService.telemetryListener = { state ->
                            runOnUiThread {
                                val data = mapOf(
                                    "isFaceDetected" to state.isFaceDetected,
                                    "isAttentive" to state.isAttentive,
                                    "isLookingDown" to state.isLookingDown,
                                    "isLookingUp" to state.isLookingUp,
                                    "isBlinking" to state.isBlinking,
                                    "yaw" to state.yaw,
                                    "pitch" to state.pitch,
                                    "eyeOpenness" to state.eyeOpenness,
                                    "isNodLeft" to state.isNodLeft,
                                    "isNodRight" to state.isNodRight,
                                    "isNodUp" to state.isNodUp,
                                    "isNodDown" to state.isNodDown,
                                    "detectedHandGesture" to state.detectedHandGesture,
                                    "isSwipeLeft" to state.isSwipeLeft,
                                    "isSwipeRight" to state.isSwipeRight,
                                    "isSwipeUp" to state.isSwipeUp,
                                    "isSwipeDown" to state.isSwipeDown,
                                    "activeApp" to GazeAccessibilityService.activePackageName,
                                    "rawConfidence" to state.rawConfidence,
                                    "internalState" to state.internalState
                                )
                                telemetryChannel?.invokeMethod("onGazeStateChanged", data)
                            }
                        }
                        result.success(true)
                    } catch (e: SecurityException) {
                        Log.e("MainActivity", "Failed to start FGS due to SecurityException: ${e.message}")
                        result.error("SECURITY_EXCEPTION", "Foreground service camera permission error: ${e.message}", null)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to start service: ${e.message}")
                        result.error("SERVICE_START_FAILED", e.message, null)
                    }
                }
                "stopService" -> {
                    val serviceIntent = Intent(this, GazeForegroundService::class.java)
                    stopService(serviceIntent)
                    GazeForegroundService.telemetryListener = null
                    result.success(true)
                }
                "isServiceRunning" -> {
                    result.success(GazeForegroundService.isServiceRunning)
                }
                "isAccessibilityServiceEnabled" -> {
                    result.success(GazeAccessibilityService.instance != null)
                }
                "openAccessibilitySettings" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    result.success(true)
                }
                "openOverlaySettings" -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "isOverlayPermissionGranted" -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        result.success(Settings.canDrawOverlays(this))
                    } else {
                        result.success(true)
                    }
                }
                "openBatteryOptimizationSettings" -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "isBatteryExempted" -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        result.success(pm.isIgnoringBatteryOptimizations(packageName))
                    } else {
                        result.success(true)
                    }
                }
                "getActivePackageName" -> {
                    result.success(GazeAccessibilityService.activePackageName)
                }
                "triggerScrollDown" -> {
                    val speed = call.argument<Double>("scrollSpeed")?.toFloat() ?: 1.0f
                    val success = GazeAccessibilityService.instance?.performScrollDown(speed) ?: false
                    result.success(success)
                }
                "triggerScrollUp" -> {
                    val speed = call.argument<Double>("scrollSpeed")?.toFloat() ?: 1.0f
                    val success = GazeAccessibilityService.instance?.performScrollUp(speed) ?: false
                    result.success(success)
                }
                "triggerScrollLeft" -> {
                    val speed = call.argument<Double>("scrollSpeed")?.toFloat() ?: 1.0f
                    val success = GazeAccessibilityService.instance?.performScrollLeft(speed) ?: false
                    result.success(success)
                }
                "triggerScrollRight" -> {
                    val speed = call.argument<Double>("scrollSpeed")?.toFloat() ?: 1.0f
                    val success = GazeAccessibilityService.instance?.performScrollRight(speed) ?: false
                    result.success(success)
                }
                "isCameraPermissionGranted" -> {
                    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    result.success(granted)
                }
                "requestCameraPermission" -> {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
                    result.success(true)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }
}
