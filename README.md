# Mobile Hub

Unified Android app for:
- `invertor-bast`
- `load_controller`
- `garage`

Background notifications are generated locally in Android (no cloud required).

## Structure

- `android/` - Android app.
- `push-bridge/` - optional legacy FCM bridge (not required for local background notifications).

## Local background notifications

The app polls controllers in the background via `WorkManager` and shows system notifications for:
- PV generation appeared/disappeared.
- GRID ON/OFF.
- Mode changes of GRID/LOAD/BOILER1/PUMP/BOILER2/GATE with reason.

See setup in `android/README.md`.
