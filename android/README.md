# Android App (my Home)

Unified interface for inverter, load controller, and garage.

## Background notifications (without cloud)

The app uses `WorkManager` for background polling and local system notifications.

Events:
- PV generation appeared/disappeared.
- GRID turned ON/OFF.
- Mode changes with reason:
  - grid
  - load
  - boiler1
  - pump
  - boiler2
  - gate

Important Android limitation:
- periodic background work is scheduled by the OS and has a minimum period around 15 minutes.
- for near real-time alerts, the app must stay open or use a foreground service.

## Build

```powershell
cd mobile-hub\android
.\gradlew.bat assembleDebug
```

APK path:
- `app/build/outputs/apk/debug/app-debug.apk`
