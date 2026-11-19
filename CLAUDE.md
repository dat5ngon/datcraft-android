# Datcraft (Car Launcher)

Native Android app for car head units. Displays `dat5ngon.github.io` in a full-screen WebView. When the user starts navigation from the car-nav web app, the app enters split-screen mode with Google Maps.

## Architecture

- `MainActivity.kt` -- full-screen WebView, hosts the JS bridge, orchestrates split-screen launch
- `WebAppInterface.kt` -- JavaScript bridge (`window.CarLauncher`) for web-to-native communication
- `FloatingWebViewService.kt` -- foreground service that manages the floating WebView overlay (fallback mode)
- `OverlayManager.kt` -- handles floating window positioning, sizing, drag, and side-swap (fallback mode)

## Split-Screen Navigation

When the user taps "Start" in car-nav:

1. **Primary mode (split-screen)**: Uses Android's multi-window API (`FLAG_ACTIVITY_LAUNCH_ADJACENT`) to launch Google Maps in the adjacent panel. The car launcher stays in one half, Google Maps in the other. Requires Android 7+ (API 24, which is the app's minSdk).

2. **Fallback mode (floating overlay)**: If split-screen fails or isn't supported, falls back to the original approach -- launches Google Maps full-screen and keeps the WebView as a floating overlay window on top using `SYSTEM_ALERT_WINDOW`.

## JS Bridge API

The native app injects `window.CarLauncher` into the WebView:

- `startNavigation(lat, lng, name)` -- triggers split-screen with Google Maps navigation
- `isCarLauncher()` -- returns `true` so web apps can detect they're running inside the native shell

## Permissions

- `INTERNET` -- WebView
- `SYSTEM_ALERT_WINDOW` -- floating overlay (fallback mode)
- `FOREGROUND_SERVICE` -- keep overlay service alive (fallback mode)
- `FOREGROUND_SERVICE_SPECIAL_USE` -- Android 14+ foreground service type
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` -- geolocation for car-nav

## Build

```
i run-datcraft
```

## Local Development

Requires Android Studio or Gradle with Android SDK. Min SDK 24 (Android 7.0), target SDK 34.
