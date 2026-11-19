# Keep the JavaScript bridge interface methods
-keepclassmembers class com.dawebapps.carlauncher.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep class names for WebView JS bridge
-keepattributes JavascriptInterface
-keep class com.dawebapps.carlauncher.WebAppInterface { *; }
