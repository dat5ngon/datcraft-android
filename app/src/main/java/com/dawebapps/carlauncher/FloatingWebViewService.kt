package com.dawebapps.carlauncher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Foreground service that displays the WebView as a floating overlay window.
 * Started when the user taps "Start" in car-nav to launch Google Maps navigation.
 * Google Maps runs full-screen underneath; this overlay floats on top.
 */
class FloatingWebViewService : Service() {

    companion object {
        private const val TAG = "FloatingWebView"
        const val CHANNEL_ID = "floating_webview_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_URL = "url"
        const val ACTION_STOP = "com.dawebapps.carlauncher.STOP_OVERLAY"
        const val ACTION_SWAP_SIDE = "com.dawebapps.carlauncher.SWAP_SIDE"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var floatingWebView: WebView? = null
    private var popupWebView: WebView? = null
    private var overlayManager: OverlayManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "══════ Service onCreate ══════")
        createNotificationChannel()
        overlayManager = OverlayManager(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "══════ onStartCommand action=${intent?.action} ══════")

        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SWAP_SIDE) {
            swapSide()
            return START_STICKY
        }

        // Start as foreground service immediately (must be called within 5 seconds)
        // On Android 14+ (API 34), must pass the foreground service type explicitly
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "startForeground() succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground() FAILED", e)
            Toast.makeText(this, "Overlay service failed: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        // Check overlay permission inside the service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW permission not granted -- cannot create overlay")
            Toast.makeText(this, "Overlay permission not granted", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL) ?: "https://dat5ngon.github.io"
        Log.i(TAG, "Creating overlay with URL: $url")

        // Remove existing overlay if any
        removeOverlay()

        // Inflate the floating layout
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.floating_webview, null)

        if (overlayView == null) {
            Log.e(TAG, "Failed to inflate floating_webview layout")
            stopSelf()
            return START_NOT_STICKY
        }

        // Set up WebView
        floatingWebView = overlayView?.findViewById(R.id.floatingWebView)
        if (floatingWebView == null) {
            Log.e(TAG, "floatingWebView not found in layout")
            stopSelf()
            return START_NOT_STICKY
        }

        floatingWebView?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportMultipleWindows(true)

            webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    url: String?
                ): Boolean {
                    if (url == null) return false
                    // Allow our own pages and Google OAuth/API URLs in-WebView
                    if (MainActivity.shouldLoadInWebView(url)) return false
                    // Open everything else in an external browser
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(browserIntent)
                    } catch (_: Exception) { }
                    return true
                }
            }

            @SuppressLint("SetJavaScriptEnabled")
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

                    removePopupWebView()

                    val popup = WebView(this@FloatingWebViewService)
                    popup.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportMultipleWindows(true)
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

                    popup.webViewClient = object : WebViewClient() {
                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                            v: WebView?,
                            popupUrl: String?
                        ): Boolean {
                            return false // load everything in popup
                        }
                    }
                    popup.webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            removePopupWebView()
                        }
                    }

                    // Add popup on top of the floating WebView
                    val container = overlayView as? FrameLayout
                    container?.addView(popup, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    popupWebView = popup

                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    removePopupWebView()
                }
            }

            // Inject the same JS bridge so web apps know they're in the native shell
            addJavascriptInterface(
                WebAppInterface(this@FloatingWebViewService) { _, _, _ ->
                    // Navigation from floating overlay -- already in nav mode, ignore
                },
                "CarLauncher"
            )

            loadUrl(url)
        }

        // Create and add the overlay window
        val manager = overlayManager!!
        layoutParams = manager.createLayoutParams()

        // Allow touches to pass through to Google Maps outside the overlay area.
        // FLAG_NOT_TOUCH_MODAL: touches outside the overlay go to the app behind
        // FLAG_LAYOUT_IN_SCREEN: position relative to screen, not parent window
        // We do NOT set FLAG_NOT_FOCUSABLE so the WebView can receive touch/keyboard input.
        layoutParams?.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        try {
            windowManager?.addView(overlayView, layoutParams)
            Log.i(TAG, "══════ Overlay view ADDED to WindowManager ══════")
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "BadTokenException -- overlay permission may have been revoked", e)
            Toast.makeText(this, "Cannot create overlay -- permission issue", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            Toast.makeText(this, "Failed to create overlay: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        // Wire up control buttons
        setupControls()

        // Attach drag listener to the handle bar
        val dragHandle = overlayView?.findViewById<android.view.View>(R.id.dragHandle)
        if (dragHandle != null && overlayView != null && layoutParams != null) {
            manager.attachDragListener(dragHandle, overlayView!!, windowManager!!, layoutParams!!)
        }

        return START_STICKY
    }

    private fun setupControls() {
        // Back button -- navigate the floating WebView back
        overlayView?.findViewById<android.view.View>(R.id.btnBack)?.setOnClickListener {
            floatingWebView?.let { wv ->
                if (wv.canGoBack()) wv.goBack()
            }
        }

        // Swap sides button
        overlayView?.findViewById<android.view.View>(R.id.btnSwapSide)?.setOnClickListener {
            swapSide()
        }

        // Minimize button -- remove overlay, keep service running
        overlayView?.findViewById<android.view.View>(R.id.btnMinimize)?.setOnClickListener {
            removeOverlay()
        }

        // Full screen button -- stop overlay service, return to full-screen WebView
        overlayView?.findViewById<android.view.View>(R.id.btnFullScreen)?.setOnClickListener {
            // Launch MainActivity back to foreground
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(mainIntent)
            stopSelf()
        }
    }

    private fun swapSide() {
        val manager = overlayManager ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val view = overlayView ?: return

        val newSide = manager.swapSide()
        params.gravity = if (newSide == "left") Gravity.START or Gravity.TOP
                         else Gravity.END or Gravity.TOP
        params.x = 0

        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
            // View may have been removed
        }
    }

    private fun removePopupWebView() {
        popupWebView?.let { popup ->
            val container = overlayView as? FrameLayout
            container?.removeView(popup)
            popup.destroy()
        }
        popupWebView = null
    }

    private fun removeOverlay() {
        removePopupWebView()
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "Overlay view removed")
            } catch (_: Exception) {
                // Already removed
            }
        }
        floatingWebView?.destroy()
        floatingWebView = null
        overlayView = null
    }

    override fun onDestroy() {
        Log.i(TAG, "══════ Service onDestroy ══════")
        removeOverlay()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingWebViewService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPending = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(mainPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPending
            )
            .setOngoing(true)
            .build()
    }
}
