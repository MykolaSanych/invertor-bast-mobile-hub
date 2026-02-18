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

const state = {
  config: { ...DEFAULT_CONFIG },
  status: null,
  pending: new Map(),
  reqSeq: 0,
  pollHandle: null,
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
};

window.HubNative = {
  onStatusResult(requestId, payload) {
    const data = normalizePayload(payload);
    if (!data) {
      rejectPending(requestId, "Invalid status payload");
      return;
    }

    state.status = data;
    renderAll();
    trackConnectivityHealth(data);

    const pending = state.pending.get(requestId);
    if (pending) {
      state.pending.delete(requestId);
      pending.resolve(data);
    }
  },

  onStatusError(requestId, message) {
    rejectPending(requestId, message || "Status error");
    showToast(message || "Status request failed");
  },

  onActionResult(requestId, ok, message) {
    const pending = state.pending.get(requestId);
    if (!pending) return;

    state.pending.delete(requestId);
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
    state.pending.set(requestId, { resolve, reject });
    try {
      invoker(requestId);
    } catch (error) {
      state.pending.delete(requestId);
      reject(error);
    }
  });
}

function rejectPending(requestId, message) {
  const pending = state.pending.get(requestId);
  if (!pending) return;
  state.pending.delete(requestId);
  pending.reject(new Error(message || "Request failed"));
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

function pickNumber(values, fallback = 0) {
  for (const value of values) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return fallback;
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
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, "0");
  const d = String(now.getDate()).padStart(2, "0");
  const hh = String(now.getHours()).padStart(2, "0");
  const mm = String(now.getMinutes()).padStart(2, "0");
  const ss = String(now.getSeconds()).padStart(2, "0");
  return `${y}-${m}-${d} ${hh}:${mm}:${ss}`;
}

function classifyGateState(garage) {
  const openPin = Number(garage?.gateOpenPin);
  const closedPin = Number(garage?.gateClosedPin);
  const hasPins = Number.isFinite(openPin) && Number.isFinite(closedPin) && openPin >= 0 && closedPin >= 0;
  if (hasPins) {
    const openActive = openPin === 0;
    const closedActive = closedPin === 0;
    if (closedActive && !openActive) return "closed";
    if (openActive && !closedActive) return "open";
    return "moving";
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
  return modalId === "energyModal" || modalId === "climateModal" || modalId === "timelineModal";
}

function anyLandscapeModalOpen() {
  return ["energyModal", "climateModal", "timelineModal"].some((id) => isModalOpen(id));
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
  modal.classList.add("is-open");
  if (modalNeedsLandscape(id)) {
    syncChartsOrientation();
  }
}

function closeModal(id) {
  const modal = document.getElementById(id);
  if (!modal) return;
  modal.classList.remove("is-open");
  if (modalNeedsLandscape(id)) {
    syncChartsOrientation();
  }
}

function closeAllModals() {
  document.querySelectorAll(".modal-root").forEach((modal) => {
    modal.classList.remove("is-open");
  });
  syncChartsOrientation();
}

function isModalOpen(id) {
  const modal = document.getElementById(id);
  return !!modal && modal.classList.contains("is-open");
}

function bindCardEvents() {
  const modalBindings = [
    ["cardGrid", "gridModal"],
    ["cardLoad", "loadModal"],
    ["cardBoiler1", "boiler1Modal"],
    ["cardPump", "pumpModal"],
    ["cardBoiler2", "boiler2Modal"],
    ["cardGate", "gateModal"],
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

  const headerTitle = document.getElementById("appHeaderTitle");
  if (headerTitle) {
    headerTitle.addEventListener("click", () => {
      openTimelineModal();
    });
  }

  const timelineReloadBtn = document.getElementById("timelineReloadBtn");
  if (timelineReloadBtn) {
    timelineReloadBtn.addEventListener("click", () => {
      loadLoadTimelineHistory({ force: true });
    });
  }

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

  const gateActionBtn = document.getElementById("gateActionBtn");
  if (gateActionBtn) {
    gateActionBtn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      triggerGate();
    });
  }
}

