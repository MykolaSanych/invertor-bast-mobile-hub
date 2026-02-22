package com.chapay.homehub

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.chapay.homehub.data.AppConfig
import com.chapay.homehub.data.AppConfigStorage
import com.chapay.homehub.data.GarageStatus
import com.chapay.homehub.data.InverterStatus
import com.chapay.homehub.data.LoadControllerStatus
import com.chapay.homehub.data.StatusRepository
import com.chapay.homehub.data.UnifiedStatus
import com.chapay.homehub.push.EventJournalStore
import com.chapay.homehub.push.MonitorController
import com.chapay.homehub.push.ensureNotificationChannel
import com.chapay.homehub.widget.StatusWidgetProvider
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val repository by lazy { StatusRepository(this) }
    private var chartsLandscapeMode = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Background notifications are disabled by permission", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensureNotificationChannel(this)
        MonitorController.applyStoredConfig(this, runImmediateWorker = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            displayZoomControls = false
            builtInZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {}
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(HubBridge(), "AndroidHub")
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.loadUrl("file:///android_asset/hub.html")
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidHub")
            webView.destroy()
        }
        super.onDestroy()
    }

    private inner class HubBridge {
        @JavascriptInterface
        fun getConfig(): String {
            val cfg = AppConfigStorage.load(this@MainActivity)
            return configToJson(cfg).toString()
        }

        @JavascriptInterface
        fun saveConfig(rawJson: String): Boolean {
            return runCatching {
                val current = AppConfigStorage.load(this@MainActivity)
                val parsed = JSONObject(rawJson)
                val cfg = AppConfig(
                    inverterBaseUrl = parsed.optString("inverterBaseUrl", current.inverterBaseUrl).trim(),
                    inverterPassword = parsed.optString("inverterPassword", current.inverterPassword),
                    loadControllerBaseUrl = parsed.optString("loadControllerBaseUrl", current.loadControllerBaseUrl).trim(),
                    loadControllerPassword = parsed.optString("loadControllerPassword", current.loadControllerPassword),
                    garageBaseUrl = parsed.optString("garageBaseUrl", current.garageBaseUrl).trim(),
                    garagePassword = parsed.optString("garagePassword", current.garagePassword),
                    pollIntervalSec = parsed.optInt("pollIntervalSec", current.pollIntervalSec).coerceIn(2, 60),
                    inverterEnabled = parsed.optBoolean("inverterEnabled", current.inverterEnabled),
                    loadControllerEnabled = parsed.optBoolean("loadControllerEnabled", current.loadControllerEnabled),
                    garageEnabled = parsed.optBoolean("garageEnabled", current.garageEnabled),
                    realtimeMonitorEnabled = parsed.optBoolean("realtimeMonitorEnabled", current.realtimeMonitorEnabled),
                    realtimePollIntervalSec = parsed.optInt("realtimePollIntervalSec", current.realtimePollIntervalSec).coerceIn(3, 60),
                    notifyPvGeneration = parsed.optBoolean("notifyPvGeneration", current.notifyPvGeneration),
                    notifyGridRelay = parsed.optBoolean("notifyGridRelay", current.notifyGridRelay),
                    notifyGridPresence = parsed.optBoolean("notifyGridPresence", current.notifyGridPresence),
                    notifyGridMode = parsed.optBoolean("notifyGridMode", current.notifyGridMode),
                    notifyLoadMode = parsed.optBoolean("notifyLoadMode", current.notifyLoadMode),
                    notifyBoiler1Mode = parsed.optBoolean("notifyBoiler1Mode", current.notifyBoiler1Mode),
                    notifyPumpMode = parsed.optBoolean("notifyPumpMode", current.notifyPumpMode),
                    notifyBoiler2Mode = parsed.optBoolean("notifyBoiler2Mode", current.notifyBoiler2Mode),
                    notifyGateState = parsed.optBoolean("notifyGateState", current.notifyGateState),
                )
                AppConfigStorage.save(this@MainActivity, cfg)
                MonitorController.applyConfig(this@MainActivity, cfg, runImmediateWorker = true)
                true
            }.getOrDefault(false)
        }

        @JavascriptInterface
        fun fetchStatus(requestId: String) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                runCatching { repository.fetchUnified(cfg) }
                    .onSuccess { status -> sendStatus(requestId, status) }
                    .onFailure { err -> sendStatusError(requestId, err.message ?: "Status request failed") }
            }
        }

        @JavascriptInterface
        fun requestMulticastRefresh(requestId: String) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                runCatching { repository.fetchUnified(cfg) }
                    .onSuccess { status -> sendStatus(requestId, status) }
                    .onFailure { err -> sendStatusError(requestId, err.message ?: "Refresh failed") }
            }
        }

        @JavascriptInterface
        fun fetchInverterDaily(date: String, requestId: String) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                runCatching { repository.fetchInverterDaily(cfg, date) }
                    .onSuccess { json ->
                        if (json == null) {
                            sendDataError(requestId, "No data")
                        } else {
                            sendDataResult(requestId, json.toString())
                        }
                    }
                    .onFailure { err -> sendDataError(requestId, err.message ?: "Daily data failed") }
            }
        }

        @JavascriptInterface
        fun fetchInverterMonthly(month: String, requestId: String) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                runCatching { repository.fetchInverterMonthly(cfg, month) }
                    .onSuccess { json ->
                        if (json == null) {
                            sendDataError(requestId, "No data")
                        } else {
                            sendDataResult(requestId, json.toString())
                        }
                    }
                    .onFailure { err -> sendDataError(requestId, err.message ?: "Monthly data failed") }
            }
        }

        @JavascriptInterface
        fun fetchInverterYearly(requestId: String) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                runCatching { repository.fetchInverterYearly(cfg) }
                    .onSuccess { json ->
                        if (json == null) {
                            sendDataError(requestId, "No data")
                        } else {
                            sendDataResult(requestId, json.toString())
                        }
                    }
                    .onFailure { err -> sendDataError(requestId, err.message ?: "Yearly data failed") }
            }
        }

        @JavascriptInterface
        fun fetchLoadControllerHistory(requestId: String) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                runCatching { repository.fetchLoadControllerHistory(cfg) }
                    .onSuccess { json ->
                        if (json == null) {
                            sendDataError(requestId, "No data")
                        } else {
                            sendDataResult(requestId, json.toString())
                        }
                    }
                    .onFailure { err -> sendDataError(requestId, err.message ?: "History data failed") }
            }
        }

        @JavascriptInterface
        fun fetchEventJournal(requestId: String) {
            lifecycleScope.launch {
                runCatching { EventJournalStore.toJson(this@MainActivity) }
                    .onSuccess { payload -> sendDataResult(requestId, payload.toString()) }
                    .onFailure { err -> sendDataError(requestId, err.message ?: "Event journal load failed") }
            }
        }

        @JavascriptInterface
        fun clearEventJournal(requestId: String) {
            lifecycleScope.launch {
                runCatching {
                    EventJournalStore.clear(this@MainActivity)
                    JSONObject().put("ok", true)
                }
                    .onSuccess { payload -> sendDataResult(requestId, payload.toString()) }
                    .onFailure { err -> sendDataError(requestId, err.message ?: "Event journal clear failed") }
            }
        }

        @JavascriptInterface
        fun setInverterGridMode(mode: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setInverterGridMode(cfg, mode.uppercase()) }
        }

        @JavascriptInterface
        fun setInverterLoadMode(mode: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setInverterLoadMode(cfg, mode.uppercase()) }
        }

        @JavascriptInterface
        fun setInverterLoadLock(locked: Boolean, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setInverterLoadLock(cfg, locked) }
        }

        @JavascriptInterface
        fun setBoiler1Mode(mode: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setBoiler1Mode(cfg, mode.uppercase()) }
        }

        @JavascriptInterface
        fun setBoiler1Lock(mode: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setBoiler1Lock(cfg, mode.uppercase()) }
        }

        @JavascriptInterface
        fun setPumpMode(mode: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setPumpMode(cfg, mode.uppercase()) }
        }

        @JavascriptInterface
        fun setPumpLock(mode: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setPumpLock(cfg, mode.uppercase()) }
        }

        @JavascriptInterface
        fun setBoiler2Mode(mode: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setBoiler2Mode(cfg, mode.uppercase()) }
        }

        @JavascriptInterface
        fun setBoiler2Lock(mode: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setBoiler2Lock(cfg, mode.uppercase()) }
        }

        @JavascriptInterface
        fun triggerGate(requestId: String) {
            runModeCommand(requestId) { cfg -> repository.triggerGate(cfg) }
        }

        @JavascriptInterface
        fun openInAppUrl(url: String): Boolean {
            val raw = url.trim()
            if (raw.isEmpty()) return false
            if (!::webView.isInitialized) return false

            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false

            return runCatching {
                runOnUiThread {
                    webView.loadUrl(uri.toString())
                }
                true
            }.getOrDefault(false)
        }

        @JavascriptInterface
        fun openExternalUrl(url: String): Boolean {
            val raw = url.trim()
            if (raw.isEmpty()) return false

            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false

            return runCatching {
                runOnUiThread {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                true
            }.getOrDefault(false)
        }

        @JavascriptInterface
        fun setChartsLandscapeMode(enabled: Boolean) {
            runOnUiThread {
                if (chartsLandscapeMode == enabled) return@runOnUiThread
                chartsLandscapeMode = enabled
                requestedOrientation = if (enabled) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }

        private fun runModeCommand(
            requestId: String,
            action: suspend (AppConfig) -> Boolean,
        ) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                val ok = runCatching { action(cfg) }.getOrDefault(false)
                if (!ok) {
                    sendActionResult(requestId, false, "Command failed")
                    return@launch
                }
                sendActionResult(requestId, true, null)
                runCatching { repository.fetchUnified(cfg) }
                    .onSuccess { status -> sendStatus("refresh-$requestId", status) }
                    .onFailure { err ->
                        Log.w("HomeHub", "Refresh after command failed: ${err.message}")
                    }
            }
        }

        private fun sendStatus(requestId: String, status: UnifiedStatus) {
            StatusWidgetProvider.updateAllWidgets(this@MainActivity, status)
            evaluateJs(
                "window.HubNative&&window.HubNative.onStatusResult(" +
                    "${JSONObject.quote(requestId)},${status.toJson()});",
            )
        }

        private fun sendStatusError(requestId: String, message: String) {
            evaluateJs(
                "window.HubNative&&window.HubNative.onStatusError(" +
                    "${JSONObject.quote(requestId)},${JSONObject.quote(message)});",
            )
        }

        private fun sendActionResult(requestId: String, ok: Boolean, message: String?) {
            val safeMessage = message?.let { JSONObject.quote(it) } ?: "null"
            evaluateJs(
                "window.HubNative&&window.HubNative.onActionResult(" +
                    "${JSONObject.quote(requestId)},$ok,$safeMessage);",
            )
        }

        private fun sendDataResult(requestId: String, payload: String) {
            evaluateJs(
                "window.HubNative&&window.HubNative.onDataResult(" +
                    "${JSONObject.quote(requestId)},$payload);",
            )
        }

        private fun sendDataError(requestId: String, message: String) {
            evaluateJs(
                "window.HubNative&&window.HubNative.onDataError(" +
                    "${JSONObject.quote(requestId)},${JSONObject.quote(message)});",
            )
        }

        private fun evaluateJs(script: String) {
            runOnUiThread {
                if (!::webView.isInitialized) return@runOnUiThread
                webView.evaluateJavascript(script, null)
            }
        }
    }
}

