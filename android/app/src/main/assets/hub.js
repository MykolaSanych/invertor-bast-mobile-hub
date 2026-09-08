const DEFAULT_CONFIG = {
  inverterBaseUrl: "http://192.168.1.2",
  inverterPassword: "keP8OsYo_MbyuWMkbSuiDe8N",
  loadControllerBaseUrl: "http://192.168.1.3",
  loadControllerPassword: "keP8OsYo_MbyuWMkbSuiDe8N",
  garageBaseUrl: "http://192.168.1.4",
  garagePassword: "keP8OsYo_MbyuWMkbSuiDe8N",
  pollIntervalSec: 5,
  inverterEnabled: true,
  loadControllerEnabled: true,
  garageEnabled: true,
  realtimeMonitorEnabled: false,
  realtimePollIntervalSec: 5,
  graphSyncIntervalMin: 15,
  graphSyncPerCycle: 2,
  graphSyncRequestFetchLimit: 365,
  notifyPvGeneration: true,
  notifyGridRelay: true,
  notifyGridPresence: true,
  notifyGridMode: true,
  notifyLoadMode: true,
  notifyBoiler1Mode: true,
  notifyPumpMode: true,
  notifyBoiler2Mode: true,
  notifyGateState: true,
  notifyModuleOffline: true,
  notifyPowerOverload: true,
  notifyLogicUnstable: true,
  interfaceMode: "pro",
};

const LOAD_TIMELINE_POWER_ON_THRESHOLD = 50;
// Насос споживає десятки ват навіть у режимі очікування, тому для нього
// окремий, вищий поріг "увімкнено" — синхронізований з прошивкою load_controller.
const LOAD_TIMELINE_PUMP_POWER_ON_THRESHOLD = 150;
const LOAD_TIMELINE_HISTORY_REFRESH_MS = 60 * 1000;
const LOAD_TIMELINE_VISIBLE_HOURS = 6;
const LOAD_TIMELINE_MAX_SAMPLES = 1600;
const AUTOMATION_HISTORY_REFRESH_MS = 60 * 1000;
const AUTOMATION_HISTORY_DEFAULT_HOURS = 6;
const LOGIC_HISTORY_DEFAULT_HOURS = 3;
const LOGIC_UNSTABLE_TRANSITIONS = 4;
const LOGIC_UNSTABLE_WINDOW_MS = 30 * 60 * 1000;
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
const GRAPH_CACHE_SCHEMA_VERSION = 2;
const GRAPH_CACHE_MAX_ENTRIES_PER_TYPE = 180;
const ANALYTICS_CACHE_STORAGE_KEY = "hub.analyticsPayloadCache.v1";
const ANALYTICS_CACHE_SCHEMA_VERSION = 2;
const ANALYTICS_CACHE_MAX_ENTRIES = 120;
const MODULE_HISTORY_CACHE_STORAGE_KEY = "hub.moduleHistoryCache.v1";
const MODULE_HISTORY_CACHE_SCHEMA_VERSION = 1;
const MODULE_HISTORY_CACHE_MAX_ENTRIES = 48;
const DAILY_ARCHIVE_CACHE_STORAGE_KEY = "hub.dailyArchiveCache.v1";
const DAILY_ARCHIVE_CACHE_SCHEMA_VERSION = 2;
const DAILY_ARCHIVE_CACHE_MAX_ENTRIES = 540;
const DAILY_ARCHIVE_DATES_STALE_MS = 20 * 60 * 1000;
const DAILY_ARCHIVE_BACKGROUND_FETCH_LIMIT = 2;
const DAILY_ARCHIVE_REQUEST_FETCH_LIMIT = 365;
const GRAPH_SYNC_MIN_GLOBAL_GAP_MS = 3500;
const GRAPH_SYNC_MIN_PER_KEY_GAP_MS = 25000;
const GRAPH_SYNC_INTERVAL_MS = 15 * 60 * 1000;
const GRAPH_SYNC_INTERVAL_JITTER_MS = 45 * 1000;
const GRAPH_SYNC_EAGER_RETRY_MS = 12 * 1000;
const GRAPH_SYNC_BUSY_RETRY_MS = 45 * 1000;
const GRAPH_SYNC_MAX_ITEMS_PER_CYCLE = 2;
const GRAPH_SYNC_VIEW_WINDOW_MS = 14 * 24 * 60 * 60 * 1000;
const TIMELINE_CACHE_STORAGE_KEY = "hub.timelineCache.v1";
const TIMELINE_CACHE_SCHEMA_VERSION = 1;
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
  uiMode: "pro",
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
  analyticsCache: {
    loaded: false,
    persistHandle: null,
    entries: {},
  },
  moduleHistoryCache: {
    loaded: false,
    persistHandle: null,
    entries: {},
  },
  dailyArchiveCache: {
    loaded: false,
    persistHandle: null,
    dates: [],
    datesFetchedAtMs: 0,
    entries: {},
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
    // Абсолютний час (мс, локальний годинник телефону), коли спрацює
    // автовимкнення світла після відкриття воріт; null - таймер неактивний.
    // Полічений один раз при кожному оновленні статусу з garage_light_auto_off_in_sec,
    // а відлік на екрані йде щосекунди локально (updateGarageLightCountdown).
    lightAutoOffAtMs: null,
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
    persistHandle: null,
    // "" означає "сьогодні, наживо"; конкретна дата "YYYY-MM-DD" - перегляд
    // збереженої на флешці load_controller доби без живого дозапису.
    selectedDate: "",
  },
  events: {
    items: [],
    loadedAtMs: 0,
    viewMode: "all",
    cardKey: "",
    cardDate: "",
  },
  automationHistory: {
    items: [],
    loadedAtMs: 0,
    hours: 0,
  },
  capabilities: null,
  alerts: {
    active: [],
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
  logic: {
    currentKey: "",
    returnModalId: "",
    formDirty: false,
    historyHours: LOGIC_HISTORY_DEFAULT_HOURS,
    // Індекси правил, розгорнутих користувачем у поточному відкритті модалки
    // - flowEl повністю перемальовується на кожне оновлення статусу, тому без
    // цього стан "розгорнуто" скидався б за кожним циклом опитування.
    expandedRules: new Set(),
  },
  schemeControlLandscape: false,
  schemeControlReturnToSchemeModalId: "",
  schemeControlPendingModalId: "",
};

window.HubNative = {
  onStatusResult(requestId, payload) {
    const data = normalizePayload(payload);
    if (!data) {
      rejectPending(requestId, "Некоректні дані статусу");
      return;
    }

    const isPartial = isPartialStatusRequestId(requestId);
    updateModuleSignalTimes(data);
    if (!isPartial) {
      updateModuleMissCounts(data);
    }
    state.status = mergeStatusForUi(data, { isPartial });
    state.capabilities = buildHubCapabilities(state.status);
    if (!isPartial) {
      appendAutomationHistorySampleFromStatus(state.status);
    }
    renderAll();
    trackConnectivityHealth(data);
    if (!isPartial) {
      void ensureAutomationHistory(AUTOMATION_HISTORY_DEFAULT_HOURS, { silent: true });
    }

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
    rejectPending(requestId, message || "Помилка статусу");
    applyLiveCardStates(state.status, { flash: false });
    showToast(message || "Не вдалося отримати статус");
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
      pending.reject(new Error(message || "Команда не виконана"));
    }
  },

  onDataResult(requestId, payload) {
    const data = normalizePayload(payload);
    if (!data) {
      rejectPending(requestId, "Некоректні дані");
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
    rejectPending(requestId, message || "Не вдалося отримати дані");
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
    return Promise.reject(new Error("Міст недоступний"));
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
  pending.reject(new Error(message || "Запит не виконано"));
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

function pruneTimestampedCacheEntries(entries, maxEntries) {
  const slot = entries && typeof entries === "object" ? entries : {};
  const keys = Object.keys(slot);
  if (keys.length <= maxEntries) return;

  keys.sort((a, b) => {
    const ea = slot[a] || {};
    const eb = slot[b] || {};
    const sa = Number(ea.viewedAtMs || ea.fetchedAtMs || 0);
    const sb = Number(eb.viewedAtMs || eb.fetchedAtMs || 0);
    return sb - sa;
  });

  keys.slice(maxEntries).forEach((key) => {
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

function persistAnalyticsCacheNow() {
  if (!state.analyticsCache.loaded) return;
  try {
    const payload = {
      version: ANALYTICS_CACHE_SCHEMA_VERSION,
      entries: state.analyticsCache.entries || {},
      savedAtMs: Date.now(),
    };
    localStorage.setItem(ANALYTICS_CACHE_STORAGE_KEY, JSON.stringify(payload));
  } catch (error) {
    // Ignore storage errors (quota/private mode).
  }
}

function scheduleAnalyticsCachePersist() {
  if (state.analyticsCache.persistHandle) {
    clearTimeout(state.analyticsCache.persistHandle);
  }
  state.analyticsCache.persistHandle = setTimeout(() => {
    state.analyticsCache.persistHandle = null;
    persistAnalyticsCacheNow();
  }, 400);
}

function loadAnalyticsCacheFromStorage() {
  if (state.analyticsCache.loaded) return;
  state.analyticsCache.loaded = true;
  state.analyticsCache.entries = {};
  try {
    const raw = localStorage.getItem(ANALYTICS_CACHE_STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return;
    if (Number(parsed.version) !== ANALYTICS_CACHE_SCHEMA_VERSION) return;
    if (parsed.entries && typeof parsed.entries === "object") {
      state.analyticsCache.entries = parsed.entries;
    }
    pruneTimestampedCacheEntries(state.analyticsCache.entries, ANALYTICS_CACHE_MAX_ENTRIES);
  } catch (error) {
    // Ignore invalid cache payload.
  }
}

function getAnalyticsCacheEntry(period, selector) {
  loadAnalyticsCacheFromStorage();
  const key = graphEntryKey(period, selector);
  const entry = state.analyticsCache.entries[key];
  if (!entry || typeof entry !== "object" || !entry.payload) return null;
  return entry;
}

function touchAnalyticsCacheEntry(period, selector, viewedAtMs = Date.now()) {
  loadAnalyticsCacheFromStorage();
  const key = graphEntryKey(period, selector);
  const entry = state.analyticsCache.entries[key];
  if (!entry || typeof entry !== "object") return;
  entry.viewedAtMs = viewedAtMs;
  scheduleAnalyticsCachePersist();
}

function upsertAnalyticsCacheEntry(period, selector, payload, fetchedAtMs = Date.now()) {
  loadAnalyticsCacheFromStorage();
  const key = graphEntryKey(period, selector);
  const previous = state.analyticsCache.entries[key];
  const viewedAtMs = Number(previous?.viewedAtMs || fetchedAtMs);
  state.analyticsCache.entries[key] = {
    payload,
    fetchedAtMs,
    viewedAtMs,
  };
  pruneTimestampedCacheEntries(state.analyticsCache.entries, ANALYTICS_CACHE_MAX_ENTRIES);
  scheduleAnalyticsCachePersist();
}

function isAnalyticsCacheStale(entry, period, nowMs = Date.now()) {
  if (!entry || typeof entry !== "object") return true;
  const fetchedAtMs = Number(entry.fetchedAtMs || 0);
  if (!Number.isFinite(fetchedAtMs) || fetchedAtMs <= 0) return true;
  return nowMs - fetchedAtMs > graphCacheTtlMs("energy", period);
}

function moduleHistoryCacheKey(moduleKey, date) {
  return `${safeText(moduleKey, "module")}::${safeText(date, "current")}`;
}

function moduleHistoryCacheTtlMs(date) {
  const day = safeText(date, "");
  if (day && day !== todayIso()) {
    return 30 * 24 * 60 * 60 * 1000;
  }
  return 5 * 60 * 1000;
}

function persistModuleHistoryCacheNow() {
  if (!state.moduleHistoryCache.loaded) return;
  try {
    const payload = {
      version: MODULE_HISTORY_CACHE_SCHEMA_VERSION,
      entries: state.moduleHistoryCache.entries || {},
      savedAtMs: Date.now(),
    };
    localStorage.setItem(MODULE_HISTORY_CACHE_STORAGE_KEY, JSON.stringify(payload));
  } catch (error) {
    // Ignore storage errors (quota/private mode).
  }
}

function scheduleModuleHistoryCachePersist() {
  if (state.moduleHistoryCache.persistHandle) {
    clearTimeout(state.moduleHistoryCache.persistHandle);
  }
  state.moduleHistoryCache.persistHandle = setTimeout(() => {
    state.moduleHistoryCache.persistHandle = null;
    persistModuleHistoryCacheNow();
  }, 400);
}

function loadModuleHistoryCacheFromStorage() {
  if (state.moduleHistoryCache.loaded) return;
  state.moduleHistoryCache.loaded = true;
  state.moduleHistoryCache.entries = {};
  try {
    const raw = localStorage.getItem(MODULE_HISTORY_CACHE_STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return;
    if (Number(parsed.version) !== MODULE_HISTORY_CACHE_SCHEMA_VERSION) return;
    if (parsed.entries && typeof parsed.entries === "object") {
      state.moduleHistoryCache.entries = parsed.entries;
    }
    pruneTimestampedCacheEntries(state.moduleHistoryCache.entries, MODULE_HISTORY_CACHE_MAX_ENTRIES);
  } catch (error) {
    // Ignore invalid cache payload.
  }
}

function getModuleHistoryCacheEntry(moduleKey, date) {
  loadModuleHistoryCacheFromStorage();
  const key = moduleHistoryCacheKey(moduleKey, date);
  const entry = state.moduleHistoryCache.entries[key];
  if (!entry || typeof entry !== "object" || !entry.payload) return null;
  return entry;
}

function upsertModuleHistoryCacheEntry(moduleKey, date, payload, fetchedAtMs = Date.now()) {
  loadModuleHistoryCacheFromStorage();
  const payloadDate = safeText(payload?.date, date || "current");
  const key = moduleHistoryCacheKey(moduleKey, payloadDate);
  const previous = state.moduleHistoryCache.entries[key];
  const viewedAtMs = Number(previous?.viewedAtMs || fetchedAtMs);
  state.moduleHistoryCache.entries[key] = {
    payload,
    fetchedAtMs,
    viewedAtMs,
  };
  pruneTimestampedCacheEntries(state.moduleHistoryCache.entries, MODULE_HISTORY_CACHE_MAX_ENTRIES);
  scheduleModuleHistoryCachePersist();
}

function touchModuleHistoryCacheEntry(moduleKey, date, viewedAtMs = Date.now()) {
  loadModuleHistoryCacheFromStorage();
  const key = moduleHistoryCacheKey(moduleKey, date);
  const entry = state.moduleHistoryCache.entries[key];
  if (!entry || typeof entry !== "object") return;
  entry.viewedAtMs = viewedAtMs;
  scheduleModuleHistoryCachePersist();
}

function isModuleHistoryCacheStale(entry, date, nowMs = Date.now()) {
  if (!entry || typeof entry !== "object") return true;
  const fetchedAtMs = Number(entry.fetchedAtMs || 0);
  if (!Number.isFinite(fetchedAtMs) || fetchedAtMs <= 0) return true;
  return nowMs - fetchedAtMs > moduleHistoryCacheTtlMs(date);
}

function normalizeIsoDate(value) {
  const date = safeText(value, "");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return "";
  return date;
}

function normalizeIsoMonth(value) {
  const month = safeText(value, "");
  if (!/^\d{4}-\d{2}$/.test(month)) return "";
  return month;
}

function sortIsoDatesDesc(values) {
  const unique = Array.from(new Set((Array.isArray(values) ? values : []).map((item) => normalizeIsoDate(item)).filter(Boolean)));
  unique.sort((a, b) => (a < b ? 1 : a > b ? -1 : 0));
  return unique;
}

function parseInverterDatesPayload(payload) {
  if (!Array.isArray(payload)) return [];
  const dates = payload
    .map((row) => normalizeIsoDate(row?.date || row?.value || row?.day || row))
    .filter(Boolean);
  return sortIsoDatesDesc(dates);
}

function persistDailyArchiveCacheNow() {
  if (!state.dailyArchiveCache.loaded) return;
  try {
    const payload = {
      version: DAILY_ARCHIVE_CACHE_SCHEMA_VERSION,
      dates: state.dailyArchiveCache.dates || [],
      datesFetchedAtMs: Number(state.dailyArchiveCache.datesFetchedAtMs || 0),
      entries: state.dailyArchiveCache.entries || {},
      savedAtMs: Date.now(),
    };
    localStorage.setItem(DAILY_ARCHIVE_CACHE_STORAGE_KEY, JSON.stringify(payload));
  } catch (error) {
    // Ignore storage errors (quota/private mode).
  }
}

function scheduleDailyArchiveCachePersist() {
  if (state.dailyArchiveCache.persistHandle) {
    clearTimeout(state.dailyArchiveCache.persistHandle);
  }
  state.dailyArchiveCache.persistHandle = setTimeout(() => {
    state.dailyArchiveCache.persistHandle = null;
    persistDailyArchiveCacheNow();
  }, 450);
}

function pruneDailyArchiveEntries() {
  const entries = state.dailyArchiveCache.entries || {};
  const dates = Object.keys(entries)
    .map((date) => normalizeIsoDate(date))
    .filter(Boolean)
    .sort((a, b) => (a < b ? 1 : a > b ? -1 : 0));
  if (dates.length <= DAILY_ARCHIVE_CACHE_MAX_ENTRIES) return;
  dates.slice(DAILY_ARCHIVE_CACHE_MAX_ENTRIES).forEach((date) => {
    delete entries[date];
  });
}

function loadDailyArchiveCacheFromStorage() {
  if (state.dailyArchiveCache.loaded) return;
  state.dailyArchiveCache.loaded = true;
  state.dailyArchiveCache.dates = [];
  state.dailyArchiveCache.datesFetchedAtMs = 0;
  state.dailyArchiveCache.entries = {};
  try {
    const raw = localStorage.getItem(DAILY_ARCHIVE_CACHE_STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return;
    if (Number(parsed.version) !== DAILY_ARCHIVE_CACHE_SCHEMA_VERSION) return;
    state.dailyArchiveCache.dates = sortIsoDatesDesc(parsed.dates);
    state.dailyArchiveCache.datesFetchedAtMs = Number(parsed.datesFetchedAtMs || 0);
    if (parsed.entries && typeof parsed.entries === "object") {
      Object.entries(parsed.entries).forEach(([key, value]) => {
        const date = normalizeIsoDate(key);
        if (!date || !value || typeof value !== "object") return;
        if (!value.payload || typeof value.payload !== "object") return;
        state.dailyArchiveCache.entries[date] = {
          payload: value.payload,
          fetchedAtMs: Number(value.fetchedAtMs || 0),
          viewedAtMs: Number(value.viewedAtMs || 0),
        };
      });
    }
    pruneDailyArchiveEntries();
  } catch (error) {
    // Ignore invalid cache payload.
  }
}

function dailyArchiveEntryTtlMs(date) {
  const day = normalizeIsoDate(date);
  if (!day || day !== todayIso()) {
    return 12 * 60 * 60 * 1000;
  }
  return 5 * 60 * 1000;
}

function isDailyArchiveEntryStale(entry, date, nowMs = Date.now()) {
  if (!entry || typeof entry !== "object") return true;
  const fetchedAtMs = Number(entry.fetchedAtMs || 0);
  if (!Number.isFinite(fetchedAtMs) || fetchedAtMs <= 0) return true;
  return nowMs - fetchedAtMs > dailyArchiveEntryTtlMs(date);
}

function getDailyArchiveEntry(date) {
  loadDailyArchiveCacheFromStorage();
  const day = normalizeIsoDate(date);
  if (!day) return null;
  const entry = state.dailyArchiveCache.entries[day];
  if (!entry || typeof entry !== "object" || !entry.payload) return null;
  if (!isDailyPayloadLike(entry.payload, day)) {
    delete state.dailyArchiveCache.entries[day];
    state.dailyArchiveCache.dates = sortIsoDatesDesc([
      ...(state.dailyArchiveCache.dates || []),
      ...Object.keys(state.dailyArchiveCache.entries || {}),
    ]);
    scheduleDailyArchiveCachePersist();
    return null;
  }
  return entry;
}

function touchDailyArchiveEntry(date, viewedAtMs = Date.now()) {
  loadDailyArchiveCacheFromStorage();
  const day = normalizeIsoDate(date);
  if (!day) return;
  const entry = state.dailyArchiveCache.entries[day];
  if (!entry || typeof entry !== "object") return;
  entry.viewedAtMs = viewedAtMs;
  scheduleDailyArchiveCachePersist();
}

function upsertDailyArchiveEntry(date, payload, fetchedAtMs = Date.now()) {
  loadDailyArchiveCacheFromStorage();
  const day = normalizeIsoDate(date);
  if (!day || !payload || typeof payload !== "object") return;
  if (!isDailyPayloadLike(payload, day)) return;
  const previous = state.dailyArchiveCache.entries[day];
  state.dailyArchiveCache.entries[day] = {
    payload,
    fetchedAtMs,
    viewedAtMs: Number(previous?.viewedAtMs || fetchedAtMs),
  };
  state.dailyArchiveCache.dates = sortIsoDatesDesc([
    ...(state.dailyArchiveCache.dates || []),
    day,
    ...Object.keys(state.dailyArchiveCache.entries),
  ]);
  pruneDailyArchiveEntries();
  scheduleDailyArchiveCachePersist();
}

function setDailyArchiveKnownDates(dates, fetchedAtMs = Date.now()) {
  loadDailyArchiveCacheFromStorage();
  state.dailyArchiveCache.dates = sortIsoDatesDesc([
    ...(state.dailyArchiveCache.dates || []),
    ...dates,
    ...Object.keys(state.dailyArchiveCache.entries),
  ]);
  state.dailyArchiveCache.datesFetchedAtMs = fetchedAtMs;
  scheduleDailyArchiveCachePersist();
}

function datesForMonth(year, month) {
  const safeYear = Number.isInteger(year) ? year : Number.parseInt(year, 10);
  const safeMonth = Number.isInteger(month) ? month : Number.parseInt(month, 10);
  if (!Number.isFinite(safeYear) || !Number.isFinite(safeMonth) || safeMonth < 1 || safeMonth > 12) {
    return [];
  }
  const lastDay = new Date(safeYear, safeMonth, 0).getDate();
  const prefix = `${String(safeYear).padStart(4, "0")}-${String(safeMonth).padStart(2, "0")}`;
  const dates = [];
  for (let day = 1; day <= lastDay; day += 1) {
    dates.push(`${prefix}-${String(day).padStart(2, "0")}`);
  }
  return dates;
}

async function fetchInverterDatesFromBridge(options = {}) {
  loadDailyArchiveCacheFromStorage();
  const force = !!options.force;
  const nowMs = Date.now();
  const cachedDates = sortIsoDatesDesc(state.dailyArchiveCache.dates || []);
  const hasFreshDates =
    cachedDates.length > 0 &&
    nowMs - Number(state.dailyArchiveCache.datesFetchedAtMs || 0) <= DAILY_ARCHIVE_DATES_STALE_MS;

  if (!force && hasFreshDates) {
    return cachedDates;
  }
  if (!hasBridge() || !window.AndroidHub || typeof window.AndroidHub.fetchInverterDates !== "function") {
    return cachedDates;
  }

  try {
    const payload = await bridgeRequest("dates", (requestId) => {
      window.AndroidHub.fetchInverterDates(requestId);
    });
    const dates = parseInverterDatesPayload(payload);
    if (dates.length > 0) {
      setDailyArchiveKnownDates(dates, nowMs);
      return dates;
    }
  } catch (error) {
    // Silent fallback to cached dates.
  }

  return cachedDates;
}

function isDailyPayloadLike(payload, expectedDate = "") {
  if (!(payload && typeof payload === "object" && Array.isArray(payload.hours))) return false;
  const expected = normalizeIsoDate(expectedDate);
  if (!expected) return true;
  const payloadDate = normalizeIsoDate(payload?.date || "");
  if (!payloadDate) return false;
  return payloadDate === expected;
}

async function fetchDailyPayloadFromBridge(date, options = {}) {
  const day = normalizeIsoDate(date);
  if (!day) return null;

  const force = !!options.force;
  const cachedEntry = getDailyArchiveEntry(day);
  if (!force && cachedEntry && !isDailyArchiveEntryStale(cachedEntry, day, Date.now())) {
    touchDailyArchiveEntry(day);
    return cachedEntry.payload;
  }

  if (!hasBridge() || !window.AndroidHub || typeof window.AndroidHub.fetchInverterDaily !== "function") {
    return cachedEntry?.payload || null;
  }

  const payload = await bridgeRequest("daily-sync", (requestId) => {
    window.AndroidHub.fetchInverterDaily(day, requestId);
  });
  if (!isDailyPayloadLike(payload, day)) {
    return cachedEntry?.payload || null;
  }
  upsertDailyArchiveEntry(day, payload, Date.now());
  upsertAnalyticsCacheEntry("daily", day, payload, Date.now());
  return payload;
}

function computeDailyEnergyTotals(payload) {
  const rows = Array.isArray(payload?.hours) ? payload.hours : [];
  const totals = payload?.totals && typeof payload.totals === "object" ? payload.totals : {};
  let pv = maybeFiniteNumber(totals.dailyPV, null);
  let home = maybeFiniteNumber(totals.dailyHome, null);
  let grid = maybeFiniteNumber(totals.dailyGrid, null);

  if (pv === null) {
    pv = rows.reduce((sum, row) => sum + toFiniteNumber(row?.pv, 0), 0);
  }
  if (home === null) {
    home = rows.reduce((sum, row) => sum + toFiniteNumber(row?.home, 0), 0);
  }
  if (grid === null) {
    grid = rows.reduce((sum, row) => sum + toFiniteNumber(row?.grid, 0), 0);
  }

  return {
    pv: Math.round(pv * 10) / 10,
    home: Math.round(home * 10) / 10,
    grid: Math.round(grid * 10) / 10,
  };
}

const DAILY_CLIMATE_CANDIDATE_KEYS = Object.freeze({
  temp: Object.freeze(["temp", "temp_int", "temp_internal", "inside_temp", "internal.temp"]),
  hum: Object.freeze(["hum", "hum_int", "hum_internal", "inside_hum", "internal.hum"]),
  press: Object.freeze(["press", "press_int", "press_internal", "inside_press", "internal.press"]),
  tempExt: Object.freeze(["temp_ext", "external_temp", "outside_temp", "external.temp", "outside.temp"]),
  humExt: Object.freeze(["hum_ext", "external_hum", "outside_hum", "external.hum", "outside.hum"]),
  pressExt: Object.freeze(["press_ext", "external_press", "outside_press", "external.press", "outside.press"]),
  tempCorridor: Object.freeze(["temp_corridor", "corridor_temp", "temp_load", "temp_lc", "corridor.temp"]),
  humCorridor: Object.freeze(["hum_corridor", "corridor_hum", "hum_load", "hum_lc", "corridor.hum"]),
  pressCorridor: Object.freeze(["press_corridor", "corridor_press", "press_load", "press_lc", "corridor.press"]),
  tempGarage: Object.freeze(["temp_garage", "garage_temp", "garage.temp"]),
  humGarage: Object.freeze(["hum_garage", "garage_hum", "garage.hum"]),
  pressGarage: Object.freeze(["press_garage", "garage_press", "garage.press"]),
  tempInverter: Object.freeze(["inverter_temp", "inverterTemp", "inverter_rs232_temp", "inverter_rs232.temp", "rs232_temp"]),
});

function averageDailyRowByCandidates(rows, keys) {
  if (!Array.isArray(rows) || !Array.isArray(keys)) return 0;
  let sum = 0;
  let count = 0;
  rows.forEach((row) => {
    const value = climateNumberFromCandidates(row, keys);
    if (Number.isFinite(value)) {
      sum += value;
      count += 1;
    }
  });
  if (!count) return 0;
  return Math.round((sum / count) * 10) / 10;
}

function computeDailyClimateSummary(payload) {
  const rows = Array.isArray(payload?.hours) ? payload.hours : [];
  return {
    temp: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.temp),
    hum: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.hum),
    press: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.press),
    temp_ext: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.tempExt),
    hum_ext: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.humExt),
    press_ext: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.pressExt),
    temp_corridor: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.tempCorridor),
    hum_corridor: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.humCorridor),
    press_corridor: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.pressCorridor),
    temp_garage: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.tempGarage),
    hum_garage: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.humGarage),
    press_garage: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.pressGarage),
    inverter_temp: averageDailyRowByCandidates(rows, DAILY_CLIMATE_CANDIDATE_KEYS.tempInverter),
  };
}

