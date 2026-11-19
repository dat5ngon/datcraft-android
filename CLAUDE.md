# Datcraft

Native Android app for car head units. Displays `dat5ngon.github.io` in a full-screen WebView. When the user starts navigation from the car-nav web app, the app enters split-screen mode with Google Maps.

## Architecture

- `MainActivity.kt` -- full-screen WebView, hosts the JS bridge, orchestrates split-screen launch
- `WebAppInterface.kt` -- JavaScript bridge (`window.Datcraft`) for web-to-native communication
- `FloatingWebViewService.kt` -- foreground service that manages the floating WebView overlay
- `OverlayManager.kt` -- handles floating window positioning, sizing, drag, and side-swap

## Split-Screen Navigation (Floating Overlay)

When the user taps "Start" in car-nav:

1. `MainActivity` starts `FloatingWebViewService` (foreground service)
2. The service creates a floating WebView overlay window using `SYSTEM_ALERT_WINDOW` / `TYPE_APPLICATION_OVERLAY`
3. After a 1.5s delay (to let the overlay attach), `MainActivity` launches Google Maps navigation intent
4. Google Maps runs full-screen underneath; the floating overlay stays on top

On Android 14+ (API 34), `startForeground()` must pass `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` explicitly.
On Android 13+ (API 33), `POST_NOTIFICATIONS` permission is required for the foreground service notification.

## JS Bridge API

The native app injects `window.Datcraft` into the WebView:

- `startNavigation(lat, lng, name)` -- triggers split-screen with Google Maps navigation
- `isDatcraft()` -- returns `true` so web apps can detect they're running inside the native shell

## Permissions

- `INTERNET` -- WebView
- `SYSTEM_ALERT_WINDOW` -- floating overlay window
- `FOREGROUND_SERVICE` -- keep overlay service alive
- `FOREGROUND_SERVICE_SPECIAL_USE` -- Android 14+ foreground service type
- `POST_NOTIFICATIONS` -- Android 13+ foreground service notification
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` -- geolocation for car-nav

## Build

```
i run-datcraft
```

## Local Development

Requires Android Studio or Gradle with Android SDK. Min SDK 24 (Android 7.0), target SDK 34.
