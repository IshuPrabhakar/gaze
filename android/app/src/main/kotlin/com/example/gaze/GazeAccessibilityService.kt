package com.example.gaze

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class GazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GazeAccessibility"
        
        // Singleton instance accessible by GazeForegroundService
        var instance: GazeAccessibilityService? = null
            private set
            
        // Tracks the package name of the app currently visible on screen
        var activePackageName: String = ""
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "=====> onServiceConnected: Accessibility Service initialized and bound by system <=====")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            Log.v(TAG, "onAccessibilityEvent received a null event frame.")
            return
        }
        
        // Detect when the user switches apps to adjust configurations dynamically if needed
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName
            if (pkg != null) {
                val oldPackage = activePackageName
                activePackageName = pkg.toString()
                Log.d(TAG, "App Switch Detected | Previous: '$oldPackage' -> Current Active: '$activePackageName'")
            } else {
                Log.w(TAG, "TYPE_WINDOW_STATE_CHANGED occurred but package name data is missing.")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "=====> onInterrupt: System has interrupted or suspended the accessibility service <=====")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "=====> onDestroy: GazeAccessibilityService is being shutting down <=====")
        if (instance == this) {
            Log.d(TAG, "Clearing static instance singleton reference.")
            instance = null
        }
    }

    private fun scrollNode(action: Int): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(rootNode)
        if (scrollableNode != null) {
            val result = scrollableNode.performAction(action)
            return result
        }
        return false
    }

    private fun findScrollableNode(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }

    fun performScrollDown(intensity: Float = 1.0f): Boolean {
        Log.d(TAG, "performScrollDown requested.")
        
        // Try node-based scrolling first for standard apps
        if (scrollNode(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            Log.i(TAG, "Successfully scrolled forward using node action.")
            return true
        }
        
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        val startX = (screenWidth / 2).toFloat()
        val startY = (screenHeight * 0.75).toFloat() // Start lower
        val endY = (screenHeight * 0.25).toFloat()   // Pull up higher
        
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        
        val gestureBuilder = GestureDescription.Builder()
        
        // 300ms is standard for a quick but reliable swipe
        val flickDuration = (300L / intensity).toLong().coerceIn(150L, 500L)
        
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, flickDuration)
        gestureBuilder.addStroke(strokeDescription)
        
        return dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.i(TAG, "Scroll down gesture completed.")
            }
        }, null)
    }

    fun performScrollUp(intensity: Float = 1.0f): Boolean {
        Log.d(TAG, "performScrollUp requested.")
        
        if (scrollNode(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            Log.i(TAG, "Successfully scrolled backward using node action.")
            return true
        }
        
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        val startX = (screenWidth / 2).toFloat()
        val startY = (screenHeight * 0.25).toFloat() // Start above
        val endY = (screenHeight * 0.75).toFloat()   // Push down
        
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        
        val gestureBuilder = GestureDescription.Builder()
        
        val flickDuration = (300L / intensity).toLong().coerceIn(150L, 500L)
        
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, flickDuration)
        gestureBuilder.addStroke(strokeDescription)
        
        return dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.i(TAG, "Scroll up gesture completed.")
            }
        }, null)
    }
}