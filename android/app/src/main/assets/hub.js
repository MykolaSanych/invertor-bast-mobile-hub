const DEFAULT_CONFIG = {
  inverterBaseUrl: "http://192.168.1.2",
  inverterPassword: "admin",
  loadControllerBaseUrl: "http://192.168.1.3",
  loadControllerPassword: "admin",
  garageBaseUrl: "http://192.168.1.4",
  garagePassword: "admin",
  pollIntervalSec: 5,
  inverterEnabled: true,
  loadControllerEnabled: true,
  garageEnabled: true,
  realtimeMonitorEnabled: false,
  realtimePollIntervalSec: 5,
  notifyPvGeneration: true,
  notifyGridRelay: true,
  notifyGridPresence: true,
  notifyGridMode: true,
  notifyLoadMode: true,
  notifyBoiler1Mode: true,
  notifyPumpMode: true,
  notifyBoiler2Mode: true,
  notifyGateState: true,
};

const LOAD_TIMELINE_POWER_ON_THRESHOLD = 50;
const LOAD_TIMELINE_HISTORY_REFRESH_MS = 60 * 1000;
const LOAD_TIMELINE_VISIBLE_HOURS = 6;
const LOAD_TIMELINE_MAX_SAMPLES = 1600;
const BRIDGE_REQUEST_TIMEOUT_MS = 15000;
const CONSUMPTION_DISPLAY_THRESHOLD_W = 50;
const CARD_NEON_POWER_THRESHOLD = 1;
const DEVICE_POWER_NOISE_FLOOR_W = 27;
const ZERO_VOLTAGE_THRESHOLD_V = 0.5;
const MODULE_STALE_AFTER_MISSES = 3;
const SCHEME_FLOW_COLORS = Object.freeze({
  pv: [255, 179, 71],
  grid: [79, 124, 255],
  battery: [51, 255, 153],
  loadFallback: [255, 77, 109],
});
const GRAPH_CACHE_STORAGE_KEY = "hub.graphCache.v1";
const GRAPH_CACHE_SCHEMA_VERSION = 1;
const GRAPH_CACHE_MAX_ENTRIES_PER_TYPE = 180;
const GRAPH_SYNC_MIN_GLOBAL_GAP_MS = 3500;
const GRAPH_SYNC_MIN_PER_KEY_GAP_MS = 25000;
const GRAPH_SYNC_INTERVAL_MS = 15 * 60 * 1000;
const GRAPH_SYNC_INTERVAL_JITTER_MS = 45 * 1000;
const GRAPH_SYNC_MAX_ITEMS_PER_CYCLE = 2;
const GRAPH_SYNC_VIEW_WINDOW_MS = 14 * 24 * 60 * 60 * 1000;
const GRAPH_CACHE_TTL_MS = Object.freeze({
  energy: Object.freeze({
    daily: 5 * 60 * 1000,
    monthly: 40 * 60 * 1000,
    yearly: 6 * 60 * 60 * 1000,
  }),
  climate: Object.freeze({
    daily: 5 * 60 * 1000,
    monthly: 40 * 60 * 1000,
    yearly: 6 * 60 * 60 * 1000,
  }),
});

const state = {
  config: { ...DEFAULT_CONFIG },
  status: null,
  pending: new Map(),
  reqSeq: 0,
  statusRequestInFlight: false,
  pollHandle: null,
  signalAgeHandle: null,
  noBridgeToastShown: false,
  emptyStatusCount: 0,
  energy: {
    period: "daily",
    last: null,
  },
  climate: {
    period: "daily",
    metric: "temp",
    last: null,
  },
  graphCache: {
    loaded: false,
    persistHandle: null,
    energy: {},
    climate: {},
  },
  graphSync: {
    queue: null,
    inFlight: new Map(),
    lastGlobalFetchAtMs: 0,
    lastAttemptByKey: {},
    timer: null,
    cycleInFlight: false,
  },
  gate: {
    lastState: "",
    lastOpenAt: "--",
    lastCloseAt: "--",
  },
  locks: {
    inverterLoadOn: false,
    boiler1: "NONE",
    pump: "NONE",
    boiler2: "NONE",
  },
  timeline: {
    samples: [],
    day: "",
    lastTimestamp: 0,
    historyReady: false,
    lastHistoryFetchMs: 0,
  },
  events: {
    items: [],
    loadedAtMs: 0,
    viewMode: "all",
  },
  moduleSignalAtMs: {
    inverter: 0,
    loadController: 0,
    garage: 0,
  },
  moduleMissCounts: {
    inverter: 0,
    loadController: 0,
    garage: 0,
  },
  schemeGesture: {
    touch: null,
    suppressClickUntilMs: 0,
  },
  schemeControlLandscape: false,
  schemeControlReturnToSchemeModalId: "",
  schemeControlPendingModalId: "",
};

window.HubNative = {
  onStatusResult(requestId, payload) {
    const data = normalizePayload(payload);
    if (!data) {
      rejectPending(requestId, "Invalid status payload");
      return;
    }

    const isPartial = isPartialStatusRequestId(requestId);
    updateModuleSignalTimes(data);
    if (!isPartial) {
      updateModuleMissCounts(data);
    }
    state.status = mergeStatusForUi(data, { isPartial });
    renderAll();
    trackConnectivityHealth(data);

    const pending = state.pending.get(requestId);
    if (pending) {
      state.pending.delete(requestId);
      if (pending.timer) {
        clearTimeout(pending.timer);
      }
      pending.resolve(data);
    }
  },

  onStatusError(requestId, message) {
    rejectPending(requestId, message || "Status error");
    applyLiveCardStates(state.status, { flash: false });
    showToast(message || "Status request failed");
  },

  onActionResult(requestId, ok, message) {
    const pending = state.pending.get(requestId);
    if (!pending) return;

    state.pending.delete(requestId);
    if (pending.timer) {
      clearTimeout(pending.timer);
    }
    if (ok) {
      pending.resolve(true);
    } else {
      pending.reject(new Error(message || "Command failed"));
    }
  },

  onDataResult(requestId, payload) {
    const data = normalizePayload(payload);
    if (!data) {
      rejectPending(requestId, "Invalid data payload");
      return;
    }
    const pending = state.pending.get(requestId);
    if (!pending) return;
    state.pending.delete(requestId);
    if (pending.timer) {
      clearTimeout(pending.timer);
    }
    pending.resolve(data);
  },

  onDataError(requestId, message) {
    rejectPending(requestId, message || "Data request failed");
  },
};

function hasBridge() {
  return !!window.AndroidHub;
}

function nextRequestId(prefix) {
  state.reqSeq += 1;
  return `${prefix}-${Date.now()}-${state.reqSeq}`;
}

function bridgeRequest(prefix, invoker) {
  if (!hasBridge()) {
    return Promise.reject(new Error("Bridge unavailable"));
  }

  const requestId = nextRequestId(prefix);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      rejectPending(requestId, `Request timeout after ${BRIDGE_REQUEST_TIMEOUT_MS} ms`);
    }, BRIDGE_REQUEST_TIMEOUT_MS);
    state.pending.set(requestId, { resolve, reject, timer });
    try {
      invoker(requestId);
    } catch (error) {
      state.pending.delete(requestId);
      clearTimeout(timer);
      reject(error);
    }
  });
}

function rejectPending(requestId, message) {
  const pending = state.pending.get(requestId);
  if (!pending) return;
  state.pending.delete(requestId);
  if (pending.timer) {
    clearTimeout(pending.timer);
  }
  pending.reject(new Error(message || "Request failed"));
}

function isPartialStatusRequestId(requestId) {
  return typeof requestId === "string" && requestId.startsWith("partial-");
}

function normalizePayload(payload) {
  if (payload === null || payload === undefined) return null;
  if (typeof payload === "string") {
    try {
      return JSON.parse(payload);
    } catch (error) {
      return null;
    }
  }
  if (typeof payload === "object") return payload;
  return null;
}

function sleepMs(ms) {
  const delay = Math.max(0, Number(ms) || 0);
  return new Promise((resolve) => setTimeout(resolve, delay));
}

function ensureGraphSyncQueue() {
  if (!state.graphSync.queue) {
    state.graphSync.queue = Promise.resolve();
  }
}

function graphEntryKey(period, selector) {
  return `${safeText(period, "daily")}::${safeText(selector, "current")}`;
}

function parseGraphEntryKey(key) {
  const raw = safeText(key, "");
  const sep = raw.indexOf("::");
  if (sep < 0) {
    return {
      period: raw || "daily",
      selector: "current",
    };
  }
  return {
    period: raw.substring(0, sep) || "daily",
    selector: raw.substring(sep + 2) || "current",
  };
}

function graphCacheTtlMs(graphType, period) {
  const bucket = GRAPH_CACHE_TTL_MS[graphType];
  if (!bucket) return 5 * 60 * 1000;
  return bucket[period] || bucket.daily || 5 * 60 * 1000;
}

function getGraphCacheSlot(graphType) {
  return graphType === "climate" ? state.graphCache.climate : state.graphCache.energy;
}

function pruneGraphCacheSlot(slot) {
  const keys = Object.keys(slot || {});
  if (keys.length <= GRAPH_CACHE_MAX_ENTRIES_PER_TYPE) return;

  keys.sort((a, b) => {
    const ea = slot[a] || {};
    const eb = slot[b] || {};
    const sa = Number(ea.viewedAtMs || ea.fetchedAtMs || 0);
    const sb = Number(eb.viewedAtMs || eb.fetchedAtMs || 0);
    return sb - sa;
  });

  keys.slice(GRAPH_CACHE_MAX_ENTRIES_PER_TYPE).forEach((key) => {
    delete slot[key];
  });
}

function persistGraphCacheNow() {
  if (!state.graphCache.loaded) return;
  try {
    const payload = {
      version: GRAPH_CACHE_SCHEMA_VERSION,
      energy: state.graphCache.energy || {},
      climate: state.graphCache.climate || {},
      savedAtMs: Date.now(),
    };
    localStorage.setItem(GRAPH_CACHE_STORAGE_KEY, JSON.stringify(payload));
  } catch (error) {
    // Ignore storage errors (quota/private mode).
  }
}

function scheduleGraphCachePersist() {
  if (state.graphCache.persistHandle) {
    clearTimeout(state.graphCache.persistHandle);
  }
  state.graphCache.persistHandle = setTimeout(() => {
    state.graphCache.persistHandle = null;
    persistGraphCacheNow();
  }, 400);
}

function loadGraphCacheFromStorage() {
  if (state.graphCache.loaded) return;
  ensureGraphSyncQueue();
  state.graphCache.loaded = true;
  state.graphCache.energy = {};
  state.graphCache.climate = {};

  try {
    const raw = localStorage.getItem(GRAPH_CACHE_STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return;
    if (Number(parsed.version) !== GRAPH_CACHE_SCHEMA_VERSION) return;
    if (parsed.energy && typeof parsed.energy === "object") {
      state.graphCache.energy = parsed.energy;
    }
    if (parsed.climate && typeof parsed.climate === "object") {
      state.graphCache.climate = parsed.climate;
    }
    pruneGraphCacheSlot(state.graphCache.energy);
    pruneGraphCacheSlot(state.graphCache.climate);
  } catch (error) {
    // Ignore invalid cache payload.
  }
}

function getGraphCacheEntry(graphType, period, selector) {
  loadGraphCacheFromStorage();
  const slot = getGraphCacheSlot(graphType);
  const key = graphEntryKey(period, selector);
  const entry = slot[key];
  if (!entry || typeof entry !== "object" || !entry.model) return null;
  return entry;
}

function touchGraphCacheEntry(graphType, period, selector, viewedAtMs = Date.now()) {
  loadGraphCacheFromStorage();
  const slot = getGraphCacheSlot(graphType);
  const key = graphEntryKey(period, selector);
  const entry = slot[key];
  if (!entry || typeof entry !== "object") return;
  entry.viewedAtMs = viewedAtMs;
  scheduleGraphCachePersist();
}

function upsertGraphCacheEntry(graphType, period, selector, model, fetchedAtMs = Date.now()) {
  loadGraphCacheFromStorage();
  const slot = getGraphCacheSlot(graphType);
  const key = graphEntryKey(period, selector);
  const previous = slot[key];
  const viewedAtMs = Number(previous?.viewedAtMs || fetchedAtMs);
  slot[key] = {
    model,
    fetchedAtMs,
    viewedAtMs,
  };
  pruneGraphCacheSlot(slot);
  scheduleGraphCachePersist();
}

function isGraphCacheStale(entry, graphType, period, nowMs = Date.now()) {
  if (!entry || typeof entry !== "object") return true;
  const fetchedAtMs = Number(entry.fetchedAtMs || 0);
  if (!Number.isFinite(fetchedAtMs) || fetchedAtMs <= 0) return true;
  const ttlMs = graphCacheTtlMs(graphType, period);
  return nowMs - fetchedAtMs > ttlMs;
}

function shouldThrottleGraphSyncKey(syncKey, force = false, nowMs = Date.now()) {
  if (force) return false;
  const lastAttempt = Number(state.graphSync.lastAttemptByKey[syncKey] || 0);
  if (lastAttempt > 0 && nowMs - lastAttempt < GRAPH_SYNC_MIN_PER_KEY_GAP_MS) {
    return true;
  }
  state.graphSync.lastAttemptByKey[syncKey] = nowMs;
  return false;
}

function enqueueGraphSync(syncKey, fetcher) {
  ensureGraphSyncQueue();
  const existing = state.graphSync.inFlight.get(syncKey);
  if (existing) return existing;

  const taskRunner = async () => {
    const nowMs = Date.now();
    const gapLeftMs = GRAPH_SYNC_MIN_GLOBAL_GAP_MS - (nowMs - state.graphSync.lastGlobalFetchAtMs);
    if (gapLeftMs > 0) {
      await sleepMs(gapLeftMs);
    }
    const result = await fetcher();
    state.graphSync.lastGlobalFetchAtMs = Date.now();
    return result;
  };

  const task = state.graphSync.queue.then(taskRunner, taskRunner);
  state.graphSync.queue = task.catch(() => undefined);
  state.graphSync.inFlight.set(syncKey, task);
  task.finally(() => {
    if (state.graphSync.inFlight.get(syncKey) === task) {
      state.graphSync.inFlight.delete(syncKey);
    }
  });
  return task;
}

function toFiniteNumber(value, fallback = 0) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return parsed;
}

function num(value, digits = 0, fallback = "0") {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return parsed.toFixed(digits);
}

function maybeNum(value, digits = 1, fallback = "--") {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return parsed.toFixed(digits);
}

function boolText(value) {
  if (value === null || value === undefined) return "---";
  return value ? "ON" : "OFF";
}

function safeText(value, fallback = "---") {
  if (value === null || value === undefined) return fallback;
  const str = String(value).trim();
  return str.length ? str : fallback;
}

function isGarbledUiText(value) {
  const text = String(value || "").trim();
  if (!text) return false;
  if (/[\uFFFD]/.test(text)) return true;
  if (/^[?\s]+$/.test(text)) return true;
  return /[?]{2,}/.test(text);
}

function uiText(value, fallback = "---") {
  const text = safeText(value, fallback);
  return isGarbledUiText(text) ? fallback : text;
}

function pickNumber(values, fallback = 0) {
  for (const value of values) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return fallback;
}

function applyPowerNoiseFloor(value, thresholdW = DEVICE_POWER_NOISE_FLOOR_W) {
  const n = Number(value);
  if (!Number.isFinite(n)) return value;
  return Math.abs(n) < thresholdW ? 0 : n;
}

function applyConsumptionDisplayFloor(value, thresholdW = CONSUMPTION_DISPLAY_THRESHOLD_W) {
  const n = Number(value);
  if (!Number.isFinite(n)) return value;
  return Math.abs(n) < thresholdW ? 0 : n;
}

function zeroGridPowerWhenNoVoltage(powerValue, voltageValue) {
  const power = Number(powerValue);
  const voltage = Number(voltageValue);
  if (!Number.isFinite(power)) return powerValue;
  if (Number.isFinite(voltage) && Math.abs(voltage) <= ZERO_VOLTAGE_THRESHOLD_V) {
    return 0;
  }
  return power;
}

function clampPoll(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 5;
  return Math.max(2, Math.min(60, Math.round(parsed)));
}

function clampRealtimePoll(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 5;
  return Math.max(3, Math.min(60, Math.round(parsed)));
}

function normalizeBaseUrl(value) {
  const trimmed = safeText(value, "").trim().replace(/\/+$/, "");
  if (!trimmed) return "";
  if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
    return trimmed;
  }
  return `http://${trimmed}`;
}

function formatClockFromMs(ts) {
  const n = Number(ts);
  if (!Number.isFinite(n) || n <= 0) return "--:--:--";
  const d = new Date(n);
  return `${String(d.getHours()).padStart(2, "0")}:${String(
    d.getMinutes(),
  ).padStart(2, "0")}:${String(d.getSeconds()).padStart(2, "0")}`;
}

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

function currentMonthIso() {
  return new Date().toISOString().slice(0, 7);
}

function setText(id, value) {
  const el = document.getElementById(id);
  if (!el) return;
  el.textContent = value;
}

function formatDateTimeFromStatus(dateValue, timeValue) {
  const date = safeText(dateValue, "");
  const time = safeText(timeValue, "");
  if (date && time && date !== "---" && time !== "--:--:--") {
    return `${date} ${time}`;
  }
  return formatDateTimeFromMs(Date.now());
}

