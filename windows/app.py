from __future__ import annotations

import argparse
import http.cookiejar
import json
import mimetypes
import os
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


DEFAULT_WEB_PASSWORD = "0961737595"
LEGACY_WEB_PASSWORD = "admin"
TIME_SYNC_BROWSER_PATH = "/api/time/syncbrowser"
PV_ACTIVE_THRESHOLD_W = 80.0
PV_TRANSITION_DEBOUNCE_MIN_MS = 3_000
PV_TRANSITION_DEBOUNCE_MAX_MS = 15_000
MAX_EVENT_ENTRIES = 300
MAX_EVENT_RESPONSE_ENTRIES = 200
MAX_HISTORY_WINDOW_MS = 6 * 60 * 60 * 1000
MAX_HISTORY_ENTRIES = 1600
MIN_HISTORY_SAMPLE_GAP_MS = 20_000
CONTROLLER_STATUS_TIMEOUT_SEC = 2.0
CONTROLLER_IO_TIMEOUT_SEC = 8.0
CONTROLLER_TIME_SYNC_THROTTLE_MS = 60_000
LOGIC_UNSTABLE_WINDOW_MS = 30 * 60 * 1000
LOGIC_UNSTABLE_TRANSITIONS = 4
LOGIC_UNSTABLE_COOLDOWN_MS = 20 * 60 * 1000

MODULE_INVERTER = "inverter"
MODULE_LOAD = "load_controller"
MODULE_GARAGE = "garage"

DEFAULT_CONFIG: dict[str, Any] = {
    "inverterBaseUrl": "http://192.168.1.2",
    "inverterPassword": DEFAULT_WEB_PASSWORD,
    "loadControllerBaseUrl": "http://192.168.1.3",
    "loadControllerPassword": DEFAULT_WEB_PASSWORD,
    "garageBaseUrl": "http://192.168.1.4",
    "garagePassword": DEFAULT_WEB_PASSWORD,
    "pollIntervalSec": 5,
    "inverterEnabled": True,
    "loadControllerEnabled": True,
    "garageEnabled": True,
    "realtimeMonitorEnabled": False,
    "realtimePollIntervalSec": 5,
    "notifyPvGeneration": True,
    "notifyGridRelay": True,
    "notifyGridPresence": True,
    "notifyGridMode": True,
    "notifyLoadMode": True,
    "notifyBoiler1Mode": True,
    "notifyPumpMode": True,
    "notifyBoiler2Mode": True,
    "notifyGateState": True,
    "notifyModuleOffline": True,
    "notifyPowerOverload": True,
    "notifyLogicUnstable": True,
}

CONFIG_STRING_KEYS = (
    "inverterBaseUrl",
    "inverterPassword",
    "loadControllerBaseUrl",
    "loadControllerPassword",
    "garageBaseUrl",
    "garagePassword",
)

CONFIG_BOOL_KEYS = (
    "inverterEnabled",
    "loadControllerEnabled",
    "garageEnabled",
    "realtimeMonitorEnabled",
    "notifyPvGeneration",
    "notifyGridRelay",
    "notifyGridPresence",
    "notifyGridMode",
    "notifyLoadMode",
    "notifyBoiler1Mode",
    "notifyPumpMode",
    "notifyBoiler2Mode",
    "notifyGateState",
    "notifyModuleOffline",
    "notifyPowerOverload",
    "notifyLogicUnstable",
)

CONFIG_INT_LIMITS = {
    "pollIntervalSec": (2, 60),
    "realtimePollIntervalSec": (3, 60),
}


def now_ms() -> int:
    return int(time.time() * 1000)


def clamp(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(maximum, value))


def normalize_password(value: Any) -> str:
    raw = str(value or "").strip()
    if not raw or raw == LEGACY_WEB_PASSWORD:
        return DEFAULT_WEB_PASSWORD
    return raw


def coerce_bool(value: Any, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "on", "yes"}:
            return True
        if normalized in {"false", "0", "off", "no"}:
            return False
    return default


def coerce_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(float(value))
    except (TypeError, ValueError):
        return default
    return clamp(parsed, minimum, maximum)


def coerce_float(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def safe_text(value: Any, fallback: str = "") -> str:
    text = str(value or "").strip()
    return text or fallback


def opt_nullable_double(payload: dict[str, Any], key: str) -> float | None:
    if key not in payload:
        return None
    raw = payload.get(key)
    if raw is None:
        return None
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None


def opt_double_any(payload: dict[str, Any], default: float, *keys: str) -> float:
    for key in keys:
        value = opt_nullable_double(payload, key)
        if value is not None:
            return value
    return default


def opt_long_any(payload: dict[str, Any], default: int, *keys: str) -> int:
    for key in keys:
        if key not in payload:
            continue
        raw = payload.get(key)
        if raw is None:
            continue
        try:
            return int(float(raw))
        except (TypeError, ValueError):
            continue
    return default


def opt_bool(payload: dict[str, Any], key: str, default: bool = False) -> bool:
    if key not in payload:
        return default
    return coerce_bool(payload.get(key), default)


def opt_string_any(payload: dict[str, Any], fallback: str, *keys: str) -> str:
    for key in keys:
        raw = payload.get(key)
        if raw is None:
            continue
        text = str(raw).strip()
        if text:
            return text
    return fallback


def read_json_file(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def write_json_file(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_suffix(path.suffix + ".tmp")
    temp_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    temp_path.replace(path)


def normalize_base_url(value: Any) -> str:
    trimmed = str(value or "").strip().rstrip("/")
    if not trimmed:
        return ""
    if trimmed.startswith("http://") or trimmed.startswith("https://"):
        return trimmed
    return f"http://{trimmed}"


def build_url(base_url: str, path: str, query: dict[str, Any] | None = None) -> str:
    url = f"{base_url}{path}"
    if not query:
        return url
    pairs = []
    for key, value in query.items():
        text = str(value or "").strip()
        if text:
            pairs.append((key, text))
    if not pairs:
        return url
    return f"{url}?{urllib.parse.urlencode(pairs)}"


class HubBackend:
    def __init__(self, repo_dir: Path, windows_dir: Path) -> None:
        self.repo_dir = repo_dir
        self.windows_dir = windows_dir
        self.assets_dir = repo_dir / "android" / "app" / "src" / "main" / "assets"
        self.data_dir = windows_dir / "data"
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookie_jar))
        self.time_sync_at_ms: dict[str, int] = {}

        self.config_path = self.data_dir / "config.json"
        self.events_path = self.data_dir / "event_journal.json"
        self.history_path = self.data_dir / "automation_history.json"
        self.snapshot_path = self.data_dir / "status_snapshot.json"
        self.pv_debounce_path = self.data_dir / "pv_debounce.json"
        self.alert_throttle_path = self.data_dir / "alert_throttle.json"

    def get_config(self) -> dict[str, Any]:
        with self.lock:
            stored = read_json_file(self.config_path, {})
            payload = dict(DEFAULT_CONFIG)
            if isinstance(stored, dict):
                payload.update(stored)
            for key in CONFIG_STRING_KEYS:
                raw = payload.get(key, DEFAULT_CONFIG[key])
                if key.endswith("Password"):
                    payload[key] = normalize_password(raw)
                else:
                    payload[key] = str(raw or "").strip()
            for key in CONFIG_BOOL_KEYS:
                payload[key] = coerce_bool(payload.get(key), bool(DEFAULT_CONFIG[key]))
            for key, (minimum, maximum) in CONFIG_INT_LIMITS.items():
                payload[key] = coerce_int(payload.get(key), int(DEFAULT_CONFIG[key]), minimum, maximum)
            return payload

    def save_config(self, raw_json: str) -> tuple[bool, dict[str, Any]]:
        with self.lock:
            current = self.get_config()
            try:
                parsed = json.loads(raw_json or "{}")
            except json.JSONDecodeError:
                return False, current
            if not isinstance(parsed, dict):
                return False, current

            updated = dict(current)
            for key in CONFIG_STRING_KEYS:
                if key not in parsed:
                    continue
                raw = parsed.get(key)
                if key.endswith("Password"):
                    updated[key] = normalize_password(raw)
                else:
                    updated[key] = str(raw or "").strip()
            for key in CONFIG_BOOL_KEYS:
                if key in parsed:
                    updated[key] = coerce_bool(parsed.get(key), current[key])
            for key, (minimum, maximum) in CONFIG_INT_LIMITS.items():
                if key in parsed:
                    updated[key] = coerce_int(parsed.get(key), current[key], minimum, maximum)

            write_json_file(self.config_path, updated)
            return True, updated

    def fetch_status(self, _refresh: bool = False) -> dict[str, Any]:
        config = self.get_config()
        status = self.fetch_unified(config)
        self.process_status(status, config)
        return status

    def fetch_unified(self, config: dict[str, Any]) -> dict[str, Any]:
        inverter = self.fetch_inverter(config) if config["inverterEnabled"] else None
        load_controller = self.fetch_load_controller(config) if config["loadControllerEnabled"] else None
        garage = self.fetch_garage(config) if config["garageEnabled"] else None
        updated_at = max(
            [
                now_ms(),
                int(inverter["updatedAtMs"]) if inverter else 0,
                int(load_controller["updatedAtMs"]) if load_controller else 0,
                int(garage["updatedAtMs"]) if garage else 0,
            ],
        )
        status = {
            "schemaVersion": 2,
            "updatedAtMs": updated_at,
            "fromMulticast": False,
            "capabilities": {},
            "inverter": inverter,
            "loadController": load_controller,
            "garage": garage,
        }
        status["capabilities"] = self.build_hub_capabilities(status)
        return status

    def fetch_inverter_daily(self, date: str) -> dict[str, Any] | None:
        config = self.get_config()
        if not config["inverterEnabled"]:
            return None
        return self.fetch_json_with_auth(
            base_url_raw=config["inverterBaseUrl"],
            password=config["inverterPassword"],
            path="/api/daily",
            query={"date": date},
        )

    def fetch_inverter_monthly(self, month: str) -> dict[str, Any] | None:
        config = self.get_config()
        if not config["inverterEnabled"]:
            return None
        return self.fetch_json_with_auth(
            base_url_raw=config["inverterBaseUrl"],
            password=config["inverterPassword"],
            path="/api/monthly",
            query={"month": month},
        )

    def fetch_inverter_yearly(self) -> dict[str, Any] | None:
        config = self.get_config()
        if not config["inverterEnabled"]:
            return None
        return self.fetch_json_with_auth(
            base_url_raw=config["inverterBaseUrl"],
            password=config["inverterPassword"],
            path="/api/yearly",
        )

    def fetch_load_history(self) -> dict[str, Any] | None:
        config = self.get_config()
        if not config["loadControllerEnabled"]:
            return None
        return self.fetch_json_with_auth(
            base_url_raw=config["loadControllerBaseUrl"],
            password=config["loadControllerPassword"],
            path="/api/history",
        )

    def fetch_garage_door_history(self) -> dict[str, Any] | None:
        config = self.get_config()
        if not config["garageEnabled"]:
            return None
        return self.fetch_json_with_auth(
            base_url_raw=config["garageBaseUrl"],
            password=config["garagePassword"],
            path="/api/doorhistory",
        )

    def fetch_garage_history(self) -> dict[str, Any] | None:
        config = self.get_config()
        if not config["garageEnabled"]:
            return None
        return self.fetch_json_with_auth(
            base_url_raw=config["garageBaseUrl"],
            password=config["garagePassword"],
            path="/api/history",
        )

    def fetch_event_journal(self) -> dict[str, Any]:
        with self.lock:
            entries = self._load_event_entries()
            items = list(reversed(entries[-MAX_EVENT_RESPONSE_ENTRIES:]))
            return {"items": items, "count": len(items)}

    def clear_event_journal(self) -> dict[str, Any]:
        with self.lock:
            write_json_file(self.events_path, [])
            return {"ok": True}

    def fetch_automation_history(self, hours: int) -> dict[str, Any]:
        safe_hours = clamp(hours if isinstance(hours, int) else int(hours or 0), 1, 6)
        items = self._recent_history_entries(safe_hours)
        return {
            "hours": safe_hours,
            "count": len(items),
            "items": items,
        }

    def open_external_url(self, url: str) -> bool:
        raw = str(url or "").strip()
        if not raw:
            return False
        parsed = urllib.parse.urlparse(raw)
        if parsed.scheme not in {"http", "https"}:
            return False
        try:
            starter = getattr(os, "startfile", None)
            if callable(starter):
                starter(raw)
                return True
            return webbrowser.open(raw, new=2)
        except OSError:
            return False

    def perform_action(self, method_name: str, args: dict[str, Any]) -> dict[str, Any]:
        config = self.get_config()
        dispatch = {
            "setInverterGridMode": lambda: self._set_inverter_grid_mode(config, args),
            "setInverterLoadMode": lambda: self._set_inverter_load_mode(config, args),
            "setInverterLoadLock": lambda: self._set_inverter_load_lock(config, args),
            "setInverterGridLogic": lambda: self._set_inverter_grid_logic(config, args),
            "setInverterLoadLogic": lambda: self._set_inverter_load_logic(config, args),
            "setBoiler1Mode": lambda: self._set_boiler1_mode(config, args),
            "setBoiler1Lock": lambda: self._set_boiler1_lock(config, args),
            "setBoiler1Logic": lambda: self._set_boiler1_logic(config, args),
            "setBoiler1AutoWindow": lambda: self._set_boiler1_auto_window(config, args),
            "setPumpMode": lambda: self._set_pump_mode(config, args),
            "setPumpLock": lambda: self._set_pump_lock(config, args),
            "setPumpLogic": lambda: self._set_pump_logic(config, args),
            "setPumpAutoWindow": lambda: self._set_pump_auto_window(config, args),
            "setBoiler2Mode": lambda: self._set_boiler2_mode(config, args),
            "setBoiler2Lock": lambda: self._set_boiler2_lock(config, args),
            "setBoiler2Logic": lambda: self._set_boiler2_logic(config, args),
            "setBoiler2AutoWindow": lambda: self._set_boiler2_auto_window(config, args),
            "triggerGate": lambda: self._trigger_gate(config),
            "toggleGarageLight": lambda: self._toggle_garage_light(config),
        }
        action = dispatch.get(method_name)
        if action is None:
            return {"ok": False, "message": f"Unknown action: {method_name}"}

        ok = bool(action())
        if not ok:
            return {"ok": False, "message": "Command failed"}

        status = self.fetch_unified(config)
        self.process_status(status, config)
        return {"ok": True, "status": status}

    def _fetch_json_result(self, url: str, timeout_sec: float) -> tuple[dict[str, Any] | None, int | None]:
        request = urllib.request.Request(
            url,
            method="GET",
            headers={
                "Accept": "application/json",
                "User-Agent": "HomeHubDesktop/1.0",
            },
        )
        try:
            with self.opener.open(request, timeout=timeout_sec) as response:
                status = response.getcode()
                body = response.read().decode("utf-8", errors="replace")
                payload = json.loads(body) if body else None
                if isinstance(payload, dict):
                    return payload, status
                return None, status
        except urllib.error.HTTPError as exc:
            return None, exc.code
        except (urllib.error.URLError, OSError, TimeoutError, ValueError, socket.timeout):
            return None, None

    def _post_form_once(self, url: str, form_pairs: list[tuple[str, str]], timeout_sec: float) -> bool:
        encoded = urllib.parse.urlencode(form_pairs).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=encoded,
            method="POST",
            headers={
                "Content-Type": "application/x-www-form-urlencoded",
                "User-Agent": "HomeHubDesktop/1.0",
            },
        )
        try:
            with self.opener.open(request, timeout=timeout_sec) as response:
                return 200 <= response.getcode() < 300
        except urllib.error.HTTPError:
            return False
        except (urllib.error.URLError, OSError, TimeoutError, socket.timeout):
            return False

    def authenticate(self, base_url: str, password: str, timeout_sec: float = CONTROLLER_IO_TIMEOUT_SEC) -> None:
        if not password:
            return
        auth_url = build_url(base_url, "/api/auth")
        self._post_form_once(auth_url, [("pass", password)], timeout_sec)

    def should_sync_controller_time(self, base_url: str) -> bool:
        current_time_ms = now_ms()
        with self.lock:
            previous = self.time_sync_at_ms.get(base_url, 0)
            if current_time_ms - previous < CONTROLLER_TIME_SYNC_THROTTLE_MS:
                return False
            self.time_sync_at_ms[base_url] = current_time_ms
            return True

    def sync_controller_time_with_auth(self, base_url: str, password: str) -> None:
        local_time = time.localtime()
        form_pairs = [
            ("year", str(local_time.tm_year)),
            ("month", str(local_time.tm_mon)),
            ("day", str(local_time.tm_mday)),
            ("hour", str(local_time.tm_hour)),
            ("minute", str(local_time.tm_min)),
            ("second", str(local_time.tm_sec)),
        ]
        self.post_form(
            base_url_raw=base_url,
            password=password,
            path=TIME_SYNC_BROWSER_PATH,
            form_pairs=form_pairs,
            sync_time_first=False,
        )

    def fetch_status_json(self, base_url_raw: str, password: str) -> dict[str, Any] | None:
        base_url = normalize_base_url(base_url_raw)
        if not base_url:
            return None
        url = build_url(base_url, "/api/status")
        payload, status_code = self._fetch_json_result(url, CONTROLLER_STATUS_TIMEOUT_SEC)
        if payload is not None:
            return payload
        if status_code not in {401, 403} or not password:
            return None
        self.authenticate(base_url, password, CONTROLLER_STATUS_TIMEOUT_SEC)
        payload, _ = self._fetch_json_result(url, CONTROLLER_STATUS_TIMEOUT_SEC)
        return payload

    def fetch_json_with_auth(
        self,
        base_url_raw: str,
        password: str,
        path: str,
        query: dict[str, Any] | None = None,
    ) -> dict[str, Any] | None:
        base_url = normalize_base_url(base_url_raw)
        if not base_url:
            return None
        if path != TIME_SYNC_BROWSER_PATH and self.should_sync_controller_time(base_url):
            try:
                self.sync_controller_time_with_auth(base_url, password)
            except OSError:
                pass
        url = build_url(base_url, path, query)
        payload, _ = self._fetch_json_result(url, CONTROLLER_IO_TIMEOUT_SEC)
        if payload is not None:
            return payload
        if not password:
            return None
        for _ in range(2):
            self.authenticate(base_url, password)
            payload, _ = self._fetch_json_result(url, CONTROLLER_IO_TIMEOUT_SEC)
            if payload is not None:
                return payload
        return None

    def post_form(
        self,
        base_url_raw: str,
        password: str,
        path: str,
        form_pairs: list[tuple[str, str]],
        *,
        sync_time_first: bool = True,
    ) -> bool:
        base_url = normalize_base_url(base_url_raw)
        if not base_url:
            return False
        if sync_time_first and path != TIME_SYNC_BROWSER_PATH and self.should_sync_controller_time(base_url):
            try:
                self.sync_controller_time_with_auth(base_url, password)
            except OSError:
                pass
        url = build_url(base_url, path)
        if self._post_form_once(url, form_pairs, CONTROLLER_IO_TIMEOUT_SEC):
            return True
        if not password:
            return False
        for _ in range(2):
            self.authenticate(base_url, password)
            if self._post_form_once(url, form_pairs, CONTROLLER_IO_TIMEOUT_SEC):
                return True
        return False

    def post_mode(self, base_url_raw: str, password: str, path: str, mode: str) -> bool:
        return self.post_form(
            base_url_raw=base_url_raw,
            password=password,
            path=path,
            form_pairs=[("mode", safe_text(mode).upper())],
        )

    def fetch_inverter(self, config: dict[str, Any]) -> dict[str, Any] | None:
        raw = self.fetch_status_json(config["inverterBaseUrl"], config["inverterPassword"])
        if raw is None:
            return None
        module = self.parse_inverter_status(raw, now_ms())
        module["capabilities"] = self.build_inverter_capabilities(module)
        return module

    def fetch_load_controller(self, config: dict[str, Any]) -> dict[str, Any] | None:
        raw = self.fetch_status_json(config["loadControllerBaseUrl"], config["loadControllerPassword"])
        if raw is None:
            return None
        module = self.parse_load_controller_status(raw, now_ms())
        module["capabilities"] = self.build_load_controller_capabilities(module)
        return module

    def fetch_garage(self, config: dict[str, Any]) -> dict[str, Any] | None:
        raw = self.fetch_status_json(config["garageBaseUrl"], config["garagePassword"])
        if raw is None:
            return None
        module = self.parse_garage_status(raw, now_ms())
        module["capabilities"] = self.build_garage_capabilities(module)
        return module

    def parse_inverter_grid_logic(self, payload: dict[str, Any]) -> dict[str, Any]:
        nested = payload.get("grid_logic")
        if not isinstance(nested, dict):
            nested = {}
        return {
            "pvThresholdW": opt_double_any(nested, 150.0, "pv_threshold_w"),
            "offDelaySec": int(opt_long_any(nested, 300, "off_delay_sec")),
            "onDelaySec": int(opt_long_any(nested, 1800, "on_delay_sec")),
            "forceGridOnW": opt_double_any(nested, 3000.0, "force_grid_on_w"),
            "batteryLowSocPct": opt_double_any(nested, 70.0, "battery_low_soc_pct"),
            "offMinSocPct": opt_double_any(nested, 85.0, "off_min_soc_pct"),
        }

    def parse_inverter_load_logic(self, payload: dict[str, Any]) -> dict[str, Any]:
        nested = payload.get("load_logic")
        if not isinstance(nested, dict):
            nested = {}
        return {
            "pvThresholdW": opt_double_any(nested, 100.0, "pv_threshold_w"),
            "shutdownDelaySec": int(opt_long_any(nested, 120, "shutdown_delay_sec")),
            "overloadPowerW": opt_double_any(nested, 4500.0, "overload_power_w"),
            "gridRestoreV": opt_double_any(nested, 180.0, "grid_restore_v"),
            "overloadGridV": opt_double_any(nested, 190.0, "overload_grid_v"),
        }

    def parse_boiler_logic(self, payload: dict[str, Any]) -> dict[str, Any]:
        nested = payload.get("boiler_logic")
        if not isinstance(nested, dict):
            nested = {}
        return {
            "pvThresholdW": opt_double_any(nested, 100.0, "pv_threshold_w"),
            "shutdownDelaySec": int(opt_long_any(nested, 120, "shutdown_delay_sec")),
            "batteryShutoffW": opt_double_any(nested, -1800.0, "battery_shutoff_w"),
            "batteryResumeW": opt_double_any(nested, 200.0, "battery_resume_w"),
            "peerActiveW": opt_double_any(nested, 1000.0, "peer_active_w"),
            "gridRestoreV": opt_double_any(nested, 180.0, "grid_restore_v"),
            "batteryReleaseGridV": opt_double_any(nested, 190.0, "battery_release_grid_v"),
            "batteryReleaseSocPct": opt_double_any(nested, 90.0, "battery_release_soc_pct"),
        }

    def parse_pump_logic(self, payload: dict[str, Any]) -> dict[str, Any]:
        nested = payload.get("pump_logic")
        if not isinstance(nested, dict):
            nested = {}
        return {
            "pvThresholdW": opt_double_any(nested, 100.0, "pv_threshold_w"),
            "shutdownDelaySec": int(opt_long_any(nested, 120, "shutdown_delay_sec")),
            "gridRestoreV": opt_double_any(nested, 180.0, "grid_restore_v"),
        }

    def parse_inverter_status(self, payload: dict[str, Any], observed_at_ms: int) -> dict[str, Any]:
        rtc_time = opt_string_any(payload, "--:--:--", "rtc_time", "time")
        rtc_date = opt_string_any(payload, "---", "rtc_date", "date")
        bme_temp = opt_nullable_double(payload, "bme_temp")
        bme_hum = opt_nullable_double(payload, "bme_hum")
        bme_press = opt_nullable_double(payload, "bme_press")
        bme_ext_temp = opt_nullable_double(payload, "bme_ext_temp")
        bme_ext_hum = opt_nullable_double(payload, "bme_ext_hum")
        bme_ext_press = opt_nullable_double(payload, "bme_ext_press")
        if "grid_present" in payload:
            grid_present = opt_bool(payload, "grid_present")
        else:
            grid_present = opt_double_any(payload, 0.0, "gridVolt") >= 170.0
        return {
            "moduleKey": "inverter",
            "available": True,
            "capabilities": {},
            "pvW": opt_double_any(payload, 0.0, "pv"),
            "gridW": opt_double_any(payload, 0.0, "ac_in", "grid"),
            "loadW": opt_double_any(payload, 0.0, "ac_out", "load"),
            "lineVoltage": opt_double_any(payload, 0.0, "gridVolt"),
            "pvVoltage": opt_double_any(payload, 0.0, "pvVolt"),
            "batteryVoltage": opt_double_any(payload, 0.0, "batVolt"),
            "gridFrequency": opt_double_any(payload, 0.0, "gridFreq"),
            "outputVoltage": opt_double_any(payload, 0.0, "outputVolt"),
            "outputFrequency": opt_double_any(payload, 0.0, "outputFreq"),
            "inverterTemp": opt_double_any(payload, 0.0, "inverterTemp"),
            "dailyPV": opt_double_any(payload, 0.0, "dailyPV", "daily_pv"),
            "dailyHome": opt_double_any(payload, 0.0, "dailyHome", "daily_home"),
            "dailyGrid": opt_double_any(payload, 0.0, "dailyGrid", "daily_grid"),
            "lastUpdate": opt_string_any(payload, rtc_time, "last_update", "time"),
            "loadOnLocked": opt_bool(payload, "load_on_locked"),
            "batterySoc": opt_double_any(payload, 0.0, "battery"),
            "batteryPower": opt_double_any(payload, 0.0, "battery_power"),
            "mode": opt_string_any(payload, "---", "mode"),
            "modeReason": opt_string_any(payload, "manual", "mode_reason"),
            "loadMode": opt_string_any(payload, "---", "load_mode"),
            "loadModeReason": opt_string_any(payload, "manual", "load_mode_reason"),
            "gridLogic": self.parse_inverter_grid_logic(payload),
            "loadLogic": self.parse_inverter_load_logic(payload),
            "gridRelayOn": opt_bool(payload, "pin34_state"),
            "gridPresent": grid_present,
            "gridRelayReason": opt_string_any(payload, "manual", "pin34_reason"),
            "loadRelayOn": opt_bool(payload, "pinLoad_state"),
            "loadRelayReason": opt_string_any(payload, "manual", "pinLoad_reason"),
            "wifiStrength": opt_double_any(payload, 0.0, "wifi_strength"),
            "uptimeSec": opt_long_any(payload, 0, "uptime"),
            "rtcTime": rtc_time,
            "rtcDate": rtc_date,
            "updatedAtMs": observed_at_ms,
            "bmeAvailable": opt_bool(payload, "bme_available") or bme_temp is not None or bme_hum is not None or bme_press is not None,
            "bmeTemp": bme_temp,
            "bmeHum": bme_hum,
            "bmePress": bme_press,
            "bmeExtAvailable": opt_bool(payload, "bme_ext_available") or bme_ext_temp is not None or bme_ext_hum is not None or bme_ext_press is not None,
            "bmeExtTemp": bme_ext_temp,
            "bmeExtHum": bme_ext_hum,
            "bmeExtPress": bme_ext_press,
        }

    def parse_load_controller_status(self, payload: dict[str, Any], observed_at_ms: int) -> dict[str, Any]:
        rtc_time = opt_string_any(payload, "--:--:--", "rtc_time", "time")
        rtc_date = opt_string_any(payload, "---", "rtc_date", "date")
        bme_temp = opt_nullable_double(payload, "bme_temp")
        bme_hum = opt_nullable_double(payload, "bme_hum")
        bme_press = opt_nullable_double(payload, "bme_press")
        return {
            "moduleKey": "loadController",
            "available": True,
            "capabilities": {},
            "boiler1Mode": opt_string_any(payload, "---", "mode"),
            "boiler1ModeReason": opt_string_any(payload, "manual", "boiler_mode_reason"),
            "boiler1On": opt_bool(payload, "boiler_on"),
            "boiler1StateReason": opt_string_any(payload, "manual", "boiler_state_reason"),
            "pumpMode": opt_string_any(payload, "---", "load_mode"),
            "pumpModeReason": opt_string_any(payload, "manual", "pump_mode_reason"),
            "pumpOn": opt_bool(payload, "pump_on"),
            "pumpStateReason": opt_string_any(payload, "manual", "pump_state_reason"),
            "boilerLock": opt_string_any(payload, "NONE", "boiler_lock"),
            "pumpLock": opt_string_any(payload, "NONE", "pump_lock"),
            "boilerLogic": self.parse_boiler_logic(payload),
            "pumpLogic": self.parse_pump_logic(payload),
            "boiler1AutoWindowEnabled": opt_bool(payload, "boiler_auto_window_enabled"),
            "boiler1AutoWindowStart": opt_string_any(payload, "00:00", "boiler_auto_window_start"),
            "boiler1AutoWindowEnd": opt_string_any(payload, "00:00", "boiler_auto_window_end"),
            "boiler1AutoWindowActive": opt_bool(payload, "boiler_auto_window_active", True),
            "pumpAutoWindowEnabled": opt_bool(payload, "pump_auto_window_enabled"),
            "pumpAutoWindowStart": opt_string_any(payload, "00:00", "pump_auto_window_start"),
            "pumpAutoWindowEnd": opt_string_any(payload, "00:00", "pump_auto_window_end"),
            "pumpAutoWindowActive": opt_bool(payload, "pump_auto_window_active", True),
            "boilerCurrent": opt_double_any(payload, 0.0, "boiler_current"),
            "boilerPower": opt_double_any(payload, 0.0, "boiler_power"),
            "dailyBoiler": opt_double_any(payload, 0.0, "daily_boiler"),
            "pumpCurrent": opt_double_any(payload, 0.0, "pump_current"),
            "pumpPower": opt_double_any(payload, 0.0, "pump_power"),
            "dailyPump": opt_double_any(payload, 0.0, "daily_pump"),
            "lineVoltage": opt_double_any(payload, 0.0, "gridVolt"),
            "pvW": opt_double_any(payload, 0.0, "pv"),
            "gridW": opt_double_any(payload, 0.0, "ac_in", "grid"),
            "loadW": opt_double_any(payload, 0.0, "ac_out", "load"),
            "batterySoc": opt_double_any(payload, 0.0, "battery"),
            "batteryPower": opt_double_any(payload, 0.0, "battery_power"),
            "wifiStrength": opt_double_any(payload, 0.0, "wifi_strength"),
            "uptimeSec": opt_long_any(payload, 0, "uptime"),
            "rtcTime": rtc_time,
            "rtcDate": rtc_date,
            "updatedAtMs": observed_at_ms,
            "bmeAvailable": opt_bool(payload, "bme_available") or bme_temp is not None or bme_hum is not None or bme_press is not None,
            "bmeTemp": bme_temp,
            "bmeHum": bme_hum,
            "bmePress": bme_press,
        }

    def parse_garage_status(self, payload: dict[str, Any], observed_at_ms: int) -> dict[str, Any]:
        rtc_time = opt_string_any(payload, "--:--:--", "rtc_time", "time")
        rtc_date = opt_string_any(payload, "---", "rtc_date", "date")
        bme_temp = opt_nullable_double(payload, "bme_temp")
        bme_hum = opt_nullable_double(payload, "bme_hum")
        bme_press = opt_nullable_double(payload, "bme_press")
        return {
            "moduleKey": "garage",
            "available": True,
            "capabilities": {},
            "boiler2Mode": opt_string_any(payload, "---", "mode", "boiler_mode"),
            "boiler2ModeReason": opt_string_any(payload, "manual", "boiler_mode_reason", "mode_reason"),
            "boiler2On": opt_bool(payload, "boiler_on"),
            "boiler2StateReason": opt_string_any(payload, "manual", "boiler_state_reason"),
            "boilerLock": opt_string_any(payload, "NONE", "boiler_lock"),
            "boilerLogic": self.parse_boiler_logic(payload),
            "boiler2AutoWindowEnabled": opt_bool(payload, "boiler_auto_window_enabled"),
            "boiler2AutoWindowStart": opt_string_any(payload, "00:00", "boiler_auto_window_start"),
            "boiler2AutoWindowEnd": opt_string_any(payload, "00:00", "boiler_auto_window_end"),
            "boiler2AutoWindowActive": opt_bool(payload, "boiler_auto_window_active", True),
            "boilerCurrent": opt_double_any(payload, 0.0, "boiler_current"),
            "boilerPower": opt_double_any(payload, 0.0, "boiler_power"),
            "dailyBoiler": opt_double_any(payload, 0.0, "daily_boiler"),
            "gateState": opt_string_any(payload, "unknown", "door_state", "door"),
            "gateReason": opt_string_any(payload, "manual", "door_reason"),
            "gateSource": opt_string_any(payload, "remote", "door_source", "door_initiator"),
            "gateOpenPin": int(opt_long_any(payload, -1, "door_open_pin")),
            "gateClosedPin": int(opt_long_any(payload, -1, "door_closed_pin")),
            "garageLightOn": opt_bool(payload, "garage_light_on"),
            "garageLightReason": opt_string_any(payload, "manual", "garage_light_reason"),
            "lineVoltage": opt_double_any(payload, 0.0, "gridVolt"),
            "pvW": opt_double_any(payload, 0.0, "pv"),
            "gridW": opt_double_any(payload, 0.0, "ac_in", "grid"),
            "loadW": opt_double_any(payload, 0.0, "ac_out", "load"),
            "batterySoc": opt_double_any(payload, 0.0, "battery"),
            "batteryPower": opt_double_any(payload, 0.0, "battery_power"),
            "wifiStrength": opt_double_any(payload, 0.0, "wifi_strength"),
            "uptimeSec": opt_long_any(payload, 0, "uptime"),
            "rtcTime": rtc_time,
            "rtcDate": rtc_date,
            "updatedAtMs": observed_at_ms,
            "bmeAvailable": opt_bool(payload, "bme_available") or bme_temp is not None or bme_hum is not None or bme_press is not None,
            "bmeTemp": bme_temp,
            "bmeHum": bme_hum,
            "bmePress": bme_press,
        }

    def build_inverter_capabilities(self, status: dict[str, Any] | None) -> dict[str, Any]:
        available = status is not None
        return {
            "available": available,
            "logicKeys": ["grid", "load"] if available else [],
            "history": available,
            "events": True,
            "climate": bool(status and (status.get("bmeAvailable") or status.get("bmeExtAvailable"))),
        }

    def build_load_controller_capabilities(self, status: dict[str, Any] | None) -> dict[str, Any]:
        available = status is not None
        return {
            "available": available,
            "logicKeys": ["boiler1", "pump"] if available else [],
            "history": available,
            "events": True,
            "autoWindow": available,
            "climate": bool(status and status.get("bmeAvailable")),
        }

    def build_garage_capabilities(self, status: dict[str, Any] | None) -> dict[str, Any]:
        available = status is not None
        return {
            "available": available,
            "logicKeys": ["boiler2"] if available else [],
            "history": available,
            "events": True,
            "autoWindow": available,
            "gate": available,
            "garageLight": available,
            "climate": bool(status and status.get("bmeAvailable")),
        }

    def build_hub_capabilities(self, status: dict[str, Any]) -> dict[str, Any]:
        inverter = status.get("inverter")
        load_controller = status.get("loadController")
        garage = status.get("garage")
        logic_keys = []
        if inverter is not None:
            logic_keys.extend(["grid", "load"])
        if load_controller is not None:
            logic_keys.extend(["boiler1", "pump"])
        if garage is not None:
            logic_keys.append("boiler2")
        return {
            "historyHours": 6,
            "eventJournal": True,
            "automationHistory": True,
            "logicKeys": logic_keys,
            "modules": {
                "inverter": self.build_inverter_capabilities(inverter),
                "loadController": self.build_load_controller_capabilities(load_controller),
                "garage": self.build_garage_capabilities(garage),
            },
        }

    def process_status(self, status: dict[str, Any], config: dict[str, Any]) -> None:
        with self.lock:
            current = self._snapshot_from_unified(status)
            previous = self._load_snapshot()
            if previous is not None:
                for event in self.detect_events(previous, current, config):
                    self._append_event(event)
            write_json_file(self.snapshot_path, current)
            self._append_history(status)

    def detect_events(
        self,
        previous: dict[str, Any],
        current: dict[str, Any],
        config: dict[str, Any],
    ) -> list[dict[str, Any]]:
        events: list[dict[str, Any]] = []

        self._append_unexpected_reboot_events(events, previous, current, config)

        if config["inverterEnabled"]:
            self._append_pv_generation_event(events, current, config)

        if config["inverterEnabled"] and previous.get("gridRelayOn") is not None and current.get("gridRelayOn") is not None:
            if previous["gridRelayOn"] != current["gridRelayOn"]:
                title = "GRID relay turned ON" if current["gridRelayOn"] else "GRID relay turned OFF"
                reason = self._normalize_grid_reason(
                    current.get("gridRelayReason"),
                    current.get("gridRelayOn"),
                    current.get("inverterBatterySoc"),
                )
                body = f"Reason: {reason}"
                metrics = self._build_metric_context(current, "inverter")
                if metrics:
                    body = f"{body}. {metrics}"
                events.append(self._event(title, body, kind="grid_relay", module="inverter"))

        if config["inverterEnabled"] and previous.get("gridPresent") is not None and current.get("gridPresent") is not None:
            if previous["gridPresent"] != current["gridPresent"]:
                title = "GRID appeared" if current["gridPresent"] else "GRID disappeared"
                voltage = int(current.get("gridVoltage") or 0)
                events.append(self._event(title, f"Line voltage: {voltage}V", kind="grid_presence", module="inverter"))

        if config["inverterEnabled"]:
            self._append_mode_event(
                events,
                previous.get("gridMode"),
                current.get("gridMode"),
                "GRID mode changed",
                self._normalize_grid_reason(
                    current.get("gridModeReason"),
                    current.get("gridRelayOn"),
                    current.get("inverterBatterySoc"),
                ),
                current,
                module="inverter",
                kind="grid_mode",
                reason_is_normalized=True,
            )
            self._append_mode_event(
                events,
                previous.get("loadMode"),
                current.get("loadMode"),
                "LOAD mode changed",
                current.get("loadModeReason"),
                current,
                module="inverter",
                kind="load_mode",
            )

        if config["loadControllerEnabled"]:
            self._append_mode_event(
                events,
                previous.get("boiler1Mode"),
                current.get("boiler1Mode"),
                "BOILER1 mode changed",
                current.get("boiler1ModeReason"),
                current,
                module="load_controller",
                kind="boiler1_mode",
            )
            self._append_mode_event(
                events,
                previous.get("pumpMode"),
                current.get("pumpMode"),
                "PUMP mode changed",
                current.get("pumpModeReason"),
                current,
                module="load_controller",
                kind="pump_mode",
            )

        if config["garageEnabled"]:
            self._append_mode_event(
                events,
                previous.get("boiler2Mode"),
                current.get("boiler2Mode"),
                "BOILER2 mode changed",
                current.get("boiler2ModeReason"),
                current,
                module="garage",
                kind="boiler2_mode",
            )

            prev_gate_state = self._normalize_gate_state(previous.get("gateState"))
            curr_gate_state = self._normalize_gate_state(current.get("gateState"))
            if prev_gate_state and curr_gate_state and prev_gate_state != curr_gate_state:
                source = self._normalize_gate_source(current.get("gateSource"), current.get("gateReason"))
                body = (
                    f"State: {prev_gate_state} -> {curr_gate_state}. "
                    f"Source: {source}. Reason: {self._normalize_reason(current.get('gateReason'))}"
                )
                events.append(self._event("Gate state changed", body, kind="gate_state", module="garage"))

        self._append_power_alert_events(events, previous, current, config)
        self._append_logic_instability_events(events, current, config)
        return events

    def _load_event_entries(self) -> list[dict[str, Any]]:
        payload = read_json_file(self.events_path, [])
        if not isinstance(payload, list):
            return []
        items: list[dict[str, Any]] = []
        for entry in payload:
            if not isinstance(entry, dict):
                continue
            title = safe_text(entry.get("title"))
            at_ms = int(entry.get("atMs") or 0)
            if not title or at_ms <= 0:
                continue
            items.append(
                {
                    "atMs": at_ms,
                    "title": title,
                    "body": safe_text(entry.get("body")),
                    "severity": safe_text(entry.get("severity"), "info"),
                    "kind": safe_text(entry.get("kind"), "event"),
                    "module": safe_text(entry.get("module"), "hub"),
                },
            )
        return items

    def _append_event(self, event: dict[str, Any]) -> None:
        entries = self._load_event_entries()
        entries.append(event)
        if len(entries) > MAX_EVENT_ENTRIES:
            entries = entries[-MAX_EVENT_ENTRIES:]
        write_json_file(self.events_path, entries)

    def _event(
        self,
        title: str,
        body: str,
        *,
        severity: str = "info",
        kind: str = "event",
        module: str = "hub",
        at_ms: int | None = None,
    ) -> dict[str, Any]:
        return {
            "atMs": at_ms or now_ms(),
            "title": safe_text(title, "event"),
            "body": safe_text(body),
            "severity": safe_text(severity, "info"),
            "kind": safe_text(kind, "event"),
            "module": safe_text(module, "hub"),
        }

    def _load_snapshot(self) -> dict[str, Any] | None:
        payload = read_json_file(self.snapshot_path, None)
        return payload if isinstance(payload, dict) else None

    def _snapshot_from_unified(self, status: dict[str, Any]) -> dict[str, Any]:
        inverter = status.get("inverter") or {}
        load_controller = status.get("loadController") or {}
        garage = status.get("garage") or {}
        load_logic = inverter.get("loadLogic") or {}
        return {
            "inverterOnline": bool(status.get("inverter")),
            "loadControllerOnline": bool(status.get("loadController")),
            "garageOnline": bool(status.get("garage")),
            "pvActive": (inverter.get("pvW") is not None and float(inverter.get("pvW") or 0.0) >= PV_ACTIVE_THRESHOLD_W) if status.get("inverter") else None,
            "pvW": inverter.get("pvW"),
            "loadW": inverter.get("loadW"),
            "inverterBatterySoc": inverter.get("batterySoc"),
            "gridRelayOn": inverter.get("gridRelayOn"),
            "gridPresent": inverter.get("gridPresent"),
            "gridVoltage": inverter.get("lineVoltage"),
            "gridRelayReason": inverter.get("gridRelayReason"),
            "gridMode": inverter.get("mode"),
            "gridModeReason": inverter.get("modeReason"),
            "inverterLoadOverloadW": load_logic.get("overloadPowerW"),
            "inverterUptimeSec": inverter.get("uptimeSec"),
            "inverterRtcTime": inverter.get("rtcTime"),
            "loadMode": inverter.get("loadMode"),
            "loadModeReason": inverter.get("loadModeReason"),
            "boiler1PowerW": load_controller.get("boilerPower"),
            "pumpPowerW": load_controller.get("pumpPower"),
            "loadControllerUptimeSec": load_controller.get("uptimeSec"),
            "loadControllerRtcTime": load_controller.get("rtcTime"),
            "boiler1Mode": load_controller.get("boiler1Mode"),
            "boiler1ModeReason": load_controller.get("boiler1ModeReason"),
            "pumpMode": load_controller.get("pumpMode"),
            "pumpModeReason": load_controller.get("pumpModeReason"),
            "boiler2PowerW": garage.get("boilerPower"),
            "boiler2Mode": garage.get("boiler2Mode"),
            "boiler2ModeReason": garage.get("boiler2ModeReason"),
            "garageUptimeSec": garage.get("uptimeSec"),
            "garageRtcTime": garage.get("rtcTime"),
            "gateState": garage.get("gateState"),
            "gateReason": garage.get("gateReason"),
            "gateSource": garage.get("gateSource"),
        }

    def _load_pv_debounce_state(self) -> dict[str, Any]:
        payload = read_json_file(self.pv_debounce_path, {})
        return payload if isinstance(payload, dict) else {}

    def _save_pv_debounce_state(self, payload: dict[str, Any]) -> None:
        write_json_file(self.pv_debounce_path, payload)

    def _load_alert_throttle(self) -> dict[str, Any]:
        payload = read_json_file(self.alert_throttle_path, {})
        return payload if isinstance(payload, dict) else {}

    def _should_emit_alert(self, key: str, cooldown_ms: int) -> bool:
        throttles = self._load_alert_throttle()
        previous_at = int(throttles.get(key) or 0)
        current_at = now_ms()
        if previous_at > 0 and current_at - previous_at < cooldown_ms:
            return False
        throttles[key] = current_at
        write_json_file(self.alert_throttle_path, throttles)
        return True

    def _append_pv_generation_event(
        self,
        events: list[dict[str, Any]],
        current: dict[str, Any],
        config: dict[str, Any],
    ) -> None:
        current_pv_active = current.get("pvActive")
        if current_pv_active is None:
            return
        state = self._load_pv_debounce_state()
        stable_active = state.get("stableActive")
        pending_active = state.get("pendingActive")
        pending_since_ms = int(state.get("pendingSinceMs") or 0)
        debounce_ms = self._pv_transition_debounce_ms(config)
        current_time_ms = now_ms()

        if stable_active is None:
            self._save_pv_debounce_state({"stableActive": current_pv_active, "pendingActive": None, "pendingSinceMs": 0})
            return
        if current_pv_active == stable_active:
            if pending_active is not None or pending_since_ms != 0:
                self._save_pv_debounce_state({"stableActive": stable_active, "pendingActive": None, "pendingSinceMs": 0})
            return
        if pending_active != current_pv_active:
            self._save_pv_debounce_state(
                {
                    "stableActive": stable_active,
                    "pendingActive": current_pv_active,
                    "pendingSinceMs": current_time_ms,
                },
            )
            return
        if current_time_ms - pending_since_ms < debounce_ms:
            return

        self._save_pv_debounce_state({"stableActive": current_pv_active, "pendingActive": None, "pendingSinceMs": 0})
        title = "PV generation started" if current_pv_active else "PV generation stopped"
        reason = f"Reason: PV={int(current.get('pvW') or 0)}W, threshold {int(PV_ACTIVE_THRESHOLD_W)}W"
        events.append(self._event(title, reason, kind="pv_generation", module="inverter"))

    def _pv_transition_debounce_ms(self, config: dict[str, Any]) -> int:
        if config.get("realtimeMonitorEnabled"):
            base_sec = clamp(int(config.get("realtimePollIntervalSec") or 5), 3, 60)
        else:
            base_sec = clamp(int(config.get("pollIntervalSec") or 5), 2, 60)
        return clamp(base_sec * 1000, PV_TRANSITION_DEBOUNCE_MIN_MS, PV_TRANSITION_DEBOUNCE_MAX_MS)

    def _append_mode_event(
        self,
        events: list[dict[str, Any]],
        previous_mode: Any,
        current_mode: Any,
        title: str,
        reason: Any,
        current: dict[str, Any],
        *,
        module: str,
        kind: str,
        reason_is_normalized: bool = False,
    ) -> None:
        previous_text = safe_text(previous_mode)
        current_text = safe_text(current_mode)
        if not previous_text or not current_text or previous_text == current_text:
            return
        reason_text = safe_text(reason) if reason_is_normalized else self._normalize_reason(reason)
        body = f"{previous_text} -> {current_text}. Reason: {reason_text}"
        metrics = self._build_metric_context(current, module)
        if metrics:
            body = f"{body}. {metrics}"
        events.append(self._event(title, body, kind=kind, module=module))

    def _append_power_alert_events(
        self,
        events: list[dict[str, Any]],
        previous: dict[str, Any],
        current: dict[str, Any],
        config: dict[str, Any],
    ) -> None:
        if not config.get("inverterEnabled") or not current.get("inverterOnline"):
            return
        threshold = float(current.get("inverterLoadOverloadW") or 4500.0)
        previous_load = previous.get("loadW")
        current_load = current.get("loadW")
        if previous_load is None or current_load is None:
            return
        if float(previous_load) <= threshold and float(current_load) > threshold:
            metrics = self._build_metric_context(current, "inverter")
            body = f"Load {int(float(current_load))}W > {int(threshold)}W"
            if metrics:
                body = f"{body}. {metrics}"
            events.append(self._event("Load overload threshold exceeded", body, severity="alert", kind="power_overload", module="inverter"))

    def _append_logic_instability_events(
        self,
        events: list[dict[str, Any]],
        current: dict[str, Any],
        config: dict[str, Any],
    ) -> None:
        checks = [
            ("grid", "GRID logic unstable", "inverter", config.get("inverterEnabled") and current.get("inverterOnline")),
            ("load", "LOAD logic unstable", "inverter", config.get("inverterEnabled") and current.get("inverterOnline")),
            ("boiler1", "BOILER1 logic unstable", "load_controller", config.get("loadControllerEnabled") and current.get("loadControllerOnline")),
            ("pump", "PUMP logic unstable", "load_controller", config.get("loadControllerEnabled") and current.get("loadControllerOnline")),
            ("boiler2", "BOILER2 logic unstable", "garage", config.get("garageEnabled") and current.get("garageOnline")),
        ]
        for logic_key, title, module, enabled in checks:
            if not enabled:
                continue
            transitions = self._count_transitions(logic_key, LOGIC_UNSTABLE_WINDOW_MS)
            if transitions < LOGIC_UNSTABLE_TRANSITIONS:
                continue
            if not self._should_emit_alert(f"logic_unstable:{logic_key}", LOGIC_UNSTABLE_COOLDOWN_MS):
                continue
            body = f"{transitions} state changes detected in the last {LOGIC_UNSTABLE_WINDOW_MS // 60_000} min."
            events.append(self._event(title, body, severity="alert", kind="logic_unstable", module=module))

    def _append_unexpected_reboot_events(
        self,
        events: list[dict[str, Any]],
        previous: dict[str, Any],
        current: dict[str, Any],
        config: dict[str, Any],
    ) -> None:
        checks = [
            (
                config.get("inverterEnabled"),
                "Inverter",
                "inverter",
                previous.get("inverterUptimeSec"),
                current.get("inverterUptimeSec"),
                current.get("inverterRtcTime"),
            ),
            (
                config.get("loadControllerEnabled"),
                "Load controller",
                "load_controller",
                previous.get("loadControllerUptimeSec"),
                current.get("loadControllerUptimeSec"),
                current.get("loadControllerRtcTime"),
            ),
            (
                config.get("garageEnabled"),
                "Garage controller",
                "garage",
                previous.get("garageUptimeSec"),
                current.get("garageUptimeSec"),
                current.get("garageRtcTime"),
            ),
        ]
        for enabled, module_name, module, previous_uptime, current_uptime, rtc_time in checks:
            if not enabled:
                continue
            if not self._is_unexpected_reboot(previous_uptime, current_uptime, rtc_time):
                continue
            body = f"Unexpected reboot detected (uptime reset: {int(previous_uptime or 0)}s -> {int(current_uptime or 0)}s)"
            events.append(self._event(f"{module_name}: power failure suspected", body, severity="alert", kind="unexpected_reboot", module=module))

    def _is_unexpected_reboot(self, previous_uptime: Any, current_uptime: Any, rtc_time: Any) -> bool:
        try:
            previous_value = int(previous_uptime)
            current_value = int(current_uptime)
        except (TypeError, ValueError):
            return False
        if current_value <= 0:
            return False
        if previous_value < 300:
            return False
        if current_value > 300:
            return False
        if current_value >= previous_value:
            return False
        if previous_value - current_value < 120:
            return False
        if self._is_planned_nightly_reboot_window(rtc_time):
            return False
        return True

    def _is_planned_nightly_reboot_window(self, rtc_time: Any) -> bool:
        minute_of_day = self._parse_minute_of_day(rtc_time)
        if minute_of_day is None:
            return False
        planned_minute = 1
        return 0 <= minute_of_day <= planned_minute + 4

    def _parse_minute_of_day(self, rtc_time: Any) -> int | None:
        raw = safe_text(rtc_time)
        if len(raw) < 5 or raw[2] != ":":
            return None
        try:
            hh = int(raw[0:2])
            mm = int(raw[3:5])
        except ValueError:
            return None
        if hh not in range(24) or mm not in range(60):
            return None
        return hh * 60 + mm

    def _normalize_gate_state(self, value: Any) -> str | None:
        normalized = safe_text(value).lower()
        if not normalized:
            return None
        if "closed" in normalized or "close" in normalized:
            return "closed"
        if "open" in normalized:
            return "open"
        return normalized

    def _normalize_gate_source(self, source: Any, reason: Any) -> str:
        normalized = safe_text(source).lower().replace("-", "_").replace(" ", "_")
        if normalized == "button" or "button" in normalized:
            return "button"
        if normalized in {"web", "garage_web", "mobile_hub", "android_widget"} or any(token in normalized for token in ("web", "hub", "widget")):
            return "web"
        if normalized:
            return "remote"
        reason_text = safe_text(reason).lower()
        if "button" in reason_text:
            return "button"
        if "web" in reason_text or "hub" in reason_text or "widget" in reason_text:
            return "web"
        return "remote"

    def _normalize_grid_reason(self, reason: Any, grid_relay_on: Any, battery_soc: Any) -> str:
        reason_text = self._normalize_reason(reason)
        if coerce_bool(grid_relay_on, False):
            try:
                if float(battery_soc) < 70.0 and self._is_startup_like_reason(reason):
                    return "Low battery SOC"
            except (TypeError, ValueError):
                pass
        return reason_text

    def _is_startup_like_reason(self, value: Any) -> bool:
        normalized = safe_text(value).lower()
        return any(token in normalized for token in ("system start", "startup", "restored from startup"))

    def _normalize_reason(self, value: Any) -> str:
        text = safe_text(value)
        if not text:
            return "Manual change"
        normalized = text.lower().replace("_", " ").replace("-", " ").strip()
        if not normalized:
            return "Manual change"
        compact = normalized.replace(" ", "")
        if normalized in {"manual", "unknown", "none", "null", "n/a", "na", "---"}:
            return "Manual change"
        if "manual" in normalized:
            return "Manual change"
        if normalized in {"manual pulse", "pulse"} or "pulse" in normalized:
            return "Manual pulse"
        if compact and compact.count("?") == len(compact):
            return "Manual change"
        if normalized.count("?") >= 3:
            return "Manual change"
        return text

    def _build_metric_context(self, current: dict[str, Any], module: str) -> str:
        if module == "inverter":
            parts = [
                self._metric_part("PV", current.get("pvW"), "W"),
                self._metric_part("LOAD", current.get("loadW"), "W"),
                self._metric_part("BAT", current.get("inverterBatterySoc"), "%"),
                self._metric_part("GRID", current.get("gridVoltage"), "V"),
            ]
        elif module == "load_controller":
            parts = [
                self._metric_part("Boiler", current.get("boiler1PowerW"), "W"),
                self._metric_part("Pump", current.get("pumpPowerW"), "W"),
                self._metric_part("PV", current.get("pvW"), "W"),
            ]
        elif module == "garage":
            parts = [
                self._metric_part("Boiler", current.get("boiler2PowerW"), "W"),
                self._metric_part("PV", current.get("pvW"), "W"),
            ]
        else:
            parts = []
        return ", ".join([part for part in parts if part])

    def _metric_part(self, label: str, value: Any, suffix: str) -> str:
        try:
            return f"{label}={int(float(value))}{suffix}"
        except (TypeError, ValueError):
            return ""

    def _load_history_entries(self) -> list[dict[str, Any]]:
        payload = read_json_file(self.history_path, [])
        if not isinstance(payload, list):
            return []
        items: list[dict[str, Any]] = []
        for entry in payload:
            if not isinstance(entry, dict):
                continue
            at_ms = int(entry.get("atMs") or 0)
            if at_ms <= 0:
                continue
            items.append(entry)
        items.sort(key=lambda item: int(item.get("atMs") or 0))
        return items

    def _recent_history_entries(self, hours: int) -> list[dict[str, Any]]:
        entries = self._load_history_entries()
        window_ms = clamp(hours, 1, 6) * 60 * 60 * 1000
        reference_at = int(entries[-1].get("atMs") or now_ms()) if entries else now_ms()
        from_ms = reference_at - window_ms
        return [entry for entry in entries if int(entry.get("atMs") or 0) >= from_ms]

    def _append_history(self, status: dict[str, Any]) -> None:
        entries = self._load_history_entries()
        current = self._history_entry_from_status(status)
        previous = entries[-1] if entries else None
        if previous is not None and not self._should_append_history(previous, current):
            return
        entries.append(current)
        self._prune_history(entries, int(current.get("atMs") or now_ms()))
        write_json_file(self.history_path, entries)

    def _history_entry_from_status(self, status: dict[str, Any]) -> dict[str, Any]:
        inverter = status.get("inverter") or {}
        load_controller = status.get("loadController") or {}
        garage = status.get("garage") or {}
        at_ms = max(
            [
                int(inverter.get("updatedAtMs") or 0),
                int(load_controller.get("updatedAtMs") or 0),
                int(garage.get("updatedAtMs") or 0),
                int(status.get("updatedAtMs") or now_ms()),
            ],
        )
        return {
            "atMs": at_ms,
            "inverterOnline": bool(status.get("inverter")),
            "loadControllerOnline": bool(status.get("loadController")),
            "garageOnline": bool(status.get("garage")),
            "pvW": inverter.get("pvW") if status.get("inverter") else load_controller.get("pvW") if status.get("loadController") else garage.get("pvW"),
            "gridW": inverter.get("gridW") if status.get("inverter") else load_controller.get("gridW") if status.get("loadController") else garage.get("gridW"),
            "loadW": inverter.get("loadW") if status.get("inverter") else load_controller.get("loadW") if status.get("loadController") else garage.get("loadW"),
            "batterySoc": inverter.get("batterySoc") if status.get("inverter") else load_controller.get("batterySoc") if status.get("loadController") else garage.get("batterySoc"),
            "batteryPower": inverter.get("batteryPower") if status.get("inverter") else load_controller.get("batteryPower") if status.get("loadController") else garage.get("batteryPower"),
            "inverterLineVoltage": inverter.get("lineVoltage") if status.get("inverter") else None,
            "loadLineVoltage": load_controller.get("lineVoltage") if status.get("loadController") else None,
            "garageLineVoltage": garage.get("lineVoltage") if status.get("garage") else None,
            "gridRelayOn": inverter.get("gridRelayOn") if status.get("inverter") else None,
            "gridMode": inverter.get("mode") if status.get("inverter") else None,
            "loadRelayOn": inverter.get("loadRelayOn") if status.get("inverter") else None,
            "loadMode": inverter.get("loadMode") if status.get("inverter") else None,
            "boiler1On": load_controller.get("boiler1On") if status.get("loadController") else None,
            "boiler1Mode": load_controller.get("boiler1Mode") if status.get("loadController") else None,
            "boiler1PowerW": load_controller.get("boilerPower") if status.get("loadController") else None,
            "boiler1AutoWindowActive": load_controller.get("boiler1AutoWindowActive") if status.get("loadController") else None,
            "pumpOn": load_controller.get("pumpOn") if status.get("loadController") else None,
            "pumpMode": load_controller.get("pumpMode") if status.get("loadController") else None,
            "pumpPowerW": load_controller.get("pumpPower") if status.get("loadController") else None,
            "pumpAutoWindowActive": load_controller.get("pumpAutoWindowActive") if status.get("loadController") else None,
            "boiler2On": garage.get("boiler2On") if status.get("garage") else None,
            "boiler2Mode": garage.get("boiler2Mode") if status.get("garage") else None,
            "boiler2PowerW": garage.get("boilerPower") if status.get("garage") else None,
            "boiler2AutoWindowActive": garage.get("boiler2AutoWindowActive") if status.get("garage") else None,
            "garageLightOn": garage.get("garageLightOn") if status.get("garage") else None,
            "gateState": garage.get("gateState") if status.get("garage") else None,
        }

    def _should_append_history(self, previous: dict[str, Any], current: dict[str, Any]) -> bool:
        previous_at = int(previous.get("atMs") or 0)
        current_at = int(current.get("atMs") or 0)
        if current_at - previous_at >= MIN_HISTORY_SAMPLE_GAP_MS:
            return True
        for key in ("inverterOnline", "loadControllerOnline", "garageOnline", "gridRelayOn", "loadRelayOn", "boiler1On", "pumpOn", "boiler2On", "garageLightOn"):
            if previous.get(key) != current.get(key):
                return True
        for key in ("gridMode", "loadMode", "boiler1Mode", "pumpMode", "boiler2Mode", "gateState"):
            if previous.get(key) != current.get(key):
                return True
        return self._has_large_metric_delta(previous, current)

    def _has_large_metric_delta(self, previous: dict[str, Any], current: dict[str, Any]) -> bool:
        return any(
            [
                self._diff(previous.get("pvW"), current.get("pvW")) >= 180.0,
                self._diff(previous.get("loadW"), current.get("loadW")) >= 220.0,
                self._diff(previous.get("gridW"), current.get("gridW")) >= 220.0,
                self._diff(previous.get("batteryPower"), current.get("batteryPower")) >= 220.0,
                self._diff(previous.get("boiler1PowerW"), current.get("boiler1PowerW")) >= 120.0,
                self._diff(previous.get("pumpPowerW"), current.get("pumpPowerW")) >= 120.0,
                self._diff(previous.get("boiler2PowerW"), current.get("boiler2PowerW")) >= 120.0,
                self._diff(previous.get("batterySoc"), current.get("batterySoc")) >= 1.0,
                self._diff(previous.get("inverterLineVoltage"), current.get("inverterLineVoltage")) >= 3.0,
                self._diff(previous.get("loadLineVoltage"), current.get("loadLineVoltage")) >= 3.0,
                self._diff(previous.get("garageLineVoltage"), current.get("garageLineVoltage")) >= 3.0,
            ],
        )

    def _diff(self, first: Any, second: Any) -> float:
        try:
            return abs(float(first) - float(second))
        except (TypeError, ValueError):
            return 0.0

    def _prune_history(self, entries: list[dict[str, Any]], reference_at: int) -> None:
        from_ms = reference_at - MAX_HISTORY_WINDOW_MS
        while entries and int(entries[0].get("atMs") or 0) < from_ms:
            entries.pop(0)
        while len(entries) > MAX_HISTORY_ENTRIES:
            entries.pop(0)

    def _count_transitions(self, key: str, window_ms: int) -> int:
        entries = self._load_history_entries()
        if len(entries) < 2:
            return 0
        reference_at = int(entries[-1].get("atMs") or now_ms())
        from_ms = reference_at - max(window_ms, 60_000)
        window = [entry for entry in entries if int(entry.get("atMs") or 0) >= from_ms]
        if len(window) < 2:
            return 0
        transitions = 0
        previous_value = self._transition_value(window[0], key)
        for entry in window[1:]:
            current_value = self._transition_value(entry, key)
            if previous_value is not None and current_value is not None and previous_value != current_value:
                transitions += 1
            if current_value is not None:
                previous_value = current_value
        return transitions

    def _transition_value(self, entry: dict[str, Any], key: str) -> str | None:
        if key == "grid":
            value = entry.get("gridRelayOn")
            return "ON" if value is True else "OFF" if value is False else None
        if key == "load":
            value = entry.get("loadRelayOn")
            return "ON" if value is True else "OFF" if value is False else None
        if key == "boiler1":
            value = entry.get("boiler1On")
            return "ON" if value is True else "OFF" if value is False else None
        if key == "pump":
            value = entry.get("pumpOn")
            return "ON" if value is True else "OFF" if value is False else None
        if key == "boiler2":
            value = entry.get("boiler2On")
            return "ON" if value is True else "OFF" if value is False else None
        if key == "gate":
            raw = safe_text(entry.get("gateState")).upper()
            return raw or None
        return None

    def _set_inverter_grid_mode(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["inverterEnabled"]:
            return False
        return self.post_mode(config["inverterBaseUrl"], config["inverterPassword"], "/api/mode", safe_text(args.get("mode"), "AUTO"))

    def _set_inverter_load_mode(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["inverterEnabled"]:
            return False
        return self.post_mode(config["inverterBaseUrl"], config["inverterPassword"], "/api/loadmode", safe_text(args.get("mode"), "AUTO"))

    def _set_inverter_load_lock(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["inverterEnabled"]:
            return False
        return self.post_form(
            config["inverterBaseUrl"],
            config["inverterPassword"],
            "/api/loadlock",
            [("locked", "1" if coerce_bool(args.get("locked"), False) else "0")],
        )

    def _set_inverter_grid_logic(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["inverterEnabled"]:
            return False
        return self.post_form(
            config["inverterBaseUrl"],
            config["inverterPassword"],
            "/api/gridlogic",
            [
                ("pv_threshold_w", str(coerce_float(args.get("pvThresholdW"), 150.0))),
                ("off_delay_sec", str(coerce_int(args.get("offDelaySec"), 300, 1, 86_400))),
                ("on_delay_sec", str(coerce_int(args.get("onDelaySec"), 1800, 1, 86_400))),
                ("force_grid_on_w", str(coerce_float(args.get("forceGridOnW"), 3000.0))),
            ],
        )

    def _set_inverter_load_logic(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["inverterEnabled"]:
            return False
        return self.post_form(
            config["inverterBaseUrl"],
            config["inverterPassword"],
            "/api/loadlogic",
            [
                ("pv_threshold_w", str(coerce_float(args.get("pvThresholdW"), 100.0))),
                ("shutdown_delay_sec", str(coerce_int(args.get("shutdownDelaySec"), 120, 1, 86_400))),
                ("overload_power_w", str(coerce_float(args.get("overloadPowerW"), 4500.0))),
            ],
        )

    def _set_boiler1_mode(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["loadControllerEnabled"]:
            return False
        return self.post_mode(config["loadControllerBaseUrl"], config["loadControllerPassword"], "/api/mode", safe_text(args.get("mode"), "AUTO"))

    def _set_boiler1_lock(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["loadControllerEnabled"]:
            return False
        return self.post_form(
            config["loadControllerBaseUrl"],
            config["loadControllerPassword"],
            "/api/boilerlock",
            [("lock", safe_text(args.get("mode"), "NONE").upper())],
        )

    def _set_boiler1_logic(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["loadControllerEnabled"]:
            return False
        return self.post_form(
            config["loadControllerBaseUrl"],
            config["loadControllerPassword"],
            "/api/boilerlogic",
            [
                ("pv_threshold_w", str(coerce_float(args.get("pvThresholdW"), 100.0))),
                ("shutdown_delay_sec", str(coerce_int(args.get("shutdownDelaySec"), 120, 1, 86_400))),
                ("battery_shutoff_w", str(coerce_float(args.get("batteryShutoffW"), -1800.0))),
                ("battery_resume_w", str(coerce_float(args.get("batteryResumeW"), 200.0))),
                ("peer_active_w", str(coerce_float(args.get("peerActiveW"), 1000.0))),
            ],
        )

    def _set_boiler1_auto_window(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["loadControllerEnabled"]:
            return False
        return self.post_form(
            config["loadControllerBaseUrl"],
            config["loadControllerPassword"],
            "/api/boilerautowindow",
            [
                ("enabled", "1" if coerce_bool(args.get("enabled"), False) else "0"),
                ("start", safe_text(args.get("start"), "00:00")),
                ("end", safe_text(args.get("end"), "00:00")),
            ],
        )

    def _set_pump_mode(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["loadControllerEnabled"]:
            return False
        return self.post_mode(config["loadControllerBaseUrl"], config["loadControllerPassword"], "/api/loadmode", safe_text(args.get("mode"), "AUTO"))

    def _set_pump_lock(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["loadControllerEnabled"]:
            return False
        return self.post_form(
            config["loadControllerBaseUrl"],
            config["loadControllerPassword"],
            "/api/pumplock",
            [("lock", safe_text(args.get("mode"), "NONE").upper())],
        )

    def _set_pump_logic(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["loadControllerEnabled"]:
            return False
        return self.post_form(
            config["loadControllerBaseUrl"],
            config["loadControllerPassword"],
            "/api/pumplogic",
            [
                ("pv_threshold_w", str(coerce_float(args.get("pvThresholdW"), 100.0))),
                ("shutdown_delay_sec", str(coerce_int(args.get("shutdownDelaySec"), 120, 1, 86_400))),
            ],
        )

    def _set_pump_auto_window(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["loadControllerEnabled"]:
            return False
        return self.post_form(
            config["loadControllerBaseUrl"],
            config["loadControllerPassword"],
            "/api/pumpautowindow",
            [
                ("enabled", "1" if coerce_bool(args.get("enabled"), False) else "0"),
                ("start", safe_text(args.get("start"), "00:00")),
                ("end", safe_text(args.get("end"), "00:00")),
            ],
        )

    def _set_boiler2_mode(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["garageEnabled"]:
            return False
        return self.post_mode(config["garageBaseUrl"], config["garagePassword"], "/api/mode", safe_text(args.get("mode"), "AUTO"))

    def _set_boiler2_lock(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["garageEnabled"]:
            return False
        return self.post_form(
            config["garageBaseUrl"],
            config["garagePassword"],
            "/api/boilerlock",
            [("lock", safe_text(args.get("mode"), "NONE").upper())],
        )

    def _set_boiler2_logic(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["garageEnabled"]:
            return False
        return self.post_form(
            config["garageBaseUrl"],
            config["garagePassword"],
            "/api/boilerlogic",
            [
                ("pv_threshold_w", str(coerce_float(args.get("pvThresholdW"), 100.0))),
                ("shutdown_delay_sec", str(coerce_int(args.get("shutdownDelaySec"), 120, 1, 86_400))),
                ("battery_shutoff_w", str(coerce_float(args.get("batteryShutoffW"), -1800.0))),
                ("battery_resume_w", str(coerce_float(args.get("batteryResumeW"), 200.0))),
                ("peer_active_w", str(coerce_float(args.get("peerActiveW"), 1000.0))),
            ],
        )

    def _set_boiler2_auto_window(self, config: dict[str, Any], args: dict[str, Any]) -> bool:
        if not config["garageEnabled"]:
            return False
        return self.post_form(
            config["garageBaseUrl"],
            config["garagePassword"],
            "/api/boilerautowindow",
            [
                ("enabled", "1" if coerce_bool(args.get("enabled"), False) else "0"),
                ("start", safe_text(args.get("start"), "00:00")),
                ("end", safe_text(args.get("end"), "00:00")),
            ],
        )

    def _trigger_gate(self, config: dict[str, Any]) -> bool:
        if not config["garageEnabled"]:
            return False
        return self.post_form(
            config["garageBaseUrl"],
            config["garagePassword"],
            "/api/door",
            [
                ("action", "pulse"),
                ("source", "windows_hub"),
                ("reason", "windows hub"),
            ],
        )

    def _toggle_garage_light(self, config: dict[str, Any]) -> bool:
        if not config["garageEnabled"]:
            return False
        return self.post_form(
            config["garageBaseUrl"],
            config["garagePassword"],
            "/api/light",
            [
                ("state", "TOGGLE"),
                ("source", "windows_hub"),
                ("reason", "windows hub"),
            ],
        )

    def render_index_html(self) -> str:
        html_path = self.assets_dir / "hub.html"
        html = html_path.read_text(encoding="utf-8")
        marker = '<script src="hub.js"></script>'
        injected = '  <script src="/desktop_bridge.js"></script>\n  <script src="hub.js"></script>'
        if marker in html and "/desktop_bridge.js" not in html:
            return html.replace(marker, injected, 1)
        return html

    def static_asset(self, path: str) -> tuple[bytes, str] | None:
        clean_path = path.lstrip("/")
        if clean_path == "desktop_bridge.js":
            asset_path = self.windows_dir / "desktop_bridge.js"
        else:
            asset_path = (self.assets_dir / clean_path).resolve()
            try:
                asset_path.relative_to(self.assets_dir.resolve())
            except ValueError:
                return None
        if not asset_path.exists() or not asset_path.is_file():
            return None
        content_type, _ = mimetypes.guess_type(str(asset_path))
        return asset_path.read_bytes(), content_type or "application/octet-stream"


class HubServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address: tuple[str, int], backend: HubBackend) -> None:
        super().__init__(server_address, HubRequestHandler)
        self.backend = backend


class HubRequestHandler(BaseHTTPRequestHandler):
    server: HubServer

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if path in {"/", "/index.html"}:
            html = self.server.backend.render_index_html().encode("utf-8")
            self._send_bytes(HTTPStatus.OK, html, "text/html; charset=utf-8")
            return

        if path == "/api/health":
            self._send_json(HTTPStatus.OK, {"ok": True, "app": "homehub-windows"})
            return

        if path == "/bridge/config":
            self._send_json(HTTPStatus.OK, self.server.backend.get_config())
            return

        if path == "/bridge/status":
            payload = self.server.backend.fetch_status(_refresh="refresh" in query)
            self._send_json(HTTPStatus.OK, payload)
            return

        if path == "/bridge/inverter/daily":
            payload = self.server.backend.fetch_inverter_daily(self._query_value(query, "date"))
            self._send_optional_json(payload)
            return

        if path == "/bridge/inverter/monthly":
            payload = self.server.backend.fetch_inverter_monthly(self._query_value(query, "month"))
            self._send_optional_json(payload)
            return

        if path == "/bridge/inverter/yearly":
            payload = self.server.backend.fetch_inverter_yearly()
            self._send_optional_json(payload)
            return

        if path == "/bridge/load/history":
            payload = self.server.backend.fetch_load_history()
            self._send_optional_json(payload)
            return

        if path == "/bridge/garage/doorhistory":
            payload = self.server.backend.fetch_garage_door_history()
            self._send_optional_json(payload)
            return

        if path == "/bridge/garage/history":
            payload = self.server.backend.fetch_garage_history()
            self._send_optional_json(payload)
            return

        if path == "/bridge/events":
            self._send_json(HTTPStatus.OK, self.server.backend.fetch_event_journal())
            return

        if path == "/bridge/automation-history":
            hours = coerce_int(self._query_value(query, "hours"), 6, 1, 6)
            self._send_json(HTTPStatus.OK, self.server.backend.fetch_automation_history(hours))
            return

        asset = self.server.backend.static_asset(path)
        if asset is not None:
            data, content_type = asset
            self._send_bytes(HTTPStatus.OK, data, content_type)
            return

        self._send_json(HTTPStatus.NOT_FOUND, {"error": f"Unknown path: {path}"})

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/bridge/config":
            raw_body = self._read_raw_body()
            ok, config = self.server.backend.save_config(raw_body.decode("utf-8", errors="replace"))
            status = HTTPStatus.OK if ok else HTTPStatus.BAD_REQUEST
            self._send_json(status, {"ok": ok, "config": config})
            return

        if path.startswith("/bridge/action/"):
            method_name = urllib.parse.unquote(path.split("/bridge/action/", 1)[1]).strip()
            payload = self._read_json_body()
            if payload is None:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid JSON body"})
                return
            result = self.server.backend.perform_action(method_name, payload)
            status = HTTPStatus.OK if result.get("ok") else HTTPStatus.BAD_REQUEST
            self._send_json(status, result)
            return

        if path == "/bridge/open-external":
            payload = self._read_json_body()
            if payload is None:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid JSON body"})
                return
            ok = self.server.backend.open_external_url(safe_text(payload.get("url")))
            status = HTTPStatus.OK if ok else HTTPStatus.BAD_REQUEST
            self._send_json(status, {"ok": ok})
            return

        self._send_json(HTTPStatus.NOT_FOUND, {"error": f"Unknown path: {path}"})

    def do_DELETE(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if path == "/bridge/events":
            self._send_json(HTTPStatus.OK, self.server.backend.clear_event_journal())
            return
        self._send_json(HTTPStatus.NOT_FOUND, {"error": f"Unknown path: {path}"})

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _query_value(self, query: dict[str, list[str]], key: str) -> str:
        values = query.get(key) or []
        return values[0] if values else ""

    def _read_raw_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length <= 0:
            return b""
        return self.rfile.read(length)

    def _read_json_body(self) -> dict[str, Any] | None:
        raw = self._read_raw_body()
        if not raw:
            return {}
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        return payload if isinstance(payload, dict) else None

    def _send_optional_json(self, payload: dict[str, Any] | None) -> None:
        if payload is None:
            self._send_json(HTTPStatus.BAD_GATEWAY, {"error": "No data"})
            return
        self._send_json(HTTPStatus.OK, payload)

    def _send_json(self, status: HTTPStatus, payload: Any) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._send_bytes(status, body, "application/json; charset=utf-8")

    def _send_bytes(self, status: HTTPStatus, payload: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)


def main() -> None:
    parser = argparse.ArgumentParser(description="Windows desktop host for the Home Hub UI")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    windows_dir = Path(__file__).resolve().parent
    repo_dir = windows_dir.parent
    backend = HubBackend(repo_dir=repo_dir, windows_dir=windows_dir)

    server = HubServer((args.host, args.port), backend)
    print(f"Home Hub desktop host listening on http://{args.host}:{args.port}/")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
