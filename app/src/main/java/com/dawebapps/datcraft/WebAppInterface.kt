package com.dawebapps.datcraft

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * JavaScript bridge injected into the WebView as `window.Datcraft`.
 * Allows the car-nav web app to trigger native Google Maps navigation
 * and control the floating overlay.
 */
class WebAppInterface(
    private val context: Context,
    private val onStartNavigation: (lat: Double, lng: Double, name: String) -> Unit
) {

    /** Web apps call this to detect they're running inside the native shell. */
    @JavascriptInterface
    fun isDatcraft(): Boolean = true

    /**
     * Called from car-nav/map.js when the user taps "Start".
     * Delegates to the activity which orchestrates the overlay + Maps launch.
     */
    @JavascriptInterface
    fun startNavigation(lat: Double, lng: Double, name: String) {
        onStartNavigation(lat, lng, name)
    }
}