function formatDateTimeFromMs(ts) {
  const n = Number(ts);
  if (!Number.isFinite(n) || n <= 0) return "--";
  const d = new Date(n);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day} ${formatClockFromMs(n)}`;
}

function moduleUpdatedText(enabled, module) {
  if (!enabled) return "disabled";
  const moduleTs = Number(module?.updatedAtMs);
  if (!Number.isFinite(moduleTs) || moduleTs <= 0) return "--:--:--";
  return formatClockFromMs(moduleTs);
}

function classifyGateState(garage) {
  const closedPin = Number(garage?.gateClosedPin);
  if (Number.isFinite(closedPin) && closedPin >= 0) {
    return closedPin === 0 ? "closed" : "open";
  }

  const raw = safeText(garage?.gateState, "").toLowerCase();
  if (raw.includes("open")) return "open";
  if (raw.includes("close") || raw.includes("closed")) return "closed";
  if (raw.includes("stop")) return "stopped";
  if (raw.includes("move")) return "moving";
  return "unknown";
}

function setGateActionButtonLabel(stateName) {
  const btn = document.getElementById("gateActionBtn");
  if (!btn) return;

  let label = "stop";
  if (stateName === "closed") label = "open";
  else if (stateName === "open") label = "close";

  btn.textContent = label;
  btn.classList.toggle("is-open", label === "open");
  btn.classList.toggle("is-close", label === "close");
  btn.classList.toggle("is-stop", label === "stop");
}

function setGarageLightActionButtonState({ disabled = false, on = false, reason = "" } = {}) {
  const btn = document.getElementById("garageLightActionBtn");
  if (!btn) return;

  btn.disabled = !!disabled;
  btn.classList.toggle("is-on", !disabled && !!on);
  btn.classList.toggle("is-off", !disabled && !on);

  if (disabled) {
    btn.textContent = "light\n--";
    btn.title = "garage module disabled";
    return;
  }

  btn.textContent = `light\n${on ? "ON" : "OFF"}`;
  btn.title = reason ? `garage light (${reason})` : "garage light";
}

function readChecked(id, fallback = false) {
  const el = document.getElementById(id);
  if (!el) return fallback;
  return !!el.checked;
}

function setWifi(strengthRaw) {
  const strength = Math.max(0, Math.min(100, Math.round(Number(strengthRaw) || 0)));
  setText("wifiStrengthTop", `${strength}%`);

  const bar = document.getElementById("wifiStrengthBar");
  if (bar) {
    const width = Math.max(4, Math.round((40 * strength) / 100));
    bar.style.width = `${width}px`;
  }

  let icon = "wifi0";
  if (strength >= 75) icon = "wifi4";
  else if (strength >= 50) icon = "wifi3";
  else if (strength >= 25) icon = "wifi2";
  else if (strength > 0) icon = "wifi1";
  setText("wifiIcon", icon);
}

function parseRtcTimestampParts(datePart, timePart) {
  const dateText = safeText(datePart, "");
  const timeText = safeText(timePart, "");
  if (dateText.length === 10 && timeText.length === 8) {
    const parsed = new Date(`${dateText}T${timeText}`).getTime();
    if (Number.isFinite(parsed)) return parsed;
  }
  return Date.now();
}

function loadTimelineDayKey(timestampMs) {
  const date = new Date(timestampMs);
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function resizeLoadTimelineCanvas(canvas) {
  const rect = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  const width = Math.max(1, Math.floor(rect.width * ratio));
  const height = Math.max(1, Math.floor(rect.height * ratio));
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
}

function drawLoadTimelineRow(ctx, samples, key, xFor, rowTop, rowHeight, color) {
  if (!samples.length) return;
  let segmentStart = null;

  for (let i = 0; i < samples.length; i += 1) {
    const sample = samples[i];
    const isOn = !!sample[key];
    if (isOn && segmentStart === null) {
      segmentStart = sample.ts;
    }
    if (!isOn && segmentStart !== null) {
      const xStart = xFor(segmentStart);
      const xEnd = xFor(sample.ts);
      ctx.fillStyle = color;
      ctx.fillRect(xStart, rowTop, Math.max(1, xEnd - xStart), rowHeight);
      segmentStart = null;
    }
  }

  if (segmentStart !== null) {
    const xStart = xFor(segmentStart);
    const xEnd = xFor(samples[samples.length - 1].ts);
    ctx.fillStyle = color;
    ctx.fillRect(xStart, rowTop, Math.max(1, xEnd - xStart), rowHeight);
  }
}

function renderLoadTimeline() {
  const canvas = document.getElementById("loadTimelineCanvas");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const wrap = canvas.parentElement;
  if (!wrap) return;

  const visibleHours = Math.max(1, LOAD_TIMELINE_VISIBLE_HOURS);
  const pxPerHour = Math.max(60, wrap.clientWidth / visibleHours);
  const fullWidth = Math.round(pxPerHour * 24);
  canvas.style.width = `${fullWidth}px`;

  resizeLoadTimelineCanvas(canvas);
  const width = canvas.width;
  const height = canvas.height;
  if (width <= 2 || height <= 2) return;

  ctx.clearRect(0, 0, width, height);

  const samples = state.timeline.samples;
  const now = samples.length ? samples[samples.length - 1].ts : Date.now();
  const dateLabel = safeText(state.timeline.day, loadTimelineDayKey(now));
  const dayStart = new Date(`${dateLabel}T00:00:00`).getTime();
  const dayEnd = new Date(`${dateLabel}T23:55:00`).getTime();
  const windowMs = Math.max(1, dayEnd - dayStart);

  const padding = { left: 60, right: 18, top: 16, bottom: 28 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  if (plotWidth <= 10 || plotHeight <= 10) return;

  const xFor = (ts) => padding.left + ((ts - dayStart) / windowMs) * plotWidth;

  ctx.fillStyle = "rgba(255,255,255,0.04)";
  ctx.fillRect(padding.left, padding.top, plotWidth, plotHeight);

  const rowGap = 10;
  const rows = 4;
  const rowHeight = (plotHeight - rowGap * (rows - 1)) / rows;
  const boilerTop = padding.top;
  const pumpTop = boilerTop + rowHeight + rowGap;
  const gridTop = pumpTop + rowHeight + rowGap;
  const pvTop = gridTop + rowHeight + rowGap;

  ctx.strokeStyle = "rgba(255,255,255,0.08)";
  ctx.lineWidth = 1;
  ctx.strokeRect(padding.left, padding.top, plotWidth, plotHeight);

  const tickMs = 60 * 60 * 1000;
  ctx.font = `${12 * (window.devicePixelRatio || 1)}px "Playpen Sans", sans-serif`;
  ctx.fillStyle = "rgba(230, 241, 255, 0.55)";
  ctx.textAlign = "center";
  ctx.textBaseline = "top";
  for (let t = dayStart; t <= dayEnd; t += tickMs) {
    const x = xFor(t);
    ctx.strokeStyle = "rgba(79,124,255,0.18)";
    ctx.beginPath();
    ctx.moveTo(x, padding.top);
    ctx.lineTo(x, padding.top + plotHeight);
    ctx.stroke();
    const hh = String(new Date(t).getHours()).padStart(2, "0");
    ctx.fillText(`${hh}:00`, x, padding.top + plotHeight + 6);
  }

  ctx.fillStyle = "rgba(230, 241, 255, 0.75)";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";
  ctx.fillText("BOILER", padding.left - 8, boilerTop + rowHeight / 2);
  ctx.fillText("PUMP", padding.left - 8, pumpTop + rowHeight / 2);
  ctx.fillText("GRID", padding.left - 8, gridTop + rowHeight / 2);
  ctx.fillText("PV", padding.left - 8, pvTop + rowHeight / 2);

  ctx.fillStyle = "rgba(255,77,109,0.12)";
  ctx.fillRect(padding.left, boilerTop, plotWidth, rowHeight);
  ctx.fillStyle = "rgba(79,124,255,0.12)";
  ctx.fillRect(padding.left, pumpTop, plotWidth, rowHeight);
  ctx.fillStyle = "rgba(51,255,153,0.1)";
  ctx.fillRect(padding.left, gridTop, plotWidth, rowHeight);
  ctx.fillStyle = "rgba(255,179,71,0.12)";
  ctx.fillRect(padding.left, pvTop, plotWidth, rowHeight);

  drawLoadTimelineRow(ctx, samples, "boilerOn", xFor, boilerTop, rowHeight, "rgba(255,77,109,0.85)");
  drawLoadTimelineRow(ctx, samples, "pumpOn", xFor, pumpTop, rowHeight, "rgba(79,124,255,0.85)");
  drawLoadTimelineRow(ctx, samples, "gridOn", xFor, gridTop, rowHeight, "rgba(51,255,153,0.85)");
  drawLoadTimelineRow(ctx, samples, "pvOn", xFor, pvTop, rowHeight, "rgba(255,179,71,0.85)");

  if (!samples.length) {
    ctx.fillStyle = "rgba(230, 241, 255, 0.7)";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText("no timeline data", width / 2, height / 2);
  }

  setText("timelineMeta", `date: ${dateLabel} (00:00-23:55), view ~${visibleHours}h`);
}

function applyLoadTimelineHistory(payload) {
  const dateLabel = safeText(payload?.date, "");
  const rows = Array.isArray(payload?.samples) ? payload.samples : [];
  if (!dateLabel) {
    throw new Error("history payload is empty");
  }

  const dayStart = new Date(`${dateLabel}T00:00:00`).getTime();
  if (!Number.isFinite(dayStart)) {
    throw new Error("invalid timeline date");
  }

  const samples = [];
  rows.forEach((row) => {
    const minute = Number(row?.m);
    if (!Number.isFinite(minute)) return;
    const ts = dayStart + minute * 60 * 1000;
    const flags = Number(row?.f) || 0;
    samples.push({
      ts,
      boilerOn: (flags & 0x01) !== 0,
      pumpOn: (flags & 0x02) !== 0,
      gridOn: (flags & 0x04) !== 0,
      pvOn: (flags & 0x08) !== 0,
    });
  });

  samples.sort((a, b) => a.ts - b.ts);
  state.timeline.samples = samples;
  state.timeline.day = dateLabel;
  state.timeline.lastTimestamp = samples.length ? samples[samples.length - 1].ts : dayStart;
  state.timeline.historyReady = true;
}

function recordLoadTimelineSample(loadController) {
  if (!loadController || typeof loadController !== "object") return;

  const boilerPower = Number(loadController.boilerPower);
  const pumpPower = Number(loadController.pumpPower);
  const hasSampleBasis = Number.isFinite(boilerPower) || Number.isFinite(pumpPower);
  if (!hasSampleBasis) return;

  let ts = parseRtcTimestampParts(loadController.rtcDate, loadController.rtcTime);
  const dayKey = safeText(loadController.rtcDate, "");
  if (dayKey && dayKey !== state.timeline.day) {
    state.timeline.day = dayKey;
    state.timeline.samples = [];
    state.timeline.lastTimestamp = 0;
    state.timeline.historyReady = false;
  }
  if (!state.timeline.day) {
    state.timeline.day = loadTimelineDayKey(ts);
  }

  if (ts <= state.timeline.lastTimestamp) {
    ts = state.timeline.lastTimestamp + clampPoll(state.config.pollIntervalSec) * 1000;
  }
  state.timeline.lastTimestamp = ts;

  const sample = {
    ts,
    boilerOn: Number.isFinite(boilerPower) ? boilerPower > LOAD_TIMELINE_POWER_ON_THRESHOLD : !!loadController.boiler1On,
    pumpOn: Number.isFinite(pumpPower) ? pumpPower > LOAD_TIMELINE_POWER_ON_THRESHOLD : !!loadController.pumpOn,
    gridOn: Number(loadController.lineVoltage) > 180,
    pvOn: Number(loadController.pvW) > 22,
  };

  const tail = state.timeline.samples[state.timeline.samples.length - 1];
  if (!tail || sample.ts > tail.ts) {
    state.timeline.samples.push(sample);
    if (state.timeline.samples.length > LOAD_TIMELINE_MAX_SAMPLES) {
      state.timeline.samples = state.timeline.samples.slice(
        state.timeline.samples.length - LOAD_TIMELINE_MAX_SAMPLES,
      );
    }
  }

  if (isModalOpen("timelineModal")) {
    renderLoadTimeline();
  }
}

async function loadLoadTimelineHistory(options = {}) {
  const { force = false } = options;
  if (!hasBridge()) {
    renderLoadTimeline();
    return;
  }
  if (!state.config.loadControllerEnabled) {
    renderLoadTimeline();
    showToast("load controller module disabled");
    return;
  }

  const nowMs = Date.now();
  if (
    !force &&
    state.timeline.samples.length &&
    nowMs - state.timeline.lastHistoryFetchMs < LOAD_TIMELINE_HISTORY_REFRESH_MS
  ) {
    renderLoadTimeline();
    return;
  }

  setText("timelineChartTitle", "load controller timeline - loading...");
  try {
    const payload = await bridgeRequest("timeline-history", (requestId) => {
      window.AndroidHub.fetchLoadControllerHistory(requestId);
    });
    applyLoadTimelineHistory(payload);
    setText("timelineChartTitle", "load controller timeline");
    renderLoadTimeline();
  } catch (error) {
    setText("timelineChartTitle", "load controller timeline - error");
    renderLoadTimeline();
    showToast(`timeline load failed: ${error.message}`);
  } finally {
    state.timeline.lastHistoryFetchMs = nowMs;
  }
}

async function openTimelineModal() {
  openModal("timelineModal");
  await loadLoadTimelineHistory({ force: true });
}

function normalizeEventJournalViewMode(value) {
  return value === "gateDaily" ? "gateDaily" : "all";
}

function eventJournalViewTitle(viewMode) {
  const mode = normalizeEventJournalViewMode(viewMode);
  if (mode === "gateDaily") {
    return `garage gate history (${loadTimelineDayKey(Date.now())})`;
  }
  return "event journal";
}

function eventJournalEmptyText(viewMode) {
  const mode = normalizeEventJournalViewMode(viewMode);
  if (mode === "gateDaily") {
    return "no gate state changes today";
  }
  return "no events";
}

function syncEventJournalView(viewMode) {
  const mode = normalizeEventJournalViewMode(viewMode);
  state.events.viewMode = mode;
  const clearBtn = document.getElementById("eventsClearBtn");
  if (clearBtn) {
    clearBtn.hidden = mode !== "all";
  }
}

function eventDayKey(atMs) {
  const ts = Number(atMs);
  if (!Number.isFinite(ts) || ts <= 0) return "";
  return loadTimelineDayKey(ts);
}

function isGateStateChangeEvent(entry) {
  const title = safeText(entry?.title, "").toLowerCase();
  const body = safeText(entry?.body, "").toLowerCase();
  return title.includes("gate state changed") || (title.includes("gate") && body.includes("reason:"));
}

function filterEventJournalItems(items, viewMode) {
  const list = Array.isArray(items) ? items : [];
  const mode = normalizeEventJournalViewMode(viewMode);
  if (mode !== "gateDaily") return list;
  const todayKey = loadTimelineDayKey(Date.now());
  return list.filter((entry) => isGateStateChangeEvent(entry) && eventDayKey(entry?.atMs) === todayKey);
}

function renderEventJournal(items, options = {}) {
  const { emptyText = "no events" } = options;
  const root = document.getElementById("eventList");
  if (!root) return;
  root.innerHTML = "";

  if (!Array.isArray(items) || !items.length) {
    const empty = document.createElement("div");
    empty.className = "event-item-empty";
    empty.textContent = emptyText;
    root.appendChild(empty);
    return;
  }

  items.forEach((entry) => {
    const item = document.createElement("div");
    item.className = "event-item";

    const head = document.createElement("div");
    head.className = "event-item-head";

    const title = document.createElement("div");
    title.className = "event-item-title";
    title.textContent = safeText(entry?.title, "event");

    const time = document.createElement("div");
    time.className = "event-item-time";
    time.textContent = safeText(entry?.atText, formatDateTimeFromMs(entry?.atMs));

    const body = document.createElement("div");
    body.className = "event-item-body";
    body.textContent = safeText(entry?.body, "-");

    head.appendChild(title);
    head.appendChild(time);
    item.appendChild(head);
    item.appendChild(body);
    root.appendChild(item);
  });
}

function mapGarageDoorHistoryPayloadToEvents(payload) {
  const date = safeText(payload?.date, loadTimelineDayKey(Date.now()));
  const rows = Array.isArray(payload?.items) ? payload.items : [];
  const seen = new Set();
  const items = rows.map((row) => {
    const time = safeText(row?.time, "--:--:--");
    const state = safeText(row?.state, "unknown");
    const source = safeText(row?.source, "remote");
    const stateReason = safeText(row?.state_reason, "unknown");
    const triggerReason = safeText(row?.trigger_reason, "unknown");
    const dedupeKey = `${time}|${state}|${source}|${stateReason}|${triggerReason}`;
    if (seen.has(dedupeKey)) return null;
    seen.add(dedupeKey);
    const parsedAtMs = Date.parse(`${date}T${time}`);
    const atMs = Number.isFinite(parsedAtMs) ? parsedAtMs : Date.now();
    return {
      atMs,
      atText: `${date} ${time}`,
      title: `gate: ${state}`,
      body: `source: ${source}; trigger: ${triggerReason}; state: ${stateReason}`,
    };
  }).filter(Boolean);
  return { date, items };
}

async function loadGarageGateHistory() {
  const viewMode = "gateDaily";
  syncEventJournalView(viewMode);
  setText("eventChartTitle", eventJournalViewTitle(viewMode));

  if (!hasBridge()) {
    renderEventJournal([], { emptyText: eventJournalEmptyText(viewMode) });
    showToast("Android bridge not available");
    return;
  }
  if (!window.AndroidHub || typeof window.AndroidHub.fetchGarageDoorHistory !== "function") {
    await loadEventJournal({ viewMode: "gateDaily" });
    return;
  }

  setText("eventChartTitle", `${eventJournalViewTitle(viewMode)} - loading...`);
  try {
    const payload = await bridgeRequest("garage-door-history", (requestId) => {
      window.AndroidHub.fetchGarageDoorHistory(requestId);
    });
    const mapped = mapGarageDoorHistoryPayloadToEvents(payload);
    renderEventJournal(mapped.items, { emptyText: eventJournalEmptyText(viewMode) });
    setText("eventChartTitle", `garage gate history (${safeText(mapped.date, loadTimelineDayKey(Date.now()))})`);
  } catch (error) {
    // Fallback to local app journal filter for older garage firmware.
    await loadEventJournal({ viewMode: "gateDaily" });
    showToast(`garage history fallback: ${error.message}`);
  }
}

async function loadEventJournal(options = {}) {
  const viewMode = normalizeEventJournalViewMode(options.viewMode || state.events.viewMode);
  syncEventJournalView(viewMode);
  setText("eventChartTitle", eventJournalViewTitle(viewMode));

  if (!hasBridge()) {
    renderEventJournal([], { emptyText: eventJournalEmptyText(viewMode) });
    showToast("Android bridge not available");
    return;
  }

  setText("eventChartTitle", `${eventJournalViewTitle(viewMode)} - loading...`);
  try {
    const payload = await bridgeRequest("events", (requestId) => {
      window.AndroidHub.fetchEventJournal(requestId);
    });
    const items = Array.isArray(payload?.items) ? payload.items : [];
    state.events.items = items;
    state.events.loadedAtMs = Date.now();
    renderEventJournal(filterEventJournalItems(items, viewMode), {
      emptyText: eventJournalEmptyText(viewMode),
    });
    setText("eventChartTitle", eventJournalViewTitle(viewMode));
  } catch (error) {
    renderEventJournal([], { emptyText: eventJournalEmptyText(viewMode) });
    setText("eventChartTitle", `${eventJournalViewTitle(viewMode)} - error`);
    showToast(`event journal failed: ${error.message}`);
  }
}

async function clearEventJournal() {
  if (!hasBridge()) {
    showToast("Android bridge not available");
    return;
  }
  if (!window.confirm("clear event journal?")) return;

  try {
    await bridgeRequest("events-clear", (requestId) => {
      window.AndroidHub.clearEventJournal(requestId);
    });
    state.events.items = [];
    state.events.loadedAtMs = Date.now();
    renderEventJournal([], { emptyText: eventJournalEmptyText(state.events.viewMode) });
    showToast("event journal cleared");
  } catch (error) {
    showToast(`event clear failed: ${error.message}`);
  }
}

async function openEventModal() {
  openModal("eventModal");
  await loadEventJournal({ viewMode: "all" });
}

async function openGateHistoryModal() {
  openModal("eventModal");
  await loadGarageGateHistory();
}

function updateButtonStates(selector, expected) {
  const normalizedExpected = safeText(expected, "").trim().toUpperCase();
  document.querySelectorAll(selector).forEach((btn) => {
    const ownMode =
      btn.dataset.gridMode ||
      btn.dataset.loadMode ||
      btn.dataset.boiler1Mode ||
      btn.dataset.pumpMode ||
      btn.dataset.boiler2Mode;
    const normalizedOwn = safeText(ownMode, "").trim().toUpperCase();
    btn.classList.toggle("active", normalizedOwn === normalizedExpected);
  });
}

function normalizeLockMode(value) {
  const mode = safeText(value, "NONE").toUpperCase();
  if (mode === "ON" || mode === "OFF") return mode;
  return "NONE";
}

function setModeButtonLocked(buttonId, locked) {
  const btn = document.getElementById(buttonId);
  if (!btn) return;
  btn.classList.toggle("locked", !!locked);
}

function setActiveModeGroup(prefix, mode) {
  const normalizedMode = safeText(mode, "").trim().toUpperCase();
  ["AUTO", "OFF", "ON"].forEach((item) => {
    const btn = document.getElementById(`${prefix}${item}`);
    if (!btn) return;
    btn.classList.toggle("active", item === normalizedMode);
  });
}

function applyLockedActiveButtons(prefix, lockMode) {
  if (lockMode !== "ON" && lockMode !== "OFF") return;
  ["AUTO", "OFF", "ON"].forEach((mode) => {
    const btn = document.getElementById(`${prefix}${mode}`);
    if (btn) btn.classList.remove("active");
  });
  const lockedBtn = document.getElementById(`${prefix}${lockMode}`);
  if (lockedBtn) lockedBtn.classList.add("active");
}

function showToast(message) {
  const root = document.getElementById("toastRoot");
  if (!root) return;

  const toast = document.createElement("div");
  toast.className = "toast-item";
  toast.textContent = message;
  root.appendChild(toast);
  setTimeout(() => {
    toast.remove();
  }, 3000);
}

function modalNeedsLandscape(modalId) {
  if (modalId === "energyModal" || modalId === "climateModal" || modalId === "timelineModal" || modalId === "schemeModal") {
    return true;
  }
  return isSchemeControlModal(modalId) && state.schemeControlLandscape;
}

function anyLandscapeModalOpen() {
  if (["energyModal", "climateModal", "timelineModal", "schemeModal"].some((id) => isModalOpen(id))) {
    return true;
  }
  if (!state.schemeControlLandscape) return false;
  return ["gridModal", "loadModal", "boiler1Modal", "pumpModal", "boiler2Modal"].some((id) => isModalOpen(id));
}

function syncChartsOrientation() {
  if (!hasBridge()) return;
  if (!window.AndroidHub || typeof window.AndroidHub.setChartsLandscapeMode !== "function") return;
  try {
    window.AndroidHub.setChartsLandscapeMode(anyLandscapeModalOpen());
  } catch (error) {
    // ignore orientation bridge failures
  }
}

function openModal(id) {
  const modal = document.getElementById(id);
  if (!modal) return;

  if (id === "schemeModal") {
    state.schemeControlLandscape = true;
    state.schemeControlPendingModalId = "";
  } else if (isSchemeControlModal(id)) {
    if (state.schemeControlPendingModalId === id) {
      state.schemeControlLandscape = true;
      state.schemeControlReturnToSchemeModalId = id;
      state.schemeControlPendingModalId = "";
    } else {
      state.schemeControlReturnToSchemeModalId = "";
    }
  } else {
    state.schemeControlLandscape = false;
    state.schemeControlReturnToSchemeModalId = "";
    state.schemeControlPendingModalId = "";
  }

  modal.classList.add("is-open");

  if (id === "schemeModal") {
    requestAnimationFrame(() => {
      fitSchemeStageToViewport();
    });
  }

  if (modalNeedsLandscape(id) || isSchemeControlModal(id)) {
    syncChartsOrientation();
  }
}

function closeModal(id) {
  const modal = document.getElementById(id);
  if (!modal) return;

  const affectsLandscape = modalNeedsLandscape(id) || id === "schemeModal" || isSchemeControlModal(id);
  const returnToScheme = isSchemeControlModal(id) && state.schemeControlReturnToSchemeModalId === id;
  const schemeToControlTransition = id === "schemeModal" && state.schemeControlPendingModalId.length > 0;

  modal.classList.remove("is-open");

  if (id === "boiler1Modal") {
    setAutoWindowEditorOpen("boiler1", false);
  } else if (id === "pumpModal") {
    setAutoWindowEditorOpen("pump", false);
  } else if (id === "boiler2Modal") {
    setAutoWindowEditorOpen("boiler2", false);
  }

  if (returnToScheme) {
    state.schemeControlReturnToSchemeModalId = "";
    state.schemeControlLandscape = true;
    openModal("schemeModal");
    return;
  }

  if (isSchemeControlModal(id)) {
    state.schemeControlReturnToSchemeModalId = "";
  }

  if (id === "schemeModal" && !anySchemeControlModalOpen() && !schemeToControlTransition) {
    state.schemeControlLandscape = false;
  }
  if (isSchemeControlModal(id) && !isSchemeModalOpen() && !anySchemeControlModalOpen()) {
    state.schemeControlLandscape = false;
  }

  if (affectsLandscape) {
    syncChartsOrientation();
  }
}

function closeAllModals() {
  document.querySelectorAll(".modal-root").forEach((modal) => {
    modal.classList.remove("is-open");
  });
  state.schemeControlLandscape = false;
  state.schemeControlReturnToSchemeModalId = "";
  state.schemeControlPendingModalId = "";
  syncChartsOrientation();
}

function isModalOpen(id) {
  const modal = document.getElementById(id);
  return !!modal && modal.classList.contains("is-open");
}

function isSchemeModalOpen() {
  return isModalOpen("schemeModal");
}

function isSchemeControlModal(modalId) {
  return modalId === "gridModal" || modalId === "loadModal" || modalId === "boiler1Modal" || modalId === "pumpModal" || modalId === "boiler2Modal";
}

function anySchemeControlModalOpen() {
  return ["gridModal", "loadModal", "boiler1Modal", "pumpModal", "boiler2Modal"].some((id) => isModalOpen(id));
}

function hasOtherOpenModal(exceptId) {
  const openModals = document.querySelectorAll(".modal-root.is-open");
  for (const modal of openModals) {
    if (!modal || modal.id === exceptId) continue;
    return true;
  }
  return false;
}

function markSchemeSwipeHandled() {
  state.schemeGesture.suppressClickUntilMs = Date.now() + 420;
}

function isSchemeSwipeClickSuppressed() {
  return Date.now() < Number(state.schemeGesture.suppressClickUntilMs || 0);
}

function fitSchemeStageToViewport() {
  const stage = document.getElementById("schemeStage");
  const modalBox = document.querySelector("#schemeModal .scheme-modal-box");
  if (!stage || !modalBox) return;

  const viewportWidth = Math.max(320, window.innerWidth || document.documentElement.clientWidth || 1000);
  const viewportHeight = Math.max(320, window.innerHeight || document.documentElement.clientHeight || 800);
  const modalRect = modalBox.getBoundingClientRect();

  const horizontalPadding = 10;
  const verticalPadding = 10;

  const boxWidth = Math.max(280, Math.floor(Math.min(modalBox.clientWidth - horizontalPadding, viewportWidth - 24)));
  const boxLimitedHeight = Math.max(180, Math.floor(Math.min(modalBox.clientHeight - verticalPadding, viewportHeight - modalRect.top - 14)));

  stage.style.width = `${boxWidth}px`;
  stage.style.height = `${boxLimitedHeight}px`;
}

function bindSchemeSwipe() {
  const beginTrack = (x, y, id, source) => {
    if (state.schemeGesture.touch && state.schemeGesture.touch.source !== source) return;
    state.schemeGesture.touch = {
      source,
      id,
      startX: x,
      startY: y,
      handled: false,
    };
  };

  const tryHandle = (x, y, id, source, eventObj) => {
    const touch = state.schemeGesture.touch;
    if (!touch) return false;
    if (touch.source !== source || touch.id !== id) return false;

    const dx = x - touch.startX;
    const dy = y - touch.startY;
    const absX = Math.abs(dx);
    const absY = Math.abs(dy);

    if (!touch.handled && absX >= 34 && absY <= 120 && absX >= absY * 1.05) {
      if (dx > 0 && !isSchemeModalOpen() && !document.querySelector(".modal-root.is-open")) {
        openModal("schemeModal");
        touch.handled = true;
        markSchemeSwipeHandled();
      } else if (dx < 0 && isSchemeModalOpen()) {
        closeModal("schemeModal");
        touch.handled = true;
        markSchemeSwipeHandled();
      }
    }

    if (touch.handled && eventObj && eventObj.cancelable) {
      eventObj.preventDefault();
    }
    return touch.handled;
  };

  const endTrack = (x, y, id, source, eventObj) => {
    tryHandle(x, y, id, source, eventObj);
    const touch = state.schemeGesture.touch;
    if (touch && touch.source === source && touch.id === id) {
      state.schemeGesture.touch = null;
    }
  };

  const findTouchById = (touchList, id) => {
    if (!touchList) return null;
    for (let i = 0; i < touchList.length; i += 1) {
      if (touchList[i].identifier === id) return touchList[i];
    }
    return null;
  };

  document.addEventListener(
    "click",
    (event) => {
      if (!isSchemeSwipeClickSuppressed()) return;
      const targetElement = event.target;
      if (targetElement instanceof Element && targetElement.closest("#schemeModal")) return;
      event.preventDefault();
      event.stopImmediatePropagation();
    },
    true,
  );

  document.addEventListener(
    "pointerdown",
    (event) => {
      if (!event.isPrimary || event.pointerType === "mouse") return;
      beginTrack(event.clientX, event.clientY, event.pointerId, "pointer");
    },
    { passive: true },
  );

  document.addEventListener(
    "pointermove",
    (event) => {
      if (!event.isPrimary || event.pointerType === "mouse") return;
      tryHandle(event.clientX, event.clientY, event.pointerId, "pointer", event);
    },
    { passive: false },
  );

  document.addEventListener(
    "pointerup",
    (event) => {
      if (!event.isPrimary || event.pointerType === "mouse") return;
      endTrack(event.clientX, event.clientY, event.pointerId, "pointer", event);
    },
    { passive: true },
  );

  document.addEventListener("pointercancel", (event) => {
    const touch = state.schemeGesture.touch;
    if (touch && touch.source === "pointer" && touch.id === event.pointerId) {
      state.schemeGesture.touch = null;
    }
  });

  document.addEventListener(
    "touchstart",
    (event) => {
      const firstTouch = event.changedTouches && event.changedTouches[0];
      if (!firstTouch) return;
      beginTrack(firstTouch.clientX, firstTouch.clientY, firstTouch.identifier, "touch");
    },
    { passive: true },
  );

  document.addEventListener(
    "touchmove",
    (event) => {
      const touch = state.schemeGesture.touch;
      if (!touch || touch.source !== "touch") return;
      const current = findTouchById(event.changedTouches, touch.id) || findTouchById(event.touches, touch.id);
      if (!current) return;
      tryHandle(current.clientX, current.clientY, touch.id, "touch", event);
    },
    { passive: false },
  );

  document.addEventListener(
    "touchend",
    (event) => {
      const touch = state.schemeGesture.touch;
      if (!touch || touch.source !== "touch") return;
      const current = findTouchById(event.changedTouches, touch.id);
      if (!current) {
        state.schemeGesture.touch = null;
        return;
      }
      endTrack(current.clientX, current.clientY, touch.id, "touch", event);
    },
    { passive: true },
  );

  document.addEventListener("touchcancel", () => {
    const touch = state.schemeGesture.touch;
    if (touch && touch.source === "touch") {
      state.schemeGesture.touch = null;
    }
  });
}

function bindCardEvents() {
  const modalBindings = [
    ["cardGrid", "gridModal"],
    ["cardLoad", "loadModal"],
    ["cardBoiler1", "boiler1Modal"],
    ["cardPump", "pumpModal"],
    ["cardBoiler2", "boiler2Modal"],
  ];

  modalBindings.forEach(([cardId, modalId]) => {
    const card = document.getElementById(cardId);
    if (!card) return;
    card.addEventListener("click", () => openModal(modalId));
  });

  const energyCards = ["cardPv", "cardBattery"];
  energyCards.forEach((cardId) => {
    const card = document.getElementById(cardId);
    if (!card) return;
    card.addEventListener("click", () => {
      openEnergyModal();
    });
  });

  const climateCard = document.getElementById("climateWideCard");
  if (climateCard) {
    climateCard.addEventListener("click", () => {
      openClimateModal();
    });
  }

  const gateCard = document.getElementById("cardGate");
  if (gateCard) {
    gateCard.addEventListener("click", () => {
      openGateHistoryModal();
    });
  }

  const headerTitle = document.getElementById("appHeaderTitle");
  if (headerTitle) {
    headerTitle.addEventListener("click", () => {
      openTimelineModal();
    });
  }

  const schemeOpenBtn = document.getElementById("schemeOpenBtn");
  if (schemeOpenBtn) {
    schemeOpenBtn.addEventListener("click", () => {
      openModal("schemeModal");
    });
  }

  const eventsOpenBtn = document.getElementById("eventsOpenBtn");
  if (eventsOpenBtn) {
    eventsOpenBtn.addEventListener("click", () => {
      openEventModal();
    });
  }

  const eventsReloadBtn = document.getElementById("eventsReloadBtn");
  if (eventsReloadBtn) {
    eventsReloadBtn.addEventListener("click", () => {
      if (state.events.viewMode === "gateDaily") {
        loadGarageGateHistory();
        return;
      }
      loadEventJournal({ viewMode: state.events.viewMode });
    });
  }

  const eventsClearBtn = document.getElementById("eventsClearBtn");
  if (eventsClearBtn) {
    eventsClearBtn.addEventListener("click", () => {
      clearEventJournal();
    });
  }

  const timelineReloadBtn = document.getElementById("timelineReloadBtn");
  if (timelineReloadBtn) {
    timelineReloadBtn.addEventListener("click", () => {
      loadLoadTimelineHistory({ force: true });
    });
  }

  document.querySelectorAll("[data-scheme-open-modal]").forEach((btn) => {
    btn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      const modalId = btn.getAttribute("data-scheme-open-modal");
      if (!modalId) return;
      if (hasOtherOpenModal("schemeModal") && !isModalOpen(modalId)) return;
      state.schemeControlLandscape = true;
      state.schemeControlPendingModalId = modalId;
      closeModal("schemeModal");
      openModal(modalId);
    });
  });

  document.querySelectorAll("[data-close-modal]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const modalId = btn.getAttribute("data-close-modal");
      if (modalId) closeModal(modalId);
    });
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeAllModals();
    }
  });
}

async function sendGridMode(mode, options = {}) {
  const { silent = false } = options;
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setInverterGridMode(mode, requestId);
    });
    setActiveModeGroup("btnGrid", mode);
    if (!silent) showToast(`grid mode: ${mode}`);
  } catch (error) {
    showToast(`grid mode failed: ${error.message}`);
  }
}

async function sendInverterLoadLock(locked, options = {}) {
  const { silent = false } = options;
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setInverterLoadLock(!!locked, requestId);
    });
    state.locks.inverterLoadOn = !!locked;
    setModeButtonLocked("btnLoadON", !!locked);
    if (locked) {
      setActiveModeGroup("btnLoad", "ON");
    }
    if (!silent) {
      showToast(locked ? "load ON locked" : "load ON unlocked");
    }
    return true;
  } catch (error) {
    showToast(`load lock failed: ${error.message}`);
    return false;
  }
}

async function sendLoadMode(mode, options = {}) {
  const { silent = false } = options;
  if (mode !== "ON" && state.locks.inverterLoadOn) {
    await sendInverterLoadLock(false, { silent: true });
  }
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setInverterLoadMode(mode, requestId);
    });
    setActiveModeGroup("btnLoad", mode);
    if (!silent) showToast(`load mode: ${mode}`);
  } catch (error) {
    showToast(`load mode failed: ${error.message}`);
  }
}

async function sendBoiler1Lock(mode, options = {}) {
  const { silent = false } = options;
  const lockMode = normalizeLockMode(mode);
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setBoiler1Lock(lockMode, requestId);
    });
    state.locks.boiler1 = lockMode;
    setModeButtonLocked("btnBoiler1ON", lockMode === "ON");
    setModeButtonLocked("btnBoiler1OFF", lockMode === "OFF");
    if (lockMode === "ON" || lockMode === "OFF") {
      setActiveModeGroup("btnBoiler1", lockMode);
    }
    if (!silent) showToast(`boiler1 lock: ${lockMode}`);
    return true;
  } catch (error) {
    showToast(`boiler1 lock failed: ${error.message}`);
    return false;
  }
}

async function sendBoiler1Mode(mode, options = {}) {
  const { silent = false } = options;
  if (state.locks.boiler1 === "ON" && mode !== "ON") {
    await sendBoiler1Lock("NONE", { silent: true });
  }
  if (state.locks.boiler1 === "OFF" && mode !== "OFF") {
    await sendBoiler1Lock("NONE", { silent: true });
  }
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setBoiler1Mode(mode, requestId);
    });
    setActiveModeGroup("btnBoiler1", mode);
    if (!silent) showToast(`boiler1 mode: ${mode}`);
  } catch (error) {
    showToast(`boiler1 mode failed: ${error.message}`);
  }
}

async function sendPumpLock(mode, options = {}) {
  const { silent = false } = options;
  const lockMode = normalizeLockMode(mode);
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setPumpLock(lockMode, requestId);
    });
    state.locks.pump = lockMode;
    setModeButtonLocked("btnPumpON", lockMode === "ON");
    setModeButtonLocked("btnPumpOFF", lockMode === "OFF");
    if (lockMode === "ON" || lockMode === "OFF") {
      setActiveModeGroup("btnPump", lockMode);
    }
    if (!silent) showToast(`pump lock: ${lockMode}`);
    return true;
  } catch (error) {
    showToast(`pump lock failed: ${error.message}`);
    return false;
  }
}

async function sendPumpMode(mode, options = {}) {
  const { silent = false } = options;
  if (state.locks.pump === "ON" && mode !== "ON") {
    await sendPumpLock("NONE", { silent: true });
  }
  if (state.locks.pump === "OFF" && mode !== "OFF") {
    await sendPumpLock("NONE", { silent: true });
  }
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setPumpMode(mode, requestId);
    });
    setActiveModeGroup("btnPump", mode);
    if (!silent) showToast(`pump mode: ${mode}`);
  } catch (error) {
    showToast(`pump mode failed: ${error.message}`);
  }
}

function normalizeAutoWindowHm(value, fallback = "00:00") {
  const text = String(value || "").trim();
  return /^\d{2}:\d{2}$/.test(text) ? text : fallback;
}

function setAutoWindowEditorOpen(prefix, open) {
  const editor = document.getElementById(`${prefix}AutoWindowEditor`);
  const btn = document.getElementById(`${prefix}AutoWindowClockBtn`);
  if (editor) editor.classList.toggle("is-open", !!open);
  if (btn && editor) btn.classList.toggle("active", !!open || btn.classList.contains("has-config"));
}

function isAutoWindowEditorOpen(prefix) {
  const editor = document.getElementById(`${prefix}AutoWindowEditor`);
  return !!editor && editor.classList.contains("is-open");
}

function renderAutoWindowBlock(prefix, stateData, options = {}) {
  const { disabled = false } = options || {};
  const { enabled = false, start = "00:00", end = "00:00", active = true } = stateData || {};
  const normalizedStart = normalizeAutoWindowHm(start, "00:00");
  const normalizedEnd = normalizeAutoWindowHm(end, "00:00");
  const statusEl = document.getElementById(`${prefix}AutoWindowStatus`);
  const btn = document.getElementById(`${prefix}AutoWindowClockBtn`);
  const enabledEl = document.getElementById(`${prefix}AutoWindowEnabled`);
  const startEl = document.getElementById(`${prefix}AutoWindowStart`);
  const endEl = document.getElementById(`${prefix}AutoWindowEnd`);
  const saveEl = document.getElementById(`${prefix}AutoWindowSave`);
  const cancelEl = document.getElementById(`${prefix}AutoWindowCancel`);

  if (statusEl) {
    statusEl.classList.remove("enabled", "active-now", "inactive-now");
    if (disabled) {
      statusEl.textContent = "module disabled";
    } else if (!enabled) {
      statusEl.textContent = "AUTO: always active";
    } else {
      statusEl.classList.add("enabled");
      statusEl.classList.add(active ? "active-now" : "inactive-now");
      statusEl.textContent = `AUTO: ${normalizedStart}-${normalizedEnd} (${active ? "active now" : "inactive now"})`;
    }
  }

  if (btn) {
    btn.disabled = !!disabled;
    btn.classList.toggle("has-config", !disabled && !!enabled);
    btn.classList.toggle("active", isAutoWindowEditorOpen(prefix) || (!disabled && !!enabled));
  }

  if (enabledEl && !isAutoWindowEditorOpen(prefix)) enabledEl.checked = !!enabled;
  if (startEl && !isAutoWindowEditorOpen(prefix)) startEl.value = normalizedStart;
  if (endEl && !isAutoWindowEditorOpen(prefix)) endEl.value = normalizedEnd;

  if (enabledEl) enabledEl.disabled = !!disabled;
  if (startEl) startEl.disabled = !!disabled;
  if (endEl) endEl.disabled = !!disabled;
  if (saveEl) saveEl.disabled = !!disabled;
  if (cancelEl) cancelEl.disabled = !!disabled;
}

async function sendBoiler1AutoWindow() {
  const enabledEl = document.getElementById("boiler1AutoWindowEnabled");
  const startEl = document.getElementById("boiler1AutoWindowStart");
  const endEl = document.getElementById("boiler1AutoWindowEnd");
  if (!enabledEl || !startEl || !endEl) return;
  const enabled = !!enabledEl.checked;
  const start = normalizeAutoWindowHm(startEl.value, "00:00");
  const end = normalizeAutoWindowHm(endEl.value, "00:00");
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setBoiler1AutoWindow(enabled, start, end, requestId);
    });
    setAutoWindowEditorOpen("boiler1", false);
    showToast("boiler1 AUTO timer updated");
  } catch (error) {
    showToast(`boiler1 AUTO timer failed: ${error.message}`);
  }
}

async function sendPumpAutoWindow() {
  const enabledEl = document.getElementById("pumpAutoWindowEnabled");
  const startEl = document.getElementById("pumpAutoWindowStart");
  const endEl = document.getElementById("pumpAutoWindowEnd");
  if (!enabledEl || !startEl || !endEl) return;
  const enabled = !!enabledEl.checked;
  const start = normalizeAutoWindowHm(startEl.value, "00:00");
  const end = normalizeAutoWindowHm(endEl.value, "00:00");
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setPumpAutoWindow(enabled, start, end, requestId);
    });
    setAutoWindowEditorOpen("pump", false);
    showToast("pump AUTO timer updated");
  } catch (error) {
    showToast(`pump AUTO timer failed: ${error.message}`);
  }
}

async function sendBoiler2AutoWindow() {
  const enabledEl = document.getElementById("boiler2AutoWindowEnabled");
  const startEl = document.getElementById("boiler2AutoWindowStart");
  const endEl = document.getElementById("boiler2AutoWindowEnd");
  if (!enabledEl || !startEl || !endEl) return;
  const enabled = !!enabledEl.checked;
  const start = normalizeAutoWindowHm(startEl.value, "00:00");
  const end = normalizeAutoWindowHm(endEl.value, "00:00");
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setBoiler2AutoWindow(enabled, start, end, requestId);
    });
    setAutoWindowEditorOpen("boiler2", false);
    showToast("boiler2 AUTO timer updated");
  } catch (error) {
    showToast(`boiler2 AUTO timer failed: ${error.message}`);
  }
}

async function sendBoiler2Lock(mode, options = {}) {
  const { silent = false } = options;
  const lockMode = normalizeLockMode(mode);
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setBoiler2Lock(lockMode, requestId);
    });
    state.locks.boiler2 = lockMode;
    setModeButtonLocked("btnBoiler2ON", lockMode === "ON");
    setModeButtonLocked("btnBoiler2OFF", lockMode === "OFF");
    if (lockMode === "ON" || lockMode === "OFF") {
      setActiveModeGroup("btnBoiler2", lockMode);
    }
    if (!silent) showToast(`boiler2 lock: ${lockMode}`);
    return true;
  } catch (error) {
    showToast(`boiler2 lock failed: ${error.message}`);
    return false;
  }
}

async function sendBoiler2Mode(mode, options = {}) {
  const { silent = false } = options;
  if (state.locks.boiler2 === "ON" && mode !== "ON") {
    await sendBoiler2Lock("NONE", { silent: true });
  }
  if (state.locks.boiler2 === "OFF" && mode !== "OFF") {
    await sendBoiler2Lock("NONE", { silent: true });
  }
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.setBoiler2Mode(mode, requestId);
    });
    setActiveModeGroup("btnBoiler2", mode);
    if (!silent) showToast(`boiler2 mode: ${mode}`);
  } catch (error) {
    showToast(`boiler2 mode failed: ${error.message}`);
  }
}

async function triggerGate() {
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.triggerGate(requestId);
    });
    showToast("gate command sent");
  } catch (error) {
    showToast(`gate command failed: ${error.message}`);
  }
}

async function toggleGarageLight() {
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.toggleGarageLight(requestId);
    });
    showToast("garage light toggled");
  } catch (error) {
    showToast(`garage light failed: ${error.message}`);
  }
}

function bindPointerClick(buttonId, handler) {
  const btn = document.getElementById(buttonId);
  if (!btn) return;
  let armed = false;

  btn.addEventListener("pointerdown", (event) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    armed = true;
  });
  btn.addEventListener("pointerup", () => {
    if (!armed) return;
    armed = false;
    handler();
  });
  btn.addEventListener("pointerleave", () => {
    armed = false;
  });
  btn.addEventListener("pointercancel", () => {
    armed = false;
  });
  btn.addEventListener(
    "click",
    (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
    },
    true,
  );
}

function bindLongPress(buttonId, shortHandler, longHandler, holdMs = 800) {
  const btn = document.getElementById(buttonId);
  if (!btn) return;

  let pressActive = false;
  let longPressFired = false;
  let timer = null;

  const clearPress = () => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    longPressFired = false;
    pressActive = false;
  };

  btn.addEventListener("pointerdown", (event) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    event.preventDefault();
    pressActive = true;
    longPressFired = false;
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      longPressFired = true;
      longHandler();
    }, holdMs);
  });

  const endPress = () => {
    if (!pressActive) return;
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    if (!longPressFired) {
      shortHandler();
    }
    longPressFired = false;
    pressActive = false;
  };

  btn.addEventListener("pointerup", endPress);
  btn.addEventListener("pointerleave", endPress);
  btn.addEventListener("pointercancel", endPress);
  btn.addEventListener("contextmenu", (event) => event.preventDefault());
  btn.addEventListener(
    "click",
    (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
    },
    true,
  );
}

function bindModeButtons() {
  bindPointerClick("btnGridAUTO", () => sendGridMode("AUTO"));
  bindPointerClick("btnGridOFF", () => sendGridMode("OFF"));
  bindPointerClick("btnGridON", () => sendGridMode("ON"));

  bindPointerClick("btnLoadAUTO", () => sendLoadMode("AUTO"));
  bindPointerClick("btnLoadOFF", () => sendLoadMode("OFF"));
  bindLongPress(
    "btnLoadON",
    () => sendLoadMode("ON"),
    async () => {
      await sendInverterLoadLock(true, { silent: true });
      await sendLoadMode("ON", { silent: true });
      showToast("load ON locked");
    },
  );

  bindPointerClick("btnBoiler1AUTO", () => sendBoiler1Mode("AUTO"));
  bindLongPress(
    "btnBoiler1OFF",
    () => sendBoiler1Mode("OFF"),
    async () => {
      await sendBoiler1Lock("OFF", { silent: true });
      await sendBoiler1Mode("OFF", { silent: true });
      showToast("boiler1 OFF locked");
    },
  );
  bindLongPress(
    "btnBoiler1ON",
    () => sendBoiler1Mode("ON"),
    async () => {
      await sendBoiler1Lock("ON", { silent: true });
      await sendBoiler1Mode("ON", { silent: true });
      showToast("boiler1 ON locked");
    },
  );

  bindPointerClick("btnPumpAUTO", () => sendPumpMode("AUTO"));
  bindLongPress(
    "btnPumpOFF",
    () => sendPumpMode("OFF"),
    async () => {
      await sendPumpLock("OFF", { silent: true });
      await sendPumpMode("OFF", { silent: true });
      showToast("pump OFF locked");
    },
  );
  bindLongPress(
    "btnPumpON",
    () => sendPumpMode("ON"),
    async () => {
      await sendPumpLock("ON", { silent: true });
      await sendPumpMode("ON", { silent: true });
      showToast("pump ON locked");
    },
  );

  const boiler1AutoWindowClockBtn = document.getElementById("boiler1AutoWindowClockBtn");
  if (boiler1AutoWindowClockBtn) {
    boiler1AutoWindowClockBtn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (boiler1AutoWindowClockBtn.disabled) return;
      setAutoWindowEditorOpen("boiler1", !isAutoWindowEditorOpen("boiler1"));
    });
  }
  const boiler1AutoWindowSave = document.getElementById("boiler1AutoWindowSave");
  if (boiler1AutoWindowSave) {
    boiler1AutoWindowSave.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      sendBoiler1AutoWindow();
    });
  }
  const boiler1AutoWindowCancel = document.getElementById("boiler1AutoWindowCancel");
  if (boiler1AutoWindowCancel) {
    boiler1AutoWindowCancel.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      setAutoWindowEditorOpen("boiler1", false);
    });
  }

  const pumpAutoWindowClockBtn = document.getElementById("pumpAutoWindowClockBtn");
  if (pumpAutoWindowClockBtn) {
    pumpAutoWindowClockBtn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (pumpAutoWindowClockBtn.disabled) return;
      setAutoWindowEditorOpen("pump", !isAutoWindowEditorOpen("pump"));
    });
  }
  const pumpAutoWindowSave = document.getElementById("pumpAutoWindowSave");
  if (pumpAutoWindowSave) {
    pumpAutoWindowSave.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      sendPumpAutoWindow();
    });
  }
  const pumpAutoWindowCancel = document.getElementById("pumpAutoWindowCancel");
  if (pumpAutoWindowCancel) {
    pumpAutoWindowCancel.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      setAutoWindowEditorOpen("pump", false);
    });
  }

  bindPointerClick("btnBoiler2AUTO", () => sendBoiler2Mode("AUTO"));
  bindLongPress(
    "btnBoiler2OFF",
    () => sendBoiler2Mode("OFF"),
    async () => {
      await sendBoiler2Lock("OFF", { silent: true });
      await sendBoiler2Mode("OFF", { silent: true });
      showToast("boiler2 OFF locked");
    },
  );
  bindLongPress(
    "btnBoiler2ON",
    () => sendBoiler2Mode("ON"),
    async () => {
      await sendBoiler2Lock("ON", { silent: true });
      await sendBoiler2Mode("ON", { silent: true });
      showToast("boiler2 ON locked");
    },
  );

  const boiler2AutoWindowClockBtn = document.getElementById("boiler2AutoWindowClockBtn");
  if (boiler2AutoWindowClockBtn) {
    boiler2AutoWindowClockBtn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (boiler2AutoWindowClockBtn.disabled) return;
      setAutoWindowEditorOpen("boiler2", !isAutoWindowEditorOpen("boiler2"));
    });
  }
  const boiler2AutoWindowSave = document.getElementById("boiler2AutoWindowSave");
  if (boiler2AutoWindowSave) {
    boiler2AutoWindowSave.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      sendBoiler2AutoWindow();
    });
  }
  const boiler2AutoWindowCancel = document.getElementById("boiler2AutoWindowCancel");
  if (boiler2AutoWindowCancel) {
    boiler2AutoWindowCancel.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      setAutoWindowEditorOpen("boiler2", false);
    });
  }

  const gateActionBtn = document.getElementById("gateActionBtn");
  if (gateActionBtn) {
    gateActionBtn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      triggerGate();
    });
  }
  const garageLightActionBtn = document.getElementById("garageLightActionBtn");
  if (garageLightActionBtn) {
    garageLightActionBtn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (garageLightActionBtn.disabled) return;
      toggleGarageLight();
    });
  }
}

function openInverterLogs() {
  const baseUrl = normalizeBaseUrl(state.config.inverterBaseUrl || DEFAULT_CONFIG.inverterBaseUrl);
  if (!baseUrl) {
    showToast("inverter URL is empty");
    return;
  }

  const logsUrl = `${baseUrl}/api/sd/files`;

  if (hasBridge() && window.AndroidHub && typeof window.AndroidHub.openExternalUrl === "function") {
    const opened = !!window.AndroidHub.openExternalUrl(logsUrl);
    if (!opened) {
      showToast("failed to open logs");
    }
    return;
  }

  const popup = window.open(logsUrl, "_blank");
  if (!popup) {
    window.location.href = logsUrl;
  }
}

function bindSettings() {
  const logsBtn = document.getElementById("logsOpenBtn");
  if (logsBtn) {
    logsBtn.addEventListener("click", () => {
      openInverterLogs();
    });
  }

  const openBtn = document.getElementById("settingsOpenBtn");
  if (openBtn) {
    openBtn.addEventListener("click", () => {
      syncConfigToForm();
      openModal("settingsModal");
    });
  }

  const saveBtn = document.getElementById("settingsSaveBtn");
  if (!saveBtn) return;

  saveBtn.addEventListener("click", () => {
    if (!hasBridge()) {
      showToast("bridge unavailable");
      return;
    }

    const nextConfig = {
      inverterBaseUrl: (document.getElementById("cfgInverterUrl")?.value || "").trim(),
      inverterPassword: document.getElementById("cfgInverterPass")?.value || "",
      loadControllerBaseUrl: (document.getElementById("cfgLoadUrl")?.value || "").trim(),
      loadControllerPassword: document.getElementById("cfgLoadPass")?.value || "",
      garageBaseUrl: (document.getElementById("cfgGarageUrl")?.value || "").trim(),
      garagePassword: document.getElementById("cfgGaragePass")?.value || "",
      pollIntervalSec: clampPoll(document.getElementById("cfgPollSec")?.value),
      inverterEnabled: readChecked("cfgInverterEnabled", true),
      loadControllerEnabled: readChecked("cfgLoadEnabled", true),
      garageEnabled: readChecked("cfgGarageEnabled", true),
      realtimeMonitorEnabled: readChecked("cfgRealtimeEnabled", false),
      realtimePollIntervalSec: clampRealtimePoll(document.getElementById("cfgRealtimeSec")?.value),
      notifyPvGeneration: readChecked("cfgNotifyPv", true),
      notifyGridRelay: readChecked("cfgNotifyGridRelay", true),
      notifyGridPresence: readChecked("cfgNotifyGridPresence", true),
      notifyGridMode: readChecked("cfgNotifyGridMode", true),
      notifyLoadMode: readChecked("cfgNotifyLoadMode", true),
      notifyBoiler1Mode: readChecked("cfgNotifyBoiler1", true),
      notifyPumpMode: readChecked("cfgNotifyPump", true),
      notifyBoiler2Mode: readChecked("cfgNotifyBoiler2", true),
      notifyGateState: readChecked("cfgNotifyGate", true),
    };

    const ok = !!window.AndroidHub.saveConfig(JSON.stringify(nextConfig));
    if (!ok) {
      showToast("settings save failed");
      return;
    }

    state.config = { ...DEFAULT_CONFIG, ...nextConfig };
    setText("pollText", `${state.config.pollIntervalSec}s`);
    applyModuleCardStates();
    applyLiveCardStates(state.status);
    restartPolling();
    restartSignalAgeTicker();
    closeModal("settingsModal");
    showToast("settings saved");
    requestStatus();
  });
}
function syncConfigToForm() {
  const cfg = state.config;
  const setInput = (id, value) => {
    const el = document.getElementById(id);
    if (el) el.value = value;
  };
  const setCheck = (id, value) => {
    const el = document.getElementById(id);
    if (el) el.checked = !!value;
  };
  setInput("cfgInverterUrl", cfg.inverterBaseUrl || "");
  setInput("cfgInverterPass", cfg.inverterPassword || "");
  setInput("cfgLoadUrl", cfg.loadControllerBaseUrl || "");
  setInput("cfgLoadPass", cfg.loadControllerPassword || "");
  setInput("cfgGarageUrl", cfg.garageBaseUrl || "");
  setInput("cfgGaragePass", cfg.garagePassword || "");
  setInput("cfgPollSec", String(clampPoll(cfg.pollIntervalSec)));
  setInput("cfgRealtimeSec", String(clampRealtimePoll(cfg.realtimePollIntervalSec)));
  setCheck("cfgInverterEnabled", cfg.inverterEnabled);
  setCheck("cfgLoadEnabled", cfg.loadControllerEnabled);
  setCheck("cfgGarageEnabled", cfg.garageEnabled);
  setCheck("cfgRealtimeEnabled", cfg.realtimeMonitorEnabled);
  setCheck("cfgNotifyPv", cfg.notifyPvGeneration);
  setCheck("cfgNotifyGridRelay", cfg.notifyGridRelay);
  setCheck("cfgNotifyGridPresence", cfg.notifyGridPresence);
  setCheck("cfgNotifyGridMode", cfg.notifyGridMode);
  setCheck("cfgNotifyLoadMode", cfg.notifyLoadMode);
  setCheck("cfgNotifyBoiler1", cfg.notifyBoiler1Mode);
  setCheck("cfgNotifyPump", cfg.notifyPumpMode);
  setCheck("cfgNotifyBoiler2", cfg.notifyBoiler2Mode);
  setCheck("cfgNotifyGate", cfg.notifyGateState);
}

function loadConfigFromBridge() {
  if (!hasBridge()) return;
  try {
    const raw = window.AndroidHub.getConfig();
    const parsed = normalizePayload(raw);
    if (!parsed) return;
    state.config = {
      ...DEFAULT_CONFIG,
      ...parsed,
      pollIntervalSec: clampPoll(parsed.pollIntervalSec),
      realtimePollIntervalSec: clampRealtimePoll(parsed.realtimePollIntervalSec),
    };
  } catch (error) {
    showToast("config read failed");
  }
}

async function requestStatus() {
  if (!hasBridge()) {
    if (!state.noBridgeToastShown) {
      state.noBridgeToastShown = true;
      showToast("Android bridge not available");
    }
    return;
  }

  if (state.statusRequestInFlight) {
    return;
  }

  state.statusRequestInFlight = true;
  try {
    await bridgeRequest("status", (requestId) => {
      window.AndroidHub.fetchStatus(requestId);
    });
  } catch (error) {
    showToast(`status failed: ${error.message}`);
  } finally {
    state.statusRequestInFlight = false;
  }
}

async function requestMulticastRefreshNow() {
  if (!hasBridge()) {
    showToast("bridge unavailable");
    return;
  }

  const btn = document.getElementById("multicastRefreshBtn");
  if (btn) btn.disabled = true;
  try {
    await bridgeRequest("status", (requestId) => {
      window.AndroidHub.requestMulticastRefresh(requestId);
    });
    showToast("refresh requested");
  } catch (error) {
    showToast(`refresh failed: ${error.message}`);
  } finally {
    if (btn) btn.disabled = false;
  }
}

function restartPolling() {
  if (state.pollHandle) {
    clearInterval(state.pollHandle);
    state.pollHandle = null;
  }
  const seconds = clampPoll(state.config.pollIntervalSec);
  state.config.pollIntervalSec = seconds;
  setText("pollText", `${seconds}s`);
  state.pollHandle = setInterval(requestStatus, seconds * 1000);
}

function trackConnectivityHealth(status) {
  const connected = !!(
    (state.config.inverterEnabled && status?.inverter) ||
    (state.config.loadControllerEnabled && status?.loadController) ||
    (state.config.garageEnabled && status?.garage)
  );
  if (connected) {
    state.emptyStatusCount = 0;
    return;
  }

  state.emptyStatusCount += 1;
  if (state.emptyStatusCount === 3) {
    showToast("No modules reachable. Check controller URLs and Wi-Fi.");
  }
}

function updateModuleSignalTimes(status) {
  const now = Date.now();

  const touchModule = (key, moduleData) => {
    if (!moduleData || typeof moduleData !== "object") return;
    const moduleTs = Number(moduleData.updatedAtMs);
    const resolvedTs = Number.isFinite(moduleTs) && moduleTs > 0 ? moduleTs : now;

    state.moduleSignalAtMs[key] = resolvedTs;
  };

  touchModule("inverter", status?.inverter);
  touchModule("loadController", status?.loadController);
  touchModule("garage", status?.garage);
}

function updateModuleMissCounts(status) {
  const syncMissCount = (key, enabled, moduleData) => {
    if (!enabled) {
      state.moduleMissCounts[key] = 0;
      return;
    }
    if (moduleData && typeof moduleData === "object") {
      state.moduleMissCounts[key] = 0;
      return;
    }
    const current = Number(state.moduleMissCounts[key]) || 0;
    state.moduleMissCounts[key] = Math.min(999, current + 1);
  };

  syncMissCount("inverter", state.config.inverterEnabled, status?.inverter);
  syncMissCount("loadController", state.config.loadControllerEnabled, status?.loadController);
  syncMissCount("garage", state.config.garageEnabled, status?.garage);
}

function mergeStatusForUi(incomingStatus, options = {}) {
  const { isPartial = false } = options;
  const prev = state.status && typeof state.status === "object" ? state.status : null;
  if (!incomingStatus || typeof incomingStatus !== "object") return prev;

  const merged = { ...(prev || {}), ...incomingStatus };

  const mergeModule = (key, enabled, nextModule) => {
    if (!enabled) return null;
    if (nextModule && typeof nextModule === "object") return nextModule;

    const prevModule = prev?.[key];
    if (!prevModule || typeof prevModule !== "object") return null;

    if (isPartial) {
      return prevModule;
    }

    const misses = Number(state.moduleMissCounts[key]) || 0;
    return misses < MODULE_STALE_AFTER_MISSES ? prevModule : null;
  };

  merged.inverter = mergeModule("inverter", state.config.inverterEnabled, incomingStatus.inverter);
  merged.loadController = mergeModule("loadController", state.config.loadControllerEnabled, incomingStatus.loadController);
  merged.garage = mergeModule("garage", state.config.garageEnabled, incomingStatus.garage);
  return merged;
}

function moduleSignalTimeoutMs() {
  return clampPoll(state.config.pollIntervalSec) * 2 * 1000;
}

function moduleHasFreshSignal(key, enabled, moduleData) {
  if (!enabled || !moduleData) return false;
  const misses = Number(state.moduleMissCounts[key]) || 0;
  if (misses >= MODULE_STALE_AFTER_MISSES) return false;
  const lastSignalTs = Number(state.moduleSignalAtMs[key]);
  if (!Number.isFinite(lastSignalTs) || lastSignalTs <= 0) return true;
  return Date.now() - lastSignalTs <= moduleSignalTimeoutMs() * MODULE_STALE_AFTER_MISSES;
}

function setModuleCardsDisabled(cardIds, disabled) {
  cardIds.forEach((id) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.classList.toggle("module-disabled", !!disabled);
  });
}

function applyModuleCardStates() {
  setModuleCardsDisabled(
    ["cardPv", "cardGrid", "cardLoad", "cardBattery", "climateWideCard"],
    !state.config.inverterEnabled,
  );
  setModuleCardsDisabled(["cardBoiler1", "cardPump"], !state.config.loadControllerEnabled);
  setModuleCardsDisabled(["cardBoiler2", "cardGate"], !state.config.garageEnabled);
}

function setCardDataState(cardId, hasData) {
  const card = document.getElementById(cardId);
  if (!card) return;
  card.classList.toggle("card-stale", !hasData);
  card.classList.toggle("card-live", !!hasData);
}

function applyCardNeonByPower(cardId, powerValue, enabled = true) {
  const card = document.getElementById(cardId);
  if (!card) return;
  const power = Number(powerValue);
  const neonEnabled = !!enabled && Number.isFinite(power) && Math.abs(power) >= CARD_NEON_POWER_THRESHOLD;
  card.classList.toggle("card-neon-on", neonEnabled);
  card.classList.toggle("card-neon-off", !neonEnabled);
}

function flashCard(cardId) {
  const card = document.getElementById(cardId);
  if (!card) return;
  if (
    card.classList.contains("module-disabled") ||
    card.classList.contains("card-stale") ||
    card.classList.contains("card-neon-off")
  ) {
    return;
  }
  card.classList.remove("card-flash");
  // Restart animation on each refresh cycle.
  void card.offsetWidth;
  card.classList.add("card-flash");
}

function applyLiveCardStates(status, options = {}) {
  const { flash = true } = options;
  const hasInverterData = moduleHasFreshSignal("inverter", state.config.inverterEnabled, status?.inverter);
  const hasLoadData = moduleHasFreshSignal("loadController", state.config.loadControllerEnabled, status?.loadController);
  const hasGarageData = moduleHasFreshSignal("garage", state.config.garageEnabled, status?.garage);
  const hasClimateData = hasInverterData || hasLoadData || hasGarageData;

  setCardDataState("cardPv", hasInverterData);
  setCardDataState("cardGrid", hasInverterData);
  setCardDataState("cardBattery", hasInverterData);
  setCardDataState("cardLoad", hasInverterData);
  setCardDataState("cardBoiler1", hasLoadData);
  setCardDataState("cardPump", hasLoadData);
  setCardDataState("cardBoiler2", hasGarageData);
  setCardDataState("cardGate", hasGarageData);
  setCardDataState("climateWideCard", hasClimateData);

  if (flash && hasInverterData) {
    flashCard("cardPv");
    flashCard("cardGrid");
    flashCard("cardBattery");
    flashCard("cardLoad");
  }
  if (flash && hasLoadData) {
    flashCard("cardBoiler1");
    flashCard("cardPump");
  }
  if (flash && hasGarageData) {
    flashCard("cardBoiler2");
    flashCard("cardGate");
  }
  if (flash && hasClimateData) {
    flashCard("climateWideCard");
  }
}

function restartSignalAgeTicker() {
  if (state.signalAgeHandle) {
    clearInterval(state.signalAgeHandle);
    state.signalAgeHandle = null;
  }
  state.signalAgeHandle = setInterval(() => {
    applyLiveCardStates(state.status, { flash: false });
  }, 1000);
}

function ensureCanvasSize(canvas, fallbackHeight = 320) {
  const rect = canvas.getBoundingClientRect();
  const width = Math.max(320, Math.floor(rect.width || canvas.clientWidth || 720));
  const height = Math.max(220, Math.floor(rect.height || canvas.clientHeight || fallbackHeight));
  const dpr = window.devicePixelRatio || 1;

  canvas.width = Math.floor(width * dpr);
  canvas.height = Math.floor(height * dpr);
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  return { ctx, width, height };
}

function drawEmptyCanvas(canvas, message) {
  if (!canvas) return;
  const { ctx, width, height } = ensureCanvasSize(canvas);
  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = "rgba(7,12,25,0.92)";
  ctx.fillRect(0, 0, width, height);
  ctx.fillStyle = "rgba(200,215,230,0.9)";
  ctx.font = "600 14px sans-serif";
  ctx.textAlign = "center";
  ctx.fillText(message, width / 2, height / 2);
}

function colorWithAlpha(color, alpha) {
  const hex = String(color || "").trim();
  if (hex.startsWith("#")) {
    const raw = hex.slice(1);
    const full =
      raw.length === 3
        ? raw
            .split("")
            .map((ch) => ch + ch)
            .join("")
        : raw;
    if (/^[0-9a-fA-F]{6}$/.test(full)) {
      const r = Number.parseInt(full.slice(0, 2), 16);
      const g = Number.parseInt(full.slice(2, 4), 16);
      const b = Number.parseInt(full.slice(4, 6), 16);
      return `rgba(${r},${g},${b},${Math.max(0, Math.min(1, alpha))})`;
    }
  }
  return color;
}

function collectSeriesFiniteValues(series) {
  const out = [];
  series.forEach((entry) => {
    (entry.data || []).forEach((value) => {
      if (Number.isFinite(value)) out.push(value);
    });
  });
  return out;
}

function drawAxesAndGrid(ctx, labels, width, height, min, max, options = {}) {
  const pad = { left: 46, right: 12, top: 16, bottom: 36 };
  const chartW = Math.max(10, width - pad.left - pad.right);
  const chartH = Math.max(10, height - pad.top - pad.bottom);
  const yTicks = 5;
  const maxXTicks = options.maxXTicks || 8;
  const step = Math.max(1, Math.ceil(labels.length / maxXTicks));

  const xPos = (index) => {
    if (labels.length <= 1) return pad.left + chartW / 2;
    return pad.left + (index * chartW) / (labels.length - 1);
  };
  const yPos = (value) => {
    const ratio = (value - min) / (max - min);
    return pad.top + chartH - ratio * chartH;
  };

  ctx.strokeStyle = "rgba(160,190,220,0.25)";
  ctx.lineWidth = 1;
  ctx.fillStyle = "rgba(180,205,230,0.85)";
  ctx.font = "11px sans-serif";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";

  for (let t = 0; t <= yTicks; t += 1) {
    const ratio = t / yTicks;
    const y = pad.top + chartH - ratio * chartH;
    const value = min + ratio * (max - min);

    ctx.beginPath();
    ctx.moveTo(pad.left, y);
    ctx.lineTo(pad.left + chartW, y);
    ctx.stroke();
    ctx.fillText(num(value, 1), pad.left - 6, y);
  }

  ctx.strokeStyle = "rgba(200,220,240,0.45)";
  ctx.lineWidth = 1.2;
  ctx.beginPath();
  ctx.moveTo(pad.left, pad.top);
  ctx.lineTo(pad.left, pad.top + chartH);
  ctx.lineTo(pad.left + chartW, pad.top + chartH);
  ctx.stroke();

  ctx.fillStyle = "rgba(180,205,230,0.85)";
  ctx.textAlign = "center";
  ctx.textBaseline = "top";
  for (let i = 0; i < labels.length; i += step) {
    ctx.fillText(String(labels[i] ?? ""), xPos(i), pad.top + chartH + 6);
  }
  if ((labels.length - 1) % step !== 0) {
    const lastIndex = labels.length - 1;
    ctx.fillText(String(labels[lastIndex] ?? ""), xPos(lastIndex), pad.top + chartH + 6);
  }

  if (options.yTitle) {
    ctx.save();
    ctx.translate(14, pad.top + chartH / 2);
    ctx.rotate(-Math.PI / 2);
    ctx.fillStyle = "rgba(180,205,230,0.75)";
    ctx.font = "11px sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(options.yTitle, 0, 0);
    ctx.restore();
  }

  return {
    pad,
    chartW,
    chartH,
    xPos,
    yPos,
    baselineY: yPos(0),
  };
}

function drawLineChart(canvas, labels, series, options = {}) {
  if (!canvas) return;
  const { ctx, width, height } = ensureCanvasSize(canvas, options.height || 330);
  ctx.clearRect(0, 0, width, height);

  ctx.fillStyle = "rgba(7,12,25,0.92)";
  ctx.fillRect(0, 0, width, height);

  const allValues = collectSeriesFiniteValues(series);

  if (allValues.length === 0 || labels.length === 0) {
    drawEmptyCanvas(canvas, "No chart data");
    return;
  }

  let min = Math.min(...allValues);
  let max = Math.max(...allValues);
  if (options.forceZeroMin) min = Math.min(0, min);
  if (Math.abs(max - min) < 1e-9) {
    const pad = Math.max(1, Math.abs(max) * 0.1);
    min -= pad;
    max += pad;
  }

  const axes = drawAxesAndGrid(ctx, labels, width, height, min, max, options);
  const xPos = axes.xPos;
  const yPos = axes.yPos;
  const baselineY = axes.baselineY;

  series.forEach((entry) => {
    const points = [];
    for (let i = 0; i < labels.length; i += 1) {
      const value = entry.data[i];
      if (!Number.isFinite(value)) continue;
      points.push({
        x: xPos(i),
        y: yPos(value),
      });
    }
    if (points.length === 0) return;

    const fillAlpha = Number(entry.fillAlpha);
    if (Number.isFinite(fillAlpha) && fillAlpha > 0) {
      ctx.beginPath();
      ctx.moveTo(points[0].x, baselineY);
      points.forEach((pt) => ctx.lineTo(pt.x, pt.y));
      ctx.lineTo(points[points.length - 1].x, baselineY);
      ctx.closePath();
      ctx.fillStyle = colorWithAlpha(entry.color, fillAlpha);
      ctx.fill();
    }

    ctx.strokeStyle = entry.color;
    ctx.lineWidth = Number.isFinite(Number(entry.lineWidth)) ? Number(entry.lineWidth) : 2;
    ctx.beginPath();
    ctx.moveTo(points[0].x, points[0].y);
    points.slice(1).forEach((pt) => ctx.lineTo(pt.x, pt.y));
    ctx.stroke();

    const pointRadius = Number.isFinite(Number(entry.pointRadius))
      ? Number(entry.pointRadius)
      : 1.8;
    if (pointRadius <= 0) return;

    ctx.fillStyle = entry.color;
    points.forEach((pt) => {
      ctx.beginPath();
      ctx.arc(pt.x, pt.y, pointRadius, 0, Math.PI * 2);
      ctx.fill();
    });
  });
}

function drawBarChart(canvas, labels, series, options = {}) {
  if (!canvas) return;
  const { ctx, width, height } = ensureCanvasSize(canvas, options.height || 330);
  ctx.clearRect(0, 0, width, height);

  ctx.fillStyle = "rgba(7,12,25,0.92)";
  ctx.fillRect(0, 0, width, height);

  const allValues = collectSeriesFiniteValues(series);
  if (allValues.length === 0 || labels.length === 0) {
    drawEmptyCanvas(canvas, "No chart data");
    return;
  }

  let min = Math.min(...allValues);
  let max = Math.max(...allValues);
  if (options.forceZeroMin) min = Math.min(0, min);
  if (Math.abs(max - min) < 1e-9) {
    const pad = Math.max(1, Math.abs(max) * 0.1);
    min -= pad;
    max += pad;
  }

  const axes = drawAxesAndGrid(ctx, labels, width, height, min, max, options);
  const xPos = axes.xPos;
  const yPos = axes.yPos;
  const baselineY = axes.baselineY;

  const groupWidth = labels.length > 1 ? axes.chartW / (labels.length - 1) : axes.chartW;
  const innerGroupWidth = Math.max(12, groupWidth * 0.72);
  const seriesCount = Math.max(1, series.length);
  const barWidth = Math.max(2, (innerGroupWidth / seriesCount) * 0.82);

  labels.forEach((_, i) => {
    const centerX = xPos(i);
    const startX = centerX - innerGroupWidth / 2;

    series.forEach((entry, sIdx) => {
      const value = entry.data[i];
      if (!Number.isFinite(value)) return;
      const x = startX + sIdx * (innerGroupWidth / seriesCount) + (innerGroupWidth / seriesCount - barWidth) / 2;
      const y = yPos(value);
      const top = Math.min(y, baselineY);
      const h = Math.max(1, Math.abs(baselineY - y));
      ctx.fillStyle = colorWithAlpha(entry.color, Number.isFinite(Number(entry.fillAlpha)) ? Number(entry.fillAlpha) : 0.65);
      ctx.fillRect(x, top, barWidth, h);
      ctx.strokeStyle = entry.color;
      ctx.lineWidth = 1;
      ctx.strokeRect(x, top, barWidth, h);
    });
  });
}

function climateField(value, unit) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return `-- ${unit}`;
  return `${parsed.toFixed(1)} ${unit}`;
}

function climateZoneRow(name, temp, hum, press) {
  return {
    name,
    values: `${climateField(temp, "C")} / ${climateField(hum, "%")} / ${climateField(press, "hPa")}`,
  };
}

function renderClimateZoneList(rows) {
  const root = document.getElementById("climateZoneList");
  if (!root) return;
  root.innerHTML = "";

  if (!rows.length) {
    const item = document.createElement("div");
    item.className = "climate-zone-row";
    item.innerHTML = '<span class="climate-zone-name">no data</span><span class="climate-zone-values">-- C / -- % / -- hPa</span>';
    root.appendChild(item);
    return;
  }

  rows.forEach((row) => {
    const item = document.createElement("div");
    item.className = "climate-zone-row";

    const name = document.createElement("span");
    name.className = "climate-zone-name";
    name.textContent = row.name;

    const values = document.createElement("span");
    values.className = "climate-zone-values";
    values.textContent = row.values;

    item.appendChild(name);
    item.appendChild(values);
    root.appendChild(item);
  });
}

function renderLegend(rootId, series) {
  const root = document.getElementById(rootId);
  if (!root) return;
  root.innerHTML = "";

  series.forEach((entry) => {
    const item = document.createElement("span");
    item.className = "legend-item";

    const dot = document.createElement("span");
    dot.className = "legend-dot";
    dot.style.background = entry.color;

    const label = document.createElement("span");
    label.textContent = entry.label;

    item.appendChild(dot);
    item.appendChild(label);
    root.appendChild(item);
  });
}

function selectedRadioValue(name, fallback) {
  const checked = document.querySelector(`input[name="${name}"]:checked`);
  return checked ? checked.value : fallback;
}

function setRadioValue(name, value) {
  const target = document.querySelector(`input[name="${name}"][value="${value}"]`);
  if (target) target.checked = true;
}

function syncEnergyToolbar() {
  const period = selectedRadioValue("energyPeriod", state.energy.period);
  const dateInput = document.getElementById("energyDateInput");
  const monthInput = document.getElementById("energyMonthInput");
  if (!dateInput || !monthInput) return;

  if (!dateInput.value) dateInput.value = todayIso();
  if (!monthInput.value) monthInput.value = currentMonthIso();

  dateInput.style.display = period === "daily" ? "" : "none";
  monthInput.style.display = period === "monthly" ? "" : "none";
}

function syncClimateToolbar() {
  const period = selectedRadioValue("climatePeriod", state.climate.period);
  const dateInput = document.getElementById("climateDateInput");
  const monthInput = document.getElementById("climateMonthInput");
  if (!dateInput || !monthInput) return;

  if (!dateInput.value) dateInput.value = todayIso();
  if (!monthInput.value) monthInput.value = currentMonthIso();

  dateInput.style.display = period === "daily" ? "" : "none";
  monthInput.style.display = period === "monthly" ? "" : "none";
}

async function openEnergyModal() {
  openModal("energyModal");
  setRadioValue("energyPeriod", state.energy.period);
  syncEnergyToolbar();
  await loadEnergyData();
}

async function openClimateModal() {
  openModal("climateModal");
  setRadioValue("climatePeriod", state.climate.period);
  syncClimateToolbar();
  updateClimateMetricButtons();
  await loadClimateData();
}
function normalizeEnergyPayload(period, payload) {
  if (!payload || typeof payload !== "object") {
    throw new Error("Empty payload");
  }

  if (period === "daily") {
    const rows = Array.isArray(payload.hours) ? payload.hours : [];
    const labels = [];
    const pv = [];
    const home = [];
    const grid = [];

    if (rows.length === 0) {
      for (let i = 0; i < 24; i += 1) {
        labels.push(`${i}:00`);
        pv.push(0);
        home.push(0);
        grid.push(0);
      }
    } else {
      rows.forEach((row, idx) => {
        labels.push(safeText(row.hour_label, `${idx}:00`));
        pv.push(toFiniteNumber(row.pv, 0));
        home.push(toFiniteNumber(row.home, 0));
        grid.push(toFiniteNumber(row.grid, 0));
      });
    }

    const date = safeText(payload.date, document.getElementById("energyDateInput")?.value || todayIso());
    return {
      title: `energy graph - day ${date}`,
      yTitle: "energy (Wh)",
      labels,
      pv,
      home,
      grid,
    };
  }

  if (period === "monthly") {
    const rows = Array.isArray(payload.days) ? payload.days : [];
    const labels = [];
    const pv = [];
    const home = [];
    const grid = [];

    rows.forEach((row, idx) => {
      labels.push(safeText(row.day, String(idx + 1)));
      pv.push(toFiniteNumber(row.pv, 0));
      home.push(toFiniteNumber(row.home, 0));
      grid.push(toFiniteNumber(row.grid, 0));
    });

    const month = safeText(payload.month, document.getElementById("energyMonthInput")?.value || currentMonthIso());
    return {
      title: `energy graph - month ${month}`,
      yTitle: "energy (Wh)",
      labels,
      pv,
      home,
      grid,
    };
  }

  const labels = Array.isArray(payload.months) ? payload.months.map((v) => String(v)) : [];
  const pv = Array.isArray(payload.pv) ? payload.pv.map((v) => toFiniteNumber(v, 0) / 1000) : [];
  const home = Array.isArray(payload.home) ? payload.home.map((v) => toFiniteNumber(v, 0) / 1000) : [];
  const grid = Array.isArray(payload.grid) ? payload.grid.map((v) => toFiniteNumber(v, 0) / 1000) : [];
  const year = safeText(payload.current_year, String(new Date().getFullYear()));
  return {
    title: `energy graph - year ${year}`,
    yTitle: "energy (kWh)",
    labels,
    pv,
    home,
    grid,
  };
}

function renderEnergyChart(model) {
  const canvas = document.getElementById("energyCanvas");
  if (!canvas) return;

  setText("energyChartTitle", model.title);
  const series = [
    { label: "pv", color: "#f39c12", data: model.pv, fillAlpha: 0.68 },
    { label: "home", color: "#e74c3c", data: model.home, fillAlpha: 0.68 },
    { label: "grid", color: "#3498db", data: model.grid, fillAlpha: 0.68 },
  ];

  drawBarChart(canvas, model.labels, series, {
    yTitle: model.yTitle,
    forceZeroMin: true,
  });
  renderLegend("energyLegend", series);
}

function resolveEnergySelector(period) {
  if (period === "daily") {
    return document.getElementById("energyDateInput")?.value || todayIso();
  }
  if (period === "monthly") {
    return document.getElementById("energyMonthInput")?.value || currentMonthIso();
  }
  return "current";
}

function resolveClimateSelector(period) {
  if (period === "daily") {
    return document.getElementById("climateDateInput")?.value || todayIso();
  }
  if (period === "monthly") {
    return document.getElementById("climateMonthInput")?.value || currentMonthIso();
  }
  return "current";
}

function isCurrentEnergySelection(period, selector) {
  const activePeriod = selectedRadioValue("energyPeriod", state.energy.period);
  if (activePeriod !== period) return false;
  return resolveEnergySelector(activePeriod) === selector;
}

function isCurrentClimateSelection(period, selector) {
  const activePeriod = selectedRadioValue("climatePeriod", state.climate.period);
  if (activePeriod !== period) return false;
  return resolveClimateSelector(activePeriod) === selector;
}

async function fetchEnergyModelFromBridge(period, selector) {
  let payload;
  if (period === "daily") {
    payload = await bridgeRequest("daily", (requestId) => {
      window.AndroidHub.fetchInverterDaily(selector, requestId);
    });
  } else if (period === "monthly") {
    payload = await bridgeRequest("monthly", (requestId) => {
      window.AndroidHub.fetchInverterMonthly(selector, requestId);
    });
  } else {
    payload = await bridgeRequest("yearly", (requestId) => {
      window.AndroidHub.fetchInverterYearly(requestId);
    });
  }
  return normalizeEnergyPayload(period, payload);
}

function seriesHasFiniteValue(series) {
  if (!Array.isArray(series)) return false;
  return series.some((value) => {
    if (value === null || value === undefined || value === "") return false;
    return Number.isFinite(Number(value));
  });
}

function evaluateClimateFallbackNeeds(model) {
  const corridorMissing = !seriesHasFiniteValue(model?.tempCorridor)
    && !seriesHasFiniteValue(model?.humCorridor)
    && !seriesHasFiniteValue(model?.pressCorridor);
  const garageMissing = !seriesHasFiniteValue(model?.tempGarage)
    && !seriesHasFiniteValue(model?.humGarage)
    && !seriesHasFiniteValue(model?.pressGarage);
  return {
    corridorMissing,
    garageMissing,
  };
}

async function fetchClimateModelFromBridge(period, selector) {
  let payload;
  if (period === "daily") {
    payload = await bridgeRequest("climate-daily", (requestId) => {
      window.AndroidHub.fetchInverterDaily(selector, requestId);
    });
  } else if (period === "monthly") {
    payload = await bridgeRequest("climate-monthly", (requestId) => {
      window.AndroidHub.fetchInverterMonthly(selector, requestId);
    });
  } else {
    payload = await bridgeRequest("climate-yearly", (requestId) => {
      window.AndroidHub.fetchInverterYearly(requestId);
    });
  }

  const model = normalizeClimatePayload(period, payload);
  if (period === "daily") {
    const fallbackNeeds = evaluateClimateFallbackNeeds(model);
    if (fallbackNeeds.corridorMissing || fallbackNeeds.garageMissing) {
      await enrichDailyClimateModelWithModuleHistory(model, {
        corridor: fallbackNeeds.corridorMissing,
        garage: fallbackNeeds.garageMissing,
      });
    }
  }
  return model;
}

async function syncGraphModel(graphType, period, selector, options = {}) {
  const force = !!options.force;
  const syncKey = `${graphType}::${period}::${selector}`;
  const cachedEntry = getGraphCacheEntry(graphType, period, selector);
  const nowMs = Date.now();

  if (shouldThrottleGraphSyncKey(syncKey, force, nowMs) && cachedEntry?.model) {
    return cachedEntry.model;
  }
  state.graphSync.lastAttemptByKey[syncKey] = nowMs;

  const model = await enqueueGraphSync(syncKey, async () => {
    if (graphType === "climate") {
      return fetchClimateModelFromBridge(period, selector);
    }
    return fetchEnergyModelFromBridge(period, selector);
  });
  upsertGraphCacheEntry(graphType, period, selector, model, Date.now());
  return model;
}

function applyGraphModelIfCurrent(graphType, period, selector, model) {
  if (!model) return;
  if (graphType === "climate") {
    if (!isCurrentClimateSelection(period, selector)) return;
    state.climate.last = model;
    renderClimateChart();
    return;
  }
  if (!isCurrentEnergySelection(period, selector)) return;
  state.energy.last = model;
  renderEnergyChart(model);
}

function collectBackgroundGraphSyncCandidates(nowMs = Date.now()) {
  loadGraphCacheFromStorage();
  const candidates = [];
  const appendCandidates = (graphType) => {
    const slot = getGraphCacheSlot(graphType);
    Object.entries(slot).forEach(([entryKey, entry]) => {
      if (!entry || typeof entry !== "object" || !entry.model) return;
      const viewedAtMs = Number(entry.viewedAtMs || entry.fetchedAtMs || 0);
      if (!Number.isFinite(viewedAtMs) || viewedAtMs <= 0) return;
      if (nowMs - viewedAtMs > GRAPH_SYNC_VIEW_WINDOW_MS) return;
      const parsed = parseGraphEntryKey(entryKey);
      if (!isGraphCacheStale(entry, graphType, parsed.period, nowMs)) return;
      candidates.push({
        graphType,
        period: parsed.period,
        selector: parsed.selector,
        viewedAtMs,
      });
    });
  };

  appendCandidates("energy");
  appendCandidates("climate");
  candidates.sort((a, b) => b.viewedAtMs - a.viewedAtMs);
  return candidates.slice(0, GRAPH_SYNC_MAX_ITEMS_PER_CYCLE);
}

async function runGraphBackgroundSyncCycle() {
  if (state.graphSync.cycleInFlight) return;
  if (!hasBridge() || document.hidden) return;

  state.graphSync.cycleInFlight = true;
  try {
    const candidates = collectBackgroundGraphSyncCandidates(Date.now());
    for (const candidate of candidates) {
      try {
        const model = await syncGraphModel(candidate.graphType, candidate.period, candidate.selector, { force: false });
        applyGraphModelIfCurrent(candidate.graphType, candidate.period, candidate.selector, model);
      } catch (error) {
        // Silent: background sync must not interrupt UI.
      }
    }
  } finally {
    state.graphSync.cycleInFlight = false;
  }
}

function scheduleNextGraphBackgroundSync(delayMs = 0) {
  if (state.graphSync.timer) {
    clearTimeout(state.graphSync.timer);
  }
  const jitterMs = Math.floor(Math.random() * GRAPH_SYNC_INTERVAL_JITTER_MS);
  const nextDelayMs = Math.max(10 * 1000, delayMs || (GRAPH_SYNC_INTERVAL_MS + jitterMs));
  state.graphSync.timer = setTimeout(async () => {
    state.graphSync.timer = null;
    await runGraphBackgroundSyncCycle();
    scheduleNextGraphBackgroundSync();
  }, nextDelayMs);
}

function initGraphSync() {
  loadGraphCacheFromStorage();
  ensureGraphSyncQueue();
  scheduleNextGraphBackgroundSync(45 * 1000);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
      runGraphBackgroundSyncCycle();
    }
  });
  window.addEventListener("beforeunload", () => {
    if (state.graphCache.persistHandle) {
      clearTimeout(state.graphCache.persistHandle);
      state.graphCache.persistHandle = null;
    }
    persistGraphCacheNow();
  });
}

async function loadEnergyData(options = {}) {
  const forceRefresh = !!options.forceRefresh;
  loadGraphCacheFromStorage();

  const period = selectedRadioValue("energyPeriod", state.energy.period);
  state.energy.period = period;
  syncEnergyToolbar();
  const selector = resolveEnergySelector(period);
  const cacheEntry = getGraphCacheEntry("energy", period, selector);
  let renderedFromCache = false;

  if (cacheEntry?.model) {
    state.energy.last = cacheEntry.model;
    touchGraphCacheEntry("energy", period, selector);
    renderEnergyChart(cacheEntry.model);
    renderedFromCache = true;
  } else {
    setText("energyChartTitle", "energy graph - loading...");
  }

  if (!hasBridge()) {
    if (!renderedFromCache) {
      drawEmptyCanvas(document.getElementById("energyCanvas"), "Bridge unavailable");
    }
    return;
  }

  const stale = !cacheEntry || isGraphCacheStale(cacheEntry, "energy", period, Date.now());
  if (!forceRefresh && cacheEntry?.model && !stale) {
    return;
  }

  const syncTask = syncGraphModel("energy", period, selector, { force: forceRefresh });
  if (cacheEntry?.model && !forceRefresh) {
    syncTask
      .then((model) => {
        applyGraphModelIfCurrent("energy", period, selector, model);
      })
      .catch(() => {
        // Keep stale cache on silent background refresh failure.
      });
    return;
  }

  try {
    const model = await syncTask;
    applyGraphModelIfCurrent("energy", period, selector, model);
  } catch (error) {
    if (!renderedFromCache) {
      drawEmptyCanvas(document.getElementById("energyCanvas"), "Failed to load data");
      setText("energyChartTitle", "energy graph - error");
      showToast(`energy data failed: ${error.message}`);
    }
  }
}

function getClimatePathValue(source, path) {
  if (!source || typeof source !== "object") return undefined;
  if (!path || typeof path !== "string") return undefined;
  if (!path.includes(".")) return source[path];

  const chunks = path.split(".");
  let cursor = source;
  for (const chunk of chunks) {
    if (!cursor || typeof cursor !== "object" || !(chunk in cursor)) return undefined;
    cursor = cursor[chunk];
  }
  return cursor;
}

function climateNumberFromCandidates(source, keys) {
  if (!Array.isArray(keys)) return null;
  for (const key of keys) {
    const raw = getClimatePathValue(source, key);
    const numValue = Number(raw);
    if (Number.isFinite(numValue)) return numValue;
  }
  return null;
}

function climateArrayFromCandidates(source, keys) {
  if (!source || typeof source !== "object" || !Array.isArray(keys)) return [];
  for (const key of keys) {
    const raw = getClimatePathValue(source, key);
    if (Array.isArray(raw)) {
      return raw.map((value) => toFiniteNumber(value, null));
    }
  }
  return [];
}

function normalizeHistoryTemperatureValue(rawValue) {
  const parsed = Number(rawValue);
  if (!Number.isFinite(parsed)) return null;
  return Math.abs(parsed) > 120 ? parsed / 10 : parsed;
}

function mapHistoryPayloadToHourlyTemperature(payload, expectedDate = "") {
  const hourly = Array(24).fill(null);
  if (!payload || typeof payload !== "object") return hourly;

  const historyDate = safeText(payload.date, "");
  if (expectedDate && historyDate && expectedDate !== historyDate) {
    return hourly;
  }

  const rows = Array.isArray(payload.samples) ? payload.samples : [];
  if (!rows.length) return hourly;

  const sums = Array(24).fill(0);
  const counts = Array(24).fill(0);
  rows.forEach((row) => {
    const minute = Number(row?.m);
    const temp = normalizeHistoryTemperatureValue(row?.t);
    if (!Number.isFinite(minute) || !Number.isFinite(temp)) return;
    const hour = Math.floor(minute / 60);
    if (hour < 0 || hour > 23) return;
    sums[hour] += temp;
    counts[hour] += 1;
  });

  for (let hour = 0; hour < 24; hour += 1) {
    if (!counts[hour]) continue;
    hourly[hour] = Math.round((sums[hour] / counts[hour]) * 10) / 10;
  }
  return hourly;
}

function mergeClimateSeries(baseSeries, fallbackSeries, requiredLength = 0) {
  const base = Array.isArray(baseSeries) ? baseSeries : [];
  const fallback = Array.isArray(fallbackSeries) ? fallbackSeries : [];
  const len = Math.max(requiredLength, base.length, fallback.length);
  const merged = [];
  for (let i = 0; i < len; i += 1) {
    const baseValue = Number(base[i]);
    if (Number.isFinite(baseValue)) {
      merged.push(baseValue);
      continue;
    }
    const fallbackValue = Number(fallback[i]);
    merged.push(Number.isFinite(fallbackValue) ? fallbackValue : null);
  }
  return merged;
}

async function enrichDailyClimateModelWithModuleHistory(model, options = {}) {
  if (!model || typeof model !== "object") return;
  const needCorridor = options.corridor !== false;
  const needGarage = options.garage !== false;
  if (!needCorridor && !needGarage) return;

  const expectedDate = safeText(model.date, "");
  const labelCount = Array.isArray(model.labels) ? model.labels.length : 24;
  const pending = [];

  if (
    needCorridor &&
    state.config.loadControllerEnabled &&
    window.AndroidHub &&
    typeof window.AndroidHub.fetchLoadControllerHistory === "function"
  ) {
    pending.push(
      bridgeRequest("climate-corridor-history", (requestId) => {
        window.AndroidHub.fetchLoadControllerHistory(requestId);
      }).then((historyPayload) => {
        const corridorHourly = mapHistoryPayloadToHourlyTemperature(historyPayload, expectedDate);
        model.tempCorridor = mergeClimateSeries(model.tempCorridor, corridorHourly, labelCount);
      }),
    );
  }

  if (
    needGarage &&
    state.config.garageEnabled &&
    window.AndroidHub &&
    typeof window.AndroidHub.fetchGarageHistory === "function"
  ) {
    pending.push(
      bridgeRequest("climate-garage-history", (requestId) => {
        window.AndroidHub.fetchGarageHistory(requestId);
      }).then((historyPayload) => {
        const garageHourly = mapHistoryPayloadToHourlyTemperature(historyPayload, expectedDate);
        model.tempGarage = mergeClimateSeries(model.tempGarage, garageHourly, labelCount);
      }),
    );
  }

  if (!pending.length) return;
  await Promise.allSettled(pending);
}

function normalizeClimatePayload(period, payload) {
  if (!payload || typeof payload !== "object") {
    throw new Error("Empty payload");
  }
  if (safeText(payload.error, "") !== "") {
    throw new Error(safeText(payload.error, "Climate data unavailable"));
  }

  const climateKeys = {
    internal: {
      temp: ["temp", "temp_int", "temp_internal", "inside_temp", "internal.temp"],
      hum: ["hum", "hum_int", "hum_internal", "inside_hum", "internal.hum"],
      press: ["press", "press_int", "press_internal", "inside_press", "internal.press"],
    },
    external: {
      temp: ["temp_ext", "external_temp", "outside_temp", "external.temp"],
      hum: ["hum_ext", "external_hum", "outside_hum", "external.hum"],
      press: ["press_ext", "external_press", "outside_press", "external.press"],
    },
    corridor: {
      temp: ["temp_corridor", "corridor_temp", "temp_load", "temp_lc", "corridor.temp"],
      hum: ["hum_corridor", "corridor_hum", "hum_load", "hum_lc", "corridor.hum"],
      press: ["press_corridor", "corridor_press", "press_load", "press_lc", "corridor.press"],
    },
    garage: {
      temp: ["temp_garage", "garage_temp", "garage.temp"],
      hum: ["hum_garage", "garage_hum", "garage.hum"],
      press: ["press_garage", "garage_press", "garage.press"],
    },
  };

  const sanitizeClimateTriplets = (tempArr, humArr, pressArr) => {
    const len = Math.max(tempArr.length, humArr.length, pressArr.length);
    for (let i = 0; i < len; i += 1) {
      const temp = Number.isFinite(tempArr[i]) ? tempArr[i] : null;
      const hum = Number.isFinite(humArr[i]) ? humArr[i] : null;
      const press = Number.isFinite(pressArr[i]) ? pressArr[i] : null;
      const allZero = temp === 0 && hum === 0 && press === 0;
      tempArr[i] = allZero ? null : temp;
      humArr[i] = hum === 0 ? null : hum;
      pressArr[i] = press === 0 ? null : press;
    }
  };

  if (period === "daily") {
    const rows = Array.isArray(payload.hours) ? payload.hours : [];
    const labels = [];
    const tempInt = [];
    const humInt = [];
    const pressInt = [];
    const tempExt = [];
    const humExt = [];
    const pressExt = [];
    const tempCorridor = [];
    const humCorridor = [];
    const pressCorridor = [];
    const tempGarage = [];
    const humGarage = [];
    const pressGarage = [];

    if (rows.length === 0) {
      for (let i = 0; i < 24; i += 1) {
        labels.push(`${i}:00`);
        tempInt.push(null);
        humInt.push(null);
        pressInt.push(null);
        tempExt.push(null);
        humExt.push(null);
        pressExt.push(null);
        tempCorridor.push(null);
        humCorridor.push(null);
        pressCorridor.push(null);
        tempGarage.push(null);
        humGarage.push(null);
        pressGarage.push(null);
      }
    } else {
      rows.forEach((row, idx) => {
        labels.push(safeText(row.hour_label, `${idx}:00`));
        tempInt.push(climateNumberFromCandidates(row, climateKeys.internal.temp));
        humInt.push(climateNumberFromCandidates(row, climateKeys.internal.hum));
        pressInt.push(climateNumberFromCandidates(row, climateKeys.internal.press));
        tempExt.push(climateNumberFromCandidates(row, climateKeys.external.temp));
        humExt.push(climateNumberFromCandidates(row, climateKeys.external.hum));
        pressExt.push(climateNumberFromCandidates(row, climateKeys.external.press));
        tempCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.temp));
        humCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.hum));
        pressCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.press));
        tempGarage.push(climateNumberFromCandidates(row, climateKeys.garage.temp));
        humGarage.push(climateNumberFromCandidates(row, climateKeys.garage.hum));
        pressGarage.push(climateNumberFromCandidates(row, climateKeys.garage.press));
      });
    }

    sanitizeClimateTriplets(tempInt, humInt, pressInt);
    sanitizeClimateTriplets(tempExt, humExt, pressExt);
    sanitizeClimateTriplets(tempCorridor, humCorridor, pressCorridor);
    sanitizeClimateTriplets(tempGarage, humGarage, pressGarage);

    const date = safeText(payload.date, document.getElementById("climateDateInput")?.value || todayIso());
    return {
      title: `climate graph - day ${date}`,
      date,
      labels,
      tempInt,
      humInt,
      pressInt,
      tempExt,
      humExt,
      pressExt,
      tempCorridor,
      humCorridor,
      pressCorridor,
      tempGarage,
      humGarage,
      pressGarage,
    };
  }

  if (period === "monthly") {
    const rows = Array.isArray(payload.days) ? payload.days : [];
    const labels = [];
    const tempInt = [];
    const humInt = [];
    const pressInt = [];
    const tempExt = [];
    const humExt = [];
    const pressExt = [];
    const tempCorridor = [];
    const humCorridor = [];
    const pressCorridor = [];
    const tempGarage = [];
    const humGarage = [];
    const pressGarage = [];

    rows.forEach((row, idx) => {
      labels.push(safeText(row.day, String(idx + 1)));
      tempInt.push(climateNumberFromCandidates(row, climateKeys.internal.temp));
      humInt.push(climateNumberFromCandidates(row, climateKeys.internal.hum));
      pressInt.push(climateNumberFromCandidates(row, climateKeys.internal.press));
      tempExt.push(climateNumberFromCandidates(row, climateKeys.external.temp));
      humExt.push(climateNumberFromCandidates(row, climateKeys.external.hum));
      pressExt.push(climateNumberFromCandidates(row, climateKeys.external.press));
      tempCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.temp));
      humCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.hum));
      pressCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.press));
      tempGarage.push(climateNumberFromCandidates(row, climateKeys.garage.temp));
      humGarage.push(climateNumberFromCandidates(row, climateKeys.garage.hum));
      pressGarage.push(climateNumberFromCandidates(row, climateKeys.garage.press));
    });

    sanitizeClimateTriplets(tempInt, humInt, pressInt);
    sanitizeClimateTriplets(tempExt, humExt, pressExt);
    sanitizeClimateTriplets(tempCorridor, humCorridor, pressCorridor);
    sanitizeClimateTriplets(tempGarage, humGarage, pressGarage);

    const month = safeText(payload.month, document.getElementById("climateMonthInput")?.value || currentMonthIso());
    return {
      title: `climate graph - month ${month}`,
      month,
      labels,
      tempInt,
      humInt,
      pressInt,
      tempExt,
      humExt,
      pressExt,
      tempCorridor,
      humCorridor,
      pressCorridor,
      tempGarage,
      humGarage,
      pressGarage,
    };
  }

  const labels = Array.isArray(payload.months) ? payload.months.map((v) => String(v)) : [];
  const year = safeText(payload.current_year, String(new Date().getFullYear()));
  const tempInt = climateArrayFromCandidates(payload, climateKeys.internal.temp);
  const humInt = climateArrayFromCandidates(payload, climateKeys.internal.hum);
  const pressInt = climateArrayFromCandidates(payload, climateKeys.internal.press);
  const tempExt = climateArrayFromCandidates(payload, climateKeys.external.temp);
  const humExt = climateArrayFromCandidates(payload, climateKeys.external.hum);
  const pressExt = climateArrayFromCandidates(payload, climateKeys.external.press);
  const tempCorridor = climateArrayFromCandidates(payload, climateKeys.corridor.temp);
  const humCorridor = climateArrayFromCandidates(payload, climateKeys.corridor.hum);
  const pressCorridor = climateArrayFromCandidates(payload, climateKeys.corridor.press);
  const tempGarage = climateArrayFromCandidates(payload, climateKeys.garage.temp);
  const humGarage = climateArrayFromCandidates(payload, climateKeys.garage.hum);
  const pressGarage = climateArrayFromCandidates(payload, climateKeys.garage.press);
  sanitizeClimateTriplets(tempInt, humInt, pressInt);
  sanitizeClimateTriplets(tempExt, humExt, pressExt);
  sanitizeClimateTriplets(tempCorridor, humCorridor, pressCorridor);
  sanitizeClimateTriplets(tempGarage, humGarage, pressGarage);
  return {
    title: `climate graph - year ${year}`,
    year,
    labels,
    tempInt,
    humInt,
    pressInt,
    tempExt,
    humExt,
    pressExt,
    tempCorridor,
    humCorridor,
    pressCorridor,
    tempGarage,
    humGarage,
    pressGarage,
  };
}

function currentClimateMetricMeta() {
  if (state.climate.metric === "hum") {
    return { label: "humidity", unit: "%" };
  }
  if (state.climate.metric === "press") {
    return { label: "pressure", unit: "hPa" };
  }
  return { label: "temperature", unit: "C" };
}

function climateSeriesForMetric(model, metric) {
  if (metric === "hum") {
    return {
      primary: model.humInt,
      external: model.humExt,
      corridor: model.humCorridor,
      garage: model.humGarage,
    };
  }
  if (metric === "press") {
    return {
      primary: model.pressInt,
      external: model.pressExt,
      corridor: model.pressCorridor,
      garage: model.pressGarage,
    };
  }
  return {
    primary: model.tempInt,
    external: model.tempExt,
    corridor: model.tempCorridor,
    garage: model.tempGarage,
  };
}

function updateClimateMetricButtons() {
  document.querySelectorAll("[data-climate-metric]").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.climateMetric === state.climate.metric);
  });
}

function renderClimateChart() {
  const model = state.climate.last;
  const canvas = document.getElementById("climateCanvas");
  if (!canvas) return;
  if (!model) {
    drawEmptyCanvas(canvas, "No chart data");
    return;
  }

  const meta = currentClimateMetricMeta();
  const selected = climateSeriesForMetric(model, state.climate.metric);
  const series = [];
  const candidates = [
    {
      label: `internal ${meta.label} (${meta.unit})`,
      color: "#7a5cff",
      data: selected.primary,
      lineWidth: 2.2,
      pointRadius: 1.4,
      fillAlpha: 0.18,
    },
    {
      label: `outside ${meta.label} (${meta.unit})`,
      color: "#00d7ff",
      data: selected.external,
      lineWidth: 2,
      pointRadius: 1.3,
      fillAlpha: 0,
    },
    {
      label: `corridor ${meta.label} (${meta.unit})`,
      color: "#ff9f43",
      data: selected.corridor,
      lineWidth: 1.9,
      pointRadius: 1.2,
      fillAlpha: 0,
    },
    {
      label: `garage ${meta.label} (${meta.unit})`,
      color: "#33d6a6",
      data: selected.garage,
      lineWidth: 1.9,
      pointRadius: 1.2,
      fillAlpha: 0,
    },
  ];
  candidates.forEach((item) => {
    const hasData = collectSeriesFiniteValues([{ data: item.data || [] }]).length > 0;
    if (hasData) series.push(item);
  });

  setText("climateChartTitle", `${model.title} - ${meta.label}`);
  if (!series.length) {
    drawEmptyCanvas(canvas, "No climate data");
    renderLegend("climateLegend", []);
    return;
  }
  drawLineChart(canvas, model.labels, series, {
    yTitle: `${meta.label} (${meta.unit})`,
  });
  renderLegend("climateLegend", series);
}

async function loadClimateData(options = {}) {
  const forceRefresh = !!options.forceRefresh;
  loadGraphCacheFromStorage();

  const period = selectedRadioValue("climatePeriod", state.climate.period);
  state.climate.period = period;
  syncClimateToolbar();
  const selector = resolveClimateSelector(period);
  const cacheEntry = getGraphCacheEntry("climate", period, selector);
  let renderedFromCache = false;

  if (cacheEntry?.model) {
    state.climate.last = cacheEntry.model;
    touchGraphCacheEntry("climate", period, selector);
    renderClimateChart();
    renderedFromCache = true;
  } else {
    setText("climateChartTitle", "climate graph - loading...");
  }

  if (!hasBridge()) {
    if (!renderedFromCache) {
      drawEmptyCanvas(document.getElementById("climateCanvas"), "Bridge unavailable");
    }
    return;
  }

  const stale = !cacheEntry || isGraphCacheStale(cacheEntry, "climate", period, Date.now());
  if (!forceRefresh && cacheEntry?.model && !stale) {
    return;
  }

  const syncTask = syncGraphModel("climate", period, selector, { force: forceRefresh });
  if (cacheEntry?.model && !forceRefresh) {
    syncTask
      .then((model) => {
        applyGraphModelIfCurrent("climate", period, selector, model);
      })
      .catch(() => {
        // Keep stale cache on silent background refresh failure.
      });
    return;
  }

  try {
    const model = await syncTask;
    applyGraphModelIfCurrent("climate", period, selector, model);
  } catch (error) {
    if (!renderedFromCache) {
      drawEmptyCanvas(document.getElementById("climateCanvas"), "Failed to load data");
      setText("climateChartTitle", "climate graph - error");
      showToast(`climate data failed: ${error.message}`);
    }
  }
}

function bindEnergyControls() {
  document.querySelectorAll('input[name="energyPeriod"]').forEach((radio) => {
    radio.addEventListener("change", () => {
      state.energy.period = radio.value;
      syncEnergyToolbar();
      loadEnergyData();
    });
  });

  const loadBtn = document.getElementById("energyLoadBtn");
  if (loadBtn) {
    loadBtn.addEventListener("click", () => {
      loadEnergyData({ forceRefresh: true });
    });
  }
}

function bindClimateControls() {
  document.querySelectorAll('input[name="climatePeriod"]').forEach((radio) => {
    radio.addEventListener("change", () => {
      state.climate.period = radio.value;
      syncClimateToolbar();
      loadClimateData();
    });
  });

  const loadBtn = document.getElementById("climateLoadBtn");
  if (loadBtn) {
    loadBtn.addEventListener("click", () => {
      loadClimateData({ forceRefresh: true });
    });
  }

  document.querySelectorAll("[data-climate-metric]").forEach((btn) => {
    btn.addEventListener("click", () => {
      state.climate.metric = btn.dataset.climateMetric || "temp";
      updateClimateMetricButtons();
      renderClimateChart();
    });
  });
}
function renderClimateWideCard(inverter, loadController, garage) {
  const zones = [];

  const invAvailable = !!(
    inverter &&
    (inverter.bmeAvailable ||
      inverter.bmeTemp !== null ||
      inverter.bmeHum !== null ||
      inverter.bmePress !== null)
  );
  if (invAvailable) {
    zones.push(climateZoneRow("outside", inverter.bmeTemp, inverter.bmeHum, inverter.bmePress));
  }

  const loadAvailable = !!(
    loadController &&
    (loadController.bmeAvailable ||
      loadController.bmeTemp !== null ||
      loadController.bmeHum !== null ||
      loadController.bmePress !== null)
  );
  if (loadAvailable) {
    zones.push(
      climateZoneRow("коридор", loadController.bmeTemp, loadController.bmeHum, loadController.bmePress),
    );
  }

  const garageAvailable = !!(
    garage &&
    (garage.bmeAvailable ||
      garage.bmeTemp !== null ||
      garage.bmeHum !== null ||
      garage.bmePress !== null)
  );
  if (garageAvailable) {
    zones.push(climateZoneRow("гараж", garage.bmeTemp, garage.bmeHum, garage.bmePress));
  }

  renderClimateZoneList(zones);

  const updated = safeText(
    loadController?.rtcTime || inverter?.rtcTime || garage?.rtcTime,
    "--:--:--",
  );
  setText("climateUpdated", `updated: ${updated}`);
}

function formatSchemePower(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return "-- W";
  return `${Math.round(n)} W`;
}

function setSchemeSwitchState(id, isClosed) {
  const el = document.getElementById(id);
  if (!el) return;

  const unknown = isClosed === null || isClosed === undefined;
  el.classList.toggle("is-on", isClosed === true);
  el.classList.toggle("is-off", isClosed === false);
  el.classList.toggle("is-unknown", unknown);

  const stateLabel = el.querySelector(".scheme-switch-state");
  if (stateLabel) {
    stateLabel.textContent = unknown ? "--" : isClosed ? "ON" : "OFF";
  }
}

function colorToRgbString(color) {
  if (!Array.isArray(color) || color.length !== 3) return "";
  const r = Math.max(0, Math.min(255, Math.round(Number(color[0]) || 0)));
  const g = Math.max(0, Math.min(255, Math.round(Number(color[1]) || 0)));
  const b = Math.max(0, Math.min(255, Math.round(Number(color[2]) || 0)));
  return `${r}, ${g}, ${b}`;
}

function buildSchemeSupplyMixColor(pvPowerW, gridPowerW, batteryPowerW) {
  const pv = Math.max(0, Number(pvPowerW) || 0);
  const grid = Math.max(0, Number(gridPowerW) || 0);
  const battery = Math.max(0, Number(batteryPowerW) || 0);
  const total = pv + grid + battery;
  if (total <= 0) return null;

  const mix = [0, 0, 0];
  const applyWeight = (weight, color) => {
    if (weight <= 0) return;
    mix[0] += weight * color[0];
    mix[1] += weight * color[1];
    mix[2] += weight * color[2];
  };

  applyWeight(pv / total, SCHEME_FLOW_COLORS.pv);
  applyWeight(grid / total, SCHEME_FLOW_COLORS.grid);
  applyWeight(battery / total, SCHEME_FLOW_COLORS.battery);
  return mix;
}

function setSchemeLinkState(id, powerValue, enabled = true, color = null) {
  const el = document.getElementById(id);
  if (!el) return;

  const power = Number(powerValue);
  const hasPower = !!enabled && Number.isFinite(power) && Math.abs(power) >= 4;
  const normalized = Number.isFinite(power) ? Math.min(1, Math.abs(power) / 6000) : 0;
  const durationMs = Math.round(1900 - normalized * 1350);

  const reverse = Number.isFinite(power) && power < 0;
  el.classList.toggle("is-active", hasPower);
  el.classList.toggle("is-reverse", reverse);
  el.style.setProperty("--flow-direction", reverse ? "reverse" : "normal");
  el.style.setProperty("--flow-duration", `${Math.max(420, durationMs)}ms`);

  const rgb = colorToRgbString(color);
  if (rgb) {
    el.style.setProperty("--chain-rgb", rgb);
  } else {
    el.style.removeProperty("--chain-rgb");
  }
}

function renderPowerScheme({
  inverter,
  loadController,
  garage,
  invOff,
  loadOff,
  garageOff,
  gridPresent,
}) {
  const gridPowerDisplayW = zeroGridPowerWhenNoVoltage(inverter.gridW, inverter.lineVoltage);
  const loadPowerDisplayW = applyConsumptionDisplayFloor(inverter.loadW);
  const boiler1PowerDisplayW = applyConsumptionDisplayFloor(loadController.boilerPower);
  const pumpPowerDisplayW = applyConsumptionDisplayFloor(loadController.pumpPower);
  const boiler2PowerDisplayW = applyConsumptionDisplayFloor(garage.boilerPower);

  setText("schemeGridPower", invOff ? "-- W" : formatSchemePower(gridPowerDisplayW));
  setText("schemePvPower", invOff ? "-- W" : formatSchemePower(inverter.pvW));
  setText("schemeBatteryPower", invOff ? "-- W" : formatSchemePower(inverter.batteryPower));
  setText("schemeLoadPower", invOff ? "-- W" : formatSchemePower(loadPowerDisplayW));
  setText("schemeBoiler1Power", loadOff ? "-- W" : formatSchemePower(boiler1PowerDisplayW));
  setText("schemePumpPower", loadOff ? "-- W" : formatSchemePower(pumpPowerDisplayW));
  setText("schemeBoiler2Power", garageOff ? "-- W" : formatSchemePower(boiler2PowerDisplayW));

  setText("schemeInvInput", invOff ? "-- V" : `${num(inverter.lineVoltage, 1, "--")} V`);
  setText("schemeInvOutput", invOff ? "-- V" : `${num(inverter.outputVoltage, 1, "--")} V`);
  setText("schemeBatterySoc", invOff ? "--%" : `${num(inverter.batterySoc, 0, "--")}%`);
  setText("schemeHouseLoad", invOff ? "-- W" : formatSchemePower(loadPowerDisplayW));

  setText("schemeBoiler1State", loadOff ? "disabled" : boolText(!!loadController.boiler1On));
  setText("schemePumpState", loadOff ? "disabled" : boolText(!!loadController.pumpOn));
  setText("schemeBoiler2State", garageOff ? "disabled" : boolText(!!garage.boiler2On));

  const gridNode = document.getElementById("schemeNodeGrid");
  if (gridNode) {
    gridNode.classList.toggle("is-present", !!gridPresent);
    gridNode.classList.toggle("is-absent", !gridPresent);
  }
  setText("schemeGridState", invOff ? "disabled" : gridPresent ? "live" : "off");

  const gridSwitchOn = invOff ? null : !!inverter.gridRelayOn;
  const loadSwitchOn = invOff ? null : !!inverter.loadRelayOn;
  const boiler1SwitchOn = loadOff ? null : !!loadController.boiler1On;
  const pumpSwitchOn = loadOff ? null : !!loadController.pumpOn;
  const boiler2SwitchOn = garageOff ? null : !!garage.boiler2On;
  const mixedSupplyColor = buildSchemeSupplyMixColor(inverter.pvW, gridPowerDisplayW, inverter.batteryPower)
    || SCHEME_FLOW_COLORS.loadFallback;

  setSchemeSwitchState("schemeSwitchGrid", gridSwitchOn);
  setSchemeSwitchState("schemeSwitchLoad", loadSwitchOn);
  setSchemeSwitchState("schemeSwitchBoiler1", boiler1SwitchOn);
  setSchemeSwitchState("schemeSwitchPump", pumpSwitchOn);
  setSchemeSwitchState("schemeSwitchBoiler2", boiler2SwitchOn);

  setSchemeLinkState("schemeLinkGrid", gridPowerDisplayW, !invOff && !!gridPresent && gridSwitchOn === true, SCHEME_FLOW_COLORS.grid);
  setSchemeLinkState("schemeLinkPv", inverter.pvW, !invOff, SCHEME_FLOW_COLORS.pv);
  setSchemeLinkState("schemeLinkBattery", inverter.batteryPower, !invOff, SCHEME_FLOW_COLORS.battery);
  setSchemeLinkState("schemeLinkLoad", loadPowerDisplayW, !invOff && loadSwitchOn === true, mixedSupplyColor);

  const topBranchActive = boiler1SwitchOn === true;
  const bottomBranchActive = boiler2SwitchOn === true;
  // Верхня вертикаль має рух зверху/знизу в протилежному напрямі до нижньої.
  setSchemeLinkState("schemeLinkHouseTop", -boiler1PowerDisplayW, !invOff && loadSwitchOn === true && topBranchActive, mixedSupplyColor);
  setSchemeLinkState("schemeLinkHouseBottom", boiler2PowerDisplayW, !invOff && loadSwitchOn === true && bottomBranchActive, mixedSupplyColor);
  setSchemeLinkState("schemeLinkBoiler1", boiler1PowerDisplayW, !loadOff && boiler1SwitchOn === true, mixedSupplyColor);
  setSchemeLinkState("schemeLinkPump", pumpPowerDisplayW, !loadOff && pumpSwitchOn === true, mixedSupplyColor);
  setSchemeLinkState("schemeLinkBoiler2", boiler2PowerDisplayW, !garageOff && boiler2SwitchOn === true, mixedSupplyColor);
}

function renderAll() {
  const status = state.status || {};
  const inverter = state.config.inverterEnabled ? status.inverter || {} : {};
  const loadController = state.config.loadControllerEnabled ? status.loadController || {} : {};
  const garage = state.config.garageEnabled ? status.garage || {} : {};

  applyModuleCardStates();
  applyLiveCardStates(status);

  const topLineVoltage = pickNumber([
    inverter.lineVoltage,
    loadController.lineVoltage,
    garage.lineVoltage,
  ]);
  const topPv = pickNumber([inverter.pvW, loadController.pvW, garage.pvW]);
  const topGrid = pickNumber([inverter.gridW, loadController.gridW, garage.gridW]);
  const topLoadRaw = pickNumber([inverter.loadW, loadController.loadW, garage.loadW]);
  const topLoad = applyConsumptionDisplayFloor(topLoadRaw);
  const topBatSoc = pickNumber([
    inverter.batterySoc,
    loadController.batterySoc,
    garage.batterySoc,
  ]);
  const topBatPower = pickNumber([
    inverter.batteryPower,
    loadController.batteryPower,
    garage.batteryPower,
  ]);
  const topWifi = pickNumber([
    inverter.wifiStrength,
    loadController.wifiStrength,
    garage.wifiStrength,
  ]);
  const topGridDisplay = Math.abs(Number(topLineVoltage) || 0) <= ZERO_VOLTAGE_THRESHOLD_V
    ? 0
    : topGrid;

  setText(
    "realTime",
    safeText(inverter.rtcTime || loadController.rtcTime || garage.rtcTime, "--:--:--"),
  );
  setText("lineVoltage", num(topLineVoltage, 1));
  setText("pvPowerTop", num(topPv, 0));
  setText("gridPowerTop", num(topGridDisplay, 0));
  setText("loadPowerTop", num(topLoad, 0));
  setText("batterySocTop", num(topBatSoc, 0));
  setText("batteryPowerTop", num(topBatPower, 0));
  setWifi(topWifi);

  const invOff = !state.config.inverterEnabled;
  const loadOff = !state.config.loadControllerEnabled;
  const garageOff = !state.config.garageEnabled;

  const gridPresent = !invOff && (
    inverter.gridPresent !== undefined && inverter.gridPresent !== null
      ? !!inverter.gridPresent
      : Number(inverter.lineVoltage) >= 170
  );
  const gridPresenceBadge = document.getElementById("gridPresenceBadge");
  if (gridPresenceBadge) {
    gridPresenceBadge.classList.toggle("is-present", gridPresent);
    gridPresenceBadge.classList.toggle("is-absent", !gridPresent);
  }

  if (!loadOff) {
    recordLoadTimelineSample(loadController);
  }

  const gridPowerCardW = zeroGridPowerWhenNoVoltage(inverter.gridW, inverter.lineVoltage);
  const loadPowerCardW = applyConsumptionDisplayFloor(inverter.loadW);
  const boiler1PowerCardW = applyConsumptionDisplayFloor(loadController.boilerPower);
  const pumpPowerCardW = applyConsumptionDisplayFloor(loadController.pumpPower);
  const boiler2PowerCardW = applyConsumptionDisplayFloor(garage.boilerPower);

  setText("pvValue", invOff ? "--" : num(inverter.pvW, 0));
  setText("pvVoltage", invOff ? "--" : num(inverter.pvVoltage, 1));
  setText("dailyPV", invOff ? "--" : num(inverter.dailyPV, 1));
  setText(
    "lastUpdatePV",
    invOff ? "--:--:--" : safeText(inverter.lastUpdate, safeText(inverter.rtcTime, "--:--:--")),
  );

  setText("gridValue", invOff ? "--" : num(gridPowerCardW, 0));
  setText("gridVoltage", invOff ? "--" : num(inverter.lineVoltage, 1));
  setText("gridFrequency", invOff ? "--" : num(inverter.gridFrequency, 1));
  setText("dailyGrid", invOff ? "--" : num(inverter.dailyGrid, 1));
  setText("gridModeIndicator", invOff ? "mode: disabled" : `mode: ${safeText(inverter.mode)}`);
  setText("gridStateIndicator", invOff ? "state: ---" : `state: ${boolText(!!inverter.gridRelayOn)}`);
  setText("gridModalState", invOff ? "---" : boolText(!!inverter.gridRelayOn));
  setText("gridModalReason", invOff ? "module disabled" : uiText(inverter.gridRelayReason, "manual"));

  setText("loadValue", invOff ? "--" : num(loadPowerCardW, 0));
  setText("outputVoltage", invOff ? "--" : num(inverter.outputVoltage, 1));
  setText("outputFrequency", invOff ? "--" : num(inverter.outputFrequency, 1));
  setText("dailyHome", invOff ? "--" : num(inverter.dailyHome, 1));
  setText("loadModeIndicator", invOff ? "mode: disabled" : `mode: ${safeText(inverter.loadMode)}`);
  setText("loadStateIndicator", invOff ? "state: ---" : `state: ${boolText(!!inverter.loadRelayOn)}`);
  setText("loadModalState", invOff ? "---" : boolText(!!inverter.loadRelayOn));
  setText("loadModalReason", invOff ? "module disabled" : uiText(inverter.loadRelayReason, "manual"));

  setText("batteryValueMain", invOff ? "--" : num(inverter.batterySoc, 0));
  setText("batteryVoltage", invOff ? "--" : num(inverter.batteryVoltage, 1));
  setText("batteryPower", invOff ? "--" : num(inverter.batteryPower, 0));
  setText("inverterTemp", invOff ? "--" : num(inverter.inverterTemp, 1));

  setText("boiler1Power", loadOff ? "--" : num(boiler1PowerCardW, 0));
  setText("boiler1Mode", loadOff ? "disabled" : safeText(loadController.boiler1Mode));
  setText("boiler1Current", loadOff ? "--" : num(loadController.boilerCurrent, 2));
  setText("boiler1Daily", loadOff ? "--" : num(loadController.dailyBoiler, 0));
  setText("boiler1State", loadOff ? "---" : boolText(!!loadController.boiler1On));
  setText("boiler1ModalState", loadOff ? "---" : boolText(!!loadController.boiler1On));
  setText("boiler1ModalReason", loadOff ? "module disabled" : uiText(loadController.boiler1StateReason, "manual"));
  renderAutoWindowBlock("boiler1", {
    enabled: !!loadController.boiler1AutoWindowEnabled,
    start: safeText(loadController.boiler1AutoWindowStart, "00:00"),
    end: safeText(loadController.boiler1AutoWindowEnd, "00:00"),
    active: loadController.boiler1AutoWindowActive !== false,
  }, { disabled: loadOff });

  setText("pumpPower", loadOff ? "--" : num(pumpPowerCardW, 0));
  setText("pumpMode", loadOff ? "disabled" : safeText(loadController.pumpMode));
  setText("pumpCurrent", loadOff ? "--" : num(loadController.pumpCurrent, 2));
  setText("pumpDaily", loadOff ? "--" : num(loadController.dailyPump, 0));
  setText("pumpState", loadOff ? "---" : boolText(!!loadController.pumpOn));
  setText("pumpModalState", loadOff ? "---" : boolText(!!loadController.pumpOn));
  setText("pumpModalReason", loadOff ? "module disabled" : uiText(loadController.pumpStateReason, "manual"));
  renderAutoWindowBlock("pump", {
    enabled: !!loadController.pumpAutoWindowEnabled,
    start: safeText(loadController.pumpAutoWindowStart, "00:00"),
    end: safeText(loadController.pumpAutoWindowEnd, "00:00"),
    active: loadController.pumpAutoWindowActive !== false,
  }, { disabled: loadOff });

  setText("boiler2Power", garageOff ? "--" : num(boiler2PowerCardW, 0));
  setText("boiler2Mode", garageOff ? "disabled" : safeText(garage.boiler2Mode));
  setText("boiler2Current", garageOff ? "--" : num(garage.boilerCurrent, 2));
  setText("boiler2Daily", garageOff ? "--" : num(garage.dailyBoiler, 0));
  setText("boiler2State", garageOff ? "---" : boolText(!!garage.boiler2On));
  setText("boiler2ModalState", garageOff ? "---" : boolText(!!garage.boiler2On));
  setText("boiler2ModalReason", garageOff ? "module disabled" : uiText(garage.boiler2StateReason, "manual"));
  renderAutoWindowBlock("boiler2", {
    enabled: !!garage.boiler2AutoWindowEnabled,
    start: safeText(garage.boiler2AutoWindowStart, "00:00"),
    end: safeText(garage.boiler2AutoWindowEnd, "00:00"),
    active: garage.boiler2AutoWindowActive !== false,
  }, { disabled: garageOff });

  const gateNormalized = garageOff ? "unknown" : classifyGateState(garage);
  if (!garageOff) {
    if (state.gate.lastState !== gateNormalized) {
      const stamp = formatDateTimeFromStatus(garage.rtcDate, garage.rtcTime);
      if (gateNormalized === "open") {
        state.gate.lastOpenAt = stamp;
      } else if (gateNormalized === "closed") {
        state.gate.lastCloseAt = stamp;
      }
      state.gate.lastState = gateNormalized;
    }
  } else {
    state.gate.lastState = "";
  }

  setText("gateState", garageOff ? "disabled" : uiText(garage.gateState, gateNormalized));
  setText("gateReason", garageOff ? "module disabled" : uiText(garage.gateReason, "manual"));
  setText("gateLastOpen", garageOff ? "--" : safeText(state.gate.lastOpenAt, "--"));
  setText("gateLastClose", garageOff ? "--" : safeText(state.gate.lastCloseAt, "--"));
  setText("gateModalState", garageOff ? "disabled" : uiText(garage.gateState, gateNormalized));
  setText("gateModalReason", garageOff ? "module disabled" : uiText(garage.gateReason, "manual"));
  setGateActionButtonLabel(gateNormalized);
  const garageLightReason = garageOff ? "module disabled" : uiText(garage.garageLightReason, "manual");
  const garageLightOn = !garageOff && !!garage.garageLightOn;
  setText("garageLightState", garageOff ? "disabled" : boolText(garageLightOn));
  setGarageLightActionButtonState({
    disabled: garageOff,
    on: garageLightOn,
    reason: garageLightReason,
  });

  renderPowerScheme({
    inverter,
    loadController,
    garage,
    invOff,
    loadOff,
    garageOff,
    gridPresent,
  });

  applyCardNeonByPower("cardPv", inverter.pvW, !invOff);
  applyCardNeonByPower("cardGrid", inverter.gridW, !invOff);
  applyCardNeonByPower("cardLoad", loadPowerCardW, !invOff);
  applyCardNeonByPower("cardBattery", inverter.batteryPower, !invOff);
  applyCardNeonByPower("cardBoiler1", boiler1PowerCardW, !loadOff);
  applyCardNeonByPower("cardPump", pumpPowerCardW, !loadOff);
  applyCardNeonByPower("cardBoiler2", boiler2PowerCardW, !garageOff);
  applyCardNeonByPower("cardGate", CARD_NEON_POWER_THRESHOLD, !garageOff);

  renderClimateWideCard(inverter, loadController, garage);

  state.locks.inverterLoadOn = !invOff && !!inverter.loadOnLocked;
  state.locks.boiler1 = loadOff ? "NONE" : normalizeLockMode(loadController.boilerLock);
  state.locks.pump = loadOff ? "NONE" : normalizeLockMode(loadController.pumpLock);
  state.locks.boiler2 = garageOff ? "NONE" : normalizeLockMode(garage.boilerLock);

  setModeButtonLocked("btnLoadON", state.locks.inverterLoadOn);
  setModeButtonLocked("btnBoiler1ON", state.locks.boiler1 === "ON");
  setModeButtonLocked("btnBoiler1OFF", state.locks.boiler1 === "OFF");
  setModeButtonLocked("btnPumpON", state.locks.pump === "ON");
  setModeButtonLocked("btnPumpOFF", state.locks.pump === "OFF");
  setModeButtonLocked("btnBoiler2ON", state.locks.boiler2 === "ON");
  setModeButtonLocked("btnBoiler2OFF", state.locks.boiler2 === "OFF");

  updateButtonStates("[data-grid-mode]", safeText(inverter.mode));
  updateButtonStates("[data-load-mode]", safeText(inverter.loadMode));
  updateButtonStates("[data-boiler1-mode]", safeText(loadController.boiler1Mode));
  updateButtonStates("[data-pump-mode]", safeText(loadController.pumpMode));
  updateButtonStates("[data-boiler2-mode]", safeText(garage.boiler2Mode));

  applyLockedActiveButtons("btnLoad", state.locks.inverterLoadOn ? "ON" : "NONE");
  applyLockedActiveButtons("btnBoiler1", state.locks.boiler1);
  applyLockedActiveButtons("btnPump", state.locks.pump);
  applyLockedActiveButtons("btnBoiler2", state.locks.boiler2);

  setText("lastUpdateText", formatClockFromMs(status.updatedAtMs));
  setText("moduleInvUpdated", moduleUpdatedText(!invOff, inverter));
  setText("moduleLoadUpdated", moduleUpdatedText(!loadOff, loadController));
  setText("moduleGarageUpdated", moduleUpdatedText(!garageOff, garage));
}

function debounce(fn, waitMs) {
  let handle = null;
  return (...args) => {
    if (handle) clearTimeout(handle);
    handle = setTimeout(() => {
      handle = null;
      fn(...args);
    }, waitMs);
  };
}

function bindResizeRedraw() {
  const redraw = debounce(() => {
    if (isModalOpen("energyModal") && state.energy.last) {
      renderEnergyChart(state.energy.last);
    }
    if (isModalOpen("climateModal") && state.climate.last) {
      renderClimateChart();
    }
    if (isModalOpen("timelineModal")) {
      renderLoadTimeline();
    }
    if (isModalOpen("schemeModal")) {
      fitSchemeStageToViewport();
    }
  }, 120);
  window.addEventListener("resize", redraw);
}

function initUi() {
  initGraphSync();
  bindCardEvents();
  bindSchemeSwipe();
  bindModeButtons();
  bindSettings();
  bindEnergyControls();
  bindClimateControls();
  bindResizeRedraw();

  loadConfigFromBridge();
  syncConfigToForm();
  applyModuleCardStates();
  applyLiveCardStates(null);
  setText("pollText", `${clampPoll(state.config.pollIntervalSec)}s`);
  setText("moduleInvUpdated", "--:--:--");
  setText("moduleLoadUpdated", "--:--:--");
  setText("moduleGarageUpdated", "--:--:--");

  syncEnergyToolbar();
  syncClimateToolbar();
  updateClimateMetricButtons();
  syncChartsOrientation();

  requestStatus();
  restartPolling();
  restartSignalAgeTicker();
}

document.addEventListener("DOMContentLoaded", initUi);
