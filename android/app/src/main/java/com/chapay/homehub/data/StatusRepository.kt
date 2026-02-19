package com.chapay.homehub.data

import android.content.Context
import android.net.wifi.WifiManager
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
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class StatusRepository(
    private val context: Context? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
    private val appContext = context?.applicationContext

    private val multicastCacheLock = Any()
    private var cachedInverter: InverterStatus? = null
    private var cachedLoadController: LoadControllerStatus? = null
    private var cachedGarage: GarageStatus? = null
    private var cachedInverterAtMs: Long = 0L
    private var cachedLoadAtMs: Long = 0L
    private var cachedGarageAtMs: Long = 0L

    private val multicastGroupAddress = InetAddress.getByName(MULTICAST_GROUP)

    suspend fun fetchUnified(config: AppConfig): UnifiedStatus {
        val multicastStatus = runCatching {
            withContext(Dispatchers.IO) { fetchUnifiedFromMulticast(config) }
        }.getOrNull()

        val hasMulticastData = multicastStatus != null && (
            (config.inverterEnabled && multicastStatus.inverter != null) ||
                (config.loadControllerEnabled && multicastStatus.loadController != null) ||
                (config.garageEnabled && multicastStatus.garage != null)
            )
        if (hasMulticastData) {
            return multicastStatus!!
        }

        return fetchUnifiedHttp(config)
    }

    private suspend fun fetchUnifiedHttp(config: AppConfig): UnifiedStatus = coroutineScope {
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

    private fun fetchUnifiedFromMulticast(config: AppConfig): UnifiedStatus {
        val receivedMulticastPacket = pollMulticastPackets(MULTICAST_LISTEN_WINDOW_MS)

        val now = System.currentTimeMillis()
        val inverter: InverterStatus?
        val loadController: LoadControllerStatus?
        val garage: GarageStatus?
        val newestPacketAt: Long

        synchronized(multicastCacheLock) {
            inverter = if (config.inverterEnabled && now - cachedInverterAtMs <= MULTICAST_CACHE_TTL_MS) {
                cachedInverter
            } else {
                null
            }
            loadController = if (config.loadControllerEnabled && now - cachedLoadAtMs <= MULTICAST_CACHE_TTL_MS) {
                cachedLoadController
            } else {
                null
            }
            garage = if (config.garageEnabled && now - cachedGarageAtMs <= MULTICAST_CACHE_TTL_MS) {
                cachedGarage
            } else {
                null
            }
            newestPacketAt = maxOf(cachedInverterAtMs, cachedLoadAtMs, cachedGarageAtMs)
        }

        return UnifiedStatus(
            inverter = inverter,
            loadController = loadController,
            garage = garage,
            updatedAtMs = if (newestPacketAt > 0L) newestPacketAt else now,
            fromMulticast = receivedMulticastPacket,
        )
    }

    private fun pollMulticastPackets(windowMs: Long): Boolean {
        var receivedPacket = false
        val multicastLock = runCatching {
            val wifiManager = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.createMulticastLock("my_home_multicast_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val socket = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                soTimeout = MULTICAST_READ_TIMEOUT_MS
                bind(InetSocketAddress(MULTICAST_PORT))
                joinGroup(multicastGroupAddress)
            }
        }.getOrNull() ?: return false

        try {
            val endAt = System.currentTimeMillis() + windowMs
            val buffer = ByteArray(MULTICAST_MAX_PACKET_SIZE)
            while (System.currentTimeMillis() < endAt) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }

                val payload = runCatching {
                    String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                }.getOrNull() ?: continue

                val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                receivedPacket = true
                applyMulticastPacket(json)
            }
        } finally {
            runCatching { socket.leaveGroup(multicastGroupAddress) }
            runCatching { socket.close() }
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }

        return receivedPacket
    }

    private fun applyMulticastPacket(json: JSONObject) {
        val now = System.currentTimeMillis()
        when (detectModuleType(json)) {
            MODULE_INVERTER -> {
                val parsed = parseInverterStatus(json)
                synchronized(multicastCacheLock) {
                    cachedInverter = parsed
                    cachedInverterAtMs = now
                }
            }

            MODULE_LOAD -> {
                val parsed = parseLoadControllerStatus(json)
                synchronized(multicastCacheLock) {
                    cachedLoadController = parsed
                    cachedLoadAtMs = now
                }
            }

            MODULE_GARAGE -> {
                val parsed = parseGarageStatus(json)
                synchronized(multicastCacheLock) {
                    cachedGarage = parsed
                    cachedGarageAtMs = now
                }
            }
        }
    }

    private fun detectModuleType(json: JSONObject): String {
        val module = json.optStringSafe("module", "").lowercase()
        if (module == MODULE_INVERTER || module == "invertor" || module == "invertor-bast") {
            return MODULE_INVERTER
        }
        if (module == MODULE_LOAD || module == "load" || module == "loadcontroller") {
            return MODULE_LOAD
        }
        if (module == MODULE_GARAGE) {
            return MODULE_GARAGE
        }

        return when {
            json.has("door_state") || json.has("door_reason") -> MODULE_GARAGE
            json.has("pump_on") || json.has("boiler_lock") && json.has("pump_lock") -> MODULE_LOAD
            else -> MODULE_INVERTER
        }
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
        parseInverterStatus(json)
    }

    private suspend fun fetchLoadController(config: AppConfig): LoadControllerStatus? = withContext(Dispatchers.IO) {
        val json = fetchStatusJson(config.loadControllerBaseUrl, config.loadControllerPassword) ?: return@withContext null
        parseLoadControllerStatus(json)
    }

    private suspend fun fetchGarage(config: AppConfig): GarageStatus? = withContext(Dispatchers.IO) {
        val json = fetchStatusJson(config.garageBaseUrl, config.garagePassword) ?: return@withContext null
        parseGarageStatus(json)
    }

    private fun parseInverterStatus(json: JSONObject): InverterStatus {
        val rtcTime = json.optStringSafeAny("--:--:--", "rtc_time", "time")
        val rtcDate = json.optStringSafeAny("---", "rtc_date", "date")
        val bmeTemp = json.optNullableDouble("bme_temp")
        val bmeHum = json.optNullableDouble("bme_hum")
        val bmePress = json.optNullableDouble("bme_press")
        val bmeExtTemp = json.optNullableDouble("bme_ext_temp")
        val bmeExtHum = json.optNullableDouble("bme_ext_hum")
        val bmeExtPress = json.optNullableDouble("bme_ext_press")

        return InverterStatus(
            pvW = json.optDoubleSafeAny("pv"),
            gridW = json.optDoubleSafeAny("ac_in", "grid"),
            loadW = json.optDoubleSafeAny("ac_out", "load"),
            lineVoltage = json.optDoubleSafeAny("gridVolt"),
            pvVoltage = json.optDoubleSafeAny("pvVolt"),
            batteryVoltage = json.optDoubleSafeAny("batVolt"),
            gridFrequency = json.optDoubleSafeAny("gridFreq"),
            outputVoltage = json.optDoubleSafeAny("outputVolt"),
            outputFrequency = json.optDoubleSafeAny("outputFreq"),
            inverterTemp = json.optDoubleSafeAny("inverterTemp"),
            dailyPv = json.optDoubleSafeAny("dailyPV", "daily_pv"),
            dailyHome = json.optDoubleSafeAny("dailyHome", "daily_home"),
            dailyGrid = json.optDoubleSafeAny("dailyGrid", "daily_grid"),
            lastUpdate = json.optStringSafeAny(rtcTime, "last_update", "time"),
            loadOnLocked = json.optBooleanSafe("load_on_locked"),
            batterySoc = json.optDoubleSafeAny("battery"),
            batteryPower = json.optDoubleSafeAny("battery_power"),
            mode = json.optStringSafeAny("---", "mode"),
            modeReason = json.optStringSafeAny("manual", "mode_reason"),
            loadMode = json.optStringSafeAny("---", "load_mode"),
            loadModeReason = json.optStringSafeAny("manual", "load_mode_reason"),
            gridRelayOn = json.optBooleanSafe("pin34_state"),
            gridRelayReason = json.optStringSafeAny("manual", "pin34_reason"),
            loadRelayOn = json.optBooleanSafe("pinLoad_state"),
            loadRelayReason = json.optStringSafeAny("manual", "pinLoad_reason"),
            wifiStrength = json.optDoubleSafeAny("wifi_strength"),
            rtcTime = rtcTime,
            rtcDate = rtcDate,
            bmeAvailable = json.optBooleanSafe("bme_available") || bmeTemp != null || bmeHum != null || bmePress != null,
            bmeTemp = bmeTemp,
            bmeHum = bmeHum,
            bmePress = bmePress,
            bmeExtAvailable = json.optBooleanSafe("bme_ext_available") || bmeExtTemp != null || bmeExtHum != null || bmeExtPress != null,
            bmeExtTemp = bmeExtTemp,
            bmeExtHum = bmeExtHum,
            bmeExtPress = bmeExtPress,
        )
    }

    private fun parseLoadControllerStatus(json: JSONObject): LoadControllerStatus {
        val rtcTime = json.optStringSafeAny("--:--:--", "rtc_time", "time")
        val rtcDate = json.optStringSafeAny("---", "rtc_date", "date")
        val bmeTemp = json.optNullableDouble("bme_temp")
        val bmeHum = json.optNullableDouble("bme_hum")
        val bmePress = json.optNullableDouble("bme_press")

        return LoadControllerStatus(
            boiler1Mode = json.optStringSafeAny("---", "mode"),
            boiler1ModeReason = json.optStringSafeAny("manual", "boiler_mode_reason"),
            boiler1On = json.optBooleanSafe("boiler_on"),
            boiler1StateReason = json.optStringSafeAny("manual", "boiler_state_reason"),
            pumpMode = json.optStringSafeAny("---", "load_mode"),
            pumpModeReason = json.optStringSafeAny("manual", "pump_mode_reason"),
            pumpOn = json.optBooleanSafe("pump_on"),
            pumpStateReason = json.optStringSafeAny("manual", "pump_state_reason"),
            boilerLock = json.optStringSafeAny("NONE", "boiler_lock"),
            pumpLock = json.optStringSafeAny("NONE", "pump_lock"),
            boilerCurrent = json.optDoubleSafeAny("boiler_current"),
            boilerPower = json.optDoubleSafeAny("boiler_power"),
            dailyBoiler = json.optDoubleSafeAny("daily_boiler"),
            pumpCurrent = json.optDoubleSafeAny("pump_current"),
            pumpPower = json.optDoubleSafeAny("pump_power"),
            dailyPump = json.optDoubleSafeAny("daily_pump"),
            lineVoltage = json.optDoubleSafeAny("gridVolt"),
            pvW = json.optDoubleSafeAny("pv"),
            gridW = json.optDoubleSafeAny("ac_in", "grid"),
            loadW = json.optDoubleSafeAny("ac_out", "load"),
            batterySoc = json.optDoubleSafeAny("battery"),
            batteryPower = json.optDoubleSafeAny("battery_power"),
            wifiStrength = json.optDoubleSafeAny("wifi_strength"),
            rtcTime = rtcTime,
            rtcDate = rtcDate,
            bmeAvailable = json.optBooleanSafe("bme_available") || bmeTemp != null || bmeHum != null || bmePress != null,
            bmeTemp = bmeTemp,
            bmeHum = bmeHum,
            bmePress = bmePress,
        )
    }

    private fun parseGarageStatus(json: JSONObject): GarageStatus {
        val rtcTime = json.optStringSafeAny("--:--:--", "rtc_time", "time")
        val rtcDate = json.optStringSafeAny("---", "rtc_date", "date")
        val bmeTemp = json.optNullableDouble("bme_temp")
        val bmeHum = json.optNullableDouble("bme_hum")
        val bmePress = json.optNullableDouble("bme_press")

        return GarageStatus(
            boiler2Mode = json.optStringSafeAny("---", "mode", "boiler_mode"),
            boiler2ModeReason = json.optStringSafeAny("manual", "boiler_mode_reason", "mode_reason"),
            boiler2On = json.optBooleanSafe("boiler_on"),
            boiler2StateReason = json.optStringSafeAny("manual", "boiler_state_reason"),
            boilerLock = json.optStringSafeAny("NONE", "boiler_lock"),
            boilerCurrent = json.optDoubleSafeAny("boiler_current"),
            boilerPower = json.optDoubleSafeAny("boiler_power"),
            dailyBoiler = json.optDoubleSafeAny("daily_boiler"),
            gateState = json.optStringSafeAny("unknown", "door_state", "door"),
            gateReason = json.optStringSafeAny("manual", "door_reason"),
            gateOpenPin = json.optInt("door_open_pin", -1),
            gateClosedPin = json.optInt("door_closed_pin", -1),
            lineVoltage = json.optDoubleSafeAny("gridVolt"),
            pvW = json.optDoubleSafeAny("pv"),
            gridW = json.optDoubleSafeAny("ac_in", "grid"),
            loadW = json.optDoubleSafeAny("ac_out", "load"),
            batterySoc = json.optDoubleSafeAny("battery"),
            batteryPower = json.optDoubleSafeAny("battery_power"),
            wifiStrength = json.optDoubleSafeAny("wifi_strength"),
            rtcTime = rtcTime,
            rtcDate = rtcDate,
            bmeAvailable = json.optBooleanSafe("bme_available") || bmeTemp != null || bmeHum != null || bmePress != null,
            bmeTemp = bmeTemp,
            bmeHum = bmeHum,
            bmePress = bmePress,
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

private fun JSONObject.optDoubleSafeAny(vararg keys: String): Double {
    keys.forEach { key ->
        val value = optNullableDouble(key)
        if (value != null) return value
    }
    return 0.0
}

private fun JSONObject.optStringSafeAny(fallback: String, vararg keys: String): String {
    keys.forEach { key ->
        val raw = opt(key)
        if (raw == null || raw == JSONObject.NULL) return@forEach
        val value = raw.toString().trim()
        if (value.isNotEmpty()) return value
    }
    return fallback
}

private const val MULTICAST_GROUP = "239.255.0.1"
private const val MULTICAST_PORT = 5005
private const val MULTICAST_LISTEN_WINDOW_MS = 1500L
private const val MULTICAST_READ_TIMEOUT_MS = 300
private const val MULTICAST_MAX_PACKET_SIZE = 4096
private const val MULTICAST_CACHE_TTL_MS = 20_000L

private const val MODULE_INVERTER = "inverter"
private const val MODULE_LOAD = "load_controller"
private const val MODULE_GARAGE = "garage"