async function syncDailyArchiveDates(options = {}) {
  const dates = await fetchInverterDatesFromBridge({ force: !!options.forceDates });
  if (dates.length) {
    setDailyArchiveKnownDates(dates, Date.now());
  }
  return dates;
}

function hasPendingBridgeRequestPrefix(prefix) {
  const safePrefix = safeText(prefix, "");
  if (!safePrefix) return false;
  for (const requestId of state.pending.keys()) {
    if (typeof requestId !== "string") continue;
    if (requestId.startsWith(`${safePrefix}-`)) return true;
  }
  return false;
}

function isLoadControllerIdleForArchiveSync() {
  if (!state.config.loadControllerEnabled) return true;
  if (hasPendingBridgeRequestPrefix("cmd")) return false;

  const loadController = state.status?.loadController;
  if (!loadController || typeof loadController !== "object") {
    return true;
  }

  const relayBusy = loadController.boiler1On === true || loadController.pumpOn === true;
  const boilerPower = Math.abs(toFiniteNumber(loadController.boilerPower, 0));
  const pumpPower = Math.abs(toFiniteNumber(loadController.pumpPower, 0));
  const powerBusy = boilerPower >= LOAD_TIMELINE_POWER_ON_THRESHOLD || pumpPower >= LOAD_TIMELINE_POWER_ON_THRESHOLD;
  return !relayBusy && !powerBusy;
}

async function ensureDailyArchiveEntries(targetDates, options = {}) {
  const force = !!options.force;
  const onlyWhenLoadIdle = options.onlyWhenLoadIdle === true;
  const configuredLimit = graphSyncPerCycleFromConfig();
  const requestedLimit = Number(options.maxFetch);
  const maxFetch = Math.max(
    0,
    Number.isFinite(requestedLimit) ? requestedLimit : configuredLimit,
  );

  const dates = sortIsoDatesDesc(targetDates);
  const stats = {
    requested: dates.length,
    fetched: 0,
    blockedByBusy: false,
  };
  if (!maxFetch || !dates.length) return stats;

  let fetched = 0;
  for (const date of dates) {
    if (fetched >= maxFetch) break;
    const entry = getDailyArchiveEntry(date);
    if (!force && entry && !isDailyArchiveEntryStale(entry, date, Date.now())) {
      continue;
    }
    if (onlyWhenLoadIdle && !isLoadControllerIdleForArchiveSync()) {
      stats.blockedByBusy = true;
      break;
    }
    try {
      const payload = await fetchDailyPayloadFromBridge(date, { force });
      if (payload && isDailyPayloadLike(payload, date)) {
        fetched += 1;
        stats.fetched += 1;
      }
    } catch (error) {
      // Silent: sync must not interrupt UI flow.
    }
  }
  return stats;
}

function buildMonthlyPayloadFromArchive(monthSelector) {
  loadDailyArchiveCacheFromStorage();
  const month = normalizeIsoMonth(monthSelector);
  if (!month) {
    throw new Error("Некоректний вибір місяця");
  }
  const year = Number.parseInt(month.slice(0, 4), 10);
  const monthNum = Number.parseInt(month.slice(5, 7), 10);
  const monthDates = datesForMonth(year, monthNum);
  const knownMonthDates = sortIsoDatesDesc(state.dailyArchiveCache.dates || [])
    .filter((date) => date.startsWith(`${month}-`));
  const knownMonthDaysCount = knownMonthDates.length > 0 ? knownMonthDates.length : monthDates.length;
  let syncedDays = 0;
  const days = monthDates.map((date) => {
    const dayNumber = Number.parseInt(date.slice(8, 10), 10);
    const entry = getDailyArchiveEntry(date);
    if (!entry || !isDailyPayloadLike(entry.payload)) {
      return {
        day: dayNumber,
        pv: 0,
        home: 0,
        grid: 0,
        temp: 0,
        hum: 0,
        press: 0,
        temp_ext: 0,
        hum_ext: 0,
        press_ext: 0,
        temp_corridor: 0,
        hum_corridor: 0,
        press_corridor: 0,
        temp_garage: 0,
        hum_garage: 0,
        press_garage: 0,
      };
    }
    syncedDays += 1;
    const energy = computeDailyEnergyTotals(entry.payload);
    const climate = computeDailyClimateSummary(entry.payload);
    return {
      day: dayNumber,
      pv: energy.pv,
      home: energy.home,
      grid: energy.grid,
      temp: climate.temp,
      hum: climate.hum,
      press: climate.press,
      temp_ext: climate.temp_ext,
      hum_ext: climate.hum_ext,
      press_ext: climate.press_ext,
      temp_corridor: climate.temp_corridor,
      hum_corridor: climate.hum_corridor,
      press_corridor: climate.press_corridor,
      temp_garage: climate.temp_garage,
      hum_garage: climate.hum_garage,
      press_garage: climate.press_garage,
    };
  });
  return {
    month,
    days,
    _coverage: {
      period: "monthly",
      expectedDays: monthDates.length,
      knownDays: knownMonthDaysCount,
      syncedDays,
    },
  };
}

function buildYearlyPayloadFromArchive(yearValue) {
  loadDailyArchiveCacheFromStorage();
  const targetYear = Number.parseInt(String(yearValue), 10);
  if (!Number.isFinite(targetYear) || targetYear < 2000 || targetYear > 2099) {
    throw new Error("Некоректний вибір року");
  }

  const monthLabels = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"];
  const monthly = Array.from({ length: 12 }, () => ({
    pv: 0,
    home: 0,
    grid: 0,
    climateDays: 0,
    temp: 0,
    hum: 0,
    press: 0,
    temp_ext: 0,
    hum_ext: 0,
    press_ext: 0,
    corridorDays: 0,
    temp_corridor: 0,
    hum_corridor: 0,
    press_corridor: 0,
    garageDays: 0,
    temp_garage: 0,
    hum_garage: 0,
    press_garage: 0,
    inverterDays: 0,
    inverter_temp: 0,
  }));

  const cachedDates = sortIsoDatesDesc(Object.keys(state.dailyArchiveCache.entries || {}))
    .filter((date) => date.startsWith(`${targetYear}-`));
  const knownYearDates = sortIsoDatesDesc(state.dailyArchiveCache.dates || [])
    .filter((date) => date.startsWith(`${targetYear}-`));
  const knownYearDaysCount = Math.max(knownYearDates.length, cachedDates.length);
  let syncedDays = 0;
  cachedDates.forEach((date) => {
    const monthIndex = Number.parseInt(date.slice(5, 7), 10) - 1;
    if (monthIndex < 0 || monthIndex > 11) return;
    const entry = getDailyArchiveEntry(date);
    if (!entry || !isDailyPayloadLike(entry.payload)) return;
    syncedDays += 1;

    const energy = computeDailyEnergyTotals(entry.payload);
    const climate = computeDailyClimateSummary(entry.payload);
    const bucket = monthly[monthIndex];
    bucket.pv += energy.pv;
    bucket.home += energy.home;
    bucket.grid += energy.grid;

    if (climate.temp || climate.hum || climate.press || climate.temp_ext || climate.hum_ext || climate.press_ext) {
      bucket.climateDays += 1;
      bucket.temp += climate.temp;
      bucket.hum += climate.hum;
      bucket.press += climate.press;
      bucket.temp_ext += climate.temp_ext;
      bucket.hum_ext += climate.hum_ext;
      bucket.press_ext += climate.press_ext;
    }
    if (climate.temp_corridor || climate.hum_corridor || climate.press_corridor) {
      bucket.corridorDays += 1;
      bucket.temp_corridor += climate.temp_corridor;
      bucket.hum_corridor += climate.hum_corridor;
      bucket.press_corridor += climate.press_corridor;
    }
    if (climate.temp_garage || climate.hum_garage || climate.press_garage) {
      bucket.garageDays += 1;
      bucket.temp_garage += climate.temp_garage;
      bucket.hum_garage += climate.hum_garage;
      bucket.press_garage += climate.press_garage;
    }
    if (climate.inverter_temp) {
      bucket.inverterDays += 1;
      bucket.inverter_temp += climate.inverter_temp;
    }
  });

  const avg = (sum, count) => (count > 0 ? Math.round((sum / count) * 10) / 10 : 0);
  return {
    status: "success",
    current_year: targetYear,
    months: monthLabels,
    pv: monthly.map((row) => Math.round(row.pv * 10) / 10),
    home: monthly.map((row) => Math.round(row.home * 10) / 10),
    grid: monthly.map((row) => Math.round(row.grid * 10) / 10),
    temp: monthly.map((row) => avg(row.temp, row.climateDays)),
    hum: monthly.map((row) => avg(row.hum, row.climateDays)),
    press: monthly.map((row) => avg(row.press, row.climateDays)),
    temp_ext: monthly.map((row) => avg(row.temp_ext, row.climateDays)),
    hum_ext: monthly.map((row) => avg(row.hum_ext, row.climateDays)),
    press_ext: monthly.map((row) => avg(row.press_ext, row.climateDays)),
    temp_corridor: monthly.map((row) => avg(row.temp_corridor, row.corridorDays)),
    hum_corridor: monthly.map((row) => avg(row.hum_corridor, row.corridorDays)),
    press_corridor: monthly.map((row) => avg(row.press_corridor, row.corridorDays)),
    temp_garage: monthly.map((row) => avg(row.temp_garage, row.garageDays)),
    hum_garage: monthly.map((row) => avg(row.hum_garage, row.garageDays)),
    press_garage: monthly.map((row) => avg(row.press_garage, row.garageDays)),
    inverter_temp: monthly.map((row) => avg(row.inverter_temp, row.inverterDays)),
    _coverage: {
      period: "yearly",
      expectedDays: 0,
      knownDays: knownYearDaysCount,
      syncedDays,
    },
  };
}

