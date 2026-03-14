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
import com.chapay.homehub.data.BoilerLogicConfig
import com.chapay.homehub.data.GarageStatus
import com.chapay.homehub.data.InverterGridLogicConfig
import com.chapay.homehub.data.InverterLoadLogicConfig
import com.chapay.homehub.data.InverterStatus
import com.chapay.homehub.data.LoadControllerStatus
import com.chapay.homehub.data.PumpLogicConfig
import com.chapay.homehub.data.StatusRepository
import com.chapay.homehub.data.UnifiedStatus
import com.chapay.homehub.push.EventJournalStore
import com.chapay.homehub.push.MonitorController
import com.chapay.homehub.push.RollingStatusHistoryStore
import com.chapay.homehub.push.StatusChangeProcessor
import com.chapay.homehub.push.ensureNotificationChannel
import com.chapay.homehub.widget.StatusWidgetProvider
import kotlinx.coroutines.launch
import org.json.JSONArray
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
                    notifyModuleOffline = parsed.optBoolean("notifyModuleOffline", current.notifyModuleOffline),
                    notifyPowerOverload = parsed.optBoolean("notifyPowerOverload", current.notifyPowerOverload),
                    notifyLogicUnstable = parsed.optBoolean("notifyLogicUnstable", current.notifyLogicUnstable),
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
                var partialSeq = 0
                runCatching {
                    repository.fetchUnifiedProgressive(cfg) { moduleKey, status ->
                        partialSeq += 1
                        sendStatus("partial-$requestId-$partialSeq-$moduleKey", status)
                    }
                }
                    .onSuccess { status ->
                        StatusChangeProcessor.process(
                            context = this@MainActivity,
                            status = status,
                            config = cfg,
                            emitNotifications = false,
                        )
                        sendStatus(requestId, status)
                    }
                    .onFailure { err -> sendStatusError(requestId, err.message ?: "Status request failed") }
            }
        }

        @JavascriptInterface
        fun requestMulticastRefresh(requestId: String) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                runCatching { repository.requestMulticastRefresh(cfg) }
                    .onSuccess { status ->
                        StatusChangeProcessor.process(
                            context = this@MainActivity,
                            status = status,
                            config = cfg,
                            emitNotifications = false,
                        )
                        sendStatus(requestId, status)
                    }
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
        fun fetchGarageDoorHistory(requestId: String) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                runCatching { repository.fetchGarageDoorHistory(cfg) }
                    .onSuccess { json ->
                        if (json == null) {
                            sendDataError(requestId, "No data")
                        } else {
                            sendDataResult(requestId, json.toString())
                        }
                    }
                    .onFailure { err -> sendDataError(requestId, err.message ?: "Garage door history failed") }
            }
        }

        @JavascriptInterface
        fun fetchGarageHistory(requestId: String) {
            lifecycleScope.launch {
                val cfg = AppConfigStorage.load(this@MainActivity)
                runCatching { repository.fetchGarageHistory(cfg) }
                    .onSuccess { json ->
                        if (json == null) {
                            sendDataError(requestId, "No data")
                        } else {
                            sendDataResult(requestId, json.toString())
                        }
                    }
                    .onFailure { err -> sendDataError(requestId, err.message ?: "Garage history data failed") }
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
        fun fetchAutomationHistory(hours: Int, requestId: String) {
            lifecycleScope.launch {
                runCatching { RollingStatusHistoryStore.toJson(this@MainActivity, hours) }
                    .onSuccess { payload -> sendDataResult(requestId, payload.toString()) }
                    .onFailure { err -> sendDataError(requestId, err.message ?: "Automation history load failed") }
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
        fun setInverterGridLogic(
            pvThresholdW: Double,
            offDelaySec: Int,
            onDelaySec: Int,
            forceGridOnW: Double,
            requestId: String,
        ) {
            runModeCommand(requestId) { cfg ->
                repository.setInverterGridLogic(
                    config = cfg,
                    pvThresholdW = pvThresholdW,
                    offDelaySec = offDelaySec,
                    onDelaySec = onDelaySec,
                    forceGridOnW = forceGridOnW,
                )
            }
        }

        @JavascriptInterface
        fun setInverterLoadLogic(
            pvThresholdW: Double,
            shutdownDelaySec: Int,
            overloadPowerW: Double,
            requestId: String,
        ) {
            runModeCommand(requestId) { cfg ->
                repository.setInverterLoadLogic(
                    config = cfg,
                    pvThresholdW = pvThresholdW,
                    shutdownDelaySec = shutdownDelaySec,
                    overloadPowerW = overloadPowerW,
                )
            }
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
        fun setBoiler1Logic(
            pvThresholdW: Double,
            shutdownDelaySec: Int,
            batteryShutoffW: Double,
            batteryResumeW: Double,
            peerActiveW: Double,
            requestId: String,
        ) {
            runModeCommand(requestId) { cfg ->
                repository.setBoiler1Logic(
                    config = cfg,
                    pvThresholdW = pvThresholdW,
                    shutdownDelaySec = shutdownDelaySec,
                    batteryShutoffW = batteryShutoffW,
                    batteryResumeW = batteryResumeW,
                    peerActiveW = peerActiveW,
                )
            }
        }

        @JavascriptInterface
        fun setBoiler1AutoWindow(enabled: Boolean, start: String, end: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setBoiler1AutoWindow(cfg, enabled, start, end) }
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
        fun setPumpLogic(
            pvThresholdW: Double,
            shutdownDelaySec: Int,
            requestId: String,
        ) {
            runModeCommand(requestId) { cfg ->
                repository.setPumpLogic(
                    config = cfg,
                    pvThresholdW = pvThresholdW,
                    shutdownDelaySec = shutdownDelaySec,
                )
            }
        }

        @JavascriptInterface
        fun setPumpAutoWindow(enabled: Boolean, start: String, end: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setPumpAutoWindow(cfg, enabled, start, end) }
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
        fun setBoiler2Logic(
            pvThresholdW: Double,
            shutdownDelaySec: Int,
            batteryShutoffW: Double,
            batteryResumeW: Double,
            peerActiveW: Double,
            requestId: String,
        ) {
            runModeCommand(requestId) { cfg ->
                repository.setBoiler2Logic(
                    config = cfg,
                    pvThresholdW = pvThresholdW,
                    shutdownDelaySec = shutdownDelaySec,
                    batteryShutoffW = batteryShutoffW,
                    batteryResumeW = batteryResumeW,
                    peerActiveW = peerActiveW,
                )
            }
        }

        @JavascriptInterface
        fun setBoiler2AutoWindow(enabled: Boolean, start: String, end: String, requestId: String) {
            runModeCommand(requestId) { cfg -> repository.setBoiler2AutoWindow(cfg, enabled, start, end) }
        }

        @JavascriptInterface
        fun triggerGate(requestId: String) {
            runModeCommand(requestId) { cfg ->
                repository.triggerGate(
                    config = cfg,
                    source = "mobile_hub",
                    reason = "mobile hub",
                )
            }
        }

        @JavascriptInterface
        fun toggleGarageLight(requestId: String) {
            runModeCommand(requestId) { cfg ->
                repository.toggleGarageLight(
                    config = cfg,
                    source = "mobile_hub",
                    reason = "mobile hub",
                )
            }
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
                    .onSuccess { status ->
                        StatusChangeProcessor.process(
                            context = this@MainActivity,
                            status = status,
                            config = cfg,
                            emitNotifications = false,
                        )
                        sendStatus("refresh-$requestId", status)
                    }
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
    put("notifyModuleOffline", cfg.notifyModuleOffline)
    put("notifyPowerOverload", cfg.notifyPowerOverload)
    put("notifyLogicUnstable", cfg.notifyLogicUnstable)
}

private fun UnifiedStatus.toJson(): JSONObject = JSONObject().apply {
    put("schemaVersion", 2)
    put("updatedAtMs", updatedAtMs)
    put("fromMulticast", fromMulticast)
    put("capabilities", buildHubCapabilitiesJson(this@toJson))
    put("inverter", inverter?.toJson() ?: JSONObject.NULL)
    put("loadController", loadController?.toJson() ?: JSONObject.NULL)
    put("garage", garage?.toJson() ?: JSONObject.NULL)
}

private fun InverterGridLogicConfig.toJson(): JSONObject = JSONObject().apply {
    put("pvThresholdW", pvThresholdW)
    put("offDelaySec", offDelaySec)
    put("onDelaySec", onDelaySec)
    put("forceGridOnW", forceGridOnW)
    put("batteryLowSocPct", batteryLowSocPct)
    put("offMinSocPct", offMinSocPct)
}

private fun InverterLoadLogicConfig.toJson(): JSONObject = JSONObject().apply {
    put("pvThresholdW", pvThresholdW)
    put("shutdownDelaySec", shutdownDelaySec)
    put("overloadPowerW", overloadPowerW)
    put("gridRestoreV", gridRestoreV)
    put("overloadGridV", overloadGridV)
}

private fun BoilerLogicConfig.toJson(): JSONObject = JSONObject().apply {
    put("pvThresholdW", pvThresholdW)
    put("shutdownDelaySec", shutdownDelaySec)
    put("batteryShutoffW", batteryShutoffW)
    put("batteryResumeW", batteryResumeW)
    put("peerActiveW", peerActiveW)
    put("gridRestoreV", gridRestoreV)
    put("batteryReleaseGridV", batteryReleaseGridV)
    put("batteryReleaseSocPct", batteryReleaseSocPct)
}

private fun PumpLogicConfig.toJson(): JSONObject = JSONObject().apply {
    put("pvThresholdW", pvThresholdW)
    put("shutdownDelaySec", shutdownDelaySec)
    put("gridRestoreV", gridRestoreV)
}

private fun InverterStatus.toJson(): JSONObject = JSONObject().apply {
    put("moduleKey", "inverter")
    put("available", true)
    put("capabilities", buildInverterCapabilitiesJson(this@toJson))
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
    put("gridLogic", gridLogic?.toJson() ?: JSONObject.NULL)
    put("loadLogic", loadLogic?.toJson() ?: JSONObject.NULL)
    put("gridRelayOn", gridRelayOn)
    put("gridPresent", gridPresent)
    put("gridRelayReason", gridRelayReason)
    put("loadRelayOn", loadRelayOn)
    put("loadRelayReason", loadRelayReason)
    put("wifiStrength", wifiStrength)
    put("uptimeSec", uptimeSec)
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
    put("moduleKey", "loadController")
    put("available", true)
    put("capabilities", buildLoadControllerCapabilitiesJson(this@toJson))
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
    put("boilerLogic", boilerLogic?.toJson() ?: JSONObject.NULL)
    put("pumpLogic", pumpLogic?.toJson() ?: JSONObject.NULL)
    put("boiler1AutoWindowEnabled", boiler1AutoWindowEnabled)
    put("boiler1AutoWindowStart", boiler1AutoWindowStart)
    put("boiler1AutoWindowEnd", boiler1AutoWindowEnd)
    put("boiler1AutoWindowActive", boiler1AutoWindowActive)
    put("pumpAutoWindowEnabled", pumpAutoWindowEnabled)
    put("pumpAutoWindowStart", pumpAutoWindowStart)
    put("pumpAutoWindowEnd", pumpAutoWindowEnd)
    put("pumpAutoWindowActive", pumpAutoWindowActive)
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
    put("uptimeSec", uptimeSec)
    put("rtcTime", rtcTime)
    put("rtcDate", rtcDate)
    put("updatedAtMs", updatedAtMs)
    put("bmeAvailable", bmeAvailable)
    put("bmeTemp", bmeTemp)
    put("bmeHum", bmeHum)
    put("bmePress", bmePress)
}

private fun GarageStatus.toJson(): JSONObject = JSONObject().apply {
    put("moduleKey", "garage")
    put("available", true)
    put("capabilities", buildGarageCapabilitiesJson(this@toJson))
    put("boiler2Mode", boiler2Mode)
    put("boiler2ModeReason", boiler2ModeReason)
    put("boiler2On", boiler2On)
    put("boiler2StateReason", boiler2StateReason)
    put("boilerLock", boilerLock)
    put("boilerLogic", boilerLogic?.toJson() ?: JSONObject.NULL)
    put("boiler2AutoWindowEnabled", boiler2AutoWindowEnabled)
    put("boiler2AutoWindowStart", boiler2AutoWindowStart)
    put("boiler2AutoWindowEnd", boiler2AutoWindowEnd)
    put("boiler2AutoWindowActive", boiler2AutoWindowActive)
    put("boilerCurrent", boilerCurrent)
    put("boilerPower", boilerPower)
    put("dailyBoiler", dailyBoiler)
    put("gateState", gateState)
    put("gateReason", gateReason)
    put("gateSource", gateSource)
    put("gateOpenPin", gateOpenPin)
    put("gateClosedPin", gateClosedPin)
    put("garageLightOn", garageLightOn)
    put("garageLightReason", garageLightReason)
    put("lineVoltage", lineVoltage)
    put("pvW", pvW)
    put("gridW", gridW)
    put("loadW", loadW)
    put("batterySoc", batterySoc)
    put("batteryPower", batteryPower)
    put("wifiStrength", wifiStrength)
    put("uptimeSec", uptimeSec)
    put("rtcTime", rtcTime)
    put("rtcDate", rtcDate)
    put("updatedAtMs", updatedAtMs)
    put("bmeAvailable", bmeAvailable)
    put("bmeTemp", bmeTemp)
    put("bmeHum", bmeHum)
    put("bmePress", bmePress)
}

private fun buildHubCapabilitiesJson(status: UnifiedStatus): JSONObject = JSONObject().apply {
    put("historyHours", 6)
    put("eventJournal", true)
    put("automationHistory", true)
    put(
        "logicKeys",
        JSONArray(
            buildList {
                if (status.inverter?.gridLogic != null) add("grid")
                if (status.inverter?.loadLogic != null) add("load")
                if (status.loadController?.boilerLogic != null) add("boiler1")
                if (status.loadController?.pumpLogic != null) add("pump")
                if (status.garage?.boilerLogic != null) add("boiler2")
            },
        ),
    )
    put(
        "modules",
        JSONObject().apply {
            put("inverter", buildInverterCapabilitiesJson(status.inverter))
            put("loadController", buildLoadControllerCapabilitiesJson(status.loadController))
            put("garage", buildGarageCapabilitiesJson(status.garage))
        },
    )
}

private fun buildInverterCapabilitiesJson(status: InverterStatus?): JSONObject = JSONObject().apply {
    put("available", status != null)
    put("logicKeys", JSONArray(buildList {
        if (status?.gridLogic != null) add("grid")
        if (status?.loadLogic != null) add("load")
    }))
    put("history", status != null)
    put("events", true)
    put("climate", status?.bmeAvailable == true || status?.bmeExtAvailable == true)
}

private fun buildLoadControllerCapabilitiesJson(status: LoadControllerStatus?): JSONObject = JSONObject().apply {
    put("available", status != null)
    put("logicKeys", JSONArray(buildList {
        if (status?.boilerLogic != null) add("boiler1")
        if (status?.pumpLogic != null) add("pump")
    }))
    put("history", status != null)
    put("events", true)
    put("autoWindow", status != null)
    put("climate", status?.bmeAvailable == true)
}

private fun buildGarageCapabilitiesJson(status: GarageStatus?): JSONObject = JSONObject().apply {
    put("available", status != null)
    put("logicKeys", JSONArray(buildList {
        if (status?.boilerLogic != null) add("boiler2")
    }))
    put("history", status != null)
    put("events", true)
    put("autoWindow", status != null)
    put("gate", status != null)
    put("garageLight", status != null)
    put("climate", status?.bmeAvailable == true)
}
