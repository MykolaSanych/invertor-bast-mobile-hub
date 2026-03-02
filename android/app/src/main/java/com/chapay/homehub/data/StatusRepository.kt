package com.chapay.homehub.data

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class StatusRepository(
    private val context: Context? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
    private val statusClient = client.newBuilder()
        .connectTimeout(CONTROLLER_STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(CONTROLLER_STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(CONTROLLER_STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val appContext = context?.applicationContext

    private val multicastCacheLock = Any()
    private var cachedInverter: InverterStatus? = null
    private var cachedLoadController: LoadControllerStatus? = null
    private var cachedGarage: GarageStatus? = null
    private var cachedInverterAtMs: Long = 0L
    private var cachedLoadAtMs: Long = 0L
    private var cachedGarageAtMs: Long = 0L
    private val multicastStatusRequestLock = Any()
    private var lastMulticastStatusRequestSeenAtMs: Long = 0L
    private var lastMulticastStatusRequestSentAtMs: Long = 0L
    private val controllerTimeSyncLock = Any()
    private val controllerTimeSyncAtMs = mutableMapOf<String, Long>()

    private val multicastGroupAddress = InetAddress.getByName(MULTICAST_GROUP)

    suspend fun fetchUnified(config: AppConfig): UnifiedStatus {
        return runCatching {
            fetchUnifiedHybrid(config)
        }.getOrElse {
            UnifiedStatus(updatedAtMs = System.currentTimeMillis())
        }
    }

    suspend fun fetchUnifiedProgressive(
        config: AppConfig,
        onPartialStatus: (moduleKey: String, status: UnifiedStatus) -> Unit,
    ): UnifiedStatus {
        return runCatching {
            fetchUnifiedHybrid(config, onPartialStatus)
        }.getOrElse {
            UnifiedStatus(updatedAtMs = System.currentTimeMillis())
        }
    }

    suspend fun requestMulticastRefresh(config: AppConfig): UnifiedStatus {
        return runCatching {
            fetchUnifiedFromMulticastDetailed(config, requestRefresh = true).status
        }.getOrElse {
            UnifiedStatus(updatedAtMs = System.currentTimeMillis())
        }
    }

    private suspend fun fetchUnifiedHybrid(
        config: AppConfig,
        onPartialStatus: ((moduleKey: String, status: UnifiedStatus) -> Unit)? = null,
    ): UnifiedStatus {
        val multicastResult = runCatching {
            fetchUnifiedFromMulticastDetailed(config, requestRefresh = true, onPartialStatus = onPartialStatus)
        }.getOrElse {
            MulticastFetchResult(
                status = UnifiedStatus(updatedAtMs = System.currentTimeMillis()),
                receivedModules = emptySet(),
            )
        }

        val needInverter = config.inverterEnabled && multicastResult.status.inverter == null
        val needLoad = config.loadControllerEnabled && multicastResult.status.loadController == null
        val needGarage = config.garageEnabled && multicastResult.status.garage == null
        if (!needInverter && !needLoad && !needGarage) {
            return multicastResult.status
        }

        val onlyModules = mutableSetOf<String>()
        if (needInverter) onlyModules += MODULE_INVERTER
        if (needLoad) onlyModules += MODULE_LOAD
        if (needGarage) onlyModules += MODULE_GARAGE

        return fetchUnifiedHttp(
            config = config,
            onPartialStatus = onPartialStatus,
            initialStatus = multicastResult.status,
            onlyModules = onlyModules,
        )
    }

    private suspend fun fetchUnifiedHttp(
        config: AppConfig,
        onPartialStatus: ((moduleKey: String, status: UnifiedStatus) -> Unit)? = null,
        initialStatus: UnifiedStatus? = null,
        onlyModules: Set<String>? = null,
    ): UnifiedStatus {
        var inverter: InverterStatus? = initialStatus?.inverter
        var loadController: LoadControllerStatus? = initialStatus?.loadController
        var garage: GarageStatus? = initialStatus?.garage
        var hasSentControllerRequest = false

        fun snapshotStatus(): UnifiedStatus {
            return UnifiedStatus(
                inverter = inverter,
                loadController = loadController,
                garage = garage,
                updatedAtMs = System.currentTimeMillis(),
            )
        }

        fun markRequestSent() {
            hasSentControllerRequest = true
        }

        suspend fun delayBeforeNextControllerRequest() {
            if (hasSentControllerRequest) {
                delay(CONTROLLER_REQUEST_STAGGER_MS)
            }
        }

        if (shouldFetchModuleHttp(config.inverterEnabled, onlyModules, MODULE_INVERTER)) {
            inverter = runCatching { fetchInverter(config) }.getOrNull()
            if (inverter != null) {
                onPartialStatus?.invoke(MODULE_INVERTER, snapshotStatus())
            }
            markRequestSent()
        }

        if (shouldFetchModuleHttp(config.loadControllerEnabled, onlyModules, MODULE_LOAD)) {
            delayBeforeNextControllerRequest()
            loadController = runCatching { fetchLoadController(config) }.getOrNull()
            if (loadController != null) {
                onPartialStatus?.invoke(MODULE_LOAD, snapshotStatus())
            }
            markRequestSent()
        }

        if (shouldFetchModuleHttp(config.garageEnabled, onlyModules, MODULE_GARAGE)) {
            delayBeforeNextControllerRequest()
            garage = runCatching { fetchGarage(config) }.getOrNull()
            if (garage != null) {
                onPartialStatus?.invoke(MODULE_GARAGE, snapshotStatus())
            }
        }

        return snapshotStatus()
    }

    private fun shouldFetchModuleHttp(enabled: Boolean, onlyModules: Set<String>?, moduleKey: String): Boolean {
        if (!enabled) return false
        return onlyModules == null || moduleKey in onlyModules
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

    suspend fun fetchGarageDoorHistory(config: AppConfig): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext null
        fetchJsonWithAuth(
            baseUrlRaw = config.garageBaseUrl,
            password = config.garagePassword,
            path = "/api/doorhistory",
        )
    }

    private fun fetchUnifiedFromMulticastDetailed(
        config: AppConfig,
        requestRefresh: Boolean = false,
        onPartialStatus: ((moduleKey: String, status: UnifiedStatus) -> Unit)? = null,
    ): MulticastFetchResult {
        val pollResult = pollMulticastPackets(
            windowMs = MULTICAST_LISTEN_WINDOW_MS,
            sendStatusRequest = requestRefresh,
            onPacketApplied = { moduleKey ->
                onPartialStatus?.invoke(moduleKey, snapshotMulticastUnifiedStatus(config, fromMulticast = true))
            },
        )
        return MulticastFetchResult(
            status = snapshotMulticastUnifiedStatus(config, fromMulticast = pollResult.receivedAny),
            receivedModules = pollResult.receivedModules,
        )
    }

    private fun snapshotMulticastUnifiedStatus(config: AppConfig, fromMulticast: Boolean): UnifiedStatus {
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
            fromMulticast = fromMulticast,
        )
    }

    private fun pollMulticastPackets(
        windowMs: Long,
        sendStatusRequest: Boolean = false,
        onPacketApplied: ((moduleKey: String) -> Unit)? = null,
    ): MulticastPollResult {
        var receivedPacket = false
        val receivedModules = linkedSetOf<String>()
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
        }.getOrNull() ?: return MulticastPollResult(receivedAny = false, receivedModules = emptySet())

        try {
            val endAt = System.currentTimeMillis() + windowMs
            val prelistenUntil = if (sendStatusRequest) {
                minOf(endAt, System.currentTimeMillis() + MULTICAST_STATUS_REQUEST_PRELISTEN_MS)
            } else {
                0L
            }
            var requestSent = false
            var requestSendAttempt = 0
            var fallbackAttemptAtMs = 0L
            val buffer = ByteArray(MULTICAST_MAX_PACKET_SIZE)
            while (System.currentTimeMillis() < endAt) {
                val now = System.currentTimeMillis()
                if (sendStatusRequest && !requestSent) {
                    val shouldTryInitialSend =
                        requestSendAttempt == 0 &&
                            now >= prelistenUntil &&
                            !receivedPacket
                    val shouldTryFallbackSend =
                        requestSendAttempt == 1 &&
                            fallbackAttemptAtMs > 0L &&
                            now >= fallbackAttemptAtMs &&
                            !receivedPacket

                    if (shouldTryInitialSend || shouldTryFallbackSend) {
                        requestSendAttempt += 1
                        requestSent = maybeSendMulticastStatusRequest(socket)
                        if (!requestSent && requestSendAttempt == 1) {
                            fallbackAttemptAtMs = now + MULTICAST_STATUS_REQUEST_FALLBACK_MS
                        }
                    }
                }

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

                if (payload.trim() == MULTICAST_STATUS_REQUEST_PAYLOAD) {
                    markMulticastStatusRequestSeen()
                    continue
                }

                val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                if (isMulticastStatusRequestPacket(json)) {
                    markMulticastStatusRequestSeen()
                    continue
                }
                val moduleKey = applyMulticastPacket(json) ?: continue
                receivedPacket = true
                receivedModules += moduleKey
                onPacketApplied?.invoke(moduleKey)
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

        return MulticastPollResult(
            receivedAny = receivedPacket,
            receivedModules = receivedModules,
        )
    }

    private fun maybeSendMulticastStatusRequest(socket: MulticastSocket): Boolean {
        if (!shouldSendMulticastStatusRequestNow()) {
            return false
        }

        val jitterMs = if (MULTICAST_STATUS_REQUEST_JITTER_MAX_MS > 0L) {
            Random.nextLong(
                MULTICAST_STATUS_REQUEST_JITTER_MIN_MS,
                MULTICAST_STATUS_REQUEST_JITTER_MAX_MS + 1L,
            )
        } else {
            0L
        }
        if (jitterMs > 0L) {
            runCatching { Thread.sleep(jitterMs) }
        }

        if (!shouldSendMulticastStatusRequestNow()) {
            return false
        }

        sendMulticastStatusRequest(socket)
        markMulticastStatusRequestSent()
        return true
    }

    private fun isMulticastStatusRequestPacket(json: JSONObject): Boolean {
        val kind = json.optStringSafe("kind", "").lowercase()
        return kind == MULTICAST_STATUS_REQUEST_KIND_V2
    }

    private fun shouldSendMulticastStatusRequestNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(multicastStatusRequestLock) {
            val lastActivityMs = maxOf(lastMulticastStatusRequestSeenAtMs, lastMulticastStatusRequestSentAtMs)
            return nowMs - lastActivityMs >= MULTICAST_STATUS_REQUEST_SUPPRESS_MS
        }
    }

    private fun markMulticastStatusRequestSeen(nowMs: Long = System.currentTimeMillis()) {
        synchronized(multicastStatusRequestLock) {
            if (nowMs > lastMulticastStatusRequestSeenAtMs) {
                lastMulticastStatusRequestSeenAtMs = nowMs
            }
        }
    }

    private fun markMulticastStatusRequestSent(nowMs: Long = System.currentTimeMillis()) {
        synchronized(multicastStatusRequestLock) {
            if (nowMs > lastMulticastStatusRequestSentAtMs) {
                lastMulticastStatusRequestSentAtMs = nowMs
            }
            if (nowMs > lastMulticastStatusRequestSeenAtMs) {
                lastMulticastStatusRequestSeenAtMs = nowMs
            }
        }
    }

    private fun sendMulticastStatusRequest(socket: MulticastSocket) {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = (calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val day = calendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val hour = calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val minute = calendar.get(Calendar.MINUTE).toString().padStart(2, '0')
        val second = calendar.get(Calendar.SECOND).toString().padStart(2, '0')
        val payloadJson = JSONObject().apply {
            put("kind", MULTICAST_STATUS_REQUEST_KIND_V2)
            put("v", 2)
            put("action", "status")
            put("request_id", "android-${System.currentTimeMillis()}")
            put("date", "$year-$month-$day")
            put("time", "$hour:$minute:$second")
        }.toString()
        val payload = payloadJson.toByteArray(StandardCharsets.UTF_8)
        runCatching {
            val packet = DatagramPacket(payload, payload.size, multicastGroupAddress, MULTICAST_PORT)
            socket.send(packet)
        }
    }

    private fun applyMulticastPacket(json: JSONObject): String? {
        val now = System.currentTimeMillis()
        return when (detectModuleType(json)) {
            MODULE_INVERTER -> {
                val parsed = parseInverterStatus(json, now)
                synchronized(multicastCacheLock) {
                    cachedInverter = parsed
                    cachedInverterAtMs = now
                }
                MODULE_INVERTER
            }

            MODULE_LOAD -> {
                val parsed = parseLoadControllerStatus(json, now)
                synchronized(multicastCacheLock) {
                    cachedLoadController = parsed
                    cachedLoadAtMs = now
                }
                MODULE_LOAD
            }

            MODULE_GARAGE -> {
                val parsed = parseGarageStatus(json, now)
                synchronized(multicastCacheLock) {
                    cachedGarage = parsed
                    cachedGarageAtMs = now
                }
                MODULE_GARAGE
            }

            else -> null
        }
    }

    private fun detectModuleType(json: JSONObject): String {
        val kind = json.optStringSafe("kind", "").lowercase()
        if (kind == MULTICAST_STATUS_REQUEST_KIND_V2) {
            return ""
        }

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

    suspend fun setBoiler1AutoWindow(
        config: AppConfig,
        enabled: Boolean,
        start: String,
        end: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.loadControllerBaseUrl,
            password = config.loadControllerPassword,
            path = "/api/boilerautowindow",
            formPairs = listOf(
                "enabled" to if (enabled) "1" else "0",
                "start" to start,
                "end" to end,
            ),
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

    suspend fun setPumpAutoWindow(
        config: AppConfig,
        enabled: Boolean,
        start: String,
        end: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.loadControllerBaseUrl,
            password = config.loadControllerPassword,
            path = "/api/pumpautowindow",
            formPairs = listOf(
                "enabled" to if (enabled) "1" else "0",
                "start" to start,
                "end" to end,
            ),
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

    suspend fun setBoiler2AutoWindow(
        config: AppConfig,
        enabled: Boolean,
        start: String,
        end: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.garageBaseUrl,
            password = config.garagePassword,
            path = "/api/boilerautowindow",
            formPairs = listOf(
                "enabled" to if (enabled) "1" else "0",
                "start" to start,
                "end" to end,
            ),
        )
    }

    suspend fun triggerGate(
        config: AppConfig,
        source: String = "mobile_hub",
        reason: String = "mobile hub",
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.garageBaseUrl,
            password = config.garagePassword,
            path = "/api/door",
            formPairs = listOf(
                "action" to "pulse",
                "source" to source,
                "reason" to reason,
            ),
        )
    }

    suspend fun toggleGarageLight(
        config: AppConfig,
        source: String = "mobile_hub",
        reason: String = "mobile hub",
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.garageBaseUrl,
            password = config.garagePassword,
            path = "/api/light",
            formPairs = listOf(
                "state" to "TOGGLE",
                "source" to source,
                "reason" to reason,
            ),
        )
    }

    private suspend fun fetchInverter(config: AppConfig): InverterStatus? = withContext(Dispatchers.IO) {
        val json = fetchStatusJson(config.inverterBaseUrl, config.inverterPassword) ?: return@withContext null
        parseInverterStatus(json, System.currentTimeMillis())
    }

    private suspend fun fetchLoadController(config: AppConfig): LoadControllerStatus? = withContext(Dispatchers.IO) {
        val json = fetchStatusJson(config.loadControllerBaseUrl, config.loadControllerPassword) ?: return@withContext null
        parseLoadControllerStatus(json, System.currentTimeMillis())
    }

    private suspend fun fetchGarage(config: AppConfig): GarageStatus? = withContext(Dispatchers.IO) {
        val json = fetchStatusJson(config.garageBaseUrl, config.garagePassword) ?: return@withContext null
        parseGarageStatus(json, System.currentTimeMillis())
    }

    private fun parseInverterStatus(json: JSONObject, observedAtMs: Long): InverterStatus {
        val rtcTime = json.optStringSafeAny("--:--:--", "rtc_time", "time")
        val rtcDate = json.optStringSafeAny("---", "rtc_date", "date")
        val bmeTemp = json.optNullableDouble("bme_temp")
        val bmeHum = json.optNullableDouble("bme_hum")
        val bmePress = json.optNullableDouble("bme_press")
        val bmeExtTemp = json.optNullableDouble("bme_ext_temp")
        val bmeExtHum = json.optNullableDouble("bme_ext_hum")
        val bmeExtPress = json.optNullableDouble("bme_ext_press")
        val gridPresent = if (json.has("grid_present")) {
            json.optBooleanSafe("grid_present")
        } else {
            json.optDoubleSafeAny("gridVolt") >= 170.0
        }

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
            gridPresent = gridPresent,
            gridRelayReason = json.optStringSafeAny("manual", "pin34_reason"),
            loadRelayOn = json.optBooleanSafe("pinLoad_state"),
            loadRelayReason = json.optStringSafeAny("manual", "pinLoad_reason"),
            wifiStrength = json.optDoubleSafeAny("wifi_strength"),
            uptimeSec = json.optLongSafeAny("uptime"),
            rtcTime = rtcTime,
            rtcDate = rtcDate,
            updatedAtMs = observedAtMs,
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

    private fun parseLoadControllerStatus(json: JSONObject, observedAtMs: Long): LoadControllerStatus {
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
            boiler1AutoWindowEnabled = json.optBooleanSafe("boiler_auto_window_enabled"),
            boiler1AutoWindowStart = json.optStringSafeAny("00:00", "boiler_auto_window_start"),
            boiler1AutoWindowEnd = json.optStringSafeAny("00:00", "boiler_auto_window_end"),
            boiler1AutoWindowActive = if (json.has("boiler_auto_window_active")) {
                json.optBooleanSafe("boiler_auto_window_active")
            } else {
                true
            },
            pumpAutoWindowEnabled = json.optBooleanSafe("pump_auto_window_enabled"),
            pumpAutoWindowStart = json.optStringSafeAny("00:00", "pump_auto_window_start"),
            pumpAutoWindowEnd = json.optStringSafeAny("00:00", "pump_auto_window_end"),
            pumpAutoWindowActive = if (json.has("pump_auto_window_active")) {
                json.optBooleanSafe("pump_auto_window_active")
            } else {
                true
            },
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
            uptimeSec = json.optLongSafeAny("uptime"),
            rtcTime = rtcTime,
            rtcDate = rtcDate,
            updatedAtMs = observedAtMs,
            bmeAvailable = json.optBooleanSafe("bme_available") || bmeTemp != null || bmeHum != null || bmePress != null,
            bmeTemp = bmeTemp,
            bmeHum = bmeHum,
            bmePress = bmePress,
        )
    }

    private fun parseGarageStatus(json: JSONObject, observedAtMs: Long): GarageStatus {
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
            boiler2AutoWindowEnabled = json.optBooleanSafe("boiler_auto_window_enabled"),
            boiler2AutoWindowStart = json.optStringSafeAny("00:00", "boiler_auto_window_start"),
            boiler2AutoWindowEnd = json.optStringSafeAny("00:00", "boiler_auto_window_end"),
            boiler2AutoWindowActive = if (json.has("boiler_auto_window_active")) {
                json.optBooleanSafe("boiler_auto_window_active")
            } else {
                true
            },
            boilerCurrent = json.optDoubleSafeAny("boiler_current"),
            boilerPower = json.optDoubleSafeAny("boiler_power"),
            dailyBoiler = json.optDoubleSafeAny("daily_boiler"),
            gateState = json.optStringSafeAny("unknown", "door_state", "door"),
            gateReason = json.optStringSafeAny("manual", "door_reason"),
            gateSource = json.optStringSafeAny("remote", "door_source", "door_initiator"),
            gateOpenPin = json.optInt("door_open_pin", -1),
            gateClosedPin = json.optInt("door_closed_pin", -1),
            garageLightOn = json.optBooleanSafe("garage_light_on"),
            garageLightReason = json.optStringSafeAny("manual", "garage_light_reason"),
            lineVoltage = json.optDoubleSafeAny("gridVolt"),
            pvW = json.optDoubleSafeAny("pv"),
            gridW = json.optDoubleSafeAny("ac_in", "grid"),
            loadW = json.optDoubleSafeAny("ac_out", "load"),
            batterySoc = json.optDoubleSafeAny("battery"),
            batteryPower = json.optDoubleSafeAny("battery_power"),
            wifiStrength = json.optDoubleSafeAny("wifi_strength"),
            uptimeSec = json.optLongSafeAny("uptime"),
            rtcTime = rtcTime,
            rtcDate = rtcDate,
            updatedAtMs = observedAtMs,
            bmeAvailable = json.optBooleanSafe("bme_available") || bmeTemp != null || bmeHum != null || bmePress != null,
            bmeTemp = bmeTemp,
            bmeHum = bmeHum,
            bmePress = bmePress,
        )
    }

    private fun fetchStatusJson(baseUrlRaw: String, password: String): JSONObject? {
        val baseUrl = normalizeBaseUrl(baseUrlRaw)
        if (baseUrl.isEmpty()) return null

        val url = buildUrl(baseUrl, "/api/status", emptyMap()) ?: return null
        val firstAttempt = fetchJsonResult(url, statusClient)
        firstAttempt.json?.let { return it }

        // For polling, skip auth retries after timeout/no-response so one dead controller only
        // blocks this module for ~2s and the app can move to the next one.
        if (!firstAttempt.isUnauthorized || password.isBlank()) return null

        authenticate(baseUrl, password, statusClient)
        return fetchJsonResult(url, statusClient).json
    }

    private fun fetchJsonWithAuth(
        baseUrlRaw: String,
        password: String,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JSONObject? {
        val baseUrl = normalizeBaseUrl(baseUrlRaw)
        if (baseUrl.isEmpty()) return null

        if (path != TIME_SYNC_BROWSER_PATH && shouldSyncControllerTime(baseUrl)) {
            runCatching { syncControllerTimeWithAuth(baseUrl, password) }
        }

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
        syncTimeFirst: Boolean = true,
    ): Boolean {
        val baseUrl = normalizeBaseUrl(baseUrlRaw)
        if (baseUrl.isEmpty()) return false

        if (syncTimeFirst && path != TIME_SYNC_BROWSER_PATH && shouldSyncControllerTime(baseUrl)) {
            runCatching { syncControllerTimeWithAuth(baseUrl, password) }
        }

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

    private fun syncControllerTimeWithAuth(baseUrl: String, password: String) {
        val calendar = Calendar.getInstance()
        val formPairs = listOf(
            "year" to calendar.get(Calendar.YEAR).toString(),
            "month" to (calendar.get(Calendar.MONTH) + 1).toString(),
            "day" to calendar.get(Calendar.DAY_OF_MONTH).toString(),
            "hour" to calendar.get(Calendar.HOUR_OF_DAY).toString(),
            "minute" to calendar.get(Calendar.MINUTE).toString(),
            "second" to calendar.get(Calendar.SECOND).toString(),
        )

        postForm(
            baseUrlRaw = baseUrl,
            password = password,
            path = TIME_SYNC_BROWSER_PATH,
            formPairs = formPairs,
            syncTimeFirst = false,
        )
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

    private fun shouldSyncControllerTime(baseUrl: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(controllerTimeSyncLock) {
            val lastSyncedAt = controllerTimeSyncAtMs[baseUrl] ?: 0L
            if (now - lastSyncedAt < CONTROLLER_TIME_SYNC_THROTTLE_MS) {
                return false
            }
            controllerTimeSyncAtMs[baseUrl] = now
            return true
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
        return fetchJsonResult(url, client).json
    }

    private fun fetchJsonResult(url: HttpUrl, httpClient: OkHttpClient): JsonFetchResult {
        val req = Request.Builder()
            .url(url)
            .get()
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use JsonFetchResult(httpCode = response.code)
                }
                val body = response.body?.string()
                val json = body?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                JsonFetchResult(json = json, httpCode = response.code)
            }
        }.getOrElse { JsonFetchResult() }
    }

    private fun authenticate(baseUrl: String, password: String, httpClient: OkHttpClient = client) {
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
            httpClient.newCall(authReq).execute().close()
        }
    }
}

private data class JsonFetchResult(
    val json: JSONObject? = null,
    val httpCode: Int? = null,
) {
    val isUnauthorized: Boolean
        get() = httpCode == 401 || httpCode == 403
}

private data class MulticastPollResult(
    val receivedAny: Boolean,
    val receivedModules: Set<String>,
)

private data class MulticastFetchResult(
    val status: UnifiedStatus,
    val receivedModules: Set<String>,
)

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

private fun JSONObject.optLongSafeAny(vararg keys: String): Long {
    keys.forEach { key ->
        if (!has(key) || isNull(key)) return@forEach
        val raw = opt(key) ?: return@forEach
        val value = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        if (value != null) return value
    }
    return 0L
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

private const val TIME_SYNC_BROWSER_PATH = "/api/time/syncbrowser"
private const val MULTICAST_STATUS_REQUEST_KIND_V2 = "homehub_status_request"

private const val MULTICAST_GROUP = "239.255.0.1"
private const val MULTICAST_PORT = 5005
private const val MULTICAST_STATUS_REQUEST_PAYLOAD = "HOMEHUB_STATUS_REQUEST_V1"
private const val MULTICAST_LISTEN_WINDOW_MS = 2500L
private const val MULTICAST_READ_TIMEOUT_MS = 300
private const val MULTICAST_MAX_PACKET_SIZE = 4096
private const val MULTICAST_CACHE_TTL_MS = 20_000L
private const val MULTICAST_STATUS_REQUEST_PRELISTEN_MS = 350L
private const val MULTICAST_STATUS_REQUEST_SUPPRESS_MS = 1_500L
private const val MULTICAST_STATUS_REQUEST_FALLBACK_MS = 900L
private const val MULTICAST_STATUS_REQUEST_JITTER_MIN_MS = 50L
private const val MULTICAST_STATUS_REQUEST_JITTER_MAX_MS = 180L
private const val CONTROLLER_REQUEST_STAGGER_MS = 2_000L
private const val CONTROLLER_STATUS_TIMEOUT_MS = 2_000L
private const val CONTROLLER_TIME_SYNC_THROTTLE_MS = 60_000L

private const val MODULE_INVERTER = "inverter"
private const val MODULE_LOAD = "load_controller"
private const val MODULE_GARAGE = "garage"
