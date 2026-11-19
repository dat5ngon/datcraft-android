# Datcraft

Native Android app for car head units that wraps [dat5ngon.github.io](https://dat5ngon.github.io) in a full-screen WebView. When the user starts navigation from the car-nav web app, Google Maps opens for turn-by-turn navigation while the WebView becomes a floating overlay panel.

## How It Works

1. App launches with a full-screen WebView showing `dat5ngon.github.io`
2. User taps "Go Places" to open the car-nav web app
3. User searches a destination and taps "Start"
4. The web app detects the native `Datcraft` JS bridge and calls `window.Datcraft.startNavigation(lat, lng, name)`
5. The native app launches Google Maps navigation via intent
6. The WebView becomes a floating overlay panel on one side of the screen
7. User can drag, resize, swap sides, minimize, or return to full-screen WebView

## Features

- **Full-screen WebView** -- all web apps from dat5ngon.github.io work natively
- **JS bridge** -- `window.Datcraft` object injected for web-to-native communication
- **Floating overlay** -- WebView floats alongside Google Maps during navigation
- **Drag & position** -- overlay panel can be dragged to any position
- **Side swap** -- quickly move the panel from left to right side
- **Home launcher** -- can be set as the default home app on car head units
- **Landscape optimized** -- designed for car head unit screens

## Requirements

- Android 7.0+ (API 24)
- Google Maps app installed (for navigation)
- "Display over other apps" permission (for floating overlay)
- Notification permission (Android 13+, for foreground service)

## Build

```bash
# Debug build
./gradlew assembleDebug

# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | WebView needs network access |
| `SYSTEM_ALERT_WINDOW` | Floating overlay window on top of Google Maps |
| `FOREGROUND_SERVICE` | Keep the floating overlay service alive |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ foreground service type |
| `POST_NOTIFICATIONS` | Android 13+ foreground service notification |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Geolocation for car-nav |

## Architecture

```
app/src/main/java/com/dawebapps/datcraft/
  MainActivity.kt           -- Full-screen WebView, hosts JS bridge
  WebAppInterface.kt         -- JS bridge (window.Datcraft)
  FloatingWebViewService.kt  -- Foreground service for floating overlay
  OverlayManager.kt          -- Overlay positioning, drag, side-swap
```

## Car-Nav Integration

The car-nav web app (`car-nav/map.js`) detects the native bridge:

```javascript
if (window.Datcraft && window.Datcraft.startNavigation) {
    window.Datcraft.startNavigation(lat, lng, name);
    return;
}
// Fallback: open Google Maps externally
```

When running in a regular browser, the existing behavior (opening Google Maps in a new tab) is preserved.