function bindSettings() {
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

  try {
    await bridgeRequest("status", (requestId) => {
      window.AndroidHub.fetchStatus(requestId);
    });
  } catch (error) {
    showToast(`status failed: ${error.message}`);
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
    showToast("No modules reachable. Check ZeroTier IPs in settings.");
  }
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

function flashCard(cardId) {
  const card = document.getElementById(cardId);
  if (!card) return;
  if (card.classList.contains("module-disabled") || card.classList.contains("card-stale")) return;
  card.classList.remove("card-flash");
  // Restart animation on each refresh cycle.
  void card.offsetWidth;
  card.classList.add("card-flash");
}

function applyLiveCardStates(status) {
  const hasInverterData = !!(state.config.inverterEnabled && status?.inverter);
  const hasLoadData = !!(state.config.loadControllerEnabled && status?.loadController);
  const hasGarageData = !!(state.config.garageEnabled && status?.garage);
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

  if (hasInverterData) {
    flashCard("cardPv");
    flashCard("cardGrid");
    flashCard("cardBattery");
    flashCard("cardLoad");
  }
  if (hasLoadData) {
    flashCard("cardBoiler1");
    flashCard("cardPump");
  }
  if (hasGarageData) {
    flashCard("cardBoiler2");
    flashCard("cardGate");
  }
  if (hasClimateData) {
    flashCard("climateWideCard");
  }
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

async function loadEnergyData() {
  if (!hasBridge()) {
    drawEmptyCanvas(document.getElementById("energyCanvas"), "Bridge unavailable");
    return;
  }

  const period = selectedRadioValue("energyPeriod", state.energy.period);
  state.energy.period = period;
  syncEnergyToolbar();
  setText("energyChartTitle", "energy graph - loading...");

  try {
    let payload;
    if (period === "daily") {
      const date = document.getElementById("energyDateInput")?.value || todayIso();
      payload = await bridgeRequest("daily", (requestId) => {
        window.AndroidHub.fetchInverterDaily(date, requestId);
      });
    } else if (period === "monthly") {
      const month = document.getElementById("energyMonthInput")?.value || currentMonthIso();
      payload = await bridgeRequest("monthly", (requestId) => {
        window.AndroidHub.fetchInverterMonthly(month, requestId);
      });
    } else {
      payload = await bridgeRequest("yearly", (requestId) => {
        window.AndroidHub.fetchInverterYearly(requestId);
      });
    }

    const model = normalizeEnergyPayload(period, payload);
    state.energy.last = model;
    renderEnergyChart(model);
  } catch (error) {
    drawEmptyCanvas(document.getElementById("energyCanvas"), "Failed to load data");
    setText("energyChartTitle", "energy graph - error");
    showToast(`energy data failed: ${error.message}`);
  }
}

function normalizeClimatePayload(period, payload) {
  if (!payload || typeof payload !== "object") {
    throw new Error("Empty payload");
  }

  if (period === "daily") {
    const rows = Array.isArray(payload.hours) ? payload.hours : [];
    const labels = [];
    const tempInt = [];
    const humInt = [];
    const pressInt = [];
    const tempExt = [];
    const humExt = [];
    const pressExt = [];

    if (rows.length === 0) {
      for (let i = 0; i < 24; i += 1) {
        labels.push(`${i}:00`);
        tempInt.push(null);
        humInt.push(null);
        pressInt.push(null);
        tempExt.push(null);
        humExt.push(null);
        pressExt.push(null);
      }
    } else {
      rows.forEach((row, idx) => {
        labels.push(safeText(row.hour_label, `${idx}:00`));
        tempInt.push(Number.isFinite(Number(row.temp)) ? Number(row.temp) : null);
        humInt.push(Number.isFinite(Number(row.hum)) ? Number(row.hum) : null);
        pressInt.push(Number.isFinite(Number(row.press)) ? Number(row.press) : null);
        tempExt.push(Number.isFinite(Number(row.temp_ext)) ? Number(row.temp_ext) : null);
        humExt.push(Number.isFinite(Number(row.hum_ext)) ? Number(row.hum_ext) : null);
        pressExt.push(Number.isFinite(Number(row.press_ext)) ? Number(row.press_ext) : null);
      });
    }

    const date = safeText(payload.date, document.getElementById("climateDateInput")?.value || todayIso());
    return {
      title: `climate graph - day ${date}`,
      labels,
      tempInt,
      humInt,
      pressInt,
      tempExt,
      humExt,
      pressExt,
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

    rows.forEach((row, idx) => {
      labels.push(safeText(row.day, String(idx + 1)));
      tempInt.push(Number.isFinite(Number(row.temp)) ? Number(row.temp) : null);
      humInt.push(Number.isFinite(Number(row.hum)) ? Number(row.hum) : null);
      pressInt.push(Number.isFinite(Number(row.press)) ? Number(row.press) : null);
      tempExt.push(Number.isFinite(Number(row.temp_ext)) ? Number(row.temp_ext) : null);
      humExt.push(Number.isFinite(Number(row.hum_ext)) ? Number(row.hum_ext) : null);
      pressExt.push(Number.isFinite(Number(row.press_ext)) ? Number(row.press_ext) : null);
    });

    const month = safeText(payload.month, document.getElementById("climateMonthInput")?.value || currentMonthIso());
    return {
      title: `climate graph - month ${month}`,
      labels,
      tempInt,
      humInt,
      pressInt,
      tempExt,
      humExt,
      pressExt,
    };
  }

  const labels = Array.isArray(payload.months) ? payload.months.map((v) => String(v)) : [];
  const year = safeText(payload.current_year, String(new Date().getFullYear()));
  return {
    title: `climate graph - year ${year}`,
    labels,
    tempInt: Array.isArray(payload.temp) ? payload.temp.map((v) => toFiniteNumber(v, 0)) : [],
    humInt: Array.isArray(payload.hum) ? payload.hum.map((v) => toFiniteNumber(v, 0)) : [],
    pressInt: Array.isArray(payload.press) ? payload.press.map((v) => toFiniteNumber(v, 0)) : [],
    tempExt: Array.isArray(payload.temp_ext) ? payload.temp_ext.map((v) => toFiniteNumber(v, 0)) : [],
    humExt: Array.isArray(payload.hum_ext) ? payload.hum_ext.map((v) => toFiniteNumber(v, 0)) : [],
    pressExt: Array.isArray(payload.press_ext) ? payload.press_ext.map((v) => toFiniteNumber(v, 0)) : [],
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
      internal: model.humInt,
      external: model.humExt,
    };
  }
  if (metric === "press") {
    return {
      internal: model.pressInt,
      external: model.pressExt,
    };
  }
  return {
    internal: model.tempInt,
    external: model.tempExt,
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
  const series = [
    {
      label: `internal ${meta.label} (${meta.unit})`,
      color: "#7a5cff",
      data: selected.internal,
      lineWidth: 2.2,
      pointRadius: 1.4,
      fillAlpha: 0.2,
    },
    {
      label: `external ${meta.label} (${meta.unit})`,
      color: "#00e6ff",
      data: selected.external,
      lineWidth: 2.2,
      pointRadius: 1.4,
      fillAlpha: 0.14,
    },
  ];

  setText("climateChartTitle", `${model.title} - ${meta.label}`);
  drawLineChart(canvas, model.labels, series, {
    yTitle: `${meta.label} (${meta.unit})`,
  });
  renderLegend("climateLegend", series);
}

async function loadClimateData() {
  if (!hasBridge()) {
    drawEmptyCanvas(document.getElementById("climateCanvas"), "Bridge unavailable");
    return;
  }

  const period = selectedRadioValue("climatePeriod", state.climate.period);
  state.climate.period = period;
  syncClimateToolbar();
  setText("climateChartTitle", "climate graph - loading...");

  try {
    let payload;
    if (period === "daily") {
      const date = document.getElementById("climateDateInput")?.value || todayIso();
      payload = await bridgeRequest("climate-daily", (requestId) => {
        window.AndroidHub.fetchInverterDaily(date, requestId);
      });
    } else if (period === "monthly") {
      const month = document.getElementById("climateMonthInput")?.value || currentMonthIso();
      payload = await bridgeRequest("climate-monthly", (requestId) => {
        window.AndroidHub.fetchInverterMonthly(month, requestId);
      });
    } else {
      payload = await bridgeRequest("climate-yearly", (requestId) => {
        window.AndroidHub.fetchInverterYearly(requestId);
      });
    }

    const model = normalizeClimatePayload(period, payload);
    state.climate.last = model;
    renderClimateChart();
  } catch (error) {
    drawEmptyCanvas(document.getElementById("climateCanvas"), "Failed to load data");
    setText("climateChartTitle", "climate graph - error");
    showToast(`climate data failed: ${error.message}`);
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
    loadBtn.addEventListener("click", loadEnergyData);
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
    loadBtn.addEventListener("click", loadClimateData);
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

  const invIntAvailable = !!(
    inverter &&
    (inverter.bmeAvailable ||
      inverter.bmeTemp !== null ||
      inverter.bmeHum !== null ||
      inverter.bmePress !== null)
  );
  if (invIntAvailable) {
    zones.push(climateZoneRow("гардероб", inverter.bmeTemp, inverter.bmeHum, inverter.bmePress));
  }

  const invExtAvailable = !!(
    inverter &&
    (inverter.bmeExtAvailable ||
      inverter.bmeExtTemp !== null ||
      inverter.bmeExtHum !== null ||
      inverter.bmeExtPress !== null)
  );
  if (invExtAvailable) {
    zones.push(
      climateZoneRow("зовні", inverter.bmeExtTemp, inverter.bmeExtHum, inverter.bmeExtPress),
    );
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
  const topLoad = pickNumber([inverter.loadW, loadController.loadW, garage.loadW]);
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

  setText(
    "realTime",
    safeText(inverter.rtcTime || loadController.rtcTime || garage.rtcTime, "--:--:--"),
  );
  setText("lineVoltage", num(topLineVoltage, 1));
  setText("pvPowerTop", num(topPv, 0));
  setText("gridPowerTop", num(topGrid, 0));
  setText("loadPowerTop", num(topLoad, 0));
  setText("batterySocTop", num(topBatSoc, 0));
  setText("batteryPowerTop", num(topBatPower, 0));
  setWifi(topWifi);

  const invOff = !state.config.inverterEnabled;
  const loadOff = !state.config.loadControllerEnabled;
  const garageOff = !state.config.garageEnabled;

  if (!loadOff) {
    recordLoadTimelineSample(loadController);
  }

  setText("pvValue", invOff ? "--" : num(inverter.pvW, 0));
  setText("pvVoltage", invOff ? "--" : num(inverter.pvVoltage, 1));
  setText("dailyPV", invOff ? "--" : num(inverter.dailyPV, 1));
  setText(
    "lastUpdatePV",
    invOff ? "--:--:--" : safeText(inverter.lastUpdate, safeText(inverter.rtcTime, "--:--:--")),
  );

  setText("gridValue", invOff ? "--" : num(inverter.gridW, 0));
  setText("gridVoltage", invOff ? "--" : num(inverter.lineVoltage, 1));
  setText("gridFrequency", invOff ? "--" : num(inverter.gridFrequency, 1));
  setText("dailyGrid", invOff ? "--" : num(inverter.dailyGrid, 1));
  setText("gridModeIndicator", invOff ? "mode: disabled" : `mode: ${safeText(inverter.mode)}`);
  setText("gridStateIndicator", invOff ? "state: ---" : `state: ${boolText(!!inverter.gridRelayOn)}`);
  setText("gridModalState", invOff ? "---" : boolText(!!inverter.gridRelayOn));
  setText("gridModalReason", invOff ? "module disabled" : safeText(inverter.gridRelayReason));

  setText("loadValue", invOff ? "--" : num(inverter.loadW, 0));
  setText("outputVoltage", invOff ? "--" : num(inverter.outputVoltage, 1));
  setText("outputFrequency", invOff ? "--" : num(inverter.outputFrequency, 1));
  setText("dailyHome", invOff ? "--" : num(inverter.dailyHome, 1));
  setText("loadModeIndicator", invOff ? "mode: disabled" : `mode: ${safeText(inverter.loadMode)}`);
  setText("loadStateIndicator", invOff ? "state: ---" : `state: ${boolText(!!inverter.loadRelayOn)}`);
  setText("loadModalState", invOff ? "---" : boolText(!!inverter.loadRelayOn));
  setText("loadModalReason", invOff ? "module disabled" : safeText(inverter.loadRelayReason));

  setText("batteryValueMain", invOff ? "--" : num(inverter.batterySoc, 0));
  setText("batteryVoltage", invOff ? "--" : num(inverter.batteryVoltage, 1));
  setText("batteryPower", invOff ? "--" : num(inverter.batteryPower, 0));
  setText("inverterTemp", invOff ? "--" : num(inverter.inverterTemp, 1));

  setText("boiler1Power", loadOff ? "--" : num(loadController.boilerPower, 0));
  setText("boiler1Mode", loadOff ? "disabled" : safeText(loadController.boiler1Mode));
  setText("boiler1Current", loadOff ? "--" : num(loadController.boilerCurrent, 2));
  setText("boiler1Daily", loadOff ? "--" : num(loadController.dailyBoiler, 0));
  setText("boiler1State", loadOff ? "---" : boolText(!!loadController.boiler1On));
  setText("boiler1ModalState", loadOff ? "---" : boolText(!!loadController.boiler1On));
  setText("boiler1ModalReason", loadOff ? "module disabled" : safeText(loadController.boiler1StateReason));

  setText("pumpPower", loadOff ? "--" : num(loadController.pumpPower, 0));
  setText("pumpMode", loadOff ? "disabled" : safeText(loadController.pumpMode));
  setText("pumpCurrent", loadOff ? "--" : num(loadController.pumpCurrent, 2));
  setText("pumpDaily", loadOff ? "--" : num(loadController.dailyPump, 0));
  setText("pumpState", loadOff ? "---" : boolText(!!loadController.pumpOn));
  setText("pumpModalState", loadOff ? "---" : boolText(!!loadController.pumpOn));
  setText("pumpModalReason", loadOff ? "module disabled" : safeText(loadController.pumpStateReason));

  setText("boiler2Power", garageOff ? "--" : num(garage.boilerPower, 0));
  setText("boiler2Mode", garageOff ? "disabled" : safeText(garage.boiler2Mode));
  setText("boiler2Current", garageOff ? "--" : num(garage.boilerCurrent, 2));
  setText("boiler2Daily", garageOff ? "--" : num(garage.dailyBoiler, 0));
  setText("boiler2State", garageOff ? "---" : boolText(!!garage.boiler2On));
  setText("boiler2ModalState", garageOff ? "---" : boolText(!!garage.boiler2On));
  setText("boiler2ModalReason", garageOff ? "module disabled" : safeText(garage.boiler2StateReason));

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

  setText("gateState", garageOff ? "disabled" : safeText(garage.gateState, gateNormalized));
  setText("gateReason", garageOff ? "module disabled" : safeText(garage.gateReason));
  setText("gateLastOpen", garageOff ? "--" : safeText(state.gate.lastOpenAt, "--"));
  setText("gateLastClose", garageOff ? "--" : safeText(state.gate.lastCloseAt, "--"));
  setText("gateModalState", garageOff ? "disabled" : safeText(garage.gateState, gateNormalized));
  setText("gateModalReason", garageOff ? "module disabled" : safeText(garage.gateReason));
  setGateActionButtonLabel(gateNormalized);

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
  }, 120);
  window.addEventListener("resize", redraw);
}

function initUi() {
  bindCardEvents();
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

  syncEnergyToolbar();
  syncClimateToolbar();
  updateClimateMetricButtons();
  syncChartsOrientation();

  requestStatus();
  restartPolling();
}

document.addEventListener("DOMContentLoaded", initUi);
