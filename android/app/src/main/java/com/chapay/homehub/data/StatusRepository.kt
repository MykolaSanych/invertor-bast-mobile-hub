package com.chapay.homehub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class StatusRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetchUnified(config: AppConfig): UnifiedStatus = coroutineScope {
        val inverterDeferred = async {
            if (!config.inverterEnabled) {
                null
            } else {
                runCatching { fetchInverter(config) }.getOrNull()
            }
        }
        val loadDeferred = async {
            if (!config.loadControllerEnabled) {
                null
            } else {
                runCatching { fetchLoadController(config) }.getOrNull()
            }
        }
        val garageDeferred = async {
            if (!config.garageEnabled) {
                null
            } else {
                runCatching { fetchGarage(config) }.getOrNull()
            }
        }

        UnifiedStatus(
            inverter = inverterDeferred.await(),
            loadController = loadDeferred.await(),
            garage = garageDeferred.await(),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    suspend fun fetchInverterDaily(config: AppConfig, date: String): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext null
        fetchJsonWithAuth(
            baseUrlRaw = config.inverterBaseUrl,
            password = config.inverterPassword,
            path = "/api/daily",
            query = mapOf("date" to date),
        )
    }

    suspend fun fetchInverterMonthly(config: AppConfig, month: String): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext null
        fetchJsonWithAuth(
            baseUrlRaw = config.inverterBaseUrl,
            password = config.inverterPassword,
            path = "/api/monthly",
            query = mapOf("month" to month),
        )
    }

    suspend fun fetchInverterYearly(config: AppConfig): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext null
        fetchJsonWithAuth(
            baseUrlRaw = config.inverterBaseUrl,
            password = config.inverterPassword,
            path = "/api/yearly",
        )
    }

    suspend fun fetchLoadControllerHistory(config: AppConfig): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext null
        fetchJsonWithAuth(
            baseUrlRaw = config.loadControllerBaseUrl,
            password = config.loadControllerPassword,
            path = "/api/history",
        )
    }

    suspend fun setInverterGridMode(config: AppConfig, mode: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext false
        postMode(config.inverterBaseUrl, config.inverterPassword, "/api/mode", mode)
    }

    suspend fun setInverterLoadMode(config: AppConfig, mode: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext false
        postMode(config.inverterBaseUrl, config.inverterPassword, "/api/loadmode", mode)
    }

    suspend fun setInverterLoadLock(config: AppConfig, locked: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.inverterBaseUrl,
            password = config.inverterPassword,
            path = "/api/loadlock",
            formPairs = listOf("locked" to if (locked) "1" else "0"),
        )
    }

    suspend fun setBoiler1Mode(config: AppConfig, mode: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext false
        postMode(config.loadControllerBaseUrl, config.loadControllerPassword, "/api/mode", mode)
    }

    suspend fun setBoiler1Lock(config: AppConfig, lockMode: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.loadControllerBaseUrl,
            password = config.loadControllerPassword,
            path = "/api/boilerlock",
            formPairs = listOf("lock" to lockMode.uppercase()),
        )
    }

    suspend fun setPumpMode(config: AppConfig, mode: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext false
        postMode(config.loadControllerBaseUrl, config.loadControllerPassword, "/api/loadmode", mode)
    }

    suspend fun setPumpLock(config: AppConfig, lockMode: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.loadControllerBaseUrl,
            password = config.loadControllerPassword,
            path = "/api/pumplock",
            formPairs = listOf("lock" to lockMode.uppercase()),
        )
    }

    suspend fun setBoiler2Mode(config: AppConfig, mode: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext false
        postMode(config.garageBaseUrl, config.garagePassword, "/api/mode", mode)
    }

    suspend fun setBoiler2Lock(config: AppConfig, lockMode: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.garageBaseUrl,
            password = config.garagePassword,
            path = "/api/boilerlock",
            formPairs = listOf("lock" to lockMode.uppercase()),
        )
    }

    suspend fun triggerGate(config: AppConfig): Boolean = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.garageBaseUrl,
            password = config.garagePassword,
            path = "/api/door",
            formPairs = listOf("action" to "pulse"),
        )
    }

    private suspend fun fetchInverter(config: AppConfig): InverterStatus? = withContext(Dispatchers.IO) {
        val json = fetchStatusJson(config.inverterBaseUrl, config.inverterPassword) ?: return@withContext null
        InverterStatus(
            pvW = json.optDoubleSafe("pv"),
            gridW = json.optDoubleSafe("ac_in"),
            loadW = json.optDoubleSafe("ac_out"),
            lineVoltage = json.optDoubleSafe("gridVolt"),
            pvVoltage = json.optDoubleSafe("pvVolt"),
            batteryVoltage = json.optDoubleSafe("batVolt"),
            gridFrequency = json.optDoubleSafe("gridFreq"),
            outputVoltage = json.optDoubleSafe("outputVolt"),
            outputFrequency = json.optDoubleSafe("outputFreq"),
            inverterTemp = json.optDoubleSafe("inverterTemp"),
            dailyPv = json.optDoubleSafe("dailyPV"),
            dailyHome = json.optDoubleSafe("dailyHome"),
            dailyGrid = json.optDoubleSafe("dailyGrid"),
            lastUpdate = json.optStringSafe("last_update", "--:--:--"),
            loadOnLocked = json.optBooleanSafe("load_on_locked"),
            batterySoc = json.optDoubleSafe("battery"),
            batteryPower = json.optDoubleSafe("battery_power"),
            mode = json.optString("mode", "---"),
            modeReason = json.optStringSafe("mode_reason", "unknown"),
            loadMode = json.optString("load_mode", "---"),
            loadModeReason = json.optStringSafe("load_mode_reason", "unknown"),
            gridRelayOn = json.optBooleanSafe("pin34_state"),
            gridRelayReason = json.optStringSafe("pin34_reason", "unknown"),
            loadRelayOn = json.optBooleanSafe("pinLoad_state"),
            loadRelayReason = json.optStringSafe("pinLoad_reason", "unknown"),
            wifiStrength = json.optDoubleSafe("wifi_strength"),
            rtcTime = json.optStringSafe("rtc_time", "--:--:--"),
            rtcDate = json.optStringSafe("rtc_date", "---"),
            bmeAvailable = json.optBooleanSafe("bme_available"),
            bmeTemp = json.optNullableDouble("bme_temp"),
            bmeHum = json.optNullableDouble("bme_hum"),
            bmePress = json.optNullableDouble("bme_press"),
            bmeExtAvailable = json.optBooleanSafe("bme_ext_available"),
            bmeExtTemp = json.optNullableDouble("bme_ext_temp"),
            bmeExtHum = json.optNullableDouble("bme_ext_hum"),
            bmeExtPress = json.optNullableDouble("bme_ext_press"),
        )
    }

    private suspend fun fetchLoadController(config: AppConfig): LoadControllerStatus? = withContext(Dispatchers.IO) {
        val json = fetchStatusJson(config.loadControllerBaseUrl, config.loadControllerPassword) ?: return@withContext null
        LoadControllerStatus(
            boiler1Mode = json.optString("mode", "---"),
            boiler1ModeReason = json.optStringSafe("boiler_mode_reason", "unknown"),
            boiler1On = json.optBooleanSafe("boiler_on"),
            boiler1StateReason = json.optStringSafe("boiler_state_reason", "unknown"),
            pumpMode = json.optString("load_mode", "---"),
            pumpModeReason = json.optStringSafe("pump_mode_reason", "unknown"),
            pumpOn = json.optBooleanSafe("pump_on"),
            pumpStateReason = json.optStringSafe("pump_state_reason", "unknown"),
            boilerLock = json.optStringSafe("boiler_lock", "NONE"),
            pumpLock = json.optStringSafe("pump_lock", "NONE"),
            boilerCurrent = json.optDoubleSafe("boiler_current"),
            boilerPower = json.optDoubleSafe("boiler_power"),
            dailyBoiler = json.optDoubleSafe("daily_boiler"),
            pumpCurrent = json.optDoubleSafe("pump_current"),
            pumpPower = json.optDoubleSafe("pump_power"),
            dailyPump = json.optDoubleSafe("daily_pump"),
            lineVoltage = json.optDoubleSafe("gridVolt"),
            pvW = json.optDoubleSafe("pv"),
            gridW = json.optDoubleSafe("ac_in"),
            loadW = json.optDoubleSafe("ac_out"),
            batterySoc = json.optDoubleSafe("battery"),
            batteryPower = json.optDoubleSafe("battery_power"),
            wifiStrength = json.optDoubleSafe("wifi_strength"),
            rtcTime = json.optStringSafe("rtc_time", "--:--:--"),
            rtcDate = json.optStringSafe("rtc_date", "---"),
            bmeAvailable = json.optBooleanSafe("bme_available"),
            bmeTemp = json.optNullableDouble("bme_temp"),
            bmeHum = json.optNullableDouble("bme_hum"),
            bmePress = json.optNullableDouble("bme_press"),
        )
    }

    private suspend fun fetchGarage(config: AppConfig): GarageStatus? = withContext(Dispatchers.IO) {
        val json = fetchStatusJson(config.garageBaseUrl, config.garagePassword) ?: return@withContext null
        GarageStatus(
            boiler2Mode = json.optString("mode", "---"),
            boiler2ModeReason = json.optStringSafe("boiler_mode_reason", "unknown"),
            boiler2On = json.optBooleanSafe("boiler_on"),
            boiler2StateReason = json.optStringSafe("boiler_state_reason", "unknown"),
            boilerLock = json.optStringSafe("boiler_lock", "NONE"),
            boilerCurrent = json.optDoubleSafe("boiler_current"),
            boilerPower = json.optDoubleSafe("boiler_power"),
            dailyBoiler = json.optDoubleSafe("daily_boiler"),
            gateState = json.optStringSafe("door_state", "unknown"),
            gateReason = json.optStringSafe("door_reason", "unknown"),
            gateOpenPin = json.optInt("door_open_pin", -1),
            gateClosedPin = json.optInt("door_closed_pin", -1),
            lineVoltage = json.optDoubleSafe("gridVolt"),
            pvW = json.optDoubleSafe("pv"),
            gridW = json.optDoubleSafe("ac_in"),
            loadW = json.optDoubleSafe("ac_out"),
            batterySoc = json.optDoubleSafe("battery"),
            batteryPower = json.optDoubleSafe("battery_power"),
            wifiStrength = json.optDoubleSafe("wifi_strength"),
            rtcTime = json.optStringSafe("rtc_time", "--:--:--"),
            rtcDate = json.optStringSafe("rtc_date", "---"),
            bmeAvailable = json.optBooleanSafe("bme_available"),
            bmeTemp = json.optNullableDouble("bme_temp"),
            bmeHum = json.optNullableDouble("bme_hum"),
            bmePress = json.optNullableDouble("bme_press"),
        )
    }

    private fun fetchStatusJson(baseUrlRaw: String, password: String): JSONObject? {
        return fetchJsonWithAuth(
            baseUrlRaw = baseUrlRaw,
            password = password,
            path = "/api/status",
        )
    }

    private fun fetchJsonWithAuth(
        baseUrlRaw: String,
        password: String,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JSONObject? {
        val baseUrl = normalizeBaseUrl(baseUrlRaw)
        if (baseUrl.isEmpty()) return null

        val url = buildUrl(baseUrl, path, query) ?: return null

        // First try without auth: works for open endpoints and active sessions.
        fetchJson(url)?.let { return it }

        if (password.isBlank()) return null

        // Retry with auth because some modules require fresh /api/auth per client session.
        repeat(2) {
            authenticate(baseUrl, password)
            fetchJson(url)?.let { return it }
        }

        return null
    }

    private fun postMode(baseUrlRaw: String, password: String, path: String, mode: String): Boolean {
        return postForm(
            baseUrlRaw = baseUrlRaw,
            password = password,
            path = path,
            formPairs = listOf("mode" to mode),
        )
    }

    private fun postForm(
        baseUrlRaw: String,
        password: String,
        path: String,
        formPairs: List<Pair<String, String>>,
    ): Boolean {
        val baseUrl = normalizeBaseUrl(baseUrlRaw)
        if (baseUrl.isEmpty()) return false

        val url = "$baseUrl$path".toHttpUrlOrNull() ?: return false

        fun postOnce(): Boolean {
            val formBuilder = FormBody.Builder()
            formPairs.forEach { (key, value) -> formBuilder.add(key, value) }
            val req = Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .build()
            return runCatching {
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

        // First attempt without forcing auth: works for active sessions.
        if (postOnce()) return true

        if (password.isBlank()) return false

        repeat(2) {
            authenticate(baseUrl, password)
            if (postOnce()) return true
        }

        return false
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun buildUrl(
        baseUrl: String,
        path: String,
        query: Map<String, String>,
    ): HttpUrl? {
        val urlBuilder = "$baseUrl$path".toHttpUrlOrNull()?.newBuilder() ?: return null
        query.forEach { (key, value) ->
            if (value.isNotBlank()) {
                urlBuilder.addQueryParameter(key, value)
            }
        }
        return urlBuilder.build()
    }

    private fun fetchJson(url: HttpUrl): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .get()
            .build()
        return runCatching {
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                runCatching { JSONObject(body) }.getOrNull()
            }
        }.getOrNull()
    }

    private fun authenticate(baseUrl: String, password: String) {
        if (password.isBlank()) return
        val authReq = Request.Builder()
            .url("$baseUrl/api/auth")
            .post(
                FormBody.Builder()
                    .add("pass", password)
                    .build(),
            )
            .build()
        runCatching {
            client.newCall(authReq).execute().close()
        }
    }
}

private fun JSONObject.optStringSafe(key: String, fallback: String): String {
    val raw = opt(key)
    if (raw == null || raw == JSONObject.NULL) return fallback
    val value = raw.toString().trim()
    return if (value.isEmpty()) fallback else value
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    val raw = opt(key)
    if (raw == null || raw == JSONObject.NULL) return null
    return when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

private fun JSONObject.optDoubleSafe(key: String): Double {
    return optNullableDouble(key) ?: 0.0
}

private fun JSONObject.optBooleanSafe(key: String): Boolean {
    val raw = opt(key) ?: return false
    return when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> {
            val normalized = raw.trim().lowercase()
            normalized == "true" || normalized == "1" || normalized == "on" || normalized == "yes"
        }
        else -> false
    }
}
