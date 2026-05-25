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
        Log.i(TAG, "=====> onDestroy: GazeAccessibilityService is being shut down <=====")
        if (instance == this) {
            Log.d(TAG, "Clearing static instance singleton reference.")
            instance = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Node-based scrolling helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun scrollNode(action: Int): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(rootNode)
        if (scrollableNode != null) {
            return scrollableNode.performAction(action)
        }
        return false
    }

    private fun findScrollableNode(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gesture helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a linear swipe gesture and dispatches it.
     *
     * @param startX  swipe start X (pixels)
     * @param startY  swipe start Y (pixels)
     * @param endX    swipe end   X (pixels)
     * @param endY    swipe end   Y (pixels)
     * @param intensity speed multiplier from [GazeForegroundService.scrollSpeed]
     * @param tag     log label
     */
    private fun dispatchSwipe(
        startX: Float, startY: Float,
        endX: Float,   endY: Float,
        intensity: Float,
        tag: String
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        // 300 ms base duration; intensity > 1 makes it faster, < 1 makes it slower.
        val flickDuration = (300L / intensity.coerceIn(0.1f, 5f)).toLong().coerceIn(150L, 500L)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, flickDuration))

        return dispatchGesture(
            gestureBuilder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "$tag gesture completed successfully.")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "$tag gesture was cancelled by the system.")
                }
            },
            null
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public scroll / swipe API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scrolls the active view downward (content moves up — finger drags from bottom to top).
     * Tries an accessibility node action first; falls back to a gesture swipe.
     */
    fun performScrollDown(intensity: Float = 1.0f): Boolean {
        Log.d(TAG, "performScrollDown requested (intensity=$intensity).")

        if (scrollNode(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            Log.i(TAG, "Scrolled forward via node action.")
            return true
        }

        val dm = resources.displayMetrics
        val cx = (dm.widthPixels / 2).toFloat()
        // Finger starts near the bottom third and lifts toward the top third.
        val startY = dm.heightPixels * 0.75f
        val endY   = dm.heightPixels * 0.25f

        return dispatchSwipe(cx, startY, cx, endY, intensity, "ScrollDown")
    }

    /**
     * Scrolls the active view upward (content moves down — finger drags from top to bottom).
     * Tries an accessibility node action first; falls back to a gesture swipe.
     */
    fun performScrollUp(intensity: Float = 1.0f): Boolean {
        Log.d(TAG, "performScrollUp requested (intensity=$intensity).")

        if (scrollNode(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            Log.i(TAG, "Scrolled backward via node action.")
            return true
        }

        val dm = resources.displayMetrics
        val cx = (dm.widthPixels / 2).toFloat()
        val startY = dm.heightPixels * 0.25f
        val endY   = dm.heightPixels * 0.75f

        return dispatchSwipe(cx, startY, cx, endY, intensity, "ScrollUp")
    }

    /**
     * Swipes the screen to the left (content scrolls left — e.g. next page/tab).
     * Finger drags from the left edge toward the right edge.
     *
     * NOTE: the original implementation had the start/end comments inverted; this
     * version is correct: to move content LEFT, the finger starts on the RIGHT.
     */
    fun performScrollLeft(intensity: Float = 1.0f): Boolean {
        Log.d(TAG, "performScrollLeft requested (intensity=$intensity).")

        val dm = resources.displayMetrics
        val cy = (dm.heightPixels / 2).toFloat()
        // Finger starts on the right and drags to the left → content moves left.
        val startX = dm.widthPixels * 0.75f
        val endX   = dm.widthPixels * 0.25f

        return dispatchSwipe(startX, cy, endX, cy, intensity, "SwipeLeft")
    }

    /**
     * Swipes the screen to the right (content scrolls right — e.g. previous page/tab).
     * Finger drags from the right edge toward the left edge.
     */
    fun performScrollRight(intensity: Float = 1.0f): Boolean {
        Log.d(TAG, "performScrollRight requested (intensity=$intensity).")

        val dm = resources.displayMetrics
        val cy = (dm.heightPixels / 2).toFloat()
        // Finger starts on the left and drags to the right → content moves right.
        val startX = dm.widthPixels * 0.25f
        val endX   = dm.widthPixels * 0.75f

        return dispatchSwipe(startX, cy, endX, cy, intensity, "SwipeRight")
    }
}