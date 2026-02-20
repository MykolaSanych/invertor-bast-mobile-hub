import asyncio
import logging
import os
from collections import deque
from dataclasses import asdict, dataclass
from datetime import datetime
from typing import Any, Dict, List, Optional

import requests
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException
from firebase_admin import credentials, get_app, initialize_app, messaging
from pydantic import BaseModel, Field

load_dotenv()

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("push-bridge")


def env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def normalize_base_url(value: str) -> str:
    raw = (value or "").strip().rstrip("/")
    if not raw:
        return ""
    if raw.startswith("http://") or raw.startswith("https://"):
        return raw
    return f"http://{raw}"


def as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        v = value.strip().lower()
        return v in {"1", "true", "on", "yes"}
    return False


def as_float(value: Any, default: float = 0.0) -> float:
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value.strip())
        except ValueError:
            return default
    return default


def as_str(value: Any, default: str = "") -> str:
    if value is None:
        return default
    text = str(value).strip()
    return text if text else default


def bearer_token(authorization: Optional[str]) -> str:
    if not authorization:
        return ""
    parts = authorization.strip().split(" ", 1)
    if len(parts) != 2:
        return ""
    if parts[0].lower() != "bearer":
        return ""
    return parts[1].strip()


@dataclass
class Event:
    event_type: str
    title: str
    reason: str
    body: str
    source: str
    payload: Dict[str, Any]


class TestPushRequest(BaseModel):
    title: str = Field(default="Test push")
    body: str = Field(default="Push bridge is working")
    reason: str = Field(default="manual_test")


class DeviceClient:
    def __init__(self, name: str, base_url: str, password: str, timeout_sec: int):
        self.name = name
        self.base_url = normalize_base_url(base_url)
        self.password = password
        self.session = requests.Session()
        self.timeout = timeout_sec
        self.last_error = ""

    def _authenticate(self) -> bool:
        if not self.password:
            return True
        try:
            response = self.session.post(
                f"{self.base_url}/api/auth",
                data={"pass": self.password},
                timeout=self.timeout,
            )
            return response.ok
        except Exception as exc:
            self.last_error = f"auth failed: {exc}"
            return False

    def fetch_status(self) -> Optional[Dict[str, Any]]:
        if not self.base_url:
            self.last_error = "base_url is empty"
            return None

        try:
            response = self.session.get(f"{self.base_url}/api/status", timeout=self.timeout)
            if response.status_code in (401, 403):
                if not self._authenticate():
                    logger.warning("%s auth failed", self.name)
                    return None
                response = self.session.get(f"{self.base_url}/api/status", timeout=self.timeout)

            response.raise_for_status()
            payload = response.json()
            if isinstance(payload, dict) and payload.get("error") == "auth_required":
                if not self._authenticate():
                    logger.warning("%s auth_required and auth failed", self.name)
                    return None
                retry = self.session.get(f"{self.base_url}/api/status", timeout=self.timeout)
                retry.raise_for_status()
                payload = retry.json()

            if not isinstance(payload, dict):
                raise ValueError("status payload is not JSON object")

            self.last_error = ""
            return payload
        except Exception as exc:
            self.last_error = str(exc)
            logger.warning("%s status fetch failed: %s", self.name, exc)
            return None


