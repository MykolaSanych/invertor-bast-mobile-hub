(function () {
  if (window.AndroidHub) return;

  function safeJsonParse(text, fallback) {
    if (!text) return fallback;
    try {
      return JSON.parse(text);
    } catch (_error) {
      return fallback;
    }
  }

  function syncJson(method, path, body) {
    const xhr = new XMLHttpRequest();
    xhr.open(method, path, false);
    xhr.setRequestHeader("Accept", "application/json");
    if (body !== undefined) {
      xhr.setRequestHeader("Content-Type", "application/json");
    }
    try {
      xhr.send(body === undefined ? null : JSON.stringify(body));
    } catch (_error) {
      return null;
    }
    if (xhr.status < 200 || xhr.status >= 300) {
      return null;
    }
    return safeJsonParse(xhr.responseText, null);
  }

  async function fetchJson(path, options) {
    const response = await fetch(path, {
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        ...(options && options.body ? { "Content-Type": "application/json" } : {}),
      },
      ...options,
    });
    const rawText = await response.text();
    const payload = safeJsonParse(rawText, null);
    if (!response.ok) {
      const message = payload && typeof payload.error === "string"
        ? payload.error
        : `HTTP ${response.status}`;
      throw new Error(message);
    }
    return payload;
  }

  function statusResult(requestId, payload) {
    if (window.HubNative && typeof window.HubNative.onStatusResult === "function") {
      window.HubNative.onStatusResult(requestId, payload);
    }
  }

  function statusError(requestId, message) {
    if (window.HubNative && typeof window.HubNative.onStatusError === "function") {
      window.HubNative.onStatusError(requestId, message);
    }
  }

  function dataResult(requestId, payload) {
    if (window.HubNative && typeof window.HubNative.onDataResult === "function") {
      window.HubNative.onDataResult(requestId, payload);
    }
  }

  function dataError(requestId, message) {
    if (window.HubNative && typeof window.HubNative.onDataError === "function") {
      window.HubNative.onDataError(requestId, message);
    }
  }

  function actionResult(requestId, ok, message) {
    if (window.HubNative && typeof window.HubNative.onActionResult === "function") {
      window.HubNative.onActionResult(requestId, ok, message || null);
    }
  }

  function requestStatus(path, requestId) {
    fetchJson(path)
      .then((payload) => statusResult(requestId, payload))
      .catch((error) => statusError(requestId, error && error.message ? error.message : "Status request failed"));
  }

  function requestData(path, requestId, options) {
    fetchJson(path, options)
      .then((payload) => dataResult(requestId, payload))
      .catch((error) => dataError(requestId, error && error.message ? error.message : "Data request failed"));
  }

  function requestAction(methodName, requestId, args) {
    fetchJson(`/bridge/action/${encodeURIComponent(methodName)}`, {
      method: "POST",
      body: JSON.stringify(args || {}),
    })
      .then((payload) => {
        actionResult(requestId, !!payload && !!payload.ok, payload && payload.message ? payload.message : null);
        if (payload && payload.status) {
          statusResult(`refresh-${requestId}`, payload.status);
        }
      })
      .catch((error) => actionResult(requestId, false, error && error.message ? error.message : "Command failed"));
  }

  window.AndroidHub = {
    getConfig() {
      const payload = syncJson("GET", "/bridge/config");
      return JSON.stringify(payload || {});
    },

    saveConfig(rawJson) {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/bridge/config", false);
      xhr.setRequestHeader("Accept", "application/json");
      xhr.setRequestHeader("Content-Type", "application/json");
      try {
        xhr.send(rawJson || "{}");
      } catch (_error) {
        return false;
      }
      if (xhr.status < 200 || xhr.status >= 300) {
        return false;
      }
      const payload = safeJsonParse(xhr.responseText, null);
      return !!(payload && payload.ok);
    },

    fetchStatus(requestId) {
      requestStatus("/bridge/status", requestId);
    },

    requestMulticastRefresh(requestId) {
      requestStatus("/bridge/status?refresh=1", requestId);
    },

    fetchInverterDaily(date, requestId) {
      requestData(`/bridge/inverter/daily?date=${encodeURIComponent(date || "")}`, requestId);
    },

    fetchInverterMonthly(month, requestId) {
      requestData(`/bridge/inverter/monthly?month=${encodeURIComponent(month || "")}`, requestId);
    },

    fetchInverterYearly(requestId) {
      requestData("/bridge/inverter/yearly", requestId);
    },

    fetchLoadControllerHistory(requestId) {
      requestData("/bridge/load/history", requestId);
    },

    fetchGarageDoorHistory(requestId) {
      requestData("/bridge/garage/doorhistory", requestId);
    },

    fetchGarageHistory(requestId) {
      requestData("/bridge/garage/history", requestId);
    },

    fetchEventJournal(requestId) {
      requestData("/bridge/events", requestId);
    },

    fetchAutomationHistory(hours, requestId) {
      requestData(`/bridge/automation-history?hours=${encodeURIComponent(hours || 0)}`, requestId);
    },

    clearEventJournal(requestId) {
      requestData("/bridge/events", requestId, { method: "DELETE" });
    },

    setInverterGridMode(mode, requestId) {
      requestAction("setInverterGridMode", requestId, { mode });
    },

    setInverterLoadMode(mode, requestId) {
      requestAction("setInverterLoadMode", requestId, { mode });
    },

    setInverterLoadLock(locked, requestId) {
      requestAction("setInverterLoadLock", requestId, { locked: !!locked });
    },

    setInverterGridLogic(pvThresholdW, offDelaySec, onDelaySec, forceGridOnW, requestId) {
      requestAction("setInverterGridLogic", requestId, {
        pvThresholdW,
        offDelaySec,
        onDelaySec,
        forceGridOnW,
      });
    },

    setInverterLoadLogic(pvThresholdW, shutdownDelaySec, overloadPowerW, requestId) {
      requestAction("setInverterLoadLogic", requestId, {
        pvThresholdW,
        shutdownDelaySec,
        overloadPowerW,
      });
    },

    setBoiler1Mode(mode, requestId) {
      requestAction("setBoiler1Mode", requestId, { mode });
    },

    setBoiler1Lock(mode, requestId) {
      requestAction("setBoiler1Lock", requestId, { mode });
    },

    setBoiler1Logic(pvThresholdW, shutdownDelaySec, batteryShutoffW, batteryResumeW, peerActiveW, requestId) {
      requestAction("setBoiler1Logic", requestId, {
        pvThresholdW,
        shutdownDelaySec,
        batteryShutoffW,
        batteryResumeW,
        peerActiveW,
      });
    },

    setBoiler1AutoWindow(enabled, start, end, requestId) {
      requestAction("setBoiler1AutoWindow", requestId, {
        enabled: !!enabled,
        start,
        end,
      });
    },

    setPumpMode(mode, requestId) {
      requestAction("setPumpMode", requestId, { mode });
    },

    setPumpLock(mode, requestId) {
      requestAction("setPumpLock", requestId, { mode });
    },

    setPumpLogic(pvThresholdW, shutdownDelaySec, requestId) {
      requestAction("setPumpLogic", requestId, {
        pvThresholdW,
        shutdownDelaySec,
      });
    },

    setPumpAutoWindow(enabled, start, end, requestId) {
      requestAction("setPumpAutoWindow", requestId, {
        enabled: !!enabled,
        start,
        end,
      });
    },

    setBoiler2Mode(mode, requestId) {
      requestAction("setBoiler2Mode", requestId, { mode });
    },

    setBoiler2Lock(mode, requestId) {
      requestAction("setBoiler2Lock", requestId, { mode });
    },

    setBoiler2Logic(pvThresholdW, shutdownDelaySec, batteryShutoffW, batteryResumeW, peerActiveW, requestId) {
      requestAction("setBoiler2Logic", requestId, {
        pvThresholdW,
        shutdownDelaySec,
        batteryShutoffW,
        batteryResumeW,
        peerActiveW,
      });
    },

    setBoiler2AutoWindow(enabled, start, end, requestId) {
      requestAction("setBoiler2AutoWindow", requestId, {
        enabled: !!enabled,
        start,
        end,
      });
    },

    triggerGate(requestId) {
      requestAction("triggerGate", requestId, {});
    },

    toggleGarageLight(requestId) {
      requestAction("toggleGarageLight", requestId, {});
    },

    openExternalUrl(url) {
      const payload = syncJson("POST", "/bridge/open-external", { url });
      return !!(payload && payload.ok);
    },

    setChartsLandscapeMode(_enabled) {
    },
  };
})();
