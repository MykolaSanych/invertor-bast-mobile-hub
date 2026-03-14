# Windows Desktop App

This folder runs a Windows desktop version of the existing Android hub UI.

It does not reimplement the interface. It serves the same `hub.html`, `hub.css`, and `hub.js` from the Android app and provides a local desktop bridge that mimics `window.AndroidHub`.

## Requirements

- Windows
- Python 3.12+ in `PATH`
- Microsoft Edge for app-mode launch (optional but recommended)

## Start

From the repo root:

```powershell
.\windows\launch.ps1
```

Or double-click:

```text
windows\launch.cmd
```

This starts a local host on `http://127.0.0.1:8765/` and opens the UI in Edge app mode.

## Stop

```powershell
.\windows\stop.ps1
```

## Runtime Data

Local config, event journal, automation history, and launcher PID files are stored in:

```text
windows\data
```

Edge app profile data is stored in:

```text
windows\edge-profile
```