class PushBridge:
    def __init__(self):
        self.poll_interval = int(env("POLL_INTERVAL_SEC", "5") or "5")
        self.poll_interval = max(2, min(60, self.poll_interval))

        self.request_timeout = int(env("REQUEST_TIMEOUT_SEC", "5") or "5")
        self.request_timeout = max(2, min(30, self.request_timeout))

        self.pv_threshold_w = as_float(env("PV_ACTIVE_THRESHOLD_W", "80"), 80.0)
        self.fcm_topic = env("FCM_TOPIC", "home-events") or "home-events"
        self.credentials_path = env("FIREBASE_CREDENTIALS_JSON", "./service-account.json")
        self.fcm_dry_run = as_bool(env("FCM_DRY_RUN", "0"))
        self.api_token = env("BRIDGE_API_TOKEN")

        self.inverter = DeviceClient(
            "inverter",
            env("INVERTER_URL"),
            env("INVERTER_PASS"),
            self.request_timeout,
        )
        self.load_controller = DeviceClient(
            "load_controller",
            env("LOAD_CONTROLLER_URL"),
            env("LOAD_CONTROLLER_PASS"),
            self.request_timeout,
        )
        self.garage = DeviceClient(
            "garage",
            env("GARAGE_URL"),
            env("GARAGE_PASS"),
            self.request_timeout,
        )

        self.prev_inverter: Optional[Dict[str, Any]] = None
        self.prev_load_controller: Optional[Dict[str, Any]] = None
        self.prev_garage: Optional[Dict[str, Any]] = None

        self.last_events: deque[Event] = deque(maxlen=200)
        self.last_poll_at = ""
        self._task: Optional[asyncio.Task] = None
        self._running = False
        self.fcm_enabled = self._init_firebase()

    def _init_firebase(self) -> bool:
        try:
            try:
                get_app()
                logger.info("Firebase already initialized")
                return True
            except ValueError:
                pass

            if not self.credentials_path or not os.path.exists(self.credentials_path):
                logger.warning(
                    "Firebase disabled: credentials file not found: %s",
                    self.credentials_path,
                )
                return False

            initialize_app(credentials.Certificate(self.credentials_path))
            logger.info("Firebase initialized")
            return True
        except Exception as exc:
            logger.error("Firebase init failed: %s", exc)
            return False

    async def start(self):
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._poll_loop())
        logger.info("Push bridge started (interval=%ss)", self.poll_interval)

    async def stop(self):
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        logger.info("Push bridge stopped")

    async def _poll_loop(self):
        while self._running:
            try:
                self.poll_once()
            except Exception as exc:
                logger.exception("poll loop error: %s", exc)
            await asyncio.sleep(self.poll_interval)

    def poll_once(self) -> List[Event]:
        inverter = self.inverter.fetch_status()
        load = self.load_controller.fetch_status()
        garage = self.garage.fetch_status()

        self.last_poll_at = datetime.utcnow().isoformat() + "Z"

        events: List[Event] = []
        events.extend(self._detect_inverter_events(self.prev_inverter, inverter))
        events.extend(self._detect_load_controller_events(self.prev_load_controller, load))
        events.extend(self._detect_garage_events(self.prev_garage, garage))

        for event in events:
            self._send_push(event)
            self.last_events.append(event)

        self.prev_inverter = inverter
        self.prev_load_controller = load
        self.prev_garage = garage
        return events

    def _send_push(self, event: Event):
        logger.info("event: %s | %s | reason=%s", event.source, event.title, event.reason)
        if not self.fcm_enabled:
            return

        try:
            data_payload = {
                "event_type": event.event_type,
                "source": event.source,
                "reason": event.reason,
                "title": event.title,
                "body": event.body,
            }
            for key, value in event.payload.items():
                data_payload[key] = as_str(value)

            message = messaging.Message(
                topic=self.fcm_topic,
                notification=messaging.Notification(title=event.title, body=event.body),
                data=data_payload,
                android=messaging.AndroidConfig(
                    priority="high",
                    notification=messaging.AndroidNotification(channel_id="home_events"),
                ),
            )
            messaging.send(message, dry_run=self.fcm_dry_run)
        except Exception as exc:
            logger.error("FCM send failed: %s", exc)

    def send_test_push(self, title: str, body: str, reason: str) -> bool:
        if not self.fcm_enabled:
            return False

        event = Event(
            event_type="manual_test",
            title=title,
            reason=reason,
            body=body,
            source="push_bridge",
            payload={},
        )
        self._send_push(event)
        self.last_events.append(event)
        return True

    def _detect_inverter_events(
        self,
        prev: Optional[Dict[str, Any]],
        curr: Optional[Dict[str, Any]],
    ) -> List[Event]:
        if not prev or not curr:
            return []

        events: List[Event] = []
        prev_pv_active = as_float(prev.get("pv")) >= self.pv_threshold_w
        curr_pv_active = as_float(curr.get("pv")) >= self.pv_threshold_w

        if prev_pv_active != curr_pv_active:
            reason = f"PV={as_float(curr.get('pv')):.0f}W, threshold={self.pv_threshold_w:.0f}W"
            title = "PV generation appeared" if curr_pv_active else "PV generation disappeared"
            events.append(
                Event(
                    event_type="pv_generation_changed",
                    title=title,
                    reason=reason,
                    body=f"{title}. Reason: {reason}",
                    source="inverter",
                    payload={"pv": as_float(curr.get("pv"))},
                )
            )

        events.extend(
            self._bool_transition_event(
                prev=prev,
                curr=curr,
                key="pin34_state",
                reason_key="pin34_reason",
                title_on="GRID turned ON",
                title_off="GRID turned OFF",
                source="inverter",
                event_type="grid_relay_changed",
            )
        )

        events.extend(
            self._mode_change_event(
                prev=prev,
                curr=curr,
                key="mode",
                reason_key="mode_reason",
                mode_title="GRID mode changed",
                source="inverter",
                event_type="grid_mode_changed",
            )
        )
        events.extend(
            self._mode_change_event(
                prev=prev,
                curr=curr,
                key="load_mode",
                reason_key="load_mode_reason",
                mode_title="LOAD mode changed",
                source="inverter",
                event_type="load_mode_changed",
            )
        )

        return events

    def _detect_load_controller_events(
        self,
        prev: Optional[Dict[str, Any]],
        curr: Optional[Dict[str, Any]],
    ) -> List[Event]:
        if not prev or not curr:
            return []

        events: List[Event] = []
        events.extend(
            self._mode_change_event(
                prev=prev,
                curr=curr,
                key="mode",
                reason_key="boiler_mode_reason",
                mode_title="BOILER1 mode changed",
                source="load_controller",
                event_type="boiler1_mode_changed",
            )
        )
        events.extend(
            self._mode_change_event(
                prev=prev,
                curr=curr,
                key="load_mode",
                reason_key="pump_mode_reason",
                mode_title="PUMP mode changed",
                source="load_controller",
                event_type="pump_mode_changed",
            )
        )
        return events

    def _detect_garage_events(
        self,
        prev: Optional[Dict[str, Any]],
        curr: Optional[Dict[str, Any]],
    ) -> List[Event]:
        if not prev or not curr:
            return []

        events: List[Event] = []
        events.extend(
            self._mode_change_event(
                prev=prev,
                curr=curr,
                key="mode",
                reason_key="boiler_mode_reason",
                mode_title="BOILER2 mode changed",
                source="garage",
                event_type="boiler2_mode_changed",
            )
        )

        prev_gate = as_str(prev.get("door_state"), "unknown")
        curr_gate = as_str(curr.get("door_state"), "unknown")
        if prev_gate != curr_gate:
            reason = as_str(curr.get("door_reason"), "unknown")
            title = "Gate state changed"
            body = f"{title}: {prev_gate} -> {curr_gate}. Reason: {reason}"
            events.append(
                Event(
                    event_type="gate_mode_changed",
                    title=title,
                    reason=reason,
                    body=body,
                    source="garage",
                    payload={"from": prev_gate, "to": curr_gate},
                )
            )
        return events

    def _bool_transition_event(
        self,
        prev: Dict[str, Any],
        curr: Dict[str, Any],
        key: str,
        reason_key: str,
        title_on: str,
        title_off: str,
        source: str,
        event_type: str,
    ) -> List[Event]:
        prev_value = as_bool(prev.get(key))
        curr_value = as_bool(curr.get(key))
        if prev_value == curr_value:
            return []

        reason = as_str(curr.get(reason_key), "unknown")
        title = title_on if curr_value else title_off
        body = f"{title}. Reason: {reason}"
        return [
            Event(
                event_type=event_type,
                title=title,
                reason=reason,
                body=body,
                source=source,
                payload={"value": curr_value},
            )
        ]

    def _mode_change_event(
        self,
        prev: Dict[str, Any],
        curr: Dict[str, Any],
        key: str,
        reason_key: str,
        mode_title: str,
        source: str,
        event_type: str,
    ) -> List[Event]:
        prev_mode = as_str(prev.get(key), "---")
        curr_mode = as_str(curr.get(key), "---")
        if prev_mode == curr_mode:
            return []

        reason = as_str(curr.get(reason_key), "unknown")
        body = f"{mode_title}: {prev_mode} -> {curr_mode}. Reason: {reason}"
        return [
            Event(
                event_type=event_type,
                title=mode_title,
                reason=reason,
                body=body,
                source=source,
                payload={"from": prev_mode, "to": curr_mode},
            )
        ]

    def probe_devices(self) -> Dict[str, Any]:
        statuses = {}
        for client in [self.inverter, self.load_controller, self.garage]:
            payload = client.fetch_status()
            statuses[client.name] = {
                "configured": bool(client.base_url),
                "ok": payload is not None,
                "last_error": client.last_error or None,
                "mode": as_str(payload.get("mode"), "---") if payload else None,
                "pv": as_float(payload.get("pv"), 0.0) if payload else None,
            }
        return statuses

    def health(self) -> Dict[str, Any]:
        return {
            "running": self._running,
            "fcm_enabled": self.fcm_enabled,
            "fcm_dry_run": self.fcm_dry_run,
            "api_token_required": bool(self.api_token),
            "firebase_credentials_found": os.path.exists(self.credentials_path),
            "poll_interval_sec": self.poll_interval,
            "request_timeout_sec": self.request_timeout,
            "pv_threshold_w": self.pv_threshold_w,
            "topic": self.fcm_topic,
            "last_poll_at": self.last_poll_at,
            "last_events_count": len(self.last_events),
            "devices": {
                "inverter": {
                    "configured": bool(self.inverter.base_url),
                    "last_error": self.inverter.last_error or None,
                },
                "load_controller": {
                    "configured": bool(self.load_controller.base_url),
                    "last_error": self.load_controller.last_error or None,
                },
                "garage": {
                    "configured": bool(self.garage.base_url),
                    "last_error": self.garage.last_error or None,
                },
            },
        }


