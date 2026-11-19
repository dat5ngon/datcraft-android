package com.dawebapps.datcraft

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main activity -- displays dat5ngon.github.io in a full-screen WebView.
 * Injects the Datcraft JS bridge so web apps can trigger native navigation.
 *
 * When navigation starts:
 * 1. Start the floating overlay service (creates the floating WebView window)
 * 2. Wait briefly for the overlay to attach
 * 3. Launch Google Maps navigation intent
 * Google Maps takes the foreground; the floating overlay stays on top.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var popupWebView: WebView? = null
    private var pendingNavLat: Double = 0.0
    private var pendingNavLng: Double = 0.0
    private var pendingNavName: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var pendingDownloadId: Long = -1L
    private var downloadReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "Datcraft"
        const val HOME_URL = "https://dat5ngon.github.io"
        const val EXTRA_RESUME_URL = "resume_url"
        private const val OVERLAY_PERMISSION_REQUEST = 1234
        private const val LOCATION_PERMISSION_REQUEST = 1235
        private const val NOTIFICATION_PERMISSION_REQUEST = 1236
        private const val OVERLAY_SETTLE_DELAY_MS = 1500L

        /** Domains that should load inside the WebView instead of an external browser. */
        private val IN_WEBVIEW_HOSTS = listOf(
            "dat5ngon.github.io",
            "accounts.google.com",       // OAuth sign-in page
            "accounts.google.co",        // regional Google sign-in variants
            "googleapis.com",            // API calls (token exchange, etc.)
            "content.googleapis.com",
            "www.googleapis.com",
            "oauth2.googleapis.com",
            "lh3.googleusercontent.com", // profile pictures loaded during sign-in
        )

        /** Returns true if the URL should be loaded inside the WebView. */
        fun shouldLoadInWebView(url: String): Boolean {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            return IN_WEBVIEW_HOSTS.any { allowed ->
                host == allowed || host.endsWith(".$allowed")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "══════ MainActivity onCreate ══════")
        setContentView(R.layout.activity_main)

        // Request permissions early
        requestLocationPermission()
        requestNotificationPermission()

        // Check overlay permission on startup and prompt if not granted
        if (!hasOverlayPermission()) {
            requestOverlayPermission()
        }

        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            setSupportMultipleWindows(true)
            @Suppress("DEPRECATION")
            setGeolocationEnabled(true)
        }

        webView.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?
            ): Boolean {
                if (url == null) return false
                // Allow our own pages and Google OAuth/API URLs to load in-WebView
                if (shouldLoadInWebView(url)) return false
                // Everything else opens in an external browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) { }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin != null && origin.contains("dat5ngon.github.io")) {
                    callback?.invoke(origin, true, true)
                } else {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    callback?.invoke(origin, hasPermission, false)
                }
            }

            // Handle window.open() popups (used by Google OAuth consent flow).
            // Create a visible popup WebView overlaid on the main one so the
            // OAuth flow runs inside the app. When the popup calls window.close(),
            // onCloseWindow removes it and the GIS callback fires in the opener.
            @SuppressLint("SetJavaScriptEnabled")
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

                // Remove any previous popup
                removePopupWebView()

                val popup = WebView(this@MainActivity)
                popup.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportMultipleWindows(true)
                    userAgentString = webView.settings.userAgentString
                }

                // Share cookies between main and popup WebViews
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

                popup.webViewClient = object : WebViewClient() {
                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        url: String?
                    ): Boolean {
                        if (url != null && shouldLoadInWebView(url)) return false
                        return false // load everything in popup -- it's the OAuth flow
                    }
                }

                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        removePopupWebView()
                    }
                }

                // Add popup on top of the main WebView
                val container = findViewById<FrameLayout>(R.id.container)
                container.addView(popup, FrameLayout.LayoutParams(
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

        webView.addJavascriptInterface(
            WebAppInterface(this) { lat, lng, name ->
                runOnUiThread {
                    onNavigationRequested(lat, lng, name)
                }
            },
            "Datcraft"
        )

        // Handle file downloads (e.g. APK download from the home page)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            Log.i(TAG, "Download requested: $fileName from $url")
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(fileName)
                    setDescription("Downloading $fileName")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                pendingDownloadId = dm.enqueue(request)
                Toast.makeText(this, "Downloading $fileName…", Toast.LENGTH_SHORT).show()

                // Register receiver to prompt install when download completes
                registerDownloadCompleteReceiver()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed, opening in browser", e)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        // Load the resume URL if coming from overlay, otherwise load home
        val startUrl = intent?.getStringExtra(EXTRA_RESUME_URL) ?: HOME_URL
        webView.loadUrl(startUrl)

        // Toolbar buttons
        findViewById<android.view.View>(R.id.btnHome).setOnClickListener {
            webView.loadUrl(HOME_URL)
        }
        findViewById<android.view.View>(R.id.btnFloatLeft).setOnClickListener {
            startFloatingOverlay("left")
        }
        findViewById<android.view.View>(R.id.btnFloatRight).setOnClickListener {
            startFloatingOverlay("right")
        }
    }

    /**
     * Start the floating overlay service with the current WebView URL,
     * positioning the overlay on the specified side.
     */
    private fun startFloatingOverlay(side: String) {
        if (!hasOverlayPermission()) {
            Toast.makeText(this, "Please grant 'Display over other apps' permission", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        // Set the preferred side before starting the service
        val overlayManager = OverlayManager(this)
        overlayManager.side = side

        val currentUrl = webView.url ?: HOME_URL
        val serviceIntent = Intent(this, FloatingWebViewService::class.java).apply {
            putExtra(FloatingWebViewService.EXTRA_URL, currentUrl)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "Started floating overlay on $side side with URL: $currentUrl")

            // Go to home screen so the overlay floats over whatever app the user switches to
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start overlay service", e)
            Toast.makeText(this, "Failed to start overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestLocationPermission() {
        val fineLocation = Manifest.permission.ACCESS_FINE_LOCATION
        val coarseLocation = Manifest.permission.ACCESS_COARSE_LOCATION

        if (ContextCompat.checkSelfPermission(this, fineLocation) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(fineLocation, coarseLocation),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "Notification permission result: granted=$granted")
            }
        }
    }

    /**
     * Request POST_NOTIFICATIONS permission on Android 13+ (API 33).
     * Required for foreground services to show their mandatory notification.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "Requesting POST_NOTIFICATIONS permission")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }

    /**
     * Called when the JS bridge requests navigation.
     */
    private fun onNavigationRequested(lat: Double, lng: Double, name: String) {
        Log.i(TAG, "══════ Navigation requested: lat=$lat, lng=$lng, name=$name ══════")
        pendingNavLat = lat
        pendingNavLng = lng
        pendingNavName = name

        if (!hasOverlayPermission()) {
            Log.w(TAG, "Overlay permission NOT granted -- requesting")
            Toast.makeText(this, "Please grant 'Display over other apps' permission", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        Log.i(TAG, "Overlay permission OK -- launching sequence")
        launchNavigationSequence()
    }

    /**
     * Step 1: Start the floating overlay service.
     * Step 2: After a delay, launch Google Maps navigation.
     *
     * The overlay service creates a floating WebView window that stays on top
     * of Google Maps. Google Maps runs full-screen underneath.
     */
    private fun launchNavigationSequence() {
        Log.i(TAG, "══════ launchNavigationSequence ══════")

        // Step 1: Start the floating overlay with the home page
        val serviceIntent = Intent(this, FloatingWebViewService::class.java).apply {
            putExtra(FloatingWebViewService.EXTRA_URL, HOME_URL)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.i(TAG, "Calling startForegroundService()")
                startForegroundService(serviceIntent)
            } else {
                Log.i(TAG, "Calling startService()")
                startService(serviceIntent)
            }
            Log.i(TAG, "Service start call returned without exception")
        } catch (e: Exception) {
            Log.e(TAG, "FAILED to start overlay service", e)
            Toast.makeText(this, "Failed to start overlay: ${e.message}", Toast.LENGTH_LONG).show()
            // Fall back to just launching Maps without overlay
            launchGoogleMapsNavigation(pendingNavLat, pendingNavLng)
            return
        }

        // Step 2: Launch Google Maps after the overlay has time to attach.
        // Increased delay to give the service more time to create the overlay window.
        handler.postDelayed({
            Log.i(TAG, "Timer fired -- launching Google Maps navigation now")
            launchGoogleMapsNavigation(pendingNavLat, pendingNavLng)
            pendingNavLat = 0.0
            pendingNavLng = 0.0
            pendingNavName = ""

            // Navigate main WebView back to home so it's ready when user returns
            webView.loadUrl(HOME_URL)
        }, OVERLAY_SETTLE_DELAY_MS)
    }

    private fun launchGoogleMapsNavigation(lat: Double, lng: Double) {
        val navUri = Uri.parse("google.navigation:q=$lat,$lng&mode=d")
        val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(mapIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Google Maps not found, falling back to browser", e)
            val webUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1" +
                    "&destination=${Uri.encode("$lat,$lng")}" +
                    "&travelmode=driving"
            )
            startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AlertDialog.Builder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_message)
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (hasOverlayPermission()) {
                Log.d(TAG, "Overlay permission granted")
                Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
                if (pendingNavLat != 0.0) {
                    launchNavigationSequence()
                }
            } else {
                Log.w(TAG, "Overlay permission denied")
                Toast.makeText(this, "Overlay permission is required for split-screen navigation", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Remove the OAuth popup WebView and return to the main WebView. */
    private fun removePopupWebView() {
        popupWebView?.let { popup ->
            val container = findViewById<FrameLayout>(R.id.container)
            container.removeView(popup)
            popup.destroy()
        }
        popupWebView = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // If a popup is showing, close it first
        if (popupWebView != null) {
            removePopupWebView()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val resumeUrl = intent?.getStringExtra(EXTRA_RESUME_URL)
        if (!resumeUrl.isNullOrEmpty()) {
            Log.i(TAG, "onNewIntent: resuming URL $resumeUrl")
            webView.loadUrl(resumeUrl)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDownloadCompleteReceiver() {
        // Unregister any previous receiver
        downloadReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != pendingDownloadId) return

                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val uri = dm.getUriForDownloadedFile(id)
                if (uri != null) {
                    Log.i(TAG, "Download complete, prompting install: $uri")
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(installIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch installer", e)
                        Toast.makeText(context, "Please open the downloaded file to install", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.w(TAG, "Download failed or file not found")
                    Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                }

                // Clean up
                try { unregisterReceiver(this) } catch (_: Exception) {}
                downloadReceiver = null
                pendingDownloadId = -1L
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
        }
    }

    override fun onDestroy() {
        downloadReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        removePopupWebView()
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}