private fun configToJson(cfg: AppConfig): JSONObject = JSONObject().apply {
    put("inverterBaseUrl", cfg.inverterBaseUrl)
    put("inverterPassword", cfg.inverterPassword)
    put("loadControllerBaseUrl", cfg.loadControllerBaseUrl)
    put("loadControllerPassword", cfg.loadControllerPassword)
    put("garageBaseUrl", cfg.garageBaseUrl)
    put("garagePassword", cfg.garagePassword)
    put("pollIntervalSec", cfg.pollIntervalSec)
    put("inverterEnabled", cfg.inverterEnabled)
    put("loadControllerEnabled", cfg.loadControllerEnabled)
    put("garageEnabled", cfg.garageEnabled)
    put("realtimeMonitorEnabled", cfg.realtimeMonitorEnabled)
    put("realtimePollIntervalSec", cfg.realtimePollIntervalSec)
    put("notifyPvGeneration", cfg.notifyPvGeneration)
    put("notifyGridRelay", cfg.notifyGridRelay)
    put("notifyGridPresence", cfg.notifyGridPresence)
    put("notifyGridMode", cfg.notifyGridMode)
    put("notifyLoadMode", cfg.notifyLoadMode)
    put("notifyBoiler1Mode", cfg.notifyBoiler1Mode)
    put("notifyPumpMode", cfg.notifyPumpMode)
    put("notifyBoiler2Mode", cfg.notifyBoiler2Mode)
    put("notifyGateState", cfg.notifyGateState)
}