bridge = PushBridge()


def require_api_token(
    x_bridge_token: Optional[str] = Header(default=None),
    authorization: Optional[str] = Header(default=None),
):
    configured = bridge.api_token
    if not configured:
        return
    if x_bridge_token and x_bridge_token == configured:
        return
    if bearer_token(authorization) == configured:
        return
    raise HTTPException(status_code=401, detail="Unauthorized")


app = FastAPI(title="Home Push Bridge", version="1.1.0")


@app.on_event("startup")
async def startup_event():
    await bridge.start()


@app.on_event("shutdown")
async def shutdown_event():
    await bridge.stop()


@app.get("/health")
def health():
    return bridge.health()


@app.get("/probe")
def probe(_auth: None = Depends(require_api_token)):
    return bridge.probe_devices()


@app.post("/poll-once")
def poll_once(_auth: None = Depends(require_api_token)):
    events = bridge.poll_once()
    return {"count": len(events), "events": [asdict(e) for e in events]}


@app.post("/test-push")
def test_push(request: TestPushRequest, _auth: None = Depends(require_api_token)):
    ok = bridge.send_test_push(request.title, request.body, request.reason)
    if not ok:
        raise HTTPException(status_code=503, detail="FCM is not configured")
    return {"status": "ok"}


@app.get("/last-events")
def last_events(limit: int = 50, _auth: None = Depends(require_api_token)):
    limit = max(1, min(200, limit))
    items = list(bridge.last_events)[-limit:]
    return {"count": len(items), "events": [asdict(e) for e in items]}
