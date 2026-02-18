# Push Bridge (FCM)

`push-bridge` polls:
- `invertor-bast`
- `load_controller`
- `garage`

and sends Firebase Cloud Messaging notifications to topic `home-events`.

This allows push notifications even when Android app is inactive.

## Events covered

- PV generation appeared / disappeared.
- GRID relay turned ON / OFF.
- Mode changes with reason:
  - grid
  - load
  - boiler1
  - pump
  - boiler2
  - gate

## 1. Firebase setup

1. Create Firebase project.
2. Enable Cloud Messaging.
3. Download Android `google-services.json` and place it at:
   - `mobile-hub/android/app/google-services.json`
4. Create Firebase Service Account key (JSON) and place it at:
   - `mobile-hub/push-bridge/service-account.json`

## 2. Push bridge setup

```powershell
cd mobile-hub\push-bridge
.\setup.ps1
```

Then edit `.env`:
- set module URLs/passwords (use ZeroTier IPs if needed)
- set `FIREBASE_CREDENTIALS_JSON`

Example important values:
- `POLL_INTERVAL_SEC=5`
- `REQUEST_TIMEOUT_SEC=5`
- `FCM_TOPIC=home-events`
- `FCM_DRY_RUN=0`

## 3. Run push bridge

```powershell
cd mobile-hub\push-bridge
.\run.ps1
```

Health check:
- `GET http://127.0.0.1:8080/health`
- `GET http://127.0.0.1:8080/probe`

Manual test push:

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/test-push -ContentType "application/json" -Body '{"title":"Test","body":"Push works","reason":"manual"}'
```

## 4. Auto-start on Windows login

Install scheduled task:

```powershell
cd mobile-hub\push-bridge
.\install-startup-task.ps1
```

Remove task:

```powershell
cd mobile-hub\push-bridge
.\remove-startup-task.ps1
```

## API

- `GET /health`
- `GET /probe`
- `POST /poll-once`
- `POST /test-push`
- `GET /last-events?limit=50`