private fun UnifiedStatus.toJson(): JSONObject = JSONObject().apply {
    put("updatedAtMs", updatedAtMs)
    put("fromMulticast", fromMulticast)
    put("inverter", inverter?.toJson() ?: JSONObject.NULL)
    put("loadController", loadController?.toJson() ?: JSONObject.NULL)
    put("garage", garage?.toJson() ?: JSONObject.NULL)
}

private fun InverterStatus.toJson(): JSONObject = JSONObject().apply {
    put("pvW", pvW)
    put("gridW", gridW)
    put("loadW", loadW)
    put("lineVoltage", lineVoltage)
    put("pvVoltage", pvVoltage)
    put("batteryVoltage", batteryVoltage)
    put("gridFrequency", gridFrequency)
    put("outputVoltage", outputVoltage)
    put("outputFrequency", outputFrequency)
    put("inverterTemp", inverterTemp)
    put("dailyPV", dailyPv)
    put("dailyHome", dailyHome)
    put("dailyGrid", dailyGrid)
    put("lastUpdate", lastUpdate)
    put("loadOnLocked", loadOnLocked)
    put("batterySoc", batterySoc)
    put("batteryPower", batteryPower)
    put("mode", mode)
    put("modeReason", modeReason)
    put("loadMode", loadMode)
    put("loadModeReason", loadModeReason)
    put("gridRelayOn", gridRelayOn)
    put("gridPresent", gridPresent)
    put("gridRelayReason", gridRelayReason)
    put("loadRelayOn", loadRelayOn)
    put("loadRelayReason", loadRelayReason)
    put("wifiStrength", wifiStrength)
    put("rtcTime", rtcTime)
    put("rtcDate", rtcDate)
    put("updatedAtMs", updatedAtMs)
    put("bmeAvailable", bmeAvailable)
    put("bmeTemp", bmeTemp)
    put("bmeHum", bmeHum)
    put("bmePress", bmePress)
    put("bmeExtAvailable", bmeExtAvailable)
    put("bmeExtTemp", bmeExtTemp)
    put("bmeExtHum", bmeExtHum)
    put("bmeExtPress", bmeExtPress)
}

