package com.dawebapps.datcraft

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Manages the floating WebView overlay window -- positioning, sizing,
 * drag-to-move, and side-swapping.
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "datcraft_prefs"
        private const val KEY_SIDE = "overlay_side"       // "left" or "right"
        private const val KEY_WIDTH_PCT = "overlay_width"  // 0.0-1.0
        private const val DEFAULT_WIDTH_PCT = 0.4f
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var side: String
        get() = prefs.getString(KEY_SIDE, "left") ?: "left"
        set(value) = prefs.edit().putString(KEY_SIDE, value).apply()

    var widthPercent: Float
        get() = prefs.getFloat(KEY_WIDTH_PCT, DEFAULT_WIDTH_PCT)
        set(value) = prefs.edit().putFloat(KEY_WIDTH_PCT, value.coerceIn(0.2f, 0.8f)).apply()

    /** Create WindowManager.LayoutParams for the floating overlay. */
    fun createLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val overlayW = (screenW * widthPercent).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            overlayW,
            screenH,
            type,
            // Default flags -- the service overrides these for proper touch handling.
            // FLAG_NOT_TOUCH_MODAL: touches outside the overlay pass to the app behind
            // FLAG_LAYOUT_IN_SCREEN: position relative to screen edges
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (side == "left") Gravity.START or Gravity.TOP
                      else Gravity.END or Gravity.TOP
            x = 0
            y = 0
        }
    }

    /** Swap the overlay to the opposite side of the screen. */
    fun swapSide(): String {
        side = if (side == "left") "right" else "left"
        return side
    }

    /**
     * Attach a drag-to-move touch listener to the given handle view.
     * Updates the overlay position via the WindowManager.
     */
    fun attachDragListener(
        handle: View,
        overlayView: View,
        windowManager: WindowManager,
        params: WindowManager.LayoutParams
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(overlayView, params)
                    } catch (_: Exception) {
                        // View may have been removed
                    }
                    true
                }
                else -> false
            }
        }
    }
}