async function syncDailyArchiveQuiet(options = {}) {
  const force = !!options.force;
  const onlyWhenLoadIdle = options.onlyWhenLoadIdle === true;
  const configuredLimit = graphSyncPerCycleFromConfig();
  const requestedLimit = Number(options.maxFetch);
  const maxFetch = Math.max(
    0,
    Number.isFinite(requestedLimit) ? requestedLimit : configuredLimit,
  );
  if (!hasBridge() || maxFetch <= 0) {
    return {
      requested: 0,
      fetched: 0,
      blockedByBusy: false,
    };
  }

  const dates = await syncDailyArchiveDates({ forceDates: force });
  if (!dates.length) {
    return {
      requested: 0,
      fetched: 0,
      blockedByBusy: false,
    };
  }
  return ensureDailyArchiveEntries(dates, {
    force,
    maxFetch,
    onlyWhenLoadIdle,
  });
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

function maybeFiniteNumber(value, fallback = null) {
  if (value === null || value === undefined || value === "") return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
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
  return value ? "УВІМК" : "ВИМК";
}

// Уже перекладена версія boolText(); залишена окремою функцією, бо
// renderPowerScheme() використовує "--" замість "---" для невідомого стану.
function boolTextUk(value) {
  if (value === null || value === undefined) return "--";
  return value ? "увімк." : "вимк.";
}

function setSchemeNodeOnOff(id, isOn) {
  const el = document.getElementById(id);
  if (!el) return;
  const unknown = isOn === null || isOn === undefined;
  el.classList.toggle("is-on", isOn === true);
  el.classList.toggle("is-off", isOn === false);
  el.classList.toggle("is-unknown", unknown);
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

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

const LOGIC_KEYS = Object.freeze(["grid", "load", "boiler1", "pump", "boiler2"]);

function readArrayStrings(value) {
  if (!Array.isArray(value)) return [];
  return value.map((item) => safeText(item, "")).filter(Boolean);
}

function buildHubCapabilities(status) {
  const root = status?.capabilities;
  const rootLogicKeys = readArrayStrings(root?.logicKeys);
  const moduleCaps = root?.modules && typeof root.modules === "object" ? root.modules : {};
  const fallbackLogicKeys = LOGIC_KEYS.filter((logicKey) => {
    const def = getLogicModalDefinition(logicKey);
    if (!def) return false;
    const moduleState = def.getModule(status || {});
    const config = def.getConfig(status || {});
    return !!moduleState && !!config;
  });

  return {
    logicKeys: rootLogicKeys.length ? rootLogicKeys : fallbackLogicKeys,
    historyHours: Math.max(1, Number(root?.historyHours) || AUTOMATION_HISTORY_DEFAULT_HOURS),
    eventJournal: root?.eventJournal !== false,
    automationHistory: root?.automationHistory !== false,
    modules: {
      inverter: moduleCaps.inverter || status?.inverter?.capabilities || {},
      loadController: moduleCaps.loadController || status?.loadController?.capabilities || {},
      garage: moduleCaps.garage || status?.garage?.capabilities || {},
    },
  };
}

function isLogicAvailable(logicKey, status = state.status) {
  const caps = state.capabilities || buildHubCapabilities(status);
  return Array.isArray(caps.logicKeys) && caps.logicKeys.includes(logicKey);
}

function logicCapability(logicKey, status = state.status) {
  const def = getLogicModalDefinition(logicKey);
  if (!def) return { available: false, moduleKey: "" };
  const moduleKey = safeText(def.moduleKey, "");
  const caps = state.capabilities || buildHubCapabilities(status);
  const moduleCaps = moduleKey ? caps.modules?.[moduleKey] || {} : {};
  return {
    available: isLogicAvailable(logicKey, status),
    moduleKey,
    moduleCaps,
  };
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

function clampGraphSyncIntervalMin(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 15;
  return Math.max(2, Math.min(120, Math.round(parsed)));
}

function clampGraphSyncPerCycle(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 2;
  return Math.max(1, Math.min(12, Math.round(parsed)));
}

function clampGraphSyncRequestFetchLimit(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 365;
  return Math.max(1, Math.min(365, Math.round(parsed)));
}

function graphSyncIntervalMsFromConfig() {
  return clampGraphSyncIntervalMin(state.config.graphSyncIntervalMin) * 60 * 1000;
}

function graphSyncPerCycleFromConfig() {
  return clampGraphSyncPerCycle(state.config.graphSyncPerCycle);
}

function graphSyncRequestFetchLimitFromConfig() {
  return clampGraphSyncRequestFetchLimit(state.config.graphSyncRequestFetchLimit);
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
  if (!enabled) return "вимкнено";
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

function setGateActionButtonLabel(stateName, options = {}) {
  const { disabled = false } = options;
  // label лишається внутрішнім англійським ключем для class-перемикачів
  // нижче; displayLabel — те, що бачить користувач.
  let label = "stop";
  if (stateName === "closed") label = "open";
  else if (stateName === "open") label = "close";
  const displayLabel = label === "open" ? "відкрити" : label === "close" ? "зачинити" : "стоп";

  document.querySelectorAll("[data-gate-action]").forEach((btn) => {
    btn.disabled = !!disabled;
    btn.textContent = disabled ? "--" : displayLabel;
    btn.classList.toggle("is-open", !disabled && label === "open");
    btn.classList.toggle("is-close", !disabled && label === "close");
    btn.classList.toggle("is-stop", !disabled && label === "stop");
  });
}

function setGarageLightActionButtonState({ disabled = false, on = false, reason = "" } = {}) {
  document.querySelectorAll("[data-garage-light-action]").forEach((btn) => {
    btn.disabled = !!disabled;
    btn.classList.toggle("is-on", !disabled && !!on);
    btn.classList.toggle("is-off", !disabled && !on);

    if (disabled) {
      btn.textContent = "світло --";
      btn.title = "модуль гаража вимкнено";
      return;
    }

    // Кнопка показує ДІЮ, яку виконає натискання (як і кнопка воріт), а не
    // поточний стан - "увімкнути", коли зараз вимкнено, "вимкнути", коли
    // зараз увімкнено.
    btn.textContent = on ? "вимкнути світло" : "увімкнути світло";
    btn.title = reason ? `світло гаража (${reason})` : "світло гаража";
  });
}

function formatCountdownClock(totalSeconds) {
  const safeSec = Math.max(0, Math.round(Number(totalSeconds) || 0));
  const minutes = Math.floor(safeSec / 60);
  const seconds = safeSec % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

// Прошивка віддає лише "скільки секунд лишалось на момент опитування", тому
// зберігаємо абсолютний дедлайн і відлічуємо локально щосекунди
// (restartSignalAgeTicker вже й так тикає раз на секунду) - інакше цифри
// стрибали б лише раз на кожен цикл опитування, а не плавно.
function applyGarageLightAutoOffFromStatus(autoOffInSec) {
  const sec = Number(autoOffInSec);
  if (!Number.isFinite(sec) || sec < 0) {
    state.gate.lightAutoOffAtMs = null;
    return;
  }
  state.gate.lightAutoOffAtMs = Date.now() + sec * 1000;
}

function updateGarageLightCountdownDisplay() {
  const row = document.getElementById("garageLightAutoOffRow");
  const el = document.getElementById("garageLightAutoOff");
  if (!row || !el) return;

  const deadline = state.gate.lightAutoOffAtMs;
  if (!deadline) {
    row.hidden = true;
    return;
  }
  const remainingMs = deadline - Date.now();
  if (remainingMs <= 0) {
    row.hidden = true;
    state.gate.lightAutoOffAtMs = null;
    return;
  }
  row.hidden = false;
  el.textContent = `${formatCountdownClock(remainingMs / 1000)} (світло після воріт)`;
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

function normalizeInterfaceMode(value) {
  return "pro";
}

function syncInterfaceModeButtons() {
  // Single-mode UI: no interface mode buttons remain.
}

function applyInterfaceMode(mode) {
  const normalized = "pro";
  state.config.interfaceMode = normalized;
  state.uiMode = normalized;
  if (document.body) {
    document.body.setAttribute("data-ui-mode", normalized);
  }
  syncInterfaceModeButtons();
}

function bindQuickInterfaceModeSwitch() {
  // Additional UI modes were removed; hub always runs in pro mode.
}

function normalizeTimelineSample(rawSample) {
  const ts = Number(rawSample?.ts);
  if (!Number.isFinite(ts) || ts <= 0) return null;
  return {
    ts,
    boilerOn: !!rawSample?.boilerOn,
    garageBoilerOn: !!rawSample?.garageBoilerOn,
    pumpOn: !!rawSample?.pumpOn,
    gridOn: !!rawSample?.gridOn,
    pvOn: !!rawSample?.pvOn,
  };
}

function sanitizeTimelineSamples(rawSamples) {
  if (!Array.isArray(rawSamples) || !rawSamples.length) return [];
  const mapped = rawSamples
    .map(normalizeTimelineSample)
    .filter((item) => item !== null)
    .sort((a, b) => a.ts - b.ts);
  if (!mapped.length) return [];

  const dedup = [];
  for (let i = 0; i < mapped.length; i += 1) {
    const sample = mapped[i];
    const tail = dedup[dedup.length - 1];
    if (tail && tail.ts === sample.ts) {
      dedup[dedup.length - 1] = sample;
    } else {
      dedup.push(sample);
    }
  }
  return dedup;
}

function persistTimelineCacheNow() {
  try {
    const day = safeText(state.timeline.day, "");
    const samples = sanitizeTimelineSamples(state.timeline.samples);
    if (!day || !samples.length) return;
    const payload = {
      version: TIMELINE_CACHE_SCHEMA_VERSION,
      day,
      lastTimestamp: Number(state.timeline.lastTimestamp || 0),
      samples,
      savedAtMs: Date.now(),
    };
    localStorage.setItem(TIMELINE_CACHE_STORAGE_KEY, JSON.stringify(payload));
  } catch (error) {
    // Ignore storage errors (quota/private mode).
  }
}

function scheduleTimelineCachePersist() {
  if (state.timeline.persistHandle) {
    clearTimeout(state.timeline.persistHandle);
  }
  state.timeline.persistHandle = setTimeout(() => {
    state.timeline.persistHandle = null;
    persistTimelineCacheNow();
  }, 400);
}

function loadTimelineCacheFromStorage() {
  if (state.timeline.samples.length) return;
  try {
    const raw = localStorage.getItem(TIMELINE_CACHE_STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw);
    if (!parsed || Number(parsed.version) !== TIMELINE_CACHE_SCHEMA_VERSION) return;
    const day = safeText(parsed.day, "");
    const samples = sanitizeTimelineSamples(parsed.samples);
    if (!day || !samples.length) return;

    state.timeline.day = day;
    state.timeline.samples = samples;
    state.timeline.lastTimestamp = Math.max(
      Number(parsed.lastTimestamp || 0),
      samples[samples.length - 1]?.ts || 0,
    );
    state.timeline.historyReady = true;
  } catch (error) {
    // Ignore invalid cache payload.
  }
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

  const padding = { left: 86, right: 18, top: 16, bottom: 28 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  if (plotWidth <= 10 || plotHeight <= 10) return;

  const xFor = (ts) => padding.left + ((ts - dayStart) / windowMs) * plotWidth;

  ctx.fillStyle = "rgba(255,255,255,0.04)";
  ctx.fillRect(padding.left, padding.top, plotWidth, plotHeight);

  const rowGap = 8;
  const rows = 5;
  const rowHeight = (plotHeight - rowGap * (rows - 1)) / rows;
  const houseBoilerTop = padding.top;
  const garageBoilerTop = houseBoilerTop + rowHeight + rowGap;
  const pumpTop = garageBoilerTop + rowHeight + rowGap;
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
  ctx.fillText("БОЙЛЕР", padding.left - 8, houseBoilerTop + rowHeight / 2);
  ctx.fillText("ГАРАЖ", padding.left - 8, garageBoilerTop + rowHeight / 2);
  ctx.fillText("НАСОС", padding.left - 8, pumpTop + rowHeight / 2);
  ctx.fillText("МЕРЕЖА", padding.left - 8, gridTop + rowHeight / 2);
  ctx.fillText("СОНЦЕ", padding.left - 8, pvTop + rowHeight / 2);

  ctx.fillStyle = "rgba(255,77,109,0.12)";
  ctx.fillRect(padding.left, houseBoilerTop, plotWidth, rowHeight);
  ctx.fillStyle = "rgba(0,230,255,0.12)";
  ctx.fillRect(padding.left, garageBoilerTop, plotWidth, rowHeight);
  ctx.fillStyle = "rgba(79,124,255,0.12)";
  ctx.fillRect(padding.left, pumpTop, plotWidth, rowHeight);
  ctx.fillStyle = "rgba(51,255,153,0.1)";
  ctx.fillRect(padding.left, gridTop, plotWidth, rowHeight);
  ctx.fillStyle = "rgba(255,179,71,0.12)";
  ctx.fillRect(padding.left, pvTop, plotWidth, rowHeight);

  drawLoadTimelineRow(ctx, samples, "boilerOn", xFor, houseBoilerTop, rowHeight, "rgba(255,77,109,0.85)");
  drawLoadTimelineRow(ctx, samples, "garageBoilerOn", xFor, garageBoilerTop, rowHeight, "rgba(0,230,255,0.85)");
  drawLoadTimelineRow(ctx, samples, "pumpOn", xFor, pumpTop, rowHeight, "rgba(79,124,255,0.85)");
  drawLoadTimelineRow(ctx, samples, "gridOn", xFor, gridTop, rowHeight, "rgba(51,255,153,0.85)");
  drawLoadTimelineRow(ctx, samples, "pvOn", xFor, pvTop, rowHeight, "rgba(255,179,71,0.85)");

  if (!samples.length) {
    ctx.fillStyle = "rgba(230, 241, 255, 0.7)";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText("немає даних хронології", width / 2, height / 2);
  }

  const garageNote = isTimelineViewingToday() ? "" : " · дані бойлера гаража за минулі доби не зберігаються";
  setText("timelineMeta", `дата: ${dateLabel} (00:00-23:55), вікно перегляду ~${visibleHours} год${garageNote}`);
}

function applyLoadTimelineHistory(payload) {
  const dateLabel = safeText(payload?.date, "");
  const rows = Array.isArray(payload?.samples) ? payload.samples : [];
  if (!dateLabel) {
    throw new Error("дані історії порожні");
  }

  const dayStart = new Date(`${dateLabel}T00:00:00`).getTime();
  if (!Number.isFinite(dayStart)) {
    throw new Error("некоректна дата хронології");
  }

  const previousSamples = Array.isArray(state.timeline.samples) ? state.timeline.samples : [];
  const previousGarageByTs = new Map(
    previousSamples.map((sample) => [Number(sample?.ts) || 0, !!sample?.garageBoilerOn]),
  );
  const samples = [];
  rows.forEach((row) => {
    const minute = Number(row?.m);
    if (!Number.isFinite(minute)) return;
    const ts = dayStart + minute * 60 * 1000;
    const flags = Number(row?.f) || 0;
    samples.push({
      ts,
      boilerOn: (flags & 0x01) !== 0,
      garageBoilerOn: previousGarageByTs.get(ts) || false,
      pumpOn: (flags & 0x02) !== 0,
      gridOn: (flags & 0x04) !== 0,
      pvOn: (flags & 0x08) !== 0,
    });
  });

  // Прошивка віддає лише 15-хв блоки ("увімкнено хоч раз протягом вікна"),
  // тому щойно завершений блок може лишатись позначеним "увімк." навіть
  // якщо пристрій уже вимкнувся. recordLoadTimelineSample() тим часом додає
  // точніші живі семпли поверх - якщо серед них є новіші за останній блок
  // з прошивки, зберігаємо їх "хвіст", інакше цей повторний запит щохвилини
  // стирав би той самий "зараз вимкнено", який щойно домалювала жива точка.
  const lastFirmwareTs = samples.length ? samples[samples.length - 1].ts : dayStart;
  const liveTail = previousSamples.filter((sample) => Number(sample?.ts) > lastFirmwareTs);

  state.timeline.samples = sanitizeTimelineSamples(samples.concat(liveTail));
  state.timeline.day = dateLabel;
  state.timeline.lastTimestamp = state.timeline.samples.length
    ? state.timeline.samples[state.timeline.samples.length - 1].ts
    : dayStart;
  state.timeline.historyReady = true;
  scheduleTimelineCachePersist();
}

function applyGarageBoilerTimelineHistory(payload) {
  const rows = Array.isArray(payload?.samples) ? payload.samples : [];
  const garageDate = safeText(payload?.date, "");
  const dayKey = safeText(state.timeline.day, "");
  if (!dayKey || !rows.length || !state.timeline.samples.length) return;
  if (garageDate && garageDate !== dayKey) return;

  const dayStart = new Date(`${dayKey}T00:00:00`).getTime();
  if (!Number.isFinite(dayStart)) return;

  const garagePoints = rows
    .map((row) => {
      const minute = Number(row?.m);
      if (!Number.isFinite(minute) || minute < 0 || minute > 1439) return null;
      const flags = Number(row?.f) || 0;
      return { minute, on: (flags & 0x01) !== 0 };
    })
    .filter((item) => item !== null)
    .sort((a, b) => a.minute - b.minute);

  if (!garagePoints.length) return;

  let pointIndex = 0;
  let lastKnown = null;
  state.timeline.samples.forEach((sample) => {
    const minute = Math.max(0, Math.min(1439, Math.floor((sample.ts - dayStart) / 60000)));
    while (pointIndex < garagePoints.length && garagePoints[pointIndex].minute <= minute) {
      lastKnown = garagePoints[pointIndex].on;
      pointIndex += 1;
    }
    if (typeof lastKnown === "boolean") {
      sample.garageBoilerOn = lastKnown;
    }
  });
  scheduleTimelineCachePersist();
}

function recordLoadTimelineSample(loadController, garage = null) {
  if (!loadController || typeof loadController !== "object") return;
  // Під час перегляду минулої доби живі семпли не дозаписуємо, щоб не
  // підмішати "сьогодні" у відображену історію.
  if (state.timeline.selectedDate) return;

  const boilerPower = Number(loadController.boilerPower);
  const pumpPower = Number(loadController.pumpPower);
  const garageBoilerPower = Number(garage?.boilerPower);
  const hasSampleBasis =
    Number.isFinite(boilerPower) ||
    Number.isFinite(pumpPower) ||
    Number.isFinite(garageBoilerPower) ||
    typeof garage?.boiler2On === "boolean";
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
    garageBoilerOn: Number.isFinite(garageBoilerPower)
      ? garageBoilerPower > LOAD_TIMELINE_POWER_ON_THRESHOLD
      : !!garage?.boiler2On,
    pumpOn: Number.isFinite(pumpPower) ? pumpPower > LOAD_TIMELINE_PUMP_POWER_ON_THRESHOLD : !!loadController.pumpOn,
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
    scheduleTimelineCachePersist();
  }

  if (isModalOpen("timelineModal")) {
    renderLoadTimeline();
  }
}

function timelineTodayKey() {
  return loadTimelineDayKey(Date.now());
}

function timelineActiveDayKey() {
  return state.timeline.selectedDate || timelineTodayKey();
}

function isTimelineViewingToday() {
  return !state.timeline.selectedDate || state.timeline.selectedDate === timelineTodayKey();
}

function updateTimelineDayControls() {
  const label = document.getElementById("timelineDayLabel");
  if (label) {
    label.textContent = isTimelineViewingToday() ? "сьогодні" : timelineActiveDayKey();
  }
  const nextBtn = document.getElementById("timelineDayNextBtn");
  if (nextBtn) {
    nextBtn.disabled = isTimelineViewingToday();
  }
  const todayBtn = document.getElementById("timelineDayTodayBtn");
  if (todayBtn) {
    todayBtn.disabled = isTimelineViewingToday();
  }
}

async function shiftTimelineDay(deltaDays) {
  const base = new Date(`${timelineActiveDayKey()}T00:00:00`);
  if (!Number.isFinite(base.getTime())) return;
  base.setDate(base.getDate() + deltaDays);
  const candidate = loadTimelineDayKey(base.getTime());
  const todayKey = timelineTodayKey();
  state.timeline.selectedDate = candidate >= todayKey ? "" : candidate;
  updateTimelineDayControls();
  await loadLoadTimelineHistory({ force: true });
}

async function jumpTimelineToToday() {
  if (isTimelineViewingToday()) return;
  state.timeline.selectedDate = "";
  updateTimelineDayControls();
  await loadLoadTimelineHistory({ force: true });
}

async function loadLoadTimelineHistory(options = {}) {
  const { force = false } = options;
  if (!hasBridge()) {
    renderLoadTimeline();
    return;
  }
  if (!state.config.loadControllerEnabled) {
    renderLoadTimeline();
    showToast("модуль load controller вимкнено");
    return;
  }

  const targetDate = state.timeline.selectedDate || "";
  const viewingToday = !targetDate;

  const nowMs = Date.now();
  if (
    !force &&
    viewingToday &&
    state.timeline.samples.length &&
    nowMs - state.timeline.lastHistoryFetchMs < LOAD_TIMELINE_HISTORY_REFRESH_MS
  ) {
    renderLoadTimeline();
    return;
  }

  setText("timelineChartTitle", "хронологія load controller - завантаження...");
  try {
    const loadHistoryPromise = bridgeRequest("timeline-history", (requestId) => {
      window.AndroidHub.fetchLoadControllerHistory(requestId, targetDate);
    });
    const garageHistoryPromise =
      viewingToday &&
      state.config.garageEnabled &&
      window.AndroidHub &&
      typeof window.AndroidHub.fetchGarageHistory === "function"
        ? bridgeRequest("timeline-garage-history", (requestId) => {
            window.AndroidHub.fetchGarageHistory(requestId);
          }).catch(() => null)
        : Promise.resolve(null);

    const [payload, garagePayload] = await Promise.all([loadHistoryPromise, garageHistoryPromise]);
    applyLoadTimelineHistory(payload);
    if (garagePayload) {
      applyGarageBoilerTimelineHistory(garagePayload);
    }
    setText("timelineChartTitle", "хронологія load controller");
    renderLoadTimeline();
  } catch (error) {
    setText("timelineChartTitle", "хронологія load controller - помилка");
    renderLoadTimeline();
    showToast(`не вдалося завантажити хронологію: ${error.message}`);
  } finally {
    state.timeline.lastHistoryFetchMs = nowMs;
    updateTimelineDayControls();
  }
}

async function openTimelineModal() {
  openModal("timelineModal");
  state.timeline.selectedDate = "";
  updateTimelineDayControls();
  await loadLoadTimelineHistory({ force: true });
}

function normalizeEventJournalViewMode(value) {
  if (value === "gateDaily") return "gateDaily";
  if (value === "cardDaily") return "cardDaily";
  return "all";
}

const CARD_EVENT_FILTERS = Object.freeze({
  // terms/exclude нижче звіряються (у нижньому регістрі) і з "kind" події, і
  // з "title"/"body" — а вони приходять з двох різних джерел, що поки що
  // різняться мовою: LocalEventEngine.kt (нативні сповіщення, вже українською
  // після цього перекладу) і сирі лог-рядки прошивок (ще англійською, доки їх
  // не перекладено окремо). Тому тут навмисно лишені ОБИДВІ мови — англійська
  // не видалена, українська додана поруч, — щоб фільтр не зламався для жодного
  // джерела в перехідний період.
  cardPv: Object.freeze({
    title: "події сонячної панелі",
    empty: "сьогодні подій сонячної панелі немає",
    modules: Object.freeze(["inverter"]),
    kinds: Object.freeze(["pv_generation"]),
    terms: Object.freeze(["pv", "solar", "generation", "сонячн", "генерац"]),
    exclude: Object.freeze(["grid", "load mode", "boiler", "pump", "gate", "door", "light", "мереж", "бойлер", "насос", "ворот", "світло"]),
  }),
  cardGrid: Object.freeze({
    title: "події мережі",
    empty: "сьогодні подій мережі немає",
    modules: Object.freeze(["inverter"]),
    kinds: Object.freeze(["grid_relay", "grid_presence", "grid_mode"]),
    terms: Object.freeze(["grid", "relay", "line voltage", "pin34", "мереж", "реле", "напруга мереж"]),
    exclude: Object.freeze(["gate", "door", "light", "ворот", "світло"]),
  }),
  cardBattery: Object.freeze({
    title: "події акумулятора",
    empty: "сьогодні подій акумулятора немає",
    modules: Object.freeze(["inverter"]),
    kinds: Object.freeze([]),
    terms: Object.freeze(["battery", "soc", "charge", "discharge", "voltage", "overload", "акб", "заряд", "акумулятор"]),
    exclude: Object.freeze(["boiler", "pump", "gate", "door", "light", "бойлер", "насос", "ворот", "світло"]),
  }),
  cardLoad: Object.freeze({
    title: "події навантаження",
    empty: "сьогодні подій навантаження немає",
    modules: Object.freeze(["inverter"]),
    kinds: Object.freeze(["load_mode", "power_alert"]),
    terms: Object.freeze(["load", "pinload", "overload", "load mode", "навантаж", "перевантаж"]),
    exclude: Object.freeze(["boiler", "pump", "gate", "door", "light", "бойлер", "насос", "ворот", "світло"]),
  }),
  cardBoiler1: Object.freeze({
    title: "події бойлера 1",
    empty: "сьогодні подій бойлера 1 немає",
    modules: Object.freeze(["load_controller"]),
    kinds: Object.freeze(["boiler1_mode", "boiler_mode"]),
    terms: Object.freeze([
      "boiler1",
      "boiler 1",
      "boiler mode",
      "boiler state",
      "boiler auto window",
      "boiler battery protection",
      "daily energy: boiler=",
      "reason:",
      "boiler",
      "бойлер 1",
      "бойлера 1",
      "бойлер",
      "причина:",
    ]),
    exclude: Object.freeze([]),
  }),
  cardPump: Object.freeze({
    title: "події насоса",
    empty: "сьогодні подій насоса немає",
    modules: Object.freeze(["load_controller"]),
    kinds: Object.freeze(["pump_mode"]),
    terms: Object.freeze(["pump", "pump mode", "pump state", "насос"]),
    exclude: Object.freeze(["boiler", "gate", "door", "light", "бойлер", "ворот", "світло"]),
  }),
  cardBoiler2: Object.freeze({
    title: "події бойлера 2",
    empty: "сьогодні подій бойлера 2 немає",
    modules: Object.freeze(["garage"]),
    kinds: Object.freeze(["boiler2_mode", "boiler_mode"]),
    terms: Object.freeze([
      "boiler2",
      "boiler 2",
      "boiler mode",
      "boiler state",
      "boiler auto window",
      "boiler battery protection",
      "daily energy: boiler=",
      "reason:",
      "boiler",
      "бойлер 2",
      "бойлера 2",
      "бойлер",
      "причина:",
    ]),
    exclude: Object.freeze([]),
  }),
  cardGate: Object.freeze({
    title: "події гаража",
    empty: "сьогодні подій гаража немає",
    modules: Object.freeze(["garage"]),
    kinds: Object.freeze(["gate_state"]),
    terms: Object.freeze(["gate", "door", "garage light", "light", "garage", "ворот", "світло гаража", "гараж"]),
    exclude: Object.freeze(["boiler", "pump"]),
  }),
});
function normalizeCardEventKey(value) {
  const key = safeText(value, "");
  return Object.prototype.hasOwnProperty.call(CARD_EVENT_FILTERS, key) ? key : "";
}

function cardEventFilter(cardKey) {
  const key = normalizeCardEventKey(cardKey);
  return key ? CARD_EVENT_FILTERS[key] : null;
}

function normalizeLogModuleName(value) {
  const raw = safeText(value, "unknown").toLowerCase();
  if (!raw) return "unknown";
  if (raw.includes("invert")) return "inverter";
  if (raw.includes("load")) return "load_controller";
  if (raw.includes("garage")) return "garage";
  return raw;
}

function matchCardEventFilter(cardFilter, payload = {}) {
  if (!cardFilter || typeof cardFilter !== "object") return false;

  const moduleName = normalizeLogModuleName(payload.moduleName);
  if (Array.isArray(cardFilter.modules) && cardFilter.modules.length) {
    if (!cardFilter.modules.includes(moduleName)) return false;
  }

  const searchable = [
    moduleName,
    safeText(payload.level, ""),
    safeText(payload.kind, ""),
    safeText(payload.title, ""),
    safeText(payload.body, ""),
    safeText(payload.message, ""),
  ]
    .join(" ")
    .toLowerCase();

  const kinds = Array.isArray(cardFilter.kinds) ? cardFilter.kinds : [];
  const kindText = safeText(payload.kind, "").toLowerCase();
  if (kinds.length && kindText) {
    const kindMatched = kinds.some((token) => token && kindText.includes(String(token).toLowerCase()));
    if (!kindMatched) return false;
  }

  const terms = Array.isArray(cardFilter.terms) ? cardFilter.terms : [];
  if (terms.length) {
    const includeMatched = terms.some((token) => token && searchable.includes(String(token).toLowerCase()));
    if (!includeMatched) return false;
  }

  const exclude = Array.isArray(cardFilter.exclude) ? cardFilter.exclude : [];
  const blocked = exclude.some((token) => token && searchable.includes(String(token).toLowerCase()));
  if (blocked) return false;

  return true;
}

function eventJournalViewTitle(viewMode) {
  const mode = normalizeEventJournalViewMode(viewMode);
  if (mode === "gateDaily") {
    return `історія воріт гаража (${loadTimelineDayKey(Date.now())})`;
  }
  if (mode === "cardDaily") {
    const cardFilter = cardEventFilter(state.events.cardKey);
    const date = safeText(state.events.cardDate, todayIso());
    return `${safeText(cardFilter?.title, "події картки")} (${date})`;
  }
  return "журнал подій";
}

function eventJournalEmptyText(viewMode) {
  const mode = normalizeEventJournalViewMode(viewMode);
  if (mode === "gateDaily") {
    return "сьогодні змін стану воріт немає";
  }
  if (mode === "cardDaily") {
    const cardFilter = cardEventFilter(state.events.cardKey);
    return safeText(cardFilter?.empty, "сьогодні подій картки немає");
  }
  return "подій немає";
}

function syncEventJournalView(viewMode, options = {}) {
  const mode = normalizeEventJournalViewMode(viewMode);
  state.events.viewMode = mode;
  if (mode === "cardDaily") {
    state.events.cardKey = normalizeCardEventKey(options.cardKey || state.events.cardKey);
    state.events.cardDate = safeText(options.cardDate || state.events.cardDate, todayIso());
  } else {
    state.events.cardKey = "";
    state.events.cardDate = "";
  }
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
  // "стан воріт змінено" / "причина:" відповідають заголовку й тілу, які
  // тепер шле LocalEventEngine.kt (раніше було "gate state changed"/"reason:").
  return title.includes("стан воріт змінено") || (title.includes("ворот") && body.includes("причина:"));
}

function filterEventJournalItems(items, viewMode) {
  const list = Array.isArray(items) ? items : [];
  const mode = normalizeEventJournalViewMode(viewMode);
  if (mode === "cardDaily") return list;
  if (mode !== "gateDaily") return list;
  const todayKey = loadTimelineDayKey(Date.now());
  return list.filter((entry) => isGateStateChangeEvent(entry) && eventDayKey(entry?.atMs) === todayKey);
}

function renderEventJournal(items, options = {}) {
  const root = document.getElementById("eventList");
  renderEventList(root, items, options);
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

function mapCardLogPayloadToEvents(payload, cardKey) {
  const cardFilter = cardEventFilter(cardKey);
  if (!cardFilter) return [];

  const defaultDate = safeText(payload?.date, todayIso());
  const rows = Array.isArray(payload?.items) ? payload.items : [];
  const events = [];

  rows.forEach((row) => {
    const message = safeText(row?.message, "");
    if (!message) return;

    const moduleName = normalizeLogModuleName(row?.module);
    if (!matchCardEventFilter(cardFilter, {
      moduleName,
      level: row?.level,
      message,
    })) return;

    const date = safeText(row?.date, defaultDate);
    const time = safeText(row?.time, "--:--:--");
    const parsedAtMs = Date.parse(`${date}T${time}`);
    const atMs = Number.isFinite(parsedAtMs) ? parsedAtMs : Date.now();
    const level = safeText(row?.level, "info").toLowerCase();
    const severity = level.includes("err") ? "alert" : level.includes("warn") ? "warn" : "info";

    events.push({
      atMs,
      atText: `${date} ${time}`,
      title: safeText(cardFilter.title, "події картки"),
      body: message,
      severity,
      kind: "log",
      module: moduleName,
    });
  });

  events.sort((a, b) => Number(b.atMs || 0) - Number(a.atMs || 0));
  return events;
}

function mapEventJournalPayloadToCardEvents(payload, cardKey, cardDate = "") {
  const cardFilter = cardEventFilter(cardKey);
  if (!cardFilter) return [];

  const expectedDate = safeText(cardDate, todayIso());
  const rows = Array.isArray(payload?.items) ? payload.items : [];
  const events = [];

  rows.forEach((row) => {
    const atMs = Number(row?.atMs);
    if (!Number.isFinite(atMs) || atMs <= 0) return;
    if (expectedDate && eventDayKey(atMs) !== expectedDate) return;

    const moduleName = normalizeLogModuleName(row?.module);
    if (!matchCardEventFilter(cardFilter, {
      moduleName,
      kind: row?.kind,
      title: row?.title,
      body: row?.body,
    })) return;

    events.push({
      atMs,
      atText: safeText(row?.atText, formatDateTimeShort(atMs)),
      title: safeText(row?.title, safeText(cardFilter.title, "події картки")),
      body: safeText(row?.body, ""),
      severity: safeText(row?.severity, "info").toLowerCase(),
      kind: safeText(row?.kind, "event"),
      module: moduleName,
    });
  });

  events.sort((a, b) => Number(b.atMs || 0) - Number(a.atMs || 0));
  return events;
}

async function loadCardDailyEvents(options = {}) {
  const cardKey = normalizeCardEventKey(options.cardKey || state.events.cardKey);
  if (!cardKey) {
    renderEventJournal([], { emptyText: "невідома картка" });
    setText("eventChartTitle", "події картки - помилка");
    return;
  }

  const cardDate = safeText(options.cardDate, todayIso());
  syncEventJournalView("cardDaily", { cardKey, cardDate });
  setText("eventChartTitle", `${eventJournalViewTitle("cardDaily")} - loading...`);

  if (!hasBridge()) {
    renderEventJournal([], { emptyText: eventJournalEmptyText("cardDaily") });
    showToast("міст Android недоступний");
    return;
  }
  if (!window.AndroidHub || typeof window.AndroidHub.fetchCardDailyEvents !== "function") {
    renderEventJournal([], { emptyText: eventJournalEmptyText("cardDaily") });
    showToast("міст логів картки недоступний");
    return;
  }

  try {
    const payload = await bridgeRequest(`card-events-${cardKey}`, (requestId) => {
      window.AndroidHub.fetchCardDailyEvents(cardKey, cardDate, requestId);
    });
    const items = mapCardLogPayloadToEvents(payload, cardKey);
    state.events.items = items;
    state.events.loadedAtMs = Date.now();
    renderEventJournal(items, { emptyText: eventJournalEmptyText("cardDaily") });
    setText("eventChartTitle", eventJournalViewTitle("cardDaily"));
  } catch (error) {
    try {
      const payload = await bridgeRequest(`card-events-fallback-${cardKey}`, (requestId) => {
        window.AndroidHub.fetchEventJournal(requestId);
      });
      const fallbackItems = mapEventJournalPayloadToCardEvents(payload, cardKey, cardDate);
      state.events.items = fallbackItems;
      state.events.loadedAtMs = Date.now();
      renderEventJournal(fallbackItems, { emptyText: eventJournalEmptyText("cardDaily") });
      setText("eventChartTitle", `${eventJournalViewTitle("cardDaily")} - fallback`);
      showToast(`логи картки недоступні: ${error.message}`);
    } catch (fallbackError) {
      renderEventJournal([], { emptyText: eventJournalEmptyText("cardDaily") });
      setText("eventChartTitle", `${eventJournalViewTitle("cardDaily")} - error`);
      showToast(`не вдалося отримати події картки: ${fallbackError.message || error.message}`);
    }
  }
}

async function loadGarageGateHistory() {
  const viewMode = "gateDaily";
  syncEventJournalView(viewMode);
  setText("eventChartTitle", eventJournalViewTitle(viewMode));

  if (!hasBridge()) {
    renderEventJournal([], { emptyText: eventJournalEmptyText(viewMode) });
    showToast("міст Android недоступний");
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
    showToast(`резервна історія гаража: ${error.message}`);
  }
}

async function loadEventJournal(options = {}) {
  const viewMode = normalizeEventJournalViewMode(options.viewMode || state.events.viewMode);
  syncEventJournalView(viewMode);
  setText("eventChartTitle", eventJournalViewTitle(viewMode));

  if (!hasBridge()) {
    renderEventJournal([], { emptyText: eventJournalEmptyText(viewMode) });
    showToast("міст Android недоступний");
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
    showToast(`не вдалося завантажити журнал подій: ${error.message}`);
  }
}

async function clearEventJournal() {
  if (!hasBridge()) {
    showToast("міст Android недоступний");
    return;
  }
  if (!window.confirm("очистити журнал подій?")) return;

  try {
    await bridgeRequest("events-clear", (requestId) => {
      window.AndroidHub.clearEventJournal(requestId);
    });
    state.events.items = [];
    state.events.loadedAtMs = Date.now();
    renderEventJournal([], { emptyText: eventJournalEmptyText(state.events.viewMode) });
    showToast("журнал подій очищено");
  } catch (error) {
    showToast(`не вдалося очистити журнал: ${error.message}`);
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

async function openCardDailyEventsModal(cardKey) {
  const normalized = normalizeCardEventKey(cardKey);
  if (!normalized) return;
  openModal("eventModal");
  await loadCardDailyEvents({
    cardKey: normalized,
    cardDate: todayIso(),
  });
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

function setModeButtonsLocked(buttonIds, locked) {
  (Array.isArray(buttonIds) ? buttonIds : [buttonIds]).forEach((buttonId) => {
    setModeButtonLocked(buttonId, locked);
  });
}

function setActiveModeGroup(prefix, mode) {
  const normalizedMode = safeText(mode, "").trim().toUpperCase();
  ["AUTO", "OFF", "ON"].forEach((item) => {
    const btn = document.getElementById(`${prefix}${item}`);
    if (!btn) return;
    btn.classList.toggle("active", item === normalizedMode);
  });
}

function setActiveModeGroups(prefixes, mode) {
  (Array.isArray(prefixes) ? prefixes : [prefixes]).forEach((prefix) => {
    setActiveModeGroup(prefix, mode);
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

function applyLockedActiveButtonGroups(prefixes, lockMode) {
  (Array.isArray(prefixes) ? prefixes : [prefixes]).forEach((prefix) => {
    applyLockedActiveButtons(prefix, lockMode);
  });
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
  const returnToLogicParent = id === "logicModal" && state.logic.returnModalId.length > 0;
  const logicReturnModalId = state.logic.returnModalId;

  modal.classList.remove("is-open");

  if (id === "boiler1Modal") {
    setAutoWindowEditorOpen("boiler1", false);
  } else if (id === "pumpModal") {
    setAutoWindowEditorOpen("pump", false);
  } else if (id === "boiler2Modal") {
    setAutoWindowEditorOpen("boiler2", false);
  } else if (id === "logicModal") {
    state.logic.formDirty = false;
    state.logic.currentKey = "";
  }

  if (returnToScheme) {
    state.schemeControlReturnToSchemeModalId = "";
    state.schemeControlLandscape = true;
    openModal("schemeModal");
    return;
  }

  if (returnToLogicParent) {
    state.logic.returnModalId = "";
    openModal(logicReturnModalId);
    return;
  }

  if (isSchemeControlModal(id)) {
    state.schemeControlReturnToSchemeModalId = "";
  }

  if (id === "logicModal") {
    state.logic.returnModalId = "";
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
  state.logic.currentKey = "";
  state.logic.returnModalId = "";
  state.logic.formDirty = false;
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

function formatLogicAutoWindowFact(enabled, start, end, active) {
  if (!enabled) return "вікно АВТО: активне завжди";
  return `вікно АВТО: ${safeText(start, "00:00")}-${safeText(end, "00:00")} (${active ? "зараз активне" : "зараз неактивне"})`;
}

function getLogicModalDefinition(key) {
  const defs = {
    grid: {
      moduleKey: "inverter",
      title: "логіка АВТО мережі",
      getModule: (status) => status?.inverter || null,
      getConfig: (status) => status?.inverter?.gridLogic || null,
      getMode: (status) => safeText(status?.inverter?.mode),
      getState: (status) => boolText(!!status?.inverter?.gridRelayOn),
      fields: [
        { key: "pvThresholdW", label: "поріг PV", unit: "Вт", step: "1", min: "0", max: "20000" },
        { key: "offDelaySec", label: "затримка ВИКЛ мережі", unit: "с", step: "1", min: "0", max: "86400" },
        { key: "onDelaySec", label: "затримка УВІМК мережі", unit: "с", step: "1", min: "0", max: "86400" },
        { key: "forceGridOnW", label: "примусове УВІМК мережі за навантаженням", unit: "Вт", step: "1", min: "0", max: "20000" },
        { key: "batteryLowSocPct", label: "заряд рятування АКБ", unit: "%", step: "0.1", min: "0", max: "100" },
        { key: "offMinSocPct", label: "мін. заряд для ВИКЛ мережі", unit: "%", step: "0.1", min: "0", max: "100" },
      ],
      getFacts: (_status, cfg) => [
        `рятування АКБ нижче ${num(cfg.batteryLowSocPct, 0)}%`,
        `ВИКЛ мережі дозволено лише вище ${num(cfg.offMinSocPct, 0)}% заряду АКБ`,
      ],
      getSteps: (_status, cfg) => [
        { tone: "warn", when: `навантаження > ${num(cfg.forceGridOnW, 0)} Вт`, action: "негайно УВІМК мережу", fields: ["forceGridOnW"] },
        { tone: "good", when: `PV >= ${num(cfg.pvThresholdW, 0)} Вт і заряд > ${num(cfg.offMinSocPct, 0)} %`, delay: `зачекати ${num(cfg.offDelaySec, 0)} с`, action: "ВИКЛ мережу", fields: ["pvThresholdW", "offMinSocPct", "offDelaySec"] },
        { tone: "warn", when: `PV < ${num(cfg.pvThresholdW, 0)} Вт`, delay: `зачекати ${num(cfg.onDelaySec, 0)} с`, action: "УВІМК мережу", fields: ["pvThresholdW", "onDelaySec"] },
        { tone: "alert", when: `заряд АКБ < ${num(cfg.batteryLowSocPct, 0)} %`, action: "негайно УВІМК мережу", fields: ["batteryLowSocPct"] },
      ],
      fixedNote: () => "Усі числові пороги цієї логіки редагуються і зберігаються на контролері.",
      invokeSave: (values, requestId) => {
        window.AndroidHub.setInverterGridLogic(
          values.pvThresholdW,
          values.offDelaySec,
          values.onDelaySec,
          values.forceGridOnW,
          values.batteryLowSocPct,
          values.offMinSocPct,
          requestId,
        );
      },
      saveSuccessMessage: "логіку мережі оновлено",
    },
    load: {
      moduleKey: "inverter",
      title: "логіка АВТО навантаження",
      getModule: (status) => status?.inverter || null,
      getConfig: (status) => status?.inverter?.loadLogic || null,
      getMode: (status) => safeText(status?.inverter?.loadMode),
      getState: (status) => boolText(!!status?.inverter?.loadRelayOn),
      fields: [
        { key: "pvThresholdW", label: "поріг PV", unit: "Вт", step: "1", min: "0", max: "20000" },
        { key: "shutdownDelaySec", label: "затримка вимкнення", unit: "с", step: "1", min: "0", max: "86400" },
        { key: "overloadPowerW", label: "поріг перевантаження", unit: "Вт", step: "1", min: "0", max: "20000" },
        { key: "gridRestoreV", label: "поріг відновлення мережі", unit: "В", step: "0.1", min: "100", max: "300" },
        { key: "overloadGridV", label: "захист перевантаження по мережі", unit: "В", step: "0.1", min: "100", max: "300" },
      ],
      getFacts: (_status, cfg) => [
        `поріг відновлення мережі ${num(cfg.gridRestoreV, 0)} В`,
        `захист перевантаження діє лише нижче ${num(cfg.overloadGridV, 0)} В мережі`,
      ],
      getSteps: (_status, cfg) => [
        { tone: "alert", when: `навантаження > ${num(cfg.overloadPowerW, 0)} Вт і мережа < ${num(cfg.overloadGridV, 0)} В`, action: "режим перемикається на ВИКЛ", fields: ["overloadPowerW", "overloadGridV"] },
        { tone: "warn", when: `PV < ${num(cfg.pvThresholdW, 0)} Вт і мережа < ${num(cfg.gridRestoreV, 0)} В`, delay: `зачекати ${num(cfg.shutdownDelaySec, 0)} с`, action: "реле навантаження ВИКЛ", fields: ["pvThresholdW", "gridRestoreV", "shutdownDelaySec"] },
        { tone: "good", when: `PV >= ${num(cfg.pvThresholdW, 0)} Вт або мережа >= ${num(cfg.gridRestoreV, 0)} В`, action: "реле навантаження УВІМК", fields: ["pvThresholdW", "gridRestoreV"] },
      ],
      fixedNote: () => "Усі числові пороги цієї логіки редагуються і зберігаються на контролері.",
      invokeSave: (values, requestId) => {
        window.AndroidHub.setInverterLoadLogic(
          values.pvThresholdW,
          values.shutdownDelaySec,
          values.overloadPowerW,
          values.gridRestoreV,
          values.overloadGridV,
          requestId,
        );
      },
      saveSuccessMessage: "логіку навантаження оновлено",
    },
    boiler1: {
      moduleKey: "loadController",
      title: "логіка АВТО бойлера 1",
      getModule: (status) => status?.loadController || null,
      getConfig: (status) => status?.loadController?.boilerLogic || null,
      getMode: (status) => safeText(status?.loadController?.boiler1Mode),
      getState: (status) => boolText(!!status?.loadController?.boiler1On),
      fields: [
        { key: "pvThresholdW", label: "поріг PV", unit: "Вт", step: "1", min: "0", max: "20000" },
        { key: "shutdownDelaySec", label: "затримка вимкнення", unit: "с", step: "1", min: "0", max: "86400" },
        { key: "batteryShutoffW", label: "вимкнення по АКБ", unit: "Вт", step: "1", min: "-10000", max: "0" },
        { key: "batteryResumeW", label: "відновлення по АКБ", unit: "Вт", step: "1", min: "-1000", max: "10000" },
        { key: "peerActiveW", label: "поріг іншого бойлера", unit: "Вт", step: "1", min: "0", max: "20000" },
        { key: "gridRestoreV", label: "поріг відновлення мережі", unit: "В", step: "0.1", min: "100", max: "300" },
        { key: "batteryReleaseGridV", label: "звільнення захисту АКБ по мережі", unit: "В", step: "0.1", min: "100", max: "300" },
        { key: "batteryReleaseSocPct", label: "звільнення захисту АКБ по заряду", unit: "%", step: "0.1", min: "0", max: "100" },
      ],
      getFacts: (status, cfg) => [
        formatLogicAutoWindowFact(
          !!status?.loadController?.boiler1AutoWindowEnabled,
          status?.loadController?.boiler1AutoWindowStart,
          status?.loadController?.boiler1AutoWindowEnd,
          status?.loadController?.boiler1AutoWindowActive !== false,
        ),
        `звільнення захисту АКБ: мережа > ${num(cfg.batteryReleaseGridV, 0)} В або заряд > ${num(cfg.batteryReleaseSocPct, 0)} %`,
      ],
      getSteps: (_status, cfg) => [
        { tone: "warn", when: "поза вікном АВТО", action: "бойлер ВИКЛ", fields: [] },
        { tone: "alert", when: `потужність_АКБ <= ${num(cfg.batteryShutoffW, 0)} Вт`, action: "захисна фіксація АКБ", fields: ["batteryShutoffW"] },
        { tone: "good", when: `потужність_АКБ >= ${num(cfg.batteryResumeW, 0)} Вт`, action: "захисну фіксацію АКБ можна зняти", fields: ["batteryResumeW"] },
        { tone: "warn", when: `інший бойлер > ${num(cfg.peerActiveW, 0)} Вт`, action: "бойлер ВИКЛ", fields: ["peerActiveW"] },
        { tone: "warn", when: `PV < ${num(cfg.pvThresholdW, 0)} Вт і мережа < ${num(cfg.gridRestoreV, 0)} В`, delay: `зачекати ${num(cfg.shutdownDelaySec, 0)} с`, action: "бойлер ВИКЛ", fields: ["pvThresholdW", "gridRestoreV", "shutdownDelaySec"] },
        { tone: "good", when: `PV >= ${num(cfg.pvThresholdW, 0)} Вт або мережа >= ${num(cfg.gridRestoreV, 0)} В`, action: "бойлер УВІМК", fields: ["pvThresholdW", "gridRestoreV"] },
      ],
      fixedNote: () => "Усі числові пороги цієї логіки редагуються і зберігаються на контролері.",
      invokeSave: (values, requestId) => {
        window.AndroidHub.setBoiler1Logic(
          values.pvThresholdW,
          values.shutdownDelaySec,
          values.batteryShutoffW,
          values.batteryResumeW,
          values.peerActiveW,
          values.gridRestoreV,
          values.batteryReleaseGridV,
          values.batteryReleaseSocPct,
          requestId,
        );
      },
      saveSuccessMessage: "логіку бойлера 1 оновлено",
    },
    pump: {
      moduleKey: "loadController",
      title: "логіка АВТО насоса",
      getModule: (status) => status?.loadController || null,
      getConfig: (status) => status?.loadController?.pumpLogic || null,
      getMode: (status) => safeText(status?.loadController?.pumpMode),
      getState: (status) => boolText(!!status?.loadController?.pumpOn),
      fields: [
        { key: "pvThresholdW", label: "поріг PV", unit: "Вт", step: "1", min: "0", max: "20000" },
        { key: "shutdownDelaySec", label: "затримка вимкнення", unit: "с", step: "1", min: "0", max: "86400" },
        { key: "gridRestoreV", label: "поріг відновлення мережі", unit: "В", step: "0.1", min: "100", max: "300" },
      ],
      getFacts: (status, cfg) => [
        formatLogicAutoWindowFact(
          !!status?.loadController?.pumpAutoWindowEnabled,
          status?.loadController?.pumpAutoWindowStart,
          status?.loadController?.pumpAutoWindowEnd,
          status?.loadController?.pumpAutoWindowActive !== false,
        ),
        `поріг відновлення мережі ${num(cfg.gridRestoreV, 0)} В`,
      ],
      getSteps: (_status, cfg) => [
        { tone: "warn", when: "поза вікном АВТО", action: "насос ВИКЛ", fields: [] },
        { tone: "warn", when: `PV < ${num(cfg.pvThresholdW, 0)} Вт і мережа < ${num(cfg.gridRestoreV, 0)} В`, delay: `зачекати ${num(cfg.shutdownDelaySec, 0)} с`, action: "насос ВИКЛ", fields: ["pvThresholdW", "gridRestoreV", "shutdownDelaySec"] },
        { tone: "good", when: `PV >= ${num(cfg.pvThresholdW, 0)} Вт або мережа >= ${num(cfg.gridRestoreV, 0)} В`, action: "насос УВІМК", fields: ["pvThresholdW", "gridRestoreV"] },
      ],
      fixedNote: () => "Усі числові пороги цієї логіки редагуються і зберігаються на контролері.",
      invokeSave: (values, requestId) => {
        window.AndroidHub.setPumpLogic(values.pvThresholdW, values.shutdownDelaySec, values.gridRestoreV, requestId);
      },
      saveSuccessMessage: "логіку насоса оновлено",
    },
    boiler2: {
      moduleKey: "garage",
      title: "логіка АВТО бойлера 2",
      getModule: (status) => status?.garage || null,
      getConfig: (status) => status?.garage?.boilerLogic || null,
      getMode: (status) => safeText(status?.garage?.boiler2Mode),
      getState: (status) => boolText(!!status?.garage?.boiler2On),
      fields: [
        { key: "pvThresholdW", label: "поріг PV", unit: "Вт", step: "1", min: "0", max: "20000" },
        { key: "shutdownDelaySec", label: "затримка вимкнення", unit: "с", step: "1", min: "0", max: "86400" },
        { key: "batteryShutoffW", label: "вимкнення по АКБ", unit: "Вт", step: "1", min: "-10000", max: "0" },
        { key: "batteryResumeW", label: "відновлення по АКБ", unit: "Вт", step: "1", min: "-1000", max: "10000" },
        { key: "peerActiveW", label: "поріг іншого бойлера", unit: "Вт", step: "1", min: "0", max: "20000" },
        { key: "gridRestoreV", label: "поріг відновлення мережі", unit: "В", step: "0.1", min: "100", max: "300" },
        { key: "batteryReleaseGridV", label: "звільнення захисту АКБ по мережі", unit: "В", step: "0.1", min: "100", max: "300" },
        { key: "batteryReleaseSocPct", label: "звільнення захисту АКБ по заряду", unit: "%", step: "0.1", min: "0", max: "100" },
      ],
      getFacts: (status, cfg) => [
        formatLogicAutoWindowFact(
          !!status?.garage?.boiler2AutoWindowEnabled,
          status?.garage?.boiler2AutoWindowStart,
          status?.garage?.boiler2AutoWindowEnd,
          status?.garage?.boiler2AutoWindowActive !== false,
        ),
        `звільнення захисту АКБ: мережа > ${num(cfg.batteryReleaseGridV, 0)} В або заряд > ${num(cfg.batteryReleaseSocPct, 0)} %`,
      ],
      getSteps: (_status, cfg) => [
        { tone: "warn", when: "поза вікном АВТО", action: "бойлер ВИКЛ", fields: [] },
        { tone: "alert", when: `потужність_АКБ <= ${num(cfg.batteryShutoffW, 0)} Вт`, action: "захисна фіксація АКБ", fields: ["batteryShutoffW"] },
        { tone: "good", when: `потужність_АКБ >= ${num(cfg.batteryResumeW, 0)} Вт`, action: "захисну фіксацію АКБ можна зняти", fields: ["batteryResumeW"] },
        { tone: "warn", when: `інший бойлер > ${num(cfg.peerActiveW, 0)} Вт`, action: "бойлер ВИКЛ", fields: ["peerActiveW"] },
        { tone: "warn", when: `PV < ${num(cfg.pvThresholdW, 0)} Вт і мережа < ${num(cfg.gridRestoreV, 0)} В`, delay: `зачекати ${num(cfg.shutdownDelaySec, 0)} с`, action: "бойлер ВИКЛ", fields: ["pvThresholdW", "gridRestoreV", "shutdownDelaySec"] },
        { tone: "good", when: `PV >= ${num(cfg.pvThresholdW, 0)} Вт або мережа >= ${num(cfg.gridRestoreV, 0)} В`, action: "бойлер УВІМК", fields: ["pvThresholdW", "gridRestoreV"] },
      ],
      fixedNote: () => "Усі числові пороги цієї логіки редагуються і зберігаються на контролері.",
      invokeSave: (values, requestId) => {
        window.AndroidHub.setBoiler2Logic(
          values.pvThresholdW,
          values.shutdownDelaySec,
          values.batteryShutoffW,
          values.batteryResumeW,
          values.peerActiveW,
          values.gridRestoreV,
          values.batteryReleaseGridV,
          values.batteryReleaseSocPct,
          requestId,
        );
      },
      saveSuccessMessage: "логіку бойлера 2 оновлено",
    },
  };

  return defs[key] || null;
}

function buildLogicFieldMarkup(field, value, disabled) {
  const safeValue = Number.isFinite(Number(value)) ? String(value) : "";
  return `
    <label class="logic-field">
      <span class="logic-field-label">${escapeHtml(field.label)}${field.unit ? `, ${escapeHtml(field.unit)}` : ""}</span>
      <input
        class="logic-field-input"
        type="number"
        data-logic-field="${escapeHtml(field.key)}"
        step="${escapeHtml(field.step || "1")}"
        min="${escapeHtml(field.min || "")}"
        max="${escapeHtml(field.max || "")}"
        value="${escapeHtml(safeValue)}"
        ${disabled ? "disabled" : ""}
      >
    </label>
  `;
}

function buildLogicFlowMarkup(steps, defFields) {
  const safeSteps = Array.isArray(steps) ? steps : [];
  if (!safeSteps.length) {
    return '<div class="logic-flow-empty">кроки логіки недоступні</div>';
  }
  const fieldByKey = new Map((Array.isArray(defFields) ? defFields : []).map((f) => [f.key, f]));
  const expanded = state.logic.expandedRules instanceof Set ? state.logic.expandedRules : new Set();

  // Правила перевіряються по черзі зверху вниз (як if/else if): щойно умова
  // одного з них справджується - решта в цьому циклі не діють. За
  // замовчуванням кожне правило згорнуто до одного рядка "ЯКЩО ... -> ...";
  // деталі (затримка, пов'язані поля, гілка "інакше") розкриваються дотиком,
  // щоб уся логіка не виглядала суцільною стіною карток.
  let markup = '<div class="logic-flow-entry"><span>перевірка по черзі зверху вниз, на кожному циклі АВТО</span></div>';
  safeSteps.forEach((step, index) => {
    const tone = safeText(step?.tone, "warn");
    const delay = safeText(step?.delay, "");
    const whenText = safeText(step?.when, "---");
    const actionText = safeText(step?.action, "---");
    const isLast = index === safeSteps.length - 1;
    const elseText = isLast
      ? "інакше: залишити поточний стан реле"
      : `інакше → правило ${String(index + 2).padStart(2, "0")}`;
    const fieldTags = (Array.isArray(step?.fields) ? step.fields : [])
      .map((key) => fieldByKey.get(key))
      .filter(Boolean)
      .map(
        (f) =>
          `<button type="button" class="logic-flow-field-tag" data-logic-jump="${escapeHtml(f.key)}">${escapeHtml(f.label)}</button>`,
      )
      .join("");
    const isExpanded = expanded.has(index);
    markup += `
      <section class="logic-flow-rule ${escapeHtml(tone)}${isExpanded ? " is-expanded" : ""}" data-logic-rule="${index}">
        <button type="button" class="logic-flow-rule-summary" data-logic-rule-toggle="${index}">
          <span class="logic-flow-rule-dot" aria-hidden="true"></span>
          <span class="logic-flow-rule-index">${String(index + 1).padStart(2, "0")}</span>
          <span class="logic-flow-rule-summary-text"><b>ЯКЩО</b> ${escapeHtml(whenText)} <b>→</b> ${escapeHtml(actionText)}</span>
          <span class="logic-flow-rule-caret" aria-hidden="true">⌄</span>
        </button>
        <div class="logic-flow-rule-details">
          <div class="logic-flow-condition">
            <span class="logic-flow-condition-badge">ЯКЩО</span>
            <span class="logic-flow-condition-text">${escapeHtml(whenText)}</span>
          </div>
          <div class="logic-flow-action">
            <span class="logic-flow-action-badge">ТО</span>
            <span class="logic-flow-action-text">${escapeHtml(actionText)}</span>
            ${delay ? `<span class="logic-flow-node-delay">${escapeHtml(delay)}</span>` : ""}
          </div>
          ${fieldTags ? `<div class="logic-flow-field-tags">${fieldTags}</div>` : ""}
          <div class="logic-flow-else">${escapeHtml(elseText)}</div>
        </div>
      </section>
    `;
  });
  markup += '<div class="logic-flow-exit"><span>повторюється на кожному оновленні статусу</span></div>';
  return markup;
}

function toggleLogicFlowRule(index) {
  const key = Number(index);
  if (!Number.isFinite(key)) return;
  if (!(state.logic.expandedRules instanceof Set)) {
    state.logic.expandedRules = new Set();
  }
  if (state.logic.expandedRules.has(key)) {
    state.logic.expandedRules.delete(key);
  } else {
    state.logic.expandedRules.add(key);
  }
  const rule = document.querySelector(`.logic-flow-rule[data-logic-rule="${key}"]`);
  if (rule) {
    rule.classList.toggle("is-expanded", state.logic.expandedRules.has(key));
  }
}

function scrollToLogicField(key) {
  if (!key) return;
  const input = document.querySelector(`#logicModalForm [data-logic-field="${key}"]`);
  if (!(input instanceof HTMLElement)) return;
  const field = input.closest(".logic-field") || input;
  field.scrollIntoView({ behavior: "smooth", block: "center" });
  field.classList.remove("is-highlighted");
  void field.offsetWidth;
  field.classList.add("is-highlighted");
  setTimeout(() => field.classList.remove("is-highlighted"), 1600);
  if (typeof input.focus === "function") {
    input.focus({ preventScroll: true });
  }
}

function formatDurationCompact(totalSec) {
  const safeSec = Math.max(0, Math.round(Number(totalSec) || 0));
  if (safeSec < 60) return `${safeSec}s`;
  const hours = Math.floor(safeSec / 3600);
  const minutes = Math.floor((safeSec % 3600) / 60);
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

function resizeHiDpiCanvas(canvas) {
  const rect = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  const width = Math.max(1, Math.floor(rect.width * ratio));
  const height = Math.max(1, Math.floor(rect.height * ratio));
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
}

function normalizeAutomationHistoryPayload(payload) {
  const rawItems = Array.isArray(payload?.items) ? payload.items : [];
  const items = rawItems
    .map((item) => ({
      atMs: Number(item?.atMs) || 0,
      inverterOnline: !!item?.inverterOnline,
      loadControllerOnline: !!item?.loadControllerOnline,
      garageOnline: !!item?.garageOnline,
      pvW: maybeFiniteNumber(item?.pvW, null),
      gridW: maybeFiniteNumber(item?.gridW, null),
      loadW: maybeFiniteNumber(item?.loadW, null),
      batterySoc: maybeFiniteNumber(item?.batterySoc, null),
      batteryPower: maybeFiniteNumber(item?.batteryPower, null),
      inverterLineVoltage: maybeFiniteNumber(item?.inverterLineVoltage, null),
      loadLineVoltage: maybeFiniteNumber(item?.loadLineVoltage, null),
      garageLineVoltage: maybeFiniteNumber(item?.garageLineVoltage, null),
      gridRelayOn: typeof item?.gridRelayOn === "boolean" ? item.gridRelayOn : null,
      gridMode: safeText(item?.gridMode, ""),
      loadRelayOn: typeof item?.loadRelayOn === "boolean" ? item.loadRelayOn : null,
      loadMode: safeText(item?.loadMode, ""),
      boiler1On: typeof item?.boiler1On === "boolean" ? item.boiler1On : null,
      boiler1Mode: safeText(item?.boiler1Mode, ""),
      boiler1PowerW: maybeFiniteNumber(item?.boiler1PowerW, null),
      boiler1AutoWindowActive: typeof item?.boiler1AutoWindowActive === "boolean" ? item.boiler1AutoWindowActive : null,
      pumpOn: typeof item?.pumpOn === "boolean" ? item.pumpOn : null,
      pumpMode: safeText(item?.pumpMode, ""),
      pumpPowerW: maybeFiniteNumber(item?.pumpPowerW, null),
      pumpAutoWindowActive: typeof item?.pumpAutoWindowActive === "boolean" ? item.pumpAutoWindowActive : null,
      boiler2On: typeof item?.boiler2On === "boolean" ? item.boiler2On : null,
      boiler2Mode: safeText(item?.boiler2Mode, ""),
      boiler2PowerW: maybeFiniteNumber(item?.boiler2PowerW, null),
      boiler2AutoWindowActive: typeof item?.boiler2AutoWindowActive === "boolean" ? item.boiler2AutoWindowActive : null,
      garageLightOn: typeof item?.garageLightOn === "boolean" ? item.garageLightOn : null,
      gateState: safeText(item?.gateState, ""),
    }))
    .filter((item) => item.atMs > 0)
    .sort((a, b) => a.atMs - b.atMs);

  return {
    hours: Math.max(1, Number(payload?.hours) || AUTOMATION_HISTORY_DEFAULT_HOURS),
    items,
  };
}

function applyAutomationHistory(payload) {
  const mapped = normalizeAutomationHistoryPayload(payload);
  state.automationHistory.items = mapped.items;
  state.automationHistory.hours = mapped.hours;
  state.automationHistory.loadedAtMs = Date.now();
}

function appendAutomationHistorySampleFromStatus(status) {
  if (!status || typeof status !== "object") return;
  const hadHydratedHistory = Number(state.automationHistory.loadedAtMs || 0) > 0;
  const inverter = status.inverter && typeof status.inverter === "object" ? status.inverter : null;
  const loadController = status.loadController && typeof status.loadController === "object" ? status.loadController : null;
  const garage = status.garage && typeof status.garage === "object" ? status.garage : null;
  const atMs = Number(status.updatedAtMs || inverter?.updatedAtMs || loadController?.updatedAtMs || garage?.updatedAtMs) || Date.now();
  const sample = {
    atMs,
    inverterOnline: !!inverter,
    loadControllerOnline: !!loadController,
    garageOnline: !!garage,
    pvW: maybeFiniteNumber(inverter?.pvW ?? loadController?.pvW ?? garage?.pvW, null),
    gridW: maybeFiniteNumber(inverter?.gridW ?? loadController?.gridW ?? garage?.gridW, null),
    loadW: maybeFiniteNumber(inverter?.loadW ?? loadController?.loadW ?? garage?.loadW, null),
    batterySoc: maybeFiniteNumber(inverter?.batterySoc ?? loadController?.batterySoc ?? garage?.batterySoc, null),
    batteryPower: maybeFiniteNumber(inverter?.batteryPower ?? loadController?.batteryPower ?? garage?.batteryPower, null),
    inverterLineVoltage: maybeFiniteNumber(inverter?.lineVoltage, null),
    loadLineVoltage: maybeFiniteNumber(loadController?.lineVoltage, null),
    garageLineVoltage: maybeFiniteNumber(garage?.lineVoltage, null),
    gridRelayOn: typeof inverter?.gridRelayOn === "boolean" ? inverter.gridRelayOn : null,
    gridMode: safeText(inverter?.mode, ""),
    loadRelayOn: typeof inverter?.loadRelayOn === "boolean" ? inverter.loadRelayOn : null,
    loadMode: safeText(inverter?.loadMode, ""),
    boiler1On: typeof loadController?.boiler1On === "boolean" ? loadController.boiler1On : null,
    boiler1Mode: safeText(loadController?.boiler1Mode, ""),
    boiler1PowerW: maybeFiniteNumber(loadController?.boilerPower, null),
    boiler1AutoWindowActive: typeof loadController?.boiler1AutoWindowActive === "boolean" ? loadController.boiler1AutoWindowActive : null,
    pumpOn: typeof loadController?.pumpOn === "boolean" ? loadController.pumpOn : null,
    pumpMode: safeText(loadController?.pumpMode, ""),
    pumpPowerW: maybeFiniteNumber(loadController?.pumpPower, null),
    pumpAutoWindowActive: typeof loadController?.pumpAutoWindowActive === "boolean" ? loadController.pumpAutoWindowActive : null,
    boiler2On: typeof garage?.boiler2On === "boolean" ? garage.boiler2On : null,
    boiler2Mode: safeText(garage?.boiler2Mode, ""),
    boiler2PowerW: maybeFiniteNumber(garage?.boilerPower, null),
    boiler2AutoWindowActive: typeof garage?.boiler2AutoWindowActive === "boolean" ? garage.boiler2AutoWindowActive : null,
    garageLightOn: typeof garage?.garageLightOn === "boolean" ? garage.garageLightOn : null,
    gateState: safeText(garage?.gateState, ""),
  };

  const items = Array.isArray(state.automationHistory.items) ? state.automationHistory.items.slice() : [];
  const last = items[items.length - 1];
  if (!last || sample.atMs > last.atMs) {
    items.push(sample);
  } else {
    return;
  }
  const fromMs = sample.atMs - AUTOMATION_HISTORY_DEFAULT_HOURS * 60 * 60 * 1000;
  state.automationHistory.items = items.filter((item) => Number(item?.atMs) >= fromMs);
  state.automationHistory.hours = Math.max(Number(state.automationHistory.hours || 0), AUTOMATION_HISTORY_DEFAULT_HOURS);
  if (hadHydratedHistory) {
    state.automationHistory.loadedAtMs = Date.now();
  }
}

async function ensureAutomationHistory(hours = AUTOMATION_HISTORY_DEFAULT_HOURS, options = {}) {
  const { force = false, silent = false } = options;
  const safeHours = Math.max(1, Math.min(6, Math.round(Number(hours) || AUTOMATION_HISTORY_DEFAULT_HOURS)));
  const cachedHours = Number(state.automationHistory.hours || 0);
  const freshEnough = Date.now() - Number(state.automationHistory.loadedAtMs || 0) < AUTOMATION_HISTORY_REFRESH_MS;
  if (!force && freshEnough && cachedHours >= safeHours && state.automationHistory.items.length) {
    return state.automationHistory.items;
  }
  if (!hasBridge() || !state.capabilities?.automationHistory) {
    return state.automationHistory.items;
  }

  try {
    const payload = await bridgeRequest("automation-history", (requestId) => {
      window.AndroidHub.fetchAutomationHistory(safeHours, requestId);
    });
    applyAutomationHistory(payload);
    state.alerts.active = collectActiveAlerts();
    renderSystemAlerts();
    if (isModalOpen("logicModal")) {
      renderLogicModal({ force: true });
    }
    return state.automationHistory.items;
  } catch (error) {
    if (!silent) {
      showToast(`не вдалося завантажити історію автоматики: ${error.message}`);
    }
    return state.automationHistory.items;
  }
}

function automationHistoryItems(hours = state.logic.historyHours || LOGIC_HISTORY_DEFAULT_HOURS) {
  const safeHours = Math.max(1, Math.min(6, Math.round(Number(hours) || LOGIC_HISTORY_DEFAULT_HOURS)));
  const items = Array.isArray(state.automationHistory.items) ? state.automationHistory.items : [];
  if (!items.length) return [];
  const latestAtMs = items[items.length - 1].atMs || Date.now();
  const fromMs = latestAtMs - safeHours * 60 * 60 * 1000;
  return items.filter((item) => item.atMs >= fromMs);
}

function logicStateValue(logicKey, sample) {
  switch (logicKey) {
    case "grid": return typeof sample?.gridRelayOn === "boolean" ? sample.gridRelayOn : null;
    case "load": return typeof sample?.loadRelayOn === "boolean" ? sample.loadRelayOn : null;
    case "boiler1": return typeof sample?.boiler1On === "boolean" ? sample.boiler1On : null;
    case "pump": return typeof sample?.pumpOn === "boolean" ? sample.pumpOn : null;
    case "boiler2": return typeof sample?.boiler2On === "boolean" ? sample.boiler2On : null;
    default: return null;
  }
}

function logicModeValue(logicKey, sample) {
  switch (logicKey) {
    case "grid": return safeText(sample?.gridMode, "");
    case "load": return safeText(sample?.loadMode, "");
    case "boiler1": return safeText(sample?.boiler1Mode, "");
    case "pump": return safeText(sample?.pumpMode, "");
    case "boiler2": return safeText(sample?.boiler2Mode, "");
    default: return "";
  }
}

function measureConditionDurationSec(samples, index, predicate) {
  if (!Array.isArray(samples) || !samples[index] || typeof predicate !== "function") return 0;
  if (!predicate(samples[index])) return 0;
  let startAtMs = samples[index].atMs;
  for (let cursor = index; cursor > 0; cursor -= 1) {
    const prev = samples[cursor - 1];
    if (!predicate(prev)) break;
    startAtMs = prev.atMs;
  }
  return Math.max(0, Math.round((samples[index].atMs - startAtMs) / 1000));
}

function logicTransitionReason(logicKey, samples, index, cfg, nextState) {
  const sample = samples[index] || {};
  const pvW = Number(sample.pvW);
  const loadW = Number(sample.loadW);
  const batterySoc = Number(sample.batterySoc);
  const batteryPower = Number(sample.batteryPower);
  const invGridV = Number(sample.inverterLineVoltage);
  const loadGridV = Number(sample.loadLineVoltage);
  const garageGridV = Number(sample.garageLineVoltage);
  const peerGarageW = Number(sample.boiler2PowerW);
  const peerHouseW = Number(sample.boiler1PowerW);

  switch (logicKey) {
    case "grid":
      if (nextState) {
        if (Number.isFinite(loadW) && loadW >= Number(cfg.forceGridOnW)) {
          return `навантаження ${num(loadW, 0)}Вт >= ${num(cfg.forceGridOnW, 0)}Вт`;
        }
        if (Number.isFinite(batterySoc) && batterySoc <= Number(cfg.batteryLowSocPct)) {
          return `заряд ${num(batterySoc, 0)}% <= ${num(cfg.batteryLowSocPct, 0)}%`;
        }
        return `PV ${num(pvW, 0)}Вт < ${num(cfg.pvThresholdW, 0)}Вт протягом ${formatDurationCompact(
          measureConditionDurationSec(samples, index, (row) => Number(row?.pvW) < Number(cfg.pvThresholdW)),
        )}`;
      }
      return `PV ${num(pvW, 0)}Вт >= ${num(cfg.pvThresholdW, 0)}Вт і заряд ${num(batterySoc, 0)}% >= ${num(cfg.offMinSocPct, 0)}% протягом ${formatDurationCompact(
        measureConditionDurationSec(
          samples,
          index,
          (row) => Number(row?.pvW) >= Number(cfg.pvThresholdW) && Number(row?.batterySoc) >= Number(cfg.offMinSocPct),
        ),
      )}`;
    case "load":
      if (!nextState) {
        if (Number.isFinite(loadW) && loadW >= Number(cfg.overloadPowerW)) {
          return `навантаження ${num(loadW, 0)}Вт >= ${num(cfg.overloadPowerW, 0)}Вт`;
        }
        return `PV ${num(pvW, 0)}Вт < ${num(cfg.pvThresholdW, 0)}Вт і мережа ${num(invGridV, 0)}В < ${num(cfg.gridRestoreV, 0)}В протягом ${formatDurationCompact(
          measureConditionDurationSec(
            samples,
            index,
            (row) => Number(row?.pvW) < Number(cfg.pvThresholdW) && Number(row?.inverterLineVoltage) < Number(cfg.gridRestoreV),
          ),
        )}`;
      }
      if (Number.isFinite(invGridV) && invGridV >= Number(cfg.gridRestoreV)) {
        return `мережа ${num(invGridV, 0)}В >= ${num(cfg.gridRestoreV, 0)}В`;
      }
      return `PV ${num(pvW, 0)}Вт >= ${num(cfg.pvThresholdW, 0)}Вт`;
    case "boiler1":
      if (!nextState) {
        if (sample.boiler1AutoWindowActive === false) return "поза вікном АВТО";
        if (Number.isFinite(batteryPower) && batteryPower <= Number(cfg.batteryShutoffW)) {
          return `АКБ ${num(batteryPower, 0)}Вт <= ${num(cfg.batteryShutoffW, 0)}Вт`;
        }
        if (Number.isFinite(peerGarageW) && peerGarageW >= Number(cfg.peerActiveW)) {
          return `інший бойлер ${num(peerGarageW, 0)}Вт >= ${num(cfg.peerActiveW, 0)}Вт`;
        }
        return `PV ${num(pvW, 0)}Вт < ${num(cfg.pvThresholdW, 0)}Вт і мережа ${num(loadGridV, 0)}В < ${num(cfg.gridRestoreV, 0)}В протягом ${formatDurationCompact(
          measureConditionDurationSec(
            samples,
            index,
            (row) => Number(row?.pvW) < Number(cfg.pvThresholdW) && Number(row?.loadLineVoltage) < Number(cfg.gridRestoreV),
          ),
        )}`;
      }
      if (Number.isFinite(loadGridV) && loadGridV >= Number(cfg.gridRestoreV)) {
        return `мережа ${num(loadGridV, 0)}В >= ${num(cfg.gridRestoreV, 0)}В`;
      }
      if (Number.isFinite(batteryPower) && batteryPower >= Number(cfg.batteryResumeW)) {
        return `АКБ ${num(batteryPower, 0)}Вт >= ${num(cfg.batteryResumeW, 0)}Вт`;
      }
      return `PV ${num(pvW, 0)}Вт >= ${num(cfg.pvThresholdW, 0)}Вт`;
    case "pump":
      if (!nextState) {
        if (sample.pumpAutoWindowActive === false) return "поза вікном АВТО";
        return `PV ${num(pvW, 0)}Вт < ${num(cfg.pvThresholdW, 0)}Вт і мережа ${num(loadGridV, 0)}В < ${num(cfg.gridRestoreV, 0)}В протягом ${formatDurationCompact(
          measureConditionDurationSec(
            samples,
            index,
            (row) => Number(row?.pvW) < Number(cfg.pvThresholdW) && Number(row?.loadLineVoltage) < Number(cfg.gridRestoreV),
          ),
        )}`;
      }
      if (Number.isFinite(loadGridV) && loadGridV >= Number(cfg.gridRestoreV)) {
        return `мережа ${num(loadGridV, 0)}В >= ${num(cfg.gridRestoreV, 0)}В`;
      }
      return `PV ${num(pvW, 0)}Вт >= ${num(cfg.pvThresholdW, 0)}Вт`;
    case "boiler2":
      if (!nextState) {
        if (sample.boiler2AutoWindowActive === false) return "поза вікном АВТО";
        if (Number.isFinite(batteryPower) && batteryPower <= Number(cfg.batteryShutoffW)) {
          return `АКБ ${num(batteryPower, 0)}Вт <= ${num(cfg.batteryShutoffW, 0)}Вт`;
        }
        if (Number.isFinite(peerHouseW) && peerHouseW >= Number(cfg.peerActiveW)) {
          return `інший бойлер ${num(peerHouseW, 0)}Вт >= ${num(cfg.peerActiveW, 0)}Вт`;
        }
        return `PV ${num(pvW, 0)}Вт < ${num(cfg.pvThresholdW, 0)}Вт і мережа ${num(garageGridV, 0)}В < ${num(cfg.gridRestoreV, 0)}В протягом ${formatDurationCompact(
          measureConditionDurationSec(
            samples,
            index,
            (row) => Number(row?.pvW) < Number(cfg.pvThresholdW) && Number(row?.garageLineVoltage) < Number(cfg.gridRestoreV),
          ),
        )}`;
      }
      if (Number.isFinite(garageGridV) && garageGridV >= Number(cfg.gridRestoreV)) {
        return `мережа ${num(garageGridV, 0)}В >= ${num(cfg.gridRestoreV, 0)}В`;
      }
      if (Number.isFinite(batteryPower) && batteryPower >= Number(cfg.batteryResumeW)) {
        return `АКБ ${num(batteryPower, 0)}Вт >= ${num(cfg.batteryResumeW, 0)}Вт`;
      }
      return `PV ${num(pvW, 0)}Вт >= ${num(cfg.pvThresholdW, 0)}Вт`;
    default:
      return "виявлено перехід";
  }
}

function deriveLogicTransitions(logicKey, samples, cfg) {
  const out = [];
  for (let index = 1; index < samples.length; index += 1) {
    const prev = samples[index - 1];
    const curr = samples[index];
    const prevState = logicStateValue(logicKey, prev);
    const currState = logicStateValue(logicKey, curr);
    const prevMode = logicModeValue(logicKey, prev);
    const currMode = logicModeValue(logicKey, curr);

    if (typeof prevState === "boolean" && typeof currState === "boolean" && prevState !== currState) {
      const stateLabel = currState ? "ON" : "OFF";
      out.push({
        atMs: curr.atMs,
        title: `relay -> ${stateLabel}`,
        body: logicTransitionReason(logicKey, samples, index, cfg, currState),
        severity: currState ? "info" : "warn",
        kind: "transition",
        module: logicCapability(logicKey).moduleKey || "hub",
      });
      continue;
    }

    if (prevMode && currMode && prevMode !== currMode) {
      out.push({
        atMs: curr.atMs,
        title: `mode ${prevMode} -> ${currMode}`,
        body: "режим змінився без перемикання реле",
        severity: "info",
        kind: "mode",
        module: logicCapability(logicKey).moduleKey || "hub",
      });
    }
  }
  return out.slice(-14).reverse();
}

function buildLogicChartModel(logicKey, samples, cfg) {
  switch (logicKey) {
    case "grid":
      return {
        series: [
          { key: "pvW", label: "PV", color: "#ffb347", values: samples.map((row) => row.pvW) },
          { key: "loadW", label: "LOAD", color: "#ff4d6d", values: samples.map((row) => row.loadW) },
        ],
        thresholds: [
          { label: "PV thr", value: cfg.pvThresholdW, color: "rgba(255,179,71,0.55)" },
          { label: "Force GRID", value: cfg.forceGridOnW, color: "rgba(255,77,109,0.55)" },
        ],
      };
    case "load":
      return {
        series: [
          { key: "pvW", label: "PV", color: "#ffb347", values: samples.map((row) => row.pvW) },
          { key: "loadW", label: "LOAD", color: "#ff4d6d", values: samples.map((row) => row.loadW) },
        ],
        thresholds: [
          { label: "PV thr", value: cfg.pvThresholdW, color: "rgba(255,179,71,0.55)" },
          { label: "Overload", value: cfg.overloadPowerW, color: "rgba(255,77,109,0.55)" },
        ],
      };
    case "boiler1":
      return {
        series: [
          { key: "pvW", label: "PV", color: "#ffb347", values: samples.map((row) => row.pvW) },
          { key: "batteryPower", label: "BAT", color: "#33ff99", values: samples.map((row) => row.batteryPower) },
          { key: "peer", label: "B2", color: "#ff4d6d", values: samples.map((row) => row.boiler2PowerW) },
        ],
        thresholds: [
          { label: "PV thr", value: cfg.pvThresholdW, color: "rgba(255,179,71,0.55)" },
          { label: "BAT stop", value: cfg.batteryShutoffW, color: "rgba(51,255,153,0.55)" },
          { label: "BAT resume", value: cfg.batteryResumeW, color: "rgba(51,255,153,0.35)" },
          { label: "Peer", value: cfg.peerActiveW, color: "rgba(255,77,109,0.55)" },
        ],
      };
    case "pump":
      return {
        series: [
          { key: "pvW", label: "PV", color: "#ffb347", values: samples.map((row) => row.pvW) },
          { key: "pumpPowerW", label: "PUMP", color: "#4f7cff", values: samples.map((row) => row.pumpPowerW) },
        ],
        thresholds: [
          { label: "PV thr", value: cfg.pvThresholdW, color: "rgba(255,179,71,0.55)" },
        ],
      };
    case "boiler2":
      return {
        series: [
          { key: "pvW", label: "PV", color: "#ffb347", values: samples.map((row) => row.pvW) },
          { key: "batteryPower", label: "BAT", color: "#33ff99", values: samples.map((row) => row.batteryPower) },
          { key: "peer", label: "B1", color: "#ff4d6d", values: samples.map((row) => row.boiler1PowerW) },
        ],
        thresholds: [
          { label: "PV thr", value: cfg.pvThresholdW, color: "rgba(255,179,71,0.55)" },
          { label: "BAT stop", value: cfg.batteryShutoffW, color: "rgba(51,255,153,0.55)" },
          { label: "BAT resume", value: cfg.batteryResumeW, color: "rgba(51,255,153,0.35)" },
          { label: "Peer", value: cfg.peerActiveW, color: "rgba(255,77,109,0.55)" },
        ],
      };
    default:
      return { series: [], thresholds: [] };
  }
}

function drawLogicHistoryChart(logicKey, cfg, samples) {
  const canvas = document.getElementById("logicHistoryCanvas");
  if (!canvas) return;
  resizeHiDpiCanvas(canvas);
  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  const width = canvas.width;
  const height = canvas.height;
  ctx.clearRect(0, 0, width, height);

  if (!samples.length) {
    ctx.fillStyle = "rgba(230,241,255,0.72)";
    ctx.font = `${12 * (window.devicePixelRatio || 1)}px "Playpen Sans", sans-serif`;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText("history is empty", width / 2, height / 2);
    return;
  }

  const model = buildLogicChartModel(logicKey, samples, cfg);
  const padding = { left: 52, right: 16, top: 18, bottom: 36 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const bandHeight = Math.max(14 * (window.devicePixelRatio || 1), plotHeight * 0.14);
  const lineHeight = plotHeight - bandHeight - 12;
  const minTs = samples[0].atMs;
  const maxTs = samples[samples.length - 1].atMs || (minTs + 1);
  const rangeTs = Math.max(1, maxTs - minTs);
  const xFor = (atMs) => padding.left + ((atMs - minTs) / rangeTs) * plotWidth;

  const values = [];
  model.series.forEach((serie) => {
    serie.values.forEach((value) => {
      const parsed = Number(value);
      if (Number.isFinite(parsed)) values.push(parsed);
    });
  });
  model.thresholds.forEach((line) => {
    const parsed = Number(line?.value);
    if (Number.isFinite(parsed)) values.push(parsed);
  });

  let minValue = values.length ? Math.min(...values) : 0;
  let maxValue = values.length ? Math.max(...values) : 1;
  if (minValue === maxValue) {
    minValue -= 1;
    maxValue += 1;
  }
  const span = maxValue - minValue;
  minValue -= span * 0.12;
  maxValue += span * 0.12;
  const yFor = (value) => padding.top + ((maxValue - value) / Math.max(1, maxValue - minValue)) * lineHeight;

  ctx.fillStyle = "rgba(255,255,255,0.04)";
  ctx.fillRect(padding.left, padding.top, plotWidth, lineHeight);
  ctx.strokeStyle = "rgba(255,255,255,0.08)";
  ctx.lineWidth = 1;
  ctx.strokeRect(padding.left, padding.top, plotWidth, lineHeight);

  ctx.font = `${11 * (window.devicePixelRatio || 1)}px "Playpen Sans", sans-serif`;
  ctx.fillStyle = "rgba(230,241,255,0.5)";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";
  for (let step = 0; step <= 4; step += 1) {
    const y = padding.top + (lineHeight / 4) * step;
    const value = maxValue - ((maxValue - minValue) / 4) * step;
    ctx.strokeStyle = "rgba(79,124,255,0.15)";
    ctx.beginPath();
    ctx.moveTo(padding.left, y);
    ctx.lineTo(padding.left + plotWidth, y);
    ctx.stroke();
    ctx.fillText(`${Math.round(value)}`, padding.left - 8, y);
  }

  model.thresholds.forEach((line, index) => {
    const value = Number(line?.value);
    if (!Number.isFinite(value)) return;
    const y = yFor(value);
    ctx.save();
    ctx.setLineDash([6, 5]);
    ctx.strokeStyle = line.color || "rgba(255,255,255,0.35)";
    ctx.beginPath();
    ctx.moveTo(padding.left, y);
    ctx.lineTo(padding.left + plotWidth, y);
    ctx.stroke();
    ctx.restore();
    ctx.fillStyle = line.color || "rgba(255,255,255,0.6)";
    ctx.textAlign = "left";
    ctx.textBaseline = index % 2 === 0 ? "bottom" : "top";
    ctx.fillText(`${line.label}: ${Math.round(value)}`, padding.left + 4, y + (index % 2 === 0 ? -2 : 2));
  });

  model.series.forEach((serie) => {
    ctx.strokeStyle = serie.color;
    ctx.lineWidth = 2.2 * (window.devicePixelRatio || 1);
    ctx.beginPath();
    let moved = false;
    samples.forEach((sample, index) => {
      const value = Number(serie.values[index]);
      if (!Number.isFinite(value)) return;
      const x = xFor(sample.atMs);
      const y = yFor(value);
      if (!moved) {
        ctx.moveTo(x, y);
        moved = true;
      } else {
        ctx.lineTo(x, y);
      }
    });
    ctx.stroke();
  });

  const bandTop = padding.top + lineHeight + 12;
  ctx.fillStyle = "rgba(255,255,255,0.05)";
  ctx.fillRect(padding.left, bandTop, plotWidth, bandHeight);
  let segmentStart = null;
  samples.forEach((sample, index) => {
    const on = logicStateValue(logicKey, sample);
    if (on === true && segmentStart === null) {
      segmentStart = sample.atMs;
    }
    const nextSample = samples[index + 1];
    if (segmentStart !== null && (on !== true || !nextSample)) {
      const xStart = xFor(segmentStart);
      const xEnd = xFor(nextSample ? nextSample.atMs : sample.atMs);
      ctx.fillStyle = "rgba(51,255,153,0.62)";
      ctx.fillRect(xStart, bandTop, Math.max(2, xEnd - xStart), bandHeight);
      segmentStart = null;
    }
  });
  ctx.fillStyle = "rgba(230,241,255,0.55)";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";
  ctx.fillText("STATE", padding.left - 8, bandTop + bandHeight / 2);

  const tickCount = Math.min(6, Math.max(2, Math.floor(plotWidth / (90 * (window.devicePixelRatio || 1)))));
  ctx.textAlign = "center";
  ctx.textBaseline = "top";
  for (let tick = 0; tick <= tickCount; tick += 1) {
    const ratio = tick / tickCount;
    const atMs = minTs + rangeTs * ratio;
    const x = padding.left + plotWidth * ratio;
    ctx.strokeStyle = "rgba(255,255,255,0.08)";
    ctx.beginPath();
    ctx.moveTo(x, padding.top);
    ctx.lineTo(x, bandTop + bandHeight);
    ctx.stroke();
    ctx.fillStyle = "rgba(230,241,255,0.48)";
    ctx.fillText(formatClockFromMs(atMs), x, bandTop + bandHeight + 6);
  }
}

function renderEventList(root, entries, options = {}) {
  const { emptyText = "no events" } = options;
  if (!root) return;
  if (!Array.isArray(entries) || !entries.length) {
    root.innerHTML = `<div class="event-item-empty">${escapeHtml(emptyText)}</div>`;
    return;
  }

  root.innerHTML = entries.map((entry) => {
    const severity = safeText(entry?.severity, "info").toLowerCase();
    const module = safeText(entry?.module, "");
    const kind = safeText(entry?.kind, "");
    const metaParts = [module, kind].filter(Boolean);
    return `
      <div class="event-item severity-${escapeHtml(severity)}">
        <div class="event-item-head">
          <div class="event-item-title">${escapeHtml(safeText(entry?.title, "event"))}</div>
          <div class="event-item-time">${escapeHtml(safeText(entry?.atText, formatDateTimeShort(entry?.atMs)))}</div>
        </div>
        <div class="event-item-body">${escapeHtml(safeText(entry?.body, ""))}</div>
        ${metaParts.length ? `<div class="event-item-meta">${escapeHtml(metaParts.join(" | "))}</div>` : ""}
      </div>
    `;
  }).join("");
}

function formatDateTimeShort(atMs) {
  const safeMs = Number(atMs);
  if (!Number.isFinite(safeMs) || safeMs <= 0) return "--";
  const date = new Date(safeMs);
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  const mon = String(date.getMonth() + 1).padStart(2, "0");
  return `${dd}.${mon} ${hh}:${mm}`;
}

function collectActiveAlerts() {
  const status = state.status || {};
  const caps = state.capabilities || buildHubCapabilities(status);
  const alerts = [];
  const hasSignalHistory = Object.values(state.moduleSignalAtMs || {}).some((value) => Number(value) > 0);
  const pushAlert = (key, title, body, severity = "alert", logicKey = "") => {
    alerts.push({ key, title, body, severity, logicKey });
  };

  if (hasSignalHistory) {
    if (state.config.inverterEnabled && !moduleHasFreshSignal("inverter", true, status?.inverter)) {
      pushAlert("offline-inverter", "інвертор офлайн", "немає свіжого статусу від інвертора", "alert");
    }
    if (state.config.loadControllerEnabled && !moduleHasFreshSignal("loadController", true, status?.loadController)) {
      pushAlert("offline-load", "load controller офлайн", "немає свіжого статусу від load controller", "alert");
    }
    if (state.config.garageEnabled && !moduleHasFreshSignal("garage", true, status?.garage)) {
      pushAlert("offline-garage", "гараж офлайн", "немає свіжого статусу від контролера гаража", "alert");
    }
  }

  const loadCfg = status?.inverter?.loadLogic;
  if (status?.inverter && loadCfg && Number(status.inverter.loadW) > Number(loadCfg.overloadPowerW || 0)) {
    pushAlert(
      "load-overload",
      "перевантаження навантаження",
      `LOAD ${num(status.inverter.loadW, 0)}W > ${num(loadCfg.overloadPowerW, 0)}W`,
      "alert",
      "load",
    );
  }

  const recentHistory = automationHistoryItems(AUTOMATION_HISTORY_DEFAULT_HOURS);
  if (recentHistory.length) {
    LOGIC_KEYS.forEach((logicKey) => {
      if (!caps.logicKeys.includes(logicKey)) return;
      let transitions = 0;
      for (let index = 1; index < recentHistory.length; index += 1) {
        const prev = logicStateValue(logicKey, recentHistory[index - 1]);
        const curr = logicStateValue(logicKey, recentHistory[index]);
        if (typeof prev === "boolean" && typeof curr === "boolean" && prev !== curr) {
          if (recentHistory[recentHistory.length - 1].atMs - recentHistory[index].atMs <= LOGIC_UNSTABLE_WINDOW_MS) {
            transitions += 1;
          }
        }
      }
      if (transitions >= LOGIC_UNSTABLE_TRANSITIONS) {
        pushAlert(
          `unstable-${logicKey}`,
          `${logicKey} unstable`,
          `${transitions} switches in the last 30 min`,
          "warn",
          logicKey,
        );
      }
    });
  }

  return alerts.slice(0, 8);
}

function renderSystemAlerts() {
  const strip = document.getElementById("alertsStrip");
  const list = document.getElementById("alertsList");
  if (!strip || !list) return;
  const alerts = state.alerts.active = collectActiveAlerts();
  if (!alerts.length) {
    strip.hidden = true;
    list.innerHTML = "";
    return;
  }
  strip.hidden = false;
  list.innerHTML = alerts.map((alert) => `
    <div class="alert-pill is-${escapeHtml(safeText(alert.severity, "alert"))}">
      <strong>${escapeHtml(safeText(alert.title, "alert"))}</strong>
      <span>${escapeHtml(safeText(alert.body, ""))}</span>
    </div>
  `).join("");
}

function applyCapabilityDrivenUi(status) {
  LOGIC_KEYS.forEach((logicKey) => {
    const def = getLogicModalDefinition(logicKey);
    const moduleState = def ? def.getModule(status || {}) : null;
    const configEnabled = def?.moduleKey === "inverter"
      ? !!state.config.inverterEnabled
      : def?.moduleKey === "loadController"
        ? !!state.config.loadControllerEnabled
        : def?.moduleKey === "garage"
          ? !!state.config.garageEnabled
          : false;
    const available = configEnabled && isLogicAvailable(logicKey, status);
    document.querySelectorAll(`[data-open-logic="${logicKey}"]`).forEach((btn) => {
      btn.hidden = !available;
      btn.disabled = !available || !moduleState;
    });
  });
}

function renderLogicModal({ force = false } = {}) {
  if (!isModalOpen("logicModal") && !force) return;
  const activeElement = document.activeElement;
  const formHasFocus = activeElement instanceof Element && !!activeElement.closest("#logicModalForm");
  const preserveForm = (state.logic.formDirty || formHasFocus) && !force;

  const def = getLogicModalDefinition(state.logic.currentKey);
  const status = state.status || {};
  const moduleState = def ? def.getModule(status) : null;
  const config = def ? def.getConfig(status) : null;
  const disabled = !def || !moduleState || !config;

  setText("logicModalTitle", def ? def.title : "mode logic");
  setText("logicModalMode", disabled ? "режим: недоступно" : `режим: ${def.getMode(status)}`);
  setText("logicModalState", disabled ? "стан: недоступно" : `стан: ${def.getState(status)}`);

  const factsEl = document.getElementById("logicModalFacts");
  const alertsEl = document.getElementById("logicModalAlerts");
  const flowEl = document.getElementById("logicModalFlow");
  const formEl = document.getElementById("logicModalForm");
  const fixedNoteEl = document.getElementById("logicModalFixedNote");
  const saveBtn = document.getElementById("logicModalSave");
  const historyTitleEl = document.getElementById("logicHistoryTitle");
  const historyMetaEl = document.getElementById("logicHistoryMeta");
  const journalEl = document.getElementById("logicJournalList");

  document.querySelectorAll("[data-logic-hours]").forEach((btn) => {
    btn.classList.toggle("active", Number(btn.getAttribute("data-logic-hours")) === Number(state.logic.historyHours));
  });

  if (!def || disabled) {
    if (factsEl) factsEl.innerHTML = '<div class="logic-fact-chip">конфігурація логіки недоступна</div>';
    if (alertsEl) alertsEl.innerHTML = "";
    if (flowEl) flowEl.innerHTML = "";
    if (formEl) formEl.innerHTML = "";
    if (fixedNoteEl) fixedNoteEl.textContent = "Оновіть прошивку пристрою й статус, щоб редагувати цю логіку.";
    if (historyTitleEl) historyTitleEl.textContent = "історія логіки";
    if (historyMetaEl) historyMetaEl.textContent = "історія недоступна";
    drawLogicHistoryChart(state.logic.currentKey, config || {}, []);
    renderEventList(journalEl, [], { emptyText: "журнал логіки недоступний" });
    if (saveBtn) saveBtn.disabled = true;
    return;
  }

  const facts = def.getFacts(status, config);
  const steps = def.getSteps(status, config);

  if (factsEl) {
    factsEl.innerHTML = facts.map((text) => `<div class="logic-fact-chip">${escapeHtml(text)}</div>`).join("");
  }

  if (alertsEl) {
    const logicAlerts = (state.alerts.active || []).filter((alert) => {
      if (alert.logicKey && alert.logicKey === state.logic.currentKey) return true;
      if (safeText(def.moduleKey, "") === "inverter") return alert.key === "offline-inverter";
      if (safeText(def.moduleKey, "") === "loadController") return alert.key === "offline-load";
      if (safeText(def.moduleKey, "") === "garage") return alert.key === "offline-garage";
      return false;
    });
    alertsEl.innerHTML = logicAlerts
      .slice(0, 4)
      .map((alert) => `<div class="logic-alert-chip">${escapeHtml(`${alert.title}: ${alert.body}`)}</div>`)
      .join("");
  }

  if (flowEl) {
    flowEl.innerHTML = buildLogicFlowMarkup(steps, def.fields);
  }

  const historySamples = automationHistoryItems(state.logic.historyHours);
  const transitions = deriveLogicTransitions(state.logic.currentKey, historySamples, config);
  if (historyTitleEl) {
    historyTitleEl.textContent = `історія: ${def.title}`;
  }
  if (historyMetaEl) {
    historyMetaEl.textContent = historySamples.length
      ? `вікно: ${state.logic.historyHours}г | зразків: ${historySamples.length} | переходів: ${transitions.length}`
      : `вікно: ${state.logic.historyHours}г | історія з'явиться після першої синхронізації`;
  }
  drawLogicHistoryChart(state.logic.currentKey, config, historySamples);
  renderEventList(journalEl, transitions, { emptyText: "у вибраному вікні переходів немає" });

  if (formEl && !preserveForm) {
    formEl.innerHTML = def.fields.map((field) => buildLogicFieldMarkup(field, config[field.key], false)).join("");
    formEl.querySelectorAll("[data-logic-field]").forEach((input) => {
      input.addEventListener("input", () => {
        state.logic.formDirty = true;
      });
    });
  }

  if (fixedNoteEl) {
    fixedNoteEl.textContent = def.fixedNote(config);
  }
  if (saveBtn) saveBtn.disabled = false;
}

function readLogicModalValues(def) {
  const values = {};
  for (const field of def.fields) {
    const input = document.querySelector(`#logicModalForm [data-logic-field="${field.key}"]`);
    if (!(input instanceof HTMLInputElement)) {
      throw new Error(`missing field ${field.key}`);
    }
    const parsed = Number(input.value);
    if (!Number.isFinite(parsed)) {
      throw new Error(`${field.label} is invalid`);
    }
    const minValue = field.min === "" || field.min == null ? null : Number(field.min);
    const maxValue = field.max === "" || field.max == null ? null : Number(field.max);
    if (Number.isFinite(minValue) && parsed < minValue) {
      throw new Error(`${field.label} must be >= ${field.min}`);
    }
    if (Number.isFinite(maxValue) && parsed > maxValue) {
      throw new Error(`${field.label} must be <= ${field.max}`);
    }
    values[field.key] = field.step === "1" ? Math.round(parsed) : parsed;
  }
  return values;
}

function openLogicModal(logicKey, returnModalId = "") {
  if (!getLogicModalDefinition(logicKey) || !isLogicAvailable(logicKey)) return;
  if (state.logic.currentKey !== logicKey) {
    state.logic.expandedRules = new Set();
  }
  state.logic.currentKey = logicKey;
  state.logic.returnModalId = returnModalId || "";
  state.logic.formDirty = false;
  if (returnModalId && isModalOpen(returnModalId)) {
    closeModal(returnModalId);
  }
  openModal("logicModal");
  renderLogicModal({ force: true });
  void ensureAutomationHistory(Math.max(state.logic.historyHours, AUTOMATION_HISTORY_DEFAULT_HOURS), { silent: true });
}

async function saveLogicModalConfig() {
  const def = getLogicModalDefinition(state.logic.currentKey);
  if (!def) return;

  let values;
  try {
    values = readLogicModalValues(def);
  } catch (error) {
    showToast(error.message || "некоректні значення логіки");
    return;
  }

  try {
    await bridgeRequest("cmd", (requestId) => {
      def.invokeSave(values, requestId);
    });
    state.logic.formDirty = false;
    showToast(def.saveSuccessMessage);
    await requestStatus();
    renderLogicModal({ force: true });
  } catch (error) {
    showToast(`${def.title} failed: ${error.message}`);
  }
}

function resetLogicModalForm() {
  state.logic.formDirty = false;
  renderLogicModal({ force: true });
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

  document.querySelectorAll("[data-card-events-btn]").forEach((btn) => {
    btn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      const cardKey = btn.getAttribute("data-card-events-btn");
      if (!cardKey) return;
      openCardDailyEventsModal(cardKey);
    });
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
      if (state.events.viewMode === "cardDaily") {
        loadCardDailyEvents({
          cardKey: state.events.cardKey,
          cardDate: state.events.cardDate || todayIso(),
        });
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

  const timelineDayPrevBtn = document.getElementById("timelineDayPrevBtn");
  if (timelineDayPrevBtn) {
    timelineDayPrevBtn.addEventListener("click", () => {
      shiftTimelineDay(-1);
    });
  }

  const timelineDayNextBtn = document.getElementById("timelineDayNextBtn");
  if (timelineDayNextBtn) {
    timelineDayNextBtn.addEventListener("click", () => {
      shiftTimelineDay(1);
    });
  }

  const timelineDayTodayBtn = document.getElementById("timelineDayTodayBtn");
  if (timelineDayTodayBtn) {
    timelineDayTodayBtn.addEventListener("click", () => {
      jumpTimelineToToday();
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

  document.querySelectorAll("[data-open-logic]").forEach((btn) => {
    btn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      const logicKey = btn.getAttribute("data-open-logic");
      if (!logicKey) return;
      openLogicModal(logicKey, btn.getAttribute("data-logic-parent") || "");
    });
  });

  document.querySelectorAll("[data-logic-hours]").forEach((btn) => {
    btn.addEventListener("click", async (event) => {
      event.preventDefault();
      event.stopPropagation();
      const hours = Math.max(1, Math.min(6, Number(btn.getAttribute("data-logic-hours")) || LOGIC_HISTORY_DEFAULT_HOURS));
      state.logic.historyHours = hours;
      renderLogicModal({ force: true });
      await ensureAutomationHistory(Math.max(hours, AUTOMATION_HISTORY_DEFAULT_HOURS), { silent: true });
    });
  });

  document.querySelectorAll("[data-close-modal]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const modalId = btn.getAttribute("data-close-modal");
      if (modalId) closeModal(modalId);
    });
  });

  const logicSaveBtn = document.getElementById("logicModalSave");
  if (logicSaveBtn) {
    logicSaveBtn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      saveLogicModalConfig();
    });
  }

  const logicResetBtn = document.getElementById("logicModalReset");
  if (logicResetBtn) {
    logicResetBtn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      resetLogicModalForm();
    });
  }

  const logicFlowEl = document.getElementById("logicModalFlow");
  if (logicFlowEl) {
    logicFlowEl.addEventListener("click", (event) => {
      const jumpTag = event.target.closest("[data-logic-jump]");
      if (jumpTag) {
        scrollToLogicField(jumpTag.getAttribute("data-logic-jump"));
        return;
      }
      const toggleBtn = event.target.closest("[data-logic-rule-toggle]");
      if (toggleBtn) {
        toggleLogicFlowRule(toggleBtn.getAttribute("data-logic-rule-toggle"));
      }
    });
  }

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
    if (!silent) showToast(`режим мережі: ${mode}`);
  } catch (error) {
    showToast(`не вдалося змінити режим мережі: ${error.message}`);
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
      showToast(locked ? "УВІМК навантаження зафіксовано" : "фіксацію УВІМК навантаження знято");
    }
    return true;
  } catch (error) {
    showToast(`не вдалося зафіксувати навантаження: ${error.message}`);
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
    if (!silent) showToast(`режим навантаження: ${mode}`);
  } catch (error) {
    showToast(`не вдалося змінити режим навантаження: ${error.message}`);
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
    if (!silent) showToast(`фіксація бойлера 1: ${lockMode}`);
    return true;
  } catch (error) {
    showToast(`не вдалося зафіксувати бойлер 1: ${error.message}`);
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
    if (!silent) showToast(`режим бойлера 1: ${mode}`);
  } catch (error) {
    showToast(`не вдалося змінити режим бойлера 1: ${error.message}`);
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
    if (!silent) showToast(`фіксація насоса: ${lockMode}`);
    return true;
  } catch (error) {
    showToast(`не вдалося зафіксувати насос: ${error.message}`);
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
    if (!silent) showToast(`режим насоса: ${mode}`);
  } catch (error) {
    showToast(`не вдалося змінити режим насоса: ${error.message}`);
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
      statusEl.textContent = "модуль вимкнено";
    } else if (!enabled) {
      statusEl.textContent = "АВТО: активний завжди";
    } else {
      statusEl.classList.add("enabled");
      statusEl.classList.add(active ? "active-now" : "inactive-now");
      statusEl.textContent = `АВТО: ${normalizedStart}-${normalizedEnd} (${active ? "зараз активне" : "зараз неактивне"})`;
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
    showToast("таймер АВТО бойлера 1 оновлено");
  } catch (error) {
    showToast(`не вдалося оновити таймер бойлера 1: ${error.message}`);
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
    showToast("таймер АВТО насоса оновлено");
  } catch (error) {
    showToast(`не вдалося оновити таймер насоса: ${error.message}`);
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
    showToast("таймер АВТО бойлера 2 оновлено");
  } catch (error) {
    showToast(`не вдалося оновити таймер бойлера 2: ${error.message}`);
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
    if (!silent) showToast(`фіксація бойлера 2: ${lockMode}`);
    return true;
  } catch (error) {
    showToast(`не вдалося зафіксувати бойлер 2: ${error.message}`);
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
    if (!silent) showToast(`режим бойлера 2: ${mode}`);
  } catch (error) {
    showToast(`не вдалося змінити режим бойлера 2: ${error.message}`);
  }
}

async function triggerGate() {
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.triggerGate(requestId);
    });
    showToast("команду на ворота надіслано");
  } catch (error) {
    showToast(`не вдалося надіслати команду воротам: ${error.message}`);
  }
}

async function toggleGarageLight() {
  try {
    await bridgeRequest("cmd", (requestId) => {
      window.AndroidHub.toggleGarageLight(requestId);
    });
    showToast("світло гаража перемкнено");
  } catch (error) {
    showToast(`не вдалося перемкнути світло гаража: ${error.message}`);
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

function bindPointerClickMany(buttonIds, handler) {
  (Array.isArray(buttonIds) ? buttonIds : [buttonIds]).forEach((buttonId) => {
    bindPointerClick(buttonId, handler);
  });
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

function bindLongPressMany(buttonIds, shortHandler, longHandler, holdMs = 800) {
  (Array.isArray(buttonIds) ? buttonIds : [buttonIds]).forEach((buttonId) => {
    bindLongPress(buttonId, shortHandler, longHandler, holdMs);
  });
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
      showToast("ВИКЛ бойлера 1 зафіксовано");
    },
  );
  bindLongPress(
    "btnBoiler1ON",
    () => sendBoiler1Mode("ON"),
    async () => {
      await sendBoiler1Lock("ON", { silent: true });
      await sendBoiler1Mode("ON", { silent: true });
      showToast("УВІМК бойлера 1 зафіксовано");
    },
  );

  bindPointerClick("btnPumpAUTO", () => sendPumpMode("AUTO"));
  bindLongPress(
    "btnPumpOFF",
    () => sendPumpMode("OFF"),
    async () => {
      await sendPumpLock("OFF", { silent: true });
      await sendPumpMode("OFF", { silent: true });
      showToast("ВИКЛ насоса зафіксовано");
    },
  );
  bindLongPress(
    "btnPumpON",
    () => sendPumpMode("ON"),
    async () => {
      await sendPumpLock("ON", { silent: true });
      await sendPumpMode("ON", { silent: true });
      showToast("УВІМК насоса зафіксовано");
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
      showToast("ВИКЛ бойлера 2 зафіксовано");
    },
  );
  bindLongPress(
    "btnBoiler2ON",
    () => sendBoiler2Mode("ON"),
    async () => {
      await sendBoiler2Lock("ON", { silent: true });
      await sendBoiler2Mode("ON", { silent: true });
      showToast("УВІМК бойлера 2 зафіксовано");
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

  document.querySelectorAll("[data-gate-action]").forEach((btn) => {
    btn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      triggerGate();
    });
  });
  document.querySelectorAll("[data-garage-light-action]").forEach((btn) => {
    btn.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (btn.disabled) return;
      toggleGarageLight();
    });
  });
}

function openInverterLogs() {
  // Усі логи (власні load controller + переслані з garage та інвертора)
  // зберігаються лише на load controller — інвертор і гараж лише
  // редіректять сюди. Раніше тут відкривався inverterBaseUrl: якщо інвертор
  // офлайн, кнопка логів переставала працювати, хоча самі логи були
  // доступні.
  const baseUrl = normalizeBaseUrl(state.config.loadControllerBaseUrl || DEFAULT_CONFIG.loadControllerBaseUrl);
  if (!baseUrl) {
    showToast("адреса load controller не задана");
    return;
  }

  const logsUrl = `${baseUrl}/api/sd/files`;

  if (hasBridge() && window.AndroidHub && typeof window.AndroidHub.openExternalUrl === "function") {
    const opened = !!window.AndroidHub.openExternalUrl(logsUrl);
    if (!opened) {
      showToast("не вдалося відкрити логи");
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

  const schemeSettingsBtn = document.getElementById("schemeSettingsBtn");
  if (schemeSettingsBtn) {
    schemeSettingsBtn.addEventListener("click", () => {
      syncConfigToForm();
      openModal("settingsModal");
    });
  }

  const saveBtn = document.getElementById("settingsSaveBtn");
  if (!saveBtn) return;

  saveBtn.addEventListener("click", () => {
    if (!hasBridge()) {
      showToast("міст недоступний");
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
      graphSyncIntervalMin: clampGraphSyncIntervalMin(document.getElementById("cfgGraphSyncMin")?.value),
      graphSyncPerCycle: clampGraphSyncPerCycle(document.getElementById("cfgGraphSyncPerCycle")?.value),
      graphSyncRequestFetchLimit: clampGraphSyncRequestFetchLimit(document.getElementById("cfgGraphSyncRequestLimit")?.value),
      notifyPvGeneration: readChecked("cfgNotifyPv", true),
      notifyGridRelay: readChecked("cfgNotifyGridRelay", true),
      notifyGridPresence: readChecked("cfgNotifyGridPresence", true),
      notifyGridMode: readChecked("cfgNotifyGridMode", true),
      notifyLoadMode: readChecked("cfgNotifyLoadMode", true),
      notifyBoiler1Mode: readChecked("cfgNotifyBoiler1", true),
      notifyPumpMode: readChecked("cfgNotifyPump", true),
      notifyBoiler2Mode: readChecked("cfgNotifyBoiler2", true),
      notifyGateState: readChecked("cfgNotifyGate", true),
      notifyModuleOffline: readChecked("cfgNotifyModuleOffline", true),
      notifyPowerOverload: readChecked("cfgNotifyPowerOverload", true),
      notifyLogicUnstable: readChecked("cfgNotifyLogicUnstable", true),
      interfaceMode: "pro",
    };

    const ok = !!window.AndroidHub.saveConfig(JSON.stringify(nextConfig));
    if (!ok) {
      showToast("не вдалося зберегти налаштування");
      return;
    }

    state.config = { ...DEFAULT_CONFIG, ...nextConfig };
    applyInterfaceMode(state.config.interfaceMode);
    setText("pollText", `${state.config.pollIntervalSec}s`);
    applyModuleCardStates();
    applyLiveCardStates(state.status);
    restartPolling();
    restartSignalAgeTicker();
    scheduleNextGraphBackgroundSync(3 * 1000);
    closeModal("settingsModal");
    showToast("налаштування збережено");
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
  setInput("cfgGraphSyncMin", String(clampGraphSyncIntervalMin(cfg.graphSyncIntervalMin)));
  setInput("cfgGraphSyncPerCycle", String(clampGraphSyncPerCycle(cfg.graphSyncPerCycle)));
  setInput("cfgGraphSyncRequestLimit", String(clampGraphSyncRequestFetchLimit(cfg.graphSyncRequestFetchLimit)));
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
  setCheck("cfgNotifyModuleOffline", cfg.notifyModuleOffline);
  setCheck("cfgNotifyPowerOverload", cfg.notifyPowerOverload);
  setCheck("cfgNotifyLogicUnstable", cfg.notifyLogicUnstable);
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
      graphSyncIntervalMin: clampGraphSyncIntervalMin(parsed.graphSyncIntervalMin),
      graphSyncPerCycle: clampGraphSyncPerCycle(parsed.graphSyncPerCycle),
      graphSyncRequestFetchLimit: clampGraphSyncRequestFetchLimit(parsed.graphSyncRequestFetchLimit),
      interfaceMode: "pro",
    };
    scheduleNextGraphBackgroundSync(3 * 1000);
  } catch (error) {
    showToast("не вдалося прочитати конфігурацію");
  }
}

async function requestStatus() {
  if (!hasBridge()) {
    if (!state.noBridgeToastShown) {
      state.noBridgeToastShown = true;
      showToast("міст Android недоступний");
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
    showToast(`помилка статусу: ${error.message}`);
  } finally {
    state.statusRequestInFlight = false;
  }
}

async function requestMulticastRefreshNow() {
  if (!hasBridge()) {
    showToast("міст недоступний");
    return;
  }

  const btn = document.getElementById("multicastRefreshBtn");
  if (btn) btn.disabled = true;
  try {
    await bridgeRequest("status", (requestId) => {
      window.AndroidHub.requestMulticastRefresh(requestId);
    });
    showToast("оновлення запитано");
  } catch (error) {
    showToast(`не вдалося оновити: ${error.message}`);
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
    showToast("Жоден модуль не відповідає. Перевірте адреси контролерів і Wi-Fi.");
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

function pickFiniteValue(values, fallback = null) {
  for (const value of values) {
    const parsed = maybeFiniteNumber(value, null);
    if (parsed !== null) return parsed;
  }
  return fallback;
}

function moduleHasInverterTelemetryPayload(moduleData) {
  if (!moduleData || typeof moduleData !== "object") return false;
  return [
    moduleData.pvW,
    moduleData.gridW,
    moduleData.loadW,
    moduleData.batterySoc,
    moduleData.batteryPower,
    moduleData.lineVoltage,
  ].some((value) => maybeFiniteNumber(value, null) !== null);
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
  const hasLoadInverterProxy = hasLoadData && moduleHasInverterTelemetryPayload(status?.loadController);
  const hasGarageInverterProxy = hasGarageData && moduleHasInverterTelemetryPayload(status?.garage);
  const hasInverterCardData = hasInverterData || hasLoadInverterProxy || hasGarageInverterProxy;
  const hasClimateData = hasInverterData || hasLoadData || hasGarageData;

  setCardDataState("cardPv", hasInverterCardData);
  setCardDataState("cardGrid", hasInverterCardData);
  setCardDataState("cardBattery", hasInverterCardData);
  setCardDataState("cardLoad", hasInverterCardData);
  setCardDataState("cardBoiler1", hasLoadData);
  setCardDataState("cardPump", hasLoadData);
  setCardDataState("cardBoiler2", hasGarageData);
  setCardDataState("cardGate", hasGarageData);
  setCardDataState("climateWideCard", hasClimateData);

  if (flash && hasInverterCardData) {
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
    updateGarageLightCountdownDisplay();
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
    drawEmptyCanvas(canvas, "Немає даних графіка");
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
    drawEmptyCanvas(canvas, "Немає даних графіка");
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

function normalizeCoverage(payloadCoverage) {
  if (!payloadCoverage || typeof payloadCoverage !== "object") return null;
  const expectedDays = Math.max(0, Number(payloadCoverage.expectedDays) || 0);
  const knownDays = Math.max(0, Number(payloadCoverage.knownDays) || 0);
  const syncedDays = Math.max(0, Number(payloadCoverage.syncedDays) || 0);
  return {
    period: safeText(payloadCoverage.period, ""),
    expectedDays,
    knownDays,
    syncedDays,
  };
}

function formatCoverageSummary(coverage) {
  if (!coverage) return "sync: --";
  const synced = Math.max(0, Number(coverage.syncedDays) || 0);
  const known = Math.max(0, Number(coverage.knownDays) || 0);
  const expected = Math.max(0, Number(coverage.expectedDays) || 0);
  if (known > 0) {
    const pct = Math.round((synced / known) * 100);
    return `sync: ${synced}/${known} days (${pct}%)`;
  }
  if (expected > 0) {
    const pct = Math.round((synced / expected) * 100);
    return `sync: ${synced}/${expected} days (${pct}%)`;
  }
  return `sync: ${synced} day${synced === 1 ? "" : "s"}`;
}

function isCoverageIncomplete(coverage) {
  if (!coverage || typeof coverage !== "object") return false;
  const synced = Math.max(0, Number(coverage.syncedDays) || 0);
  const known = Math.max(0, Number(coverage.knownDays) || 0);
  const expected = Math.max(0, Number(coverage.expectedDays) || 0);
  if (known > 0) return synced < known;
  if (expected > 0) return synced < expected;
  return false;
}

function setGraphSyncMeta(id, coverage) {
  setText(id, formatCoverageSummary(coverage));
}

function normalizeEnergyPayload(period, payload) {
  if (!payload || typeof payload !== "object") {
    throw new Error("Порожні дані");
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
    const dailyCoverage = normalizeCoverage(payload._coverage) || {
      period: "daily",
      expectedDays: 1,
      knownDays: 1,
      syncedDays: rows.length > 0 ? 1 : 0,
    };
    return {
      title: `energy graph - day ${date}`,
      yTitle: "енергія (Вт·год)",
      labels,
      pv,
      home,
      grid,
      coverage: dailyCoverage,
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
      yTitle: "енергія (Вт·год)",
      labels,
      pv,
      home,
      grid,
      coverage: normalizeCoverage(payload._coverage),
    };
  }

  const labels = Array.isArray(payload.months) ? payload.months.map((v) => String(v)) : [];
  const pv = Array.isArray(payload.pv) ? payload.pv.map((v) => toFiniteNumber(v, 0) / 1000) : [];
  const home = Array.isArray(payload.home) ? payload.home.map((v) => toFiniteNumber(v, 0) / 1000) : [];
  const grid = Array.isArray(payload.grid) ? payload.grid.map((v) => toFiniteNumber(v, 0) / 1000) : [];
  const year = safeText(payload.current_year, String(new Date().getFullYear()));
  return {
    title: `energy graph - year ${year}`,
    yTitle: "енергія (кВт·год)",
    labels,
    pv,
    home,
    grid,
    coverage: normalizeCoverage(payload._coverage),
  };
}

function renderEnergyChart(model) {
  const canvas = document.getElementById("energyCanvas");
  if (!canvas) return;

  setText("energyChartTitle", model.title);
  setGraphSyncMeta("energySyncMeta", model.coverage);
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

async function fetchAnalyticsPayloadFromBridge(period, selector, options = {}) {
  const force = !!options.force;
  const background = options.background === true;
  if (period === "daily") {
    const payload = await fetchDailyPayloadFromBridge(selector, { force });
    if (!isDailyPayloadLike(payload, normalizeIsoDate(selector))) {
      throw new Error("Немає даних за добу");
    }
    return payload;
  }

  const dates = await syncDailyArchiveDates({ forceDates: force });
  const requestLimit = graphSyncRequestFetchLimitFromConfig();
  const cycleLimit = graphSyncPerCycleFromConfig();

  const resolveFetchLimit = (targetCount) => {
    const safeTarget = Math.max(0, Number(targetCount) || 0);
    if (!safeTarget) return 0;
    const chunkLimit = Math.max(1, Math.min(cycleLimit, safeTarget));
    if (background) {
      return chunkLimit;
    }
    if (!force) {
      // Foreground open uses already-cached local data and schedules background top-up.
      return 0;
    }
    return Math.max(1, Math.min(requestLimit, chunkLimit, safeTarget));
  };

  if (period === "monthly") {
    const month = normalizeIsoMonth(selector) || currentMonthIso();
    const monthDates = dates.filter((date) => date.startsWith(`${month}-`));
    const monthYear = Number.parseInt(month.slice(0, 4), 10);
    const monthNum = Number.parseInt(month.slice(5, 7), 10);
    const fallbackDates = datesForMonth(monthYear, monthNum);
    const targetDates = monthDates.length ? monthDates : fallbackDates;
    const monthlyFetchLimit = resolveFetchLimit(targetDates.length);
    const syncStats = await ensureDailyArchiveEntries(targetDates, {
      force,
      maxFetch: monthlyFetchLimit,
      onlyWhenLoadIdle: true,
    });
    const payload = buildMonthlyPayloadFromArchive(month);
    if (isCoverageIncomplete(normalizeCoverage(payload._coverage))) {
      scheduleNextGraphBackgroundSync(syncStats?.blockedByBusy ? GRAPH_SYNC_BUSY_RETRY_MS : GRAPH_SYNC_EAGER_RETRY_MS);
    }
    return payload;
  }

  const selectedYear = Number.parseInt(String(new Date().getFullYear()), 10);
  const yearDates = dates.filter((date) => date.startsWith(`${selectedYear}-`));
  const yearlyFetchLimit = resolveFetchLimit(yearDates.length);
  const syncStats = await ensureDailyArchiveEntries(yearDates, {
    force,
    maxFetch: yearlyFetchLimit,
    onlyWhenLoadIdle: true,
  });
  const payload = buildYearlyPayloadFromArchive(selectedYear);
  if (isCoverageIncomplete(normalizeCoverage(payload._coverage))) {
    scheduleNextGraphBackgroundSync(syncStats?.blockedByBusy ? GRAPH_SYNC_BUSY_RETRY_MS : GRAPH_SYNC_EAGER_RETRY_MS);
  }
  return payload;
}

async function getAnalyticsPayloadShared(period, selector, options = {}) {
  const force = !!options.force;
  const background = options.background === true;
  const syncKey = `analytics::${period}::${selector}`;
  const cacheEntry = getAnalyticsCacheEntry(period, selector);
  const nowMs = Date.now();

  if (!force && cacheEntry?.payload && !isAnalyticsCacheStale(cacheEntry, period, nowMs)) {
    touchAnalyticsCacheEntry(period, selector, nowMs);
    return cacheEntry.payload;
  }

  if (shouldThrottleGraphSyncKey(syncKey, force, nowMs) && cacheEntry?.payload) {
    touchAnalyticsCacheEntry(period, selector, nowMs);
    return cacheEntry.payload;
  }

  const payload = await enqueueGraphSync(syncKey, () => fetchAnalyticsPayloadFromBridge(period, selector, { force, background }));
  upsertAnalyticsCacheEntry(period, selector, payload, Date.now());
  return payload;
}

function buildGraphModelFromAnalyticsCache(graphType, period, selector) {
  const rawEntry = getAnalyticsCacheEntry(period, selector);
  if (!rawEntry?.payload) return null;
  touchAnalyticsCacheEntry(period, selector);
  try {
    if (graphType === "climate") {
      return normalizeClimatePayload(period, rawEntry.payload);
    }
    return normalizeEnergyPayload(period, rawEntry.payload);
  } catch (error) {
    return null;
  }
}

async function fetchEnergyModelFromBridge(period, selector, options = {}) {
  const payload = await getAnalyticsPayloadShared(period, selector, options);
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

async function fetchModuleHistoryPayload(moduleKey, expectedDate, options = {}) {
  const force = !!options.force;
  if (!expectedDate || expectedDate !== todayIso()) return null;

  const cacheEntry = getModuleHistoryCacheEntry(moduleKey, expectedDate);
  const nowMs = Date.now();
  if (!force && cacheEntry?.payload && !isModuleHistoryCacheStale(cacheEntry, expectedDate, nowMs)) {
    touchModuleHistoryCacheEntry(moduleKey, expectedDate, nowMs);
    return cacheEntry.payload;
  }

  const syncKey = `history::${moduleKey}::${expectedDate}`;
  if (shouldThrottleGraphSyncKey(syncKey, force, nowMs) && cacheEntry?.payload) {
    touchModuleHistoryCacheEntry(moduleKey, expectedDate, nowMs);
    return cacheEntry.payload;
  }

  let payload = null;
  if (
    moduleKey === "corridor" &&
    state.config.loadControllerEnabled &&
    window.AndroidHub &&
    typeof window.AndroidHub.fetchLoadControllerHistory === "function"
  ) {
    payload = await enqueueGraphSync(syncKey, () => bridgeRequest("climate-corridor-history", (requestId) => {
      window.AndroidHub.fetchLoadControllerHistory(requestId, expectedDate);
    }));
  } else if (
    moduleKey === "garage" &&
    state.config.garageEnabled &&
    window.AndroidHub &&
    typeof window.AndroidHub.fetchGarageHistory === "function"
  ) {
    payload = await enqueueGraphSync(syncKey, () => bridgeRequest("climate-garage-history", (requestId) => {
      window.AndroidHub.fetchGarageHistory(requestId);
    }));
  }

  if (payload) {
    upsertModuleHistoryCacheEntry(moduleKey, expectedDate, payload, Date.now());
  }
  return payload;
}

async function fetchClimateModelFromBridge(period, selector, options = {}) {
  const payload = await getAnalyticsPayloadShared(period, selector, options);
  const model = normalizeClimatePayload(period, payload);
  if (period === "daily") {
    const fallbackNeeds = evaluateClimateFallbackNeeds(model);
    if (fallbackNeeds.corridorMissing || fallbackNeeds.garageMissing) {
      await enrichDailyClimateModelWithModuleHistory(model, {
        corridor: fallbackNeeds.corridorMissing,
        garage: fallbackNeeds.garageMissing,
        forceRefresh: !!options.force,
      });
    }
  }
  return model;
}

async function syncGraphModel(graphType, period, selector, options = {}) {
  const force = !!options.force;
  const background = options.background === true;
  const syncKey = `${graphType}::${period}::${selector}`;
  const cachedEntry = getGraphCacheEntry(graphType, period, selector);
  const nowMs = Date.now();

  if (shouldThrottleGraphSyncKey(syncKey, force, nowMs) && cachedEntry?.model) {
    return cachedEntry.model;
  }
  state.graphSync.lastAttemptByKey[syncKey] = nowMs;

  const model = await enqueueGraphSync(syncKey, async () => {
    if (graphType === "climate") {
      return fetchClimateModelFromBridge(period, selector, { force, background });
    }
    return fetchEnergyModelFromBridge(period, selector, { force, background });
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
  const maxItems = graphSyncPerCycleFromConfig();
  const candidates = [];
  const appendCandidates = (graphType) => {
    const slot = getGraphCacheSlot(graphType);
    Object.entries(slot).forEach(([entryKey, entry]) => {
      if (!entry || typeof entry !== "object" || !entry.model) return;
      const viewedAtMs = Number(entry.viewedAtMs || entry.fetchedAtMs || 0);
      if (!Number.isFinite(viewedAtMs) || viewedAtMs <= 0) return;
      if (nowMs - viewedAtMs > GRAPH_SYNC_VIEW_WINDOW_MS) return;
      const parsed = parseGraphEntryKey(entryKey);
      const coverageIncomplete = isCoverageIncomplete(entry.model?.coverage);
      if (!coverageIncomplete && !isGraphCacheStale(entry, graphType, parsed.period, nowMs)) return;
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
  return candidates.slice(0, maxItems);
}

async function runGraphBackgroundSyncCycle() {
  if (state.graphSync.cycleInFlight) return "skip";
  if (!hasBridge() || document.hidden) return "skip";

  state.graphSync.cycleInFlight = true;
  try {
    const perCycleLimit = graphSyncPerCycleFromConfig();
    const archiveSyncStats = await syncDailyArchiveQuiet({
      force: false,
      maxFetch: perCycleLimit,
      onlyWhenLoadIdle: true,
    });
    const blockedByBusy = archiveSyncStats?.blockedByBusy === true;
    const candidates = collectBackgroundGraphSyncCandidates(Date.now());
    for (const candidate of candidates) {
      try {
        const model = await syncGraphModel(candidate.graphType, candidate.period, candidate.selector, { force: false, background: true });
        applyGraphModelIfCurrent(candidate.graphType, candidate.period, candidate.selector, model);
      } catch (error) {
        // Silent: background sync must not interrupt UI.
      }
    }
    return blockedByBusy ? "busy" : "ok";
  } finally {
    state.graphSync.cycleInFlight = false;
  }
}

function scheduleNextGraphBackgroundSync(delayMs = 0) {
  if (state.graphSync.timer) {
    clearTimeout(state.graphSync.timer);
  }
  const jitterMs = Math.floor(Math.random() * GRAPH_SYNC_INTERVAL_JITTER_MS);
  const configuredIntervalMs = graphSyncIntervalMsFromConfig();
  const requestedDelayMs = Number(delayMs);
  const nextDelayMs = Number.isFinite(requestedDelayMs) && requestedDelayMs > 0
    ? Math.max(5 * 1000, requestedDelayMs)
    : Math.max(10 * 1000, configuredIntervalMs + jitterMs);
  state.graphSync.timer = setTimeout(async () => {
    state.graphSync.timer = null;
    const cycleResult = await runGraphBackgroundSyncCycle();
    if (cycleResult === "busy") {
      scheduleNextGraphBackgroundSync(GRAPH_SYNC_BUSY_RETRY_MS);
      return;
    }
    scheduleNextGraphBackgroundSync();
  }, nextDelayMs);
}

function initGraphSync() {
  loadGraphCacheFromStorage();
  loadAnalyticsCacheFromStorage();
  loadModuleHistoryCacheFromStorage();
  loadDailyArchiveCacheFromStorage();
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
    if (state.timeline.persistHandle) {
      clearTimeout(state.timeline.persistHandle);
      state.timeline.persistHandle = null;
    }
    if (state.analyticsCache.persistHandle) {
      clearTimeout(state.analyticsCache.persistHandle);
      state.analyticsCache.persistHandle = null;
    }
    if (state.moduleHistoryCache.persistHandle) {
      clearTimeout(state.moduleHistoryCache.persistHandle);
      state.moduleHistoryCache.persistHandle = null;
    }
    if (state.dailyArchiveCache.persistHandle) {
      clearTimeout(state.dailyArchiveCache.persistHandle);
      state.dailyArchiveCache.persistHandle = null;
    }
    persistGraphCacheNow();
    persistAnalyticsCacheNow();
    persistModuleHistoryCacheNow();
    persistDailyArchiveCacheNow();
    persistTimelineCacheNow();
  });
}

async function loadEnergyData(options = {}) {
  const forceRefresh = !!options.forceRefresh;
  loadGraphCacheFromStorage();
  loadAnalyticsCacheFromStorage();

  const period = selectedRadioValue("energyPeriod", state.energy.period);
  state.energy.period = period;
  syncEnergyToolbar();
  const selector = resolveEnergySelector(period);
  let cacheEntry = getGraphCacheEntry("energy", period, selector);
  if (!cacheEntry) {
    const derivedModel = buildGraphModelFromAnalyticsCache("energy", period, selector);
    if (derivedModel) {
      upsertGraphCacheEntry("energy", period, selector, derivedModel, Date.now());
      cacheEntry = getGraphCacheEntry("energy", period, selector);
    }
  }
  let renderedFromCache = false;

  if (cacheEntry?.model) {
    state.energy.last = cacheEntry.model;
    touchGraphCacheEntry("energy", period, selector);
    renderEnergyChart(cacheEntry.model);
    renderedFromCache = true;
  } else {
    setText("energyChartTitle", "графік енергії - завантаження...");
    setGraphSyncMeta("energySyncMeta", null);
  }

  if (!hasBridge()) {
    if (!renderedFromCache) {
      drawEmptyCanvas(document.getElementById("energyCanvas"), "Міст недоступний");
    }
    return;
  }

  const coverageIncomplete = isCoverageIncomplete(cacheEntry?.model?.coverage);
  const stale = !cacheEntry || isGraphCacheStale(cacheEntry, "energy", period, Date.now()) || coverageIncomplete;
  if (!forceRefresh && cacheEntry?.model && !stale) {
    return;
  }

  const syncTask = syncGraphModel("energy", period, selector, { force: forceRefresh, background: false });
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
      drawEmptyCanvas(document.getElementById("energyCanvas"), "Не вдалося завантажити дані");
      setText("energyChartTitle", "графік енергії - помилка");
      setGraphSyncMeta("energySyncMeta", null);
      showToast(`не вдалося завантажити дані енергії: ${error.message}`);
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
  if (!expectedDate || expectedDate !== todayIso()) return;
  const labelCount = Array.isArray(model.labels) ? model.labels.length : 24;
  const forceRefresh = !!options.forceRefresh;
  const pending = [];

  if (needCorridor) {
    pending.push(
      fetchModuleHistoryPayload("corridor", expectedDate, { force: forceRefresh }).then((historyPayload) => {
        if (!historyPayload) return;
        const corridorHourly = mapHistoryPayloadToHourlyTemperature(historyPayload, expectedDate);
        model.tempCorridor = mergeClimateSeries(model.tempCorridor, corridorHourly, labelCount);
      }),
    );
  }

  if (needGarage) {
    pending.push(
      fetchModuleHistoryPayload("garage", expectedDate, { force: forceRefresh }).then((historyPayload) => {
        if (!historyPayload) return;
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
    throw new Error("Порожні дані");
  }
  if (safeText(payload.error, "") !== "") {
    throw new Error(safeText(payload.error, "Дані клімату недоступні"));
  }

  const climateKeys = {
    internal: {
      temp: ["temp", "temp_int", "temp_internal", "inside_temp", "internal.temp"],
      hum: ["hum", "hum_int", "hum_internal", "inside_hum", "internal.hum"],
      press: ["press", "press_int", "press_internal", "inside_press", "internal.press"],
    },
    external: {
      temp: ["temp_ext", "external_temp", "outside_temp", "external.temp", "outside.temp"],
      hum: ["hum_ext", "external_hum", "outside_hum", "external.hum", "outside.hum"],
      press: ["press_ext", "external_press", "outside_press", "external.press", "outside.press"],
    },
    outsideExt: {
      temp: ["temp_outside_ext", "outside_ext_temp", "outside_ext.temp", "outside_ext.t", "outsideExt.temp"],
      hum: ["hum_outside_ext", "outside_ext_hum", "outside_ext.hum", "outside_ext.h", "outsideExt.hum"],
      press: ["press_outside_ext", "outside_ext_press", "outside_ext.press", "outside_ext.p", "outsideExt.press"],
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
    inverter: {
      temp: ["inverter_temp", "inverterTemp", "inverter_rs232_temp", "inverter_rs232.temp", "rs232_temp"],
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

  const sanitizeClimateSeries = (series, options = {}) => {
    const zeroAsNull = options.zeroAsNull === true;
    const values = Array.isArray(series) ? series : [];
    for (let i = 0; i < values.length; i += 1) {
      const parsed = Number.isFinite(values[i]) ? values[i] : null;
      values[i] = zeroAsNull && parsed === 0 ? null : parsed;
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
    const tempOutsideExt = [];
    const humOutsideExt = [];
    const pressOutsideExt = [];
    const tempCorridor = [];
    const humCorridor = [];
    const pressCorridor = [];
    const tempGarage = [];
    const humGarage = [];
    const pressGarage = [];
    const tempInverter = [];

    if (rows.length === 0) {
      for (let i = 0; i < 24; i += 1) {
        labels.push(`${i}:00`);
        tempInt.push(null);
        humInt.push(null);
        pressInt.push(null);
        tempExt.push(null);
        humExt.push(null);
        pressExt.push(null);
        tempOutsideExt.push(null);
        humOutsideExt.push(null);
        pressOutsideExt.push(null);
        tempCorridor.push(null);
        humCorridor.push(null);
        pressCorridor.push(null);
        tempGarage.push(null);
        humGarage.push(null);
        pressGarage.push(null);
        tempInverter.push(null);
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
        tempOutsideExt.push(climateNumberFromCandidates(row, climateKeys.outsideExt.temp));
        humOutsideExt.push(climateNumberFromCandidates(row, climateKeys.outsideExt.hum));
        pressOutsideExt.push(climateNumberFromCandidates(row, climateKeys.outsideExt.press));
        tempCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.temp));
        humCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.hum));
        pressCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.press));
        tempGarage.push(climateNumberFromCandidates(row, climateKeys.garage.temp));
        humGarage.push(climateNumberFromCandidates(row, climateKeys.garage.hum));
        pressGarage.push(climateNumberFromCandidates(row, climateKeys.garage.press));
        tempInverter.push(climateNumberFromCandidates(row, climateKeys.inverter.temp));
      });
    }

    sanitizeClimateTriplets(tempInt, humInt, pressInt);
    sanitizeClimateTriplets(tempExt, humExt, pressExt);
    sanitizeClimateTriplets(tempOutsideExt, humOutsideExt, pressOutsideExt);
    sanitizeClimateTriplets(tempCorridor, humCorridor, pressCorridor);
    sanitizeClimateTriplets(tempGarage, humGarage, pressGarage);
    sanitizeClimateSeries(tempInverter, { zeroAsNull: true });

    const date = safeText(payload.date, document.getElementById("climateDateInput")?.value || todayIso());
    const dailyCoverage = normalizeCoverage(payload._coverage) || {
      period: "daily",
      expectedDays: 1,
      knownDays: 1,
      syncedDays: rows.length > 0 ? 1 : 0,
    };
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
      tempOutsideExt,
      humOutsideExt,
      pressOutsideExt,
      tempCorridor,
      humCorridor,
      pressCorridor,
      tempGarage,
      humGarage,
      pressGarage,
      tempInverter,
      coverage: dailyCoverage,
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
    const tempOutsideExt = [];
    const humOutsideExt = [];
    const pressOutsideExt = [];
    const tempCorridor = [];
    const humCorridor = [];
    const pressCorridor = [];
    const tempGarage = [];
    const humGarage = [];
    const pressGarage = [];
    const tempInverter = [];

    rows.forEach((row, idx) => {
      labels.push(safeText(row.day, String(idx + 1)));
      tempInt.push(climateNumberFromCandidates(row, climateKeys.internal.temp));
      humInt.push(climateNumberFromCandidates(row, climateKeys.internal.hum));
      pressInt.push(climateNumberFromCandidates(row, climateKeys.internal.press));
      tempExt.push(climateNumberFromCandidates(row, climateKeys.external.temp));
      humExt.push(climateNumberFromCandidates(row, climateKeys.external.hum));
      pressExt.push(climateNumberFromCandidates(row, climateKeys.external.press));
      tempOutsideExt.push(climateNumberFromCandidates(row, climateKeys.outsideExt.temp));
      humOutsideExt.push(climateNumberFromCandidates(row, climateKeys.outsideExt.hum));
      pressOutsideExt.push(climateNumberFromCandidates(row, climateKeys.outsideExt.press));
      tempCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.temp));
      humCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.hum));
      pressCorridor.push(climateNumberFromCandidates(row, climateKeys.corridor.press));
      tempGarage.push(climateNumberFromCandidates(row, climateKeys.garage.temp));
      humGarage.push(climateNumberFromCandidates(row, climateKeys.garage.hum));
      pressGarage.push(climateNumberFromCandidates(row, climateKeys.garage.press));
      tempInverter.push(climateNumberFromCandidates(row, climateKeys.inverter.temp));
    });

    sanitizeClimateTriplets(tempInt, humInt, pressInt);
    sanitizeClimateTriplets(tempExt, humExt, pressExt);
    sanitizeClimateTriplets(tempOutsideExt, humOutsideExt, pressOutsideExt);
    sanitizeClimateTriplets(tempCorridor, humCorridor, pressCorridor);
    sanitizeClimateTriplets(tempGarage, humGarage, pressGarage);
    sanitizeClimateSeries(tempInverter, { zeroAsNull: true });

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
      tempOutsideExt,
      humOutsideExt,
      pressOutsideExt,
      tempCorridor,
      humCorridor,
      pressCorridor,
      tempGarage,
      humGarage,
      pressGarage,
      tempInverter,
      coverage: normalizeCoverage(payload._coverage),
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
  const tempOutsideExt = climateArrayFromCandidates(payload, climateKeys.outsideExt.temp);
  const humOutsideExt = climateArrayFromCandidates(payload, climateKeys.outsideExt.hum);
  const pressOutsideExt = climateArrayFromCandidates(payload, climateKeys.outsideExt.press);
  const tempCorridor = climateArrayFromCandidates(payload, climateKeys.corridor.temp);
  const humCorridor = climateArrayFromCandidates(payload, climateKeys.corridor.hum);
  const pressCorridor = climateArrayFromCandidates(payload, climateKeys.corridor.press);
  const tempGarage = climateArrayFromCandidates(payload, climateKeys.garage.temp);
  const humGarage = climateArrayFromCandidates(payload, climateKeys.garage.hum);
  const pressGarage = climateArrayFromCandidates(payload, climateKeys.garage.press);
  const tempInverter = climateArrayFromCandidates(payload, climateKeys.inverter.temp);
  sanitizeClimateTriplets(tempInt, humInt, pressInt);
  sanitizeClimateTriplets(tempExt, humExt, pressExt);
  sanitizeClimateTriplets(tempOutsideExt, humOutsideExt, pressOutsideExt);
  sanitizeClimateTriplets(tempCorridor, humCorridor, pressCorridor);
  sanitizeClimateTriplets(tempGarage, humGarage, pressGarage);
  sanitizeClimateSeries(tempInverter, { zeroAsNull: true });
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
    tempOutsideExt,
    humOutsideExt,
    pressOutsideExt,
    tempCorridor,
    humCorridor,
    pressCorridor,
    tempGarage,
    humGarage,
    pressGarage,
    tempInverter,
    coverage: normalizeCoverage(payload._coverage),
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
      outsideExt: null,
      corridor: model.humCorridor,
      garage: model.humGarage,
    };
  }
  if (metric === "press") {
    return {
      primary: model.pressInt,
      external: model.pressExt,
      outsideExt: null,
      corridor: model.pressCorridor,
      garage: model.pressGarage,
    };
  }
  return {
    primary: model.tempInt,
    external: model.tempExt,
    // Reuse the "outside_ext" slot for inverter temperature.
    outsideExt: model.tempInverter,
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
    drawEmptyCanvas(canvas, "Немає даних графіка");
    setGraphSyncMeta("climateSyncMeta", null);
    return;
  }

  const meta = currentClimateMetricMeta();
  const selected = climateSeriesForMetric(model, state.climate.metric);
  const series = [];
  const outsideExtLabel = state.climate.metric === "temp"
    ? `inverter ${meta.label} (${meta.unit})`
    : `outside ext ${meta.label} (${meta.unit})`;
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
      label: outsideExtLabel,
      color: "#6f8fff",
      data: selected.outsideExt,
      lineWidth: 1.9,
      pointRadius: 1.2,
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
  setGraphSyncMeta("climateSyncMeta", model.coverage);
  if (!series.length) {
    drawEmptyCanvas(canvas, "Немає даних клімату");
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
  loadAnalyticsCacheFromStorage();
  loadModuleHistoryCacheFromStorage();

  const period = selectedRadioValue("climatePeriod", state.climate.period);
  state.climate.period = period;
  syncClimateToolbar();
  const selector = resolveClimateSelector(period);
  let cacheEntry = getGraphCacheEntry("climate", period, selector);
  if (!cacheEntry) {
    const derivedModel = buildGraphModelFromAnalyticsCache("climate", period, selector);
    if (derivedModel) {
      upsertGraphCacheEntry("climate", period, selector, derivedModel, Date.now());
      cacheEntry = getGraphCacheEntry("climate", period, selector);
    }
  }
  let renderedFromCache = false;

  if (cacheEntry?.model) {
    state.climate.last = cacheEntry.model;
    touchGraphCacheEntry("climate", period, selector);
    renderClimateChart();
    renderedFromCache = true;
  } else {
    setText("climateChartTitle", "графік клімату - завантаження...");
    setGraphSyncMeta("climateSyncMeta", null);
  }

  if (!hasBridge()) {
    if (!renderedFromCache) {
      drawEmptyCanvas(document.getElementById("climateCanvas"), "Міст недоступний");
    }
    return;
  }

  const coverageIncomplete = isCoverageIncomplete(cacheEntry?.model?.coverage);
  const stale = !cacheEntry || isGraphCacheStale(cacheEntry, "climate", period, Date.now()) || coverageIncomplete;
  if (!forceRefresh && cacheEntry?.model && !stale) {
    return;
  }

  const syncTask = syncGraphModel("climate", period, selector, { force: forceRefresh, background: false });
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
      drawEmptyCanvas(document.getElementById("climateCanvas"), "Не вдалося завантажити дані");
      setText("climateChartTitle", "графік клімату - помилка");
      setGraphSyncMeta("climateSyncMeta", null);
      showToast(`не вдалося завантажити дані клімату: ${error.message}`);
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

  if (inverter && Object.prototype.hasOwnProperty.call(inverter, "inverterTemp")) {
    zones.push(climateZoneRow("inverter_temp", inverter.inverterTemp, null, null));
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
      climateZoneRow("corridor", loadController.bmeTemp, loadController.bmeHum, loadController.bmePress),
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
    zones.push(climateZoneRow("garage", garage.bmeTemp, garage.bmeHum, garage.bmePress));
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

  const boiler1On = !loadOff && !!loadController.boiler1On;
  const pumpOn = !loadOff && !!loadController.pumpOn;
  const boiler2On = !garageOff && !!garage.boiler2On;

  setText("schemeBoiler1State", loadOff ? "вимкнено" : boolTextUk(boiler1On));
  setText("schemePumpState", loadOff ? "вимкнено" : boolTextUk(pumpOn));
  setText("schemeBoiler2State", garageOff ? "вимкнено" : boolTextUk(boiler2On));

  setSchemeNodeOnOff("schemeNodeBoiler1", loadOff ? null : boiler1On);
  setSchemeNodeOnOff("schemeNodePump", loadOff ? null : pumpOn);
  setSchemeNodeOnOff("schemeNodeBoiler2", garageOff ? null : boiler2On);

  const gridNode = document.getElementById("schemeNodeGrid");
  if (gridNode) {
    gridNode.classList.toggle("is-present", !!gridPresent);
    gridNode.classList.toggle("is-absent", !gridPresent);
  }
  setText("schemeGridState", invOff ? "вимкнено" : gridPresent ? "є" : "немає");

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
  // Top and bottom vertical branches intentionally animate in opposite directions.
  setSchemeLinkState("schemeLinkHouseTop", -boiler1PowerDisplayW, !invOff && loadSwitchOn === true && topBranchActive, mixedSupplyColor);
  setSchemeLinkState("schemeLinkHouseBottom", boiler2PowerDisplayW, !invOff && loadSwitchOn === true && bottomBranchActive, mixedSupplyColor);
  setSchemeLinkState("schemeLinkBoiler1", boiler1PowerDisplayW, !loadOff && boiler1SwitchOn === true, mixedSupplyColor);
  setSchemeLinkState("schemeLinkPump", pumpPowerDisplayW, !loadOff && pumpSwitchOn === true, mixedSupplyColor);
  setSchemeLinkState("schemeLinkBoiler2", boiler2PowerDisplayW, !garageOff && boiler2SwitchOn === true, mixedSupplyColor);
}

function renderAll() {
  const status = state.status || {};
  state.capabilities = buildHubCapabilities(status);
  const inverter = state.config.inverterEnabled ? status.inverter || {} : {};
  const loadController = state.config.loadControllerEnabled ? status.loadController || {} : {};
  const garage = state.config.garageEnabled ? status.garage || {} : {};

  applyModuleCardStates();
  applyLiveCardStates(status);

  const hasInverterFresh = moduleHasFreshSignal("inverter", state.config.inverterEnabled, status?.inverter);
  const hasLoadFresh = moduleHasFreshSignal("loadController", state.config.loadControllerEnabled, status?.loadController);
  const hasGarageFresh = moduleHasFreshSignal("garage", state.config.garageEnabled, status?.garage);
  const inverterTelemetrySources = [];
  if (hasInverterFresh && inverter && typeof inverter === "object") {
    inverterTelemetrySources.push(inverter);
  }
  if (hasLoadFresh && loadController && typeof loadController === "object") {
    inverterTelemetrySources.push(loadController);
  }
  if (hasGarageFresh && garage && typeof garage === "object") {
    inverterTelemetrySources.push(garage);
  }
  if (!inverterTelemetrySources.length && inverter && typeof inverter === "object") {
    inverterTelemetrySources.push(inverter);
  }
  const inverterTelemetry = {
    pvW: pickFiniteValue(inverterTelemetrySources.map((src) => src?.pvW), null),
    gridW: pickFiniteValue(inverterTelemetrySources.map((src) => src?.gridW), null),
    loadW: pickFiniteValue(inverterTelemetrySources.map((src) => src?.loadW), null),
    batterySoc: pickFiniteValue(inverterTelemetrySources.map((src) => src?.batterySoc), null),
    batteryPower: pickFiniteValue(inverterTelemetrySources.map((src) => src?.batteryPower), null),
    lineVoltage: pickFiniteValue(inverterTelemetrySources.map((src) => src?.lineVoltage), null),
  };
  const inverterView = {
    ...inverter,
    ...inverterTelemetry,
  };

  const topLineVoltage = pickNumber([
    inverterView.lineVoltage,
    loadController.lineVoltage,
    garage.lineVoltage,
  ]);
  const topPv = pickNumber([inverterView.pvW, loadController.pvW, garage.pvW]);
  const topGrid = pickNumber([inverterView.gridW, loadController.gridW, garage.gridW]);
  const topLoadRaw = pickNumber([inverterView.loadW, loadController.loadW, garage.loadW]);
  const topLoad = applyConsumptionDisplayFloor(topLoadRaw);
  const topBatSoc = pickNumber([
    inverterView.batterySoc,
    loadController.batterySoc,
    garage.batterySoc,
  ]);
  const topBatPower = pickNumber([
    inverterView.batteryPower,
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
      : Number(inverterView.lineVoltage) >= 170
  );
  const gridPresenceBadge = document.getElementById("gridPresenceBadge");
  if (gridPresenceBadge) {
    gridPresenceBadge.classList.toggle("is-present", gridPresent);
    gridPresenceBadge.classList.toggle("is-absent", !gridPresent);
  }

  if (!loadOff) {
    recordLoadTimelineSample(loadController, garage);
  }

  const gridPowerCardW = zeroGridPowerWhenNoVoltage(inverterView.gridW, inverterView.lineVoltage);
  const loadPowerCardW = applyConsumptionDisplayFloor(inverterView.loadW);
  const boiler1PowerCardW = applyConsumptionDisplayFloor(loadController.boilerPower);
  const pumpPowerCardW = applyConsumptionDisplayFloor(loadController.pumpPower);
  const boiler2PowerCardW = applyConsumptionDisplayFloor(garage.boilerPower);

  setText("pvValue", invOff ? "--" : num(inverterView.pvW, 0, "--"));
  setText("pvVoltage", invOff ? "--" : num(inverter.pvVoltage, 1));
  setText("dailyPV", invOff ? "--" : num(inverter.dailyPV, 1));
  setText(
    "lastUpdatePV",
    invOff ? "--:--:--" : safeText(inverter.lastUpdate, safeText(inverter.rtcTime || loadController.rtcTime || garage.rtcTime, "--:--:--")),
  );

  setText("gridValue", invOff ? "--" : num(gridPowerCardW, 0, "--"));
  setText("gridVoltage", invOff ? "--" : num(inverterView.lineVoltage, 1, "--"));
  setText("gridFrequency", invOff ? "--" : num(inverter.gridFrequency, 1));
  setText("dailyGrid", invOff ? "--" : num(inverter.dailyGrid, 1));
  setText("gridModeIndicator", invOff ? "режим: вимкнено" : `режим: ${safeText(inverter.mode)}`);
  setText("gridStateIndicator", invOff ? "стан: ---" : `стан: ${boolText(!!inverter.gridRelayOn)}`);
  setText("gridModalState", invOff ? "---" : boolText(!!inverter.gridRelayOn));
  setText("gridModalReason", invOff ? "модуль вимкнено" : uiText(inverter.gridRelayReason, "вручну"));

  setText("loadValue", invOff ? "--" : num(loadPowerCardW, 0, "--"));
  setText("outputVoltage", invOff ? "--" : num(inverter.outputVoltage, 1));
  setText("outputFrequency", invOff ? "--" : num(inverter.outputFrequency, 1));
  setText("dailyHome", invOff ? "--" : num(inverter.dailyHome, 1));
  setText("loadModeIndicator", invOff ? "режим: вимкнено" : `режим: ${safeText(inverter.loadMode)}`);
  setText("loadStateIndicator", invOff ? "стан: ---" : `стан: ${boolText(!!inverter.loadRelayOn)}`);
  setText("loadModalState", invOff ? "---" : boolText(!!inverter.loadRelayOn));
  setText("loadModalReason", invOff ? "модуль вимкнено" : uiText(inverter.loadRelayReason, "вручну"));

  setText("batteryValueMain", invOff ? "--" : num(inverterView.batterySoc, 0, "--"));
  setText("batteryVoltage", invOff ? "--" : num(inverter.batteryVoltage, 1));
  setText("batteryPower", invOff ? "--" : num(inverterView.batteryPower, 0, "--"));
  setText("inverterTemp", invOff ? "--" : num(inverter.inverterTemp, 1));

  setText("boiler1Power", loadOff ? "--" : num(boiler1PowerCardW, 0));
  setText("boiler1Mode", loadOff ? "вимкнено" : safeText(loadController.boiler1Mode));
  setText("boiler1Current", loadOff ? "--" : num(loadController.boilerCurrent, 2));
  setText("boiler1Daily", loadOff ? "--" : num(loadController.dailyBoiler, 0));
  setText("boiler1State", loadOff ? "---" : boolText(!!loadController.boiler1On));
  setText("boiler1ModalState", loadOff ? "---" : boolText(!!loadController.boiler1On));
  setText("boiler1ModalReason", loadOff ? "модуль вимкнено" : uiText(loadController.boiler1StateReason, "вручну"));
  renderAutoWindowBlock("boiler1", {
    enabled: !!loadController.boiler1AutoWindowEnabled,
    start: safeText(loadController.boiler1AutoWindowStart, "00:00"),
    end: safeText(loadController.boiler1AutoWindowEnd, "00:00"),
    active: loadController.boiler1AutoWindowActive !== false,
  }, { disabled: loadOff });

  setText("pumpPower", loadOff ? "--" : num(pumpPowerCardW, 0));
  setText("pumpMode", loadOff ? "вимкнено" : safeText(loadController.pumpMode));
  setText("pumpCurrent", loadOff ? "--" : num(loadController.pumpCurrent, 2));
  setText("pumpDaily", loadOff ? "--" : num(loadController.dailyPump, 0));
  setText("pumpState", loadOff ? "---" : boolText(!!loadController.pumpOn));
  setText("pumpModalState", loadOff ? "---" : boolText(!!loadController.pumpOn));
  setText("pumpModalReason", loadOff ? "модуль вимкнено" : uiText(loadController.pumpStateReason, "вручну"));
  renderAutoWindowBlock("pump", {
    enabled: !!loadController.pumpAutoWindowEnabled,
    start: safeText(loadController.pumpAutoWindowStart, "00:00"),
    end: safeText(loadController.pumpAutoWindowEnd, "00:00"),
    active: loadController.pumpAutoWindowActive !== false,
  }, { disabled: loadOff });

  setText("boiler2Power", garageOff ? "--" : num(boiler2PowerCardW, 0));
  setText("boiler2Mode", garageOff ? "вимкнено" : safeText(garage.boiler2Mode));
  setText("boiler2Current", garageOff ? "--" : num(garage.boilerCurrent, 2));
  setText("boiler2Daily", garageOff ? "--" : num(garage.dailyBoiler, 0));
  setText("boiler2State", garageOff ? "---" : boolText(!!garage.boiler2On));
  setText("boiler2ModalState", garageOff ? "---" : boolText(!!garage.boiler2On));
  setText("boiler2ModalReason", garageOff ? "модуль вимкнено" : uiText(garage.boiler2StateReason, "вручну"));
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

  setText("gateState", garageOff ? "вимкнено" : uiText(garage.gateState, gateNormalized));
  setText("gateReason", garageOff ? "модуль вимкнено" : uiText(garage.gateReason, "вручну"));
  setText("gateLastOpen", garageOff ? "--" : safeText(state.gate.lastOpenAt, "--"));
  setText("gateLastClose", garageOff ? "--" : safeText(state.gate.lastCloseAt, "--"));
  setText("gateModalState", garageOff ? "вимкнено" : uiText(garage.gateState, gateNormalized));
  setText("gateModalReason", garageOff ? "модуль вимкнено" : uiText(garage.gateReason, "вручну"));
  setGateActionButtonLabel(gateNormalized, { disabled: garageOff });
  const garageLightReason = garageOff ? "модуль вимкнено" : uiText(garage.garageLightReason, "вручну");
  const garageLightOn = !garageOff && !!garage.garageLightOn;
  setText("garageLightState", garageOff ? "вимкнено" : boolText(garageLightOn));
  setGarageLightActionButtonState({
    disabled: garageOff,
    on: garageLightOn,
    reason: garageLightReason,
  });
  applyGarageLightAutoOffFromStatus(garageOff ? -1 : garage.garageLightAutoOffInSec);
  updateGarageLightCountdownDisplay();

  renderPowerScheme({
    inverter: inverterView,
    loadController,
    garage,
    invOff,
    loadOff,
    garageOff,
    gridPresent,
  });

  applyCardNeonByPower("cardPv", inverterView.pvW, !invOff);
  applyCardNeonByPower("cardGrid", inverterView.gridW, !invOff);
  applyCardNeonByPower("cardLoad", loadPowerCardW, !invOff);
  applyCardNeonByPower("cardBattery", inverterView.batteryPower, !invOff);
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

  applyCapabilityDrivenUi(status);

  applyLockedActiveButtons("btnLoad", state.locks.inverterLoadOn ? "ON" : "NONE");
  applyLockedActiveButtons("btnBoiler1", state.locks.boiler1);
  applyLockedActiveButtons("btnPump", state.locks.pump);
  applyLockedActiveButtons("btnBoiler2", state.locks.boiler2);

  setText("lastUpdateText", formatClockFromMs(status.updatedAtMs));
  setText("moduleInvUpdated", moduleUpdatedText(!invOff, inverter));
  setText("moduleLoadUpdated", moduleUpdatedText(!loadOff, loadController));
  setText("moduleGarageUpdated", moduleUpdatedText(!garageOff, garage));
  state.alerts.active = collectActiveAlerts();
  renderSystemAlerts();
  renderLogicModal();
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
    if (isModalOpen("logicModal")) {
      renderLogicModal();
    }
    if (isModalOpen("schemeModal")) {
      fitSchemeStageToViewport();
    }
  }, 120);
  window.addEventListener("resize", redraw);
}

function initUi() {
  initGraphSync();
  loadTimelineCacheFromStorage();
  bindCardEvents();
  bindSchemeSwipe();
  bindModeButtons();
  bindSettings();
  bindEnergyControls();
  bindClimateControls();
  bindResizeRedraw();

  loadConfigFromBridge();
  applyInterfaceMode("pro");
  syncConfigToForm();
  applyModuleCardStates();
  applyLiveCardStates(null);
  renderSystemAlerts();
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