private fun LoadControllerStatus.toJson(): JSONObject = JSONObject().apply {
    put("boiler1Mode", boiler1Mode)
    put("boiler1ModeReason", boiler1ModeReason)
    put("boiler1On", boiler1On)
    put("boiler1StateReason", boiler1StateReason)
    put("pumpMode", pumpMode)
    put("pumpModeReason", pumpModeReason)
    put("pumpOn", pumpOn)
    put("pumpStateReason", pumpStateReason)
    put("boilerLock", boilerLock)
    put("pumpLock", pumpLock)
    put("boilerCurrent", boilerCurrent)
    put("boilerPower", boilerPower)
    put("dailyBoiler", dailyBoiler)
    put("pumpCurrent", pumpCurrent)
    put("pumpPower", pumpPower)
    put("dailyPump", dailyPump)
    put("lineVoltage", lineVoltage)
    put("pvW", pvW)
    put("gridW", gridW)
    put("loadW", loadW)
    put("batterySoc", batterySoc)
    put("batteryPower", batteryPower)
    put("wifiStrength", wifiStrength)
    put("rtcTime", rtcTime)
    put("rtcDate", rtcDate)
    put("updatedAtMs", updatedAtMs)
    put("bmeAvailable", bmeAvailable)
    put("bmeTemp", bmeTemp)
    put("bmeHum", bmeHum)
    put("bmePress", bmePress)
}

private fun GarageStatus.toJson(): JSONObject = JSONObject().apply {
    put("boiler2Mode", boiler2Mode)
    put("boiler2ModeReason", boiler2ModeReason)
    put("boiler2On", boiler2On)
    put("boiler2StateReason", boiler2StateReason)
    put("boilerLock", boilerLock)
    put("boilerCurrent", boilerCurrent)
    put("boilerPower", boilerPower)
    put("dailyBoiler", dailyBoiler)
    put("gateState", gateState)
    put("gateReason", gateReason)
    put("gateOpenPin", gateOpenPin)
    put("gateClosedPin", gateClosedPin)
    put("lineVoltage", lineVoltage)
    put("pvW", pvW)
    put("gridW", gridW)
    put("loadW", loadW)
    put("batterySoc", batterySoc)
    put("batteryPower", batteryPower)
    put("wifiStrength", wifiStrength)
    put("rtcTime", rtcTime)
    put("rtcDate", rtcDate)
    put("updatedAtMs", updatedAtMs)
    put("bmeAvailable", bmeAvailable)
    put("bmeTemp", bmeTemp)
    put("bmeHum", bmeHum)
    put("bmePress", bmePress)
}
