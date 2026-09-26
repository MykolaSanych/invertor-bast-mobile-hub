package com.chapay.homehub.compose.data

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
    // Для повільних аналітичних запитів (місяць/рік): інвертор скидає відповідь лише
    // після сканування файлів.
    private val slowClient = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val appContext = context?.applicationContext
    private val chartCache = ChartCache(appContext)

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
            fetchUnifiedParallel(config)
        }.getOrElse {
            UnifiedStatus(updatedAtMs = System.currentTimeMillis())
        }
    }

    // Три модулі - це три окремі ESP32, тож опитуємо їх паралельно: час циклу
    // дорівнює найповільнішому модулю, а не сумі (раніше - послідовно з паузою
    // 2с між запитами, і при лагах ZeroTier цикл розтягувався на 10+ с).
    // Multicast не слухаємо: жодна з прошивок його вже не шле, тож 2.5с
    // очікування на кожен цикл були марними. Модуль, що не відповів, дає null у
    // своєму полі - залишає такі поля "липкими" вже шар вище (ViewModel).
    private suspend fun fetchUnifiedParallel(config: AppConfig): UnifiedStatus = coroutineScope {
        val inverter = async { if (config.inverterEnabled) runCatching { fetchInverter(config) }.getOrNull() else null }
        val load = async { if (config.loadControllerEnabled) runCatching { fetchLoadController(config) }.getOrNull() else null }
        val garage = async { if (config.garageEnabled) runCatching { fetchGarage(config) }.getOrNull() else null }
        UnifiedStatus(
            inverter = inverter.await(),
            loadController = load.await(),
            garage = garage.await(),
            updatedAtMs = System.currentTimeMillis(),
        )
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

    private fun fetchInverterAnalyticsJsonWithFallback(
        config: AppConfig,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JSONObject? {
        if (!config.inverterEnabled) return null

        val inverterPayload = fetchJsonWithAuth(
            baseUrlRaw = config.inverterBaseUrl,
            password = config.inverterPassword,
            path = path,
            query = query,
        )
        if (inverterPayload != null) {
            return inverterPayload
        }

        val baseCandidates = linkedSetOf(
            normalizeBaseUrl(config.loadControllerBaseUrl),
            "http://192.168.1.3",
        ).filter { it.isNotBlank() }

        val passCandidates = linkedSetOf(
            config.loadControllerPassword.trim(),
            config.inverterPassword.trim(),
        ).filter { it.isNotBlank() }

        baseCandidates.forEach { baseUrl ->
            passCandidates.forEach { password ->
                val payload = fetchJsonWithAuth(
                    baseUrlRaw = baseUrl,
                    password = password,
                    path = path,
                    query = query,
                )
                if (payload != null) {
                    return payload
                }
            }
        }

        return null
    }

    private fun fetchInverterAnalyticsTextWithFallback(
        config: AppConfig,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): String? {
        if (!config.inverterEnabled) return null

        val inverterPayload = fetchTextWithAuth(
            baseUrlRaw = config.inverterBaseUrl,
            password = config.inverterPassword,
            path = path,
            query = query,
        )
        if (!inverterPayload.isNullOrBlank()) {
            return inverterPayload
        }

        val baseCandidates = linkedSetOf(
            normalizeBaseUrl(config.loadControllerBaseUrl),
            "http://192.168.1.3",
        ).filter { it.isNotBlank() }

        val passCandidates = linkedSetOf(
            config.loadControllerPassword.trim(),
            config.inverterPassword.trim(),
        ).filter { it.isNotBlank() }

        baseCandidates.forEach { baseUrl ->
            passCandidates.forEach { password ->
                val payload = fetchTextWithAuth(
                    baseUrlRaw = baseUrl,
                    password = password,
                    path = path,
                    query = query,
                )
                if (!payload.isNullOrBlank()) {
                    return payload
                }
            }
        }

        return null
    }

    private fun looksLikeDailyAnalyticsPayload(payload: JSONObject?): Boolean {
        return payload?.optJSONArray("hours") != null
    }

    private fun todayIsoDate(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR).toString().padStart(4, '0')
        val month = (calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val day = calendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return "$year-$month-$day"
    }

    private fun isDailyPayloadForDate(payload: JSONObject?, expectedDateRaw: String): Boolean {
        if (!looksLikeDailyAnalyticsPayload(payload)) return false

        val expectedDate = expectedDateRaw.trim()
        if (expectedDate.isEmpty()) return true

        val payloadDate = payload?.optString("date")?.trim().orEmpty()
        if (payloadDate.isEmpty()) {
            // Older payloads without date are only acceptable for "today".
            return expectedDate == todayIsoDate()
        }
        return payloadDate == expectedDate
    }

    private fun looksLikeMonthlyAnalyticsPayload(payload: JSONObject?): Boolean {
        return payload?.optJSONArray("days") != null
    }

    private fun looksLikeYearlyAnalyticsPayload(payload: JSONObject?): Boolean {
        return payload?.optJSONArray("months") != null && payload.optJSONArray("pv") != null
    }

    suspend fun fetchInverterDaily(config: AppConfig, date: String): JSONObject? = withContext(Dispatchers.IO) {
        val requestedDate = date.trim()
        if (requestedDate.isEmpty()) return@withContext null
        // Минула доба не змінюється - після першого вдалого завантаження беремо з диска.
        // Сьогодні кеш не використовуємо як "свіже" (ttl 0): він лише показується миттєво
        // до відповіді модуля й виручає при збої зв'язку.
        cachedJson(
            "daily", config.inverterBaseUrl, requestedDate, PeriodKind.DAY,
            currentTtlMs = 0L,
            merge = ::mergeDailyPayloads,
        ) {
            fetchInverterDailyNetwork(config, requestedDate)
        }
    }

    private suspend fun fetchInverterDailyNetwork(config: AppConfig, requestedDate: String): JSONObject? = withContext(Dispatchers.IO) {

        val requested = fetchInverterAnalyticsJsonWithFallback(
            config = config,
            path = "/api/daily",
            query = mapOf("date" to requestedDate),
        )
        if (isDailyPayloadForDate(requested, requestedDate)) return@withContext requested

        // Do not substitute historical dates with current-day payloads.
        if (requestedDate != todayIsoDate()) return@withContext null

        val currentByDaily = fetchInverterAnalyticsJsonWithFallback(
            config = config,
            path = "/api/daily",
        )
        if (isDailyPayloadForDate(currentByDaily, requestedDate)) return@withContext currentByDaily

        val currentByAlias = fetchInverterAnalyticsJsonWithFallback(
            config = config,
            path = "/api/daily/current",
        )
        if (isDailyPayloadForDate(currentByAlias, requestedDate)) return@withContext currentByAlias

        null
    }

    suspend fun fetchInverterDates(config: AppConfig): JSONArray? = withContext(Dispatchers.IO) {
        val raw = fetchInverterAnalyticsTextWithFallback(
            config = config,
            path = "/api/dates",
        ) ?: return@withContext null

        val directArray = runCatching { JSONArray(raw) }.getOrNull()
        if (directArray != null) return@withContext directArray

        val wrappedObject = runCatching { JSONObject(raw) }.getOrNull()
        wrappedObject?.optJSONArray("dates")
    }

    // /api/monthly і /api/yearly інвертор збирає скануванням десятків/сотень файлів
    // на LittleFS - це 8-20 секунд (заміряно), тож звичайний 8-секундний таймаут
    // тут не годиться. Закриті місяці/роки беруться з диска без запитів до модуля;
    // поточний період оновлюється не частіше ніж раз на 2 хв.
    suspend fun fetchInverterMonthly(config: AppConfig, month: String): JSONObject? = withContext(Dispatchers.IO) {
        cachedJson("monthly", config.inverterBaseUrl, month, PeriodKind.MONTH, currentTtlMs = CURRENT_PERIOD_TTL_MS) {
            fetchInverterHeavyJson(config, "/api/monthly", mapOf("month" to month))
        }?.takeIf { it.optString("month") == month }
    }

    suspend fun fetchInverterYearly(config: AppConfig, year: Int): JSONObject? = withContext(Dispatchers.IO) {
        val safeYear = year.coerceIn(2000, 2099)
        cachedJson("yearly", config.inverterBaseUrl, safeYear.toString(), PeriodKind.YEAR, currentTtlMs = CURRENT_PERIOD_TTL_MS) {
            fetchInverterHeavyJson(config, "/api/yearly", mapOf("year" to safeYear.toString()))
        }?.takeIf { it.optInt("current_year", safeYear) == safeYear }
    }

    // ---- Дисковий кеш графіків ----

    private enum class PeriodKind { DAY, MONTH, YEAR }

    // Початок наступного періоду (локальний час) - з нього період вважається завершеним.
    private fun periodEndMs(kind: PeriodKind, key: String): Long? {
        val cal = Calendar.getInstance()
        cal.clear()
        return runCatching {
            when (kind) {
                PeriodKind.DAY -> {
                    cal.set(key.substring(0, 4).toInt(), key.substring(5, 7).toInt() - 1, key.substring(8, 10).toInt())
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
                PeriodKind.MONTH -> {
                    cal.set(key.substring(0, 4).toInt(), key.substring(5, 7).toInt() - 1, 1)
                    cal.add(Calendar.MONTH, 1)
                }
                PeriodKind.YEAR -> {
                    cal.set(key.toInt(), 0, 1)
                    cal.add(Calendar.YEAR, 1)
                }
            }
            cal.timeInMillis
        }.getOrNull()
    }

    // Закритий = збережений щонайменше через 30 хв після кінця періоду (щоб не закешувати
    // неповні дані, зняті одразу після півночі, поки модуль ще дописує файл доби).
    private fun isClosedPeriod(kind: PeriodKind, key: String, savedAtMs: Long): Boolean =
        periodEndMs(kind, key)?.let { savedAtMs >= it + CLOSED_PERIOD_GRACE_MS } ?: false

    /**
     * Кеш-обгортка: закритий період - з диска без мережі; інакше, якщо запис свіжіший за
     * [currentTtlMs], теж з диска; інакше [load], і успіх пишеться на диск. Коли [load] не
     * вдався (лагає зв'язок) - віддаємо застарілий запис, якщо він є, замість порожнього.
     */
    private suspend fun cachedJson(
        kind: String,
        scopeUrl: String,
        key: String,
        period: PeriodKind,
        currentTtlMs: Long,
        merge: ((old: JSONObject, new: JSONObject) -> JSONObject)? = null,
        load: suspend () -> JSONObject?,
    ): JSONObject? {
        val scope = chartCache.scopeOf(scopeUrl)
        val entry = chartCache.get(kind, scope, key)
        if (entry != null) {
            if (isClosedPeriod(period, key, entry.savedAtMs)) return entry.payload
            if (System.currentTimeMillis() - entry.savedAtMs < currentTtlMs) return entry.payload
        }
        val fresh = load()
        if (fresh != null) {
            // Незакритий період: модуль міг "забути" те, що телефон уже бачив (перезавантаження,
            // повна пам'ять) - зберігаємо об'єднання, а не голу відповідь.
            val toStore = if (entry != null && merge != null) runCatching { merge(entry.payload, fresh) }.getOrDefault(fresh) else fresh
            chartCache.put(kind, scope, key, toStore)
            return toStore
        }
        return entry?.payload
    }

    // Добова відповідь інвертора: об'єднання того, що телефон бачив раніше, з новою відповіддю.
    // Доба лише накопичується, тож для кожної години беремо більше значення (pv/home/grid),
    // слоти - об'єднання за хвилиною, а підсумки перераховуємо із годин (не менше за нові).
    private fun mergeDailyPayloads(old: JSONObject, new: JSONObject): JSONObject {
        fun hoursOf(p: JSONObject): Map<Int, JSONObject> {
            val arr = p.optJSONArray("hours") ?: return emptyMap()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.associateBy { it.optInt("hour", -1) }
        }
        val oldHours = hoursOf(old)
        val newHours = hoursOf(new)
        val hourKeys = (oldHours.keys + newHours.keys).filter { it in 0..23 }.sorted()
        val mergedHours = JSONArray()
        var sumPv = 0.0
        var sumHome = 0.0
        var sumGrid = 0.0
        for (h in hourKeys) {
            val a = oldHours[h]
            val b = newHours[h]
            val base = JSONObject((b ?: a)!!.toString())
            for (field in listOf("pv", "home", "grid")) {
                val v = maxOf(a?.optDouble(field, 0.0) ?: 0.0, b?.optDouble(field, 0.0) ?: 0.0)
                base.put(field, v)
            }
            sumPv += base.optDouble("pv", 0.0)
            sumHome += base.optDouble("home", 0.0)
            sumGrid += base.optDouble("grid", 0.0)
            mergedHours.put(base)
        }

        val slotMap = sortedMapOf<Int, JSONObject>()
        old.optJSONArray("slots")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { slotMap[it.optInt("m", -1)] = it } }
        new.optJSONArray("slots")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { slotMap[it.optInt("m", -1)] = it } }
        val mergedSlots = JSONArray()
        slotMap.filterKeys { it >= 0 }.values.forEach { mergedSlots.put(it) }

        val out = JSONObject(new.toString())
        out.put("hours", mergedHours)
        out.put("slots", mergedSlots)
        val newTotals = new.optJSONObject("totals")
        out.put(
            "totals",
            JSONObject()
                .put("dailyPV", maxOf(sumPv, newTotals?.optDouble("dailyPV", 0.0) ?: 0.0))
                .put("dailyHome", maxOf(sumHome, newTotals?.optDouble("dailyHome", 0.0) ?: 0.0))
                .put("dailyGrid", maxOf(sumGrid, newTotals?.optDouble("dailyGrid", 0.0) ?: 0.0)),
        )
        return out
    }

    /** Що вже є на диску для цього періоду (без мережі): екран показує це миттєво, потім оновлює. */
    suspend fun peekInverterDaily(config: AppConfig, date: String): JSONObject? = withContext(Dispatchers.IO) {
        chartCache.get("daily", chartCache.scopeOf(config.inverterBaseUrl), date.trim())?.payload
    }

    suspend fun peekInverterMonthly(config: AppConfig, month: String): JSONObject? = withContext(Dispatchers.IO) {
        chartCache.get("monthly", chartCache.scopeOf(config.inverterBaseUrl), month)?.payload
    }

    suspend fun peekInverterYearly(config: AppConfig, year: Int): JSONObject? = withContext(Dispatchers.IO) {
        chartCache.get("yearly", chartCache.scopeOf(config.inverterBaseUrl), year.toString())?.payload
    }

    suspend fun peekLoadControllerTimeline(config: AppConfig, date: String): JSONObject? = withContext(Dispatchers.IO) {
        chartCache.get("timeline", chartCache.scopeOf(config.loadControllerBaseUrl), date.trim())?.payload
    }

    suspend fun peekLoadControllerLogLines(config: AppConfig, date: String): List<String>? = withContext(Dispatchers.IO) {
        val payload = chartCache.get("log", chartCache.scopeOf(config.loadControllerBaseUrl), date.trim())?.payload
        payload?.optJSONArray("lines")?.let { arr -> List(arr.length()) { arr.optString(it) } }
    }

    // ---- Архів історії на телефоні ----

    /** Підсумки закритих діб з архіву на телефоні: дата -> (сонце, навантаження, мережа) у Вт·год. */
    suspend fun archivedDailyTotals(config: AppConfig): Map<String, Triple<Double, Double, Double>> = withContext(Dispatchers.IO) {
        val scope = chartCache.scopeOf(config.inverterBaseUrl)
        chartCache.listKeys("daily", scope).mapNotNull { date ->
            val entry = chartCache.get("daily", scope, date) ?: return@mapNotNull null
            if (!isClosedPeriod(PeriodKind.DAY, date, entry.savedAtMs)) return@mapNotNull null
            val t = entry.payload.optJSONObject("totals") ?: return@mapNotNull null
            date to Triple(t.optDouble("dailyPV", 0.0), t.optDouble("dailyHome", 0.0), t.optDouble("dailyGrid", 0.0))
        }.toMap()
    }

    class ArchiveSyncResult(val fetchedDays: Int, val ackedUpTo: String?, val note: String)

    // Дати, для яких лог-файлу на load_controller немає: не питаємо повторно за одну сесію.
    private val archiveMissingLogDates = mutableSetOf<String>()

    /**
     * Зберігає на телефоні всі закриті доби, яких там ще нема (інвертор: усі, що він має;
     * load_controller: хронологія й журнал за останні 35 діб), а потім повідомляє інвертору
     * дату, до якої архів повний (POST /api/archive/ack) - лише після цього він може
     * прибрати ці доби зі своєї пам'яті. Запити йдуть послідовно з паузами, щоб не
     * навантажувати ESP32. Викликається з ViewModel періодично, поки застосунок відкрито.
     */
    suspend fun syncHistoryArchive(config: AppConfig): ArchiveSyncResult = withContext(Dispatchers.IO) {
        var fetched = 0
        var ackedUpTo: String? = null
        val notes = mutableListOf<String>()
        val today = todayIsoDate()

        if (config.inverterEnabled) {
            val scope = chartCache.scopeOf(config.inverterBaseUrl)
            val datesRaw = fetchInverterHeavyText(config, "/api/dates")
            val datesArr = datesRaw?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (datesArr == null) {
                notes += "інвертор: список діб недоступний"
            } else {
                val dates = (0 until datesArr.length())
                    .mapNotNull { i -> datesArr.optJSONObject(i)?.optString("date")?.takeIf { it.length == 10 } }
                    .filter { it < today }
                    .sorted()
                var prefixComplete = true
                var lastComplete: String? = null
                for (date in dates) {
                    var entry = chartCache.get("daily", scope, date)
                    if (entry == null || !isClosedPeriod(PeriodKind.DAY, date, entry.savedAtMs)) {
                        fetchInverterDaily(config, date)
                        fetched++
                        delay(700)
                        entry = chartCache.get("daily", scope, date)
                    }
                    val closed = entry != null && isClosedPeriod(PeriodKind.DAY, date, entry.savedAtMs)
                    if (!closed) prefixComplete = false
                    if (prefixComplete) lastComplete = date
                    if (fetched >= 40) break // не більше 40 запитів за прохід
                }
                if (lastComplete != null) {
                    val ok = postForm(
                        baseUrlRaw = config.inverterBaseUrl,
                        password = config.inverterPassword,
                        path = "/api/archive/ack",
                        formPairs = listOf("date" to lastComplete),
                        syncTimeFirst = false,
                    )
                    if (ok) ackedUpTo = lastComplete else notes += "інвертор: підтвердження не прийнято (стара прошивка?)"
                }
            }
        }

        if (config.loadControllerEnabled) {
            val scope = chartCache.scopeOf(config.loadControllerBaseUrl)
            val cal = Calendar.getInstance()
            var requests = 0
            for (back in 1..35) {
                if (requests >= 40) break
                cal.timeInMillis = System.currentTimeMillis()
                cal.add(Calendar.DAY_OF_MONTH, -back)
                val date = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                val tl = chartCache.get("timeline", scope, date)
                if (tl == null || !isClosedPeriod(PeriodKind.DAY, date, tl.savedAtMs)) {
                    fetchLoadControllerTimeline(config, date)
                    requests++
                    delay(400)
                }
                val lg = chartCache.get("log", scope, date)
                if ((lg == null || !isClosedPeriod(PeriodKind.DAY, date, lg.savedAtMs)) && date !in archiveMissingLogDates) {
                    val lines = fetchLoadControllerLogLines(config, date)
                    if (lines == null) archiveMissingLogDates += date
                    requests++
                    delay(400)
                }
            }
        }
        ArchiveSyncResult(fetched, ackedUpTo, notes.joinToString("; "))
    }

    // ---- Режими інвертора (пріоритет виходу / зарядки) ----

    private fun parseInverterSettings(json: JSONObject): InverterSettings? {
        if (!json.optBoolean("ok", false)) return null
        fun intOrNull(key: String): Int? = if (json.isNull(key) || !json.has(key)) null else json.optInt(key, -1).takeIf { it >= 0 }
        val mode = if (json.isNull("mode")) null else json.optString("mode").takeIf { it.isNotBlank() }
        return InverterSettings(mode, intOrNull("output_priority"), intOrNull("charger_priority"))
    }

    /** Читає режим і пріоритети (інвертор сам опитує обладнання: 1-3 с). null - не відповів / стара прошивка. */
    suspend fun fetchInverterSettings(config: AppConfig): InverterSettings? = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext null
        val json = fetchInverterHeavyJson(config, "/api/inverter/settings", emptyMap()) ?: return@withContext null
        parseInverterSettings(json)
    }

    /** Змінює ОДИН пріоритет (output 0..2 або charger 0..3); повертає перечитані налаштування. */
    suspend fun setInverterPriority(config: AppConfig, output: Int?, charger: Int?): InverterSetResult = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext InverterSetResult(false, null, "Інвертор вимкнено в налаштуваннях")
        val baseUrl = normalizeBaseUrl(config.inverterBaseUrl)
        val url = "$baseUrl/api/inverter/settings".toHttpUrlOrNull()
            ?: return@withContext InverterSetResult(false, null, "Невірна адреса інвертора")

        fun once(): Pair<Int, JSONObject?>? = runCatching {
            val form = FormBody.Builder()
            if (output != null) form.add("output", output.toString())
            if (charger != null) form.add("charger", charger.toString())
            val req = Request.Builder().url(url).post(form.build()).build()
            slowClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                resp.code to body?.let { runCatching { JSONObject(it) }.getOrNull() }
            }
        }.getOrNull()

        var result = once()
        if (result == null || result.first == 401 || result.first == 403) {
            authenticate(baseUrl, config.inverterPassword)
            result = once()
        }
        val (code, json) = result ?: return@withContext InverterSetResult(false, null, "Інвертор не відповів")
        when {
            code == 200 && json != null -> InverterSetResult(true, parseInverterSettings(json), null)
            code == 429 -> InverterSetResult(false, null, "Занадто часто: зачекайте кілька секунд")
            code == 404 -> InverterSetResult(false, null, "Прошивка інвертора ще не підтримує цю команду")
            json != null && json.optString("error") == "NAK" -> InverterSetResult(false, null, "Інвертор відхилив команду (NAK)")
            json != null && json.optString("error") == "timeout" -> InverterSetResult(false, null, "Інвертор не відповів на команду")
            else -> InverterSetResult(false, null, "Помилка $code")
        }
    }

    /** (файлів, байтів) у кеші графіків. */
    fun chartCacheStats(): Pair<Int, Long> = chartCache.stats()

    fun clearChartCache(): Int = chartCache.clear()

    private fun fetchInverterHeavyText(config: AppConfig, path: String): String? {
        if (!config.inverterEnabled) return null
        val baseUrl = normalizeBaseUrl(config.inverterBaseUrl)
        if (baseUrl.isEmpty()) return null
        val url = buildUrl(baseUrl, path, emptyMap()) ?: return null

        val first = fetchTextResult(url, slowClient)
        first.text?.let { return it }
        if (first.httpCode != 401 && first.httpCode != 403) return null
        if (config.inverterPassword.isBlank()) return null

        authenticate(baseUrl, config.inverterPassword)
        return fetchTextResult(url, slowClient).text
    }

    private fun fetchInverterHeavyJson(config: AppConfig, path: String, query: Map<String, String>): JSONObject? {
        if (!config.inverterEnabled) return null
        val baseUrl = normalizeBaseUrl(config.inverterBaseUrl)
        if (baseUrl.isEmpty()) return null
        val url = buildUrl(baseUrl, path, query) ?: return null

        val first = fetchJsonResult(url, slowClient)
        first.json?.let { return it }
        if (!first.isUnauthorized || config.inverterPassword.isBlank()) return null

        authenticate(baseUrl, config.inverterPassword)
        return fetchJsonResult(url, slowClient).json
    }

    suspend fun fetchLoadControllerHistory(config: AppConfig, date: String = ""): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext null
        val trimmedDate = date.trim()
        fetchJsonWithAuth(
            baseUrlRaw = config.loadControllerBaseUrl,
            password = config.loadControllerPassword,
            path = "/api/history",
            query = if (trimmedDate.isEmpty()) emptyMap() else mapOf("date" to trimmedDate),
        )
    }

    // Події зміни стану (бойлер/насос/мережа/сонце) з точністю до секунди:
    // {"date","now":сек_доби|null,"e":[[сек,біт,стан],...]}.
    suspend fun fetchLoadControllerTimeline(config: AppConfig, date: String = ""): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext null
        val trimmedDate = date.trim()
        val load: suspend () -> JSONObject? = {
            fetchJsonWithAuth(
                baseUrlRaw = config.loadControllerBaseUrl,
                password = config.loadControllerPassword,
                path = "/api/timeline",
                query = if (trimmedDate.isEmpty()) emptyMap() else mapOf("date" to trimmedDate),
            )
        }
        // load_controller тримає лише 30 діб подій - на телефоні закриті доби лишаються назавжди.
        if (trimmedDate.isEmpty()) load() else cachedJson("timeline", config.loadControllerBaseUrl, trimmedDate, PeriodKind.DAY, currentTtlMs = 0L, load = load)
    }

    suspend fun fetchGarageDoorHistory(config: AppConfig): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext null
        fetchJsonWithAuth(
            baseUrlRaw = config.garageBaseUrl,
            password = config.garagePassword,
            path = "/api/doorhistory",
        )
    }

    suspend fun fetchGarageHistory(config: AppConfig): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext null
        fetchJsonWithAuth(
            baseUrlRaw = config.garageBaseUrl,
            password = config.garagePassword,
            path = "/api/history",
        )
    }

    suspend fun fetchLoadControllerDailyLog(config: AppConfig, date: String): JSONObject? = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext null
        val requestedDate = date.trim()
        if (requestedDate.isEmpty()) return@withContext null

        val raw = fetchTextWithAuth(
            baseUrlRaw = config.loadControllerBaseUrl,
            password = config.loadControllerPassword,
            path = "/api/log",
            query = mapOf("date" to requestedDate),
        ) ?: return@withContext null

        val items = JSONArray()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            val row = runCatching { JSONObject(trimmed) }.getOrNull() ?: return@forEach
            val message = row.optStringSafeAny("", "message", "msg")
            if (message.isEmpty()) return@forEach

            items.put(
                JSONObject().apply {
                    put("date", row.optStringSafeAny(requestedDate, "date"))
                    put("time", row.optStringSafeAny("--:--:--", "timestamp", "time"))
                    put("module", row.optStringSafeAny("unknown", "module"))
                    put("level", row.optStringSafeAny("INFO", "level"))
                    put("message", message)
                },
            )
        }

        JSONObject().apply {
            put("date", requestedDate)
            put("items", items)
            put("count", items.length())
        }
    }

    // fetchLoadControllerDailyLog() вище звертається до /api/log?date=, який
    // прошивка завжди повертає 405 (лише POST реально підтримується), і
    // парсить кожен рядок як JSON - хоча реальний формат простий текст
    // "HH:MM:SS [module] повідомлення" (appendLogLineImpl). Цей метод
    // натомість читає вже перевірений робочий /api/sd/file?name=log_DATE.log
    // (той самий файл, куди й garage пересилає свої логи).
    suspend fun fetchLoadControllerLogLines(config: AppConfig, date: String): List<String>? = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext null
        val requestedDate = date.trim()
        if (requestedDate.isEmpty()) return@withContext null

        val payload = cachedJson("log", config.loadControllerBaseUrl, requestedDate, PeriodKind.DAY, currentTtlMs = 0L) {
            val raw = fetchTextWithAuth(
                baseUrlRaw = config.loadControllerBaseUrl,
                password = config.loadControllerPassword,
                path = "/api/sd/file",
                query = mapOf("name" to "log_$requestedDate.log"),
            )
            raw?.let {
                JSONObject().put(
                    "lines",
                    JSONArray(it.lineSequence().map { line -> line.trim() }.filter { line -> line.isNotEmpty() }.toList()),
                )
            }
        } ?: return@withContext null

        payload.optJSONArray("lines")?.let { arr -> List(arr.length()) { arr.optString(it) } }
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

    suspend fun setInverterGridLogic(
        config: AppConfig,
        pvThresholdW: Double,
        offDelaySec: Int,
        onDelaySec: Int,
        forceGridOnW: Double,
        batteryLowSocPct: Double,
        offMinSocPct: Double,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.inverterBaseUrl,
            password = config.inverterPassword,
            path = "/api/gridlogic",
            formPairs = listOf(
                "pv_threshold_w" to pvThresholdW.toString(),
                "off_delay_sec" to offDelaySec.toString(),
                "on_delay_sec" to onDelaySec.toString(),
                "force_grid_on_w" to forceGridOnW.toString(),
                "battery_low_soc_pct" to batteryLowSocPct.toString(),
                "off_min_soc_pct" to offMinSocPct.toString(),
            ),
        )
    }

    suspend fun setInverterLoadLogic(
        config: AppConfig,
        pvThresholdW: Double,
        shutdownDelaySec: Int,
        overloadPowerW: Double,
        gridRestoreV: Double,
        overloadGridV: Double,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.inverterEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.inverterBaseUrl,
            password = config.inverterPassword,
            path = "/api/loadlogic",
            formPairs = listOf(
                "pv_threshold_w" to pvThresholdW.toString(),
                "shutdown_delay_sec" to shutdownDelaySec.toString(),
                "overload_power_w" to overloadPowerW.toString(),
                "grid_restore_v" to gridRestoreV.toString(),
                "overload_grid_v" to overloadGridV.toString(),
            ),
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

    suspend fun setBoiler1Logic(
        config: AppConfig,
        pvThresholdW: Double,
        shutdownDelaySec: Int,
        batteryShutoffW: Double,
        batteryResumeW: Double,
        peerActiveW: Double,
        gridRestoreV: Double,
        batteryReleaseGridV: Double,
        batteryReleaseSocPct: Double,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.loadControllerBaseUrl,
            password = config.loadControllerPassword,
            path = "/api/boilerlogic",
            formPairs = listOf(
                "pv_threshold_w" to pvThresholdW.toString(),
                "shutdown_delay_sec" to shutdownDelaySec.toString(),
                "battery_shutoff_w" to batteryShutoffW.toString(),
                "battery_resume_w" to batteryResumeW.toString(),
                "peer_active_w" to peerActiveW.toString(),
                "grid_restore_v" to gridRestoreV.toString(),
                "battery_release_grid_v" to batteryReleaseGridV.toString(),
                "battery_release_soc_pct" to batteryReleaseSocPct.toString(),
            ),
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

    suspend fun setPumpLogic(
        config: AppConfig,
        pvThresholdW: Double,
        shutdownDelaySec: Int,
        gridRestoreV: Double,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.loadControllerEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.loadControllerBaseUrl,
            password = config.loadControllerPassword,
            path = "/api/pumplogic",
            formPairs = listOf(
                "pv_threshold_w" to pvThresholdW.toString(),
                "shutdown_delay_sec" to shutdownDelaySec.toString(),
                "grid_restore_v" to gridRestoreV.toString(),
            ),
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

    suspend fun setBoiler2Logic(
        config: AppConfig,
        pvThresholdW: Double,
        shutdownDelaySec: Int,
        batteryShutoffW: Double,
        batteryResumeW: Double,
        peerActiveW: Double,
        gridRestoreV: Double,
        batteryReleaseGridV: Double,
        batteryReleaseSocPct: Double,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.garageEnabled) return@withContext false
        postForm(
            baseUrlRaw = config.garageBaseUrl,
            password = config.garagePassword,
            path = "/api/boilerlogic",
            formPairs = listOf(
                "pv_threshold_w" to pvThresholdW.toString(),
                "shutdown_delay_sec" to shutdownDelaySec.toString(),
                "battery_shutoff_w" to batteryShutoffW.toString(),
                "battery_resume_w" to batteryResumeW.toString(),
                "peer_active_w" to peerActiveW.toString(),
                "grid_restore_v" to gridRestoreV.toString(),
                "battery_release_grid_v" to batteryReleaseGridV.toString(),
                "battery_release_soc_pct" to batteryReleaseSocPct.toString(),
            ),
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
            gridLogic = parseInverterGridLogic(json),
            loadLogic = parseInverterLoadLogic(json),
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
        // Нижче порога потужність насоса - шум датчика струму: прирівнюємо до
        // нуля (разом зі струмом), щоб картка/схема/віджет не показували "привид".
        val pumpPowerRaw = json.optDoubleSafeAny("pump_power")
        val pumpBelowFloor = pumpPowerRaw < PUMP_POWER_NOISE_FLOOR_W

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
            boilerLogic = parseBoilerLogic(json),
            pumpLogic = parsePumpLogic(json),
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
            pumpCurrent = if (pumpBelowFloor) 0.0 else json.optDoubleSafeAny("pump_current"),
            pumpPower = if (pumpBelowFloor) 0.0 else pumpPowerRaw,
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
            boilerLogic = parseBoilerLogic(json),
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
            garageLightAutoOffInSec = json.optInt("garage_light_auto_off_in_sec", -1),
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

    private fun parseInverterGridLogic(json: JSONObject): InverterGridLogicConfig {
        val nested = json.optJSONObject("grid_logic")
        return InverterGridLogicConfig(
            pvThresholdW = nested?.optDoubleSafeAny("pv_threshold_w") ?: 150.0,
            offDelaySec = nested?.optInt("off_delay_sec", 300) ?: 300,
            onDelaySec = nested?.optInt("on_delay_sec", 1800) ?: 1800,
            forceGridOnW = nested?.optDoubleSafeAny("force_grid_on_w") ?: 3000.0,
            batteryLowSocPct = nested?.optDoubleSafeAny("battery_low_soc_pct") ?: 70.0,
            offMinSocPct = nested?.optDoubleSafeAny("off_min_soc_pct") ?: 85.0,
        )
    }

    private fun parseInverterLoadLogic(json: JSONObject): InverterLoadLogicConfig {
        val nested = json.optJSONObject("load_logic")
        return InverterLoadLogicConfig(
            pvThresholdW = nested?.optDoubleSafeAny("pv_threshold_w") ?: 100.0,
            shutdownDelaySec = nested?.optInt("shutdown_delay_sec", 120) ?: 120,
            overloadPowerW = nested?.optDoubleSafeAny("overload_power_w") ?: 4500.0,
            gridRestoreV = nested?.optDoubleSafeAny("grid_restore_v") ?: 180.0,
            overloadGridV = nested?.optDoubleSafeAny("overload_grid_v") ?: 190.0,
        )
    }

    private fun parseBoilerLogic(json: JSONObject): BoilerLogicConfig {
        val nested = json.optJSONObject("boiler_logic")
        return BoilerLogicConfig(
            pvThresholdW = nested?.optDoubleSafeAny("pv_threshold_w") ?: 100.0,
            shutdownDelaySec = nested?.optInt("shutdown_delay_sec", 120) ?: 120,
            batteryShutoffW = nested?.optDoubleSafeAny("battery_shutoff_w") ?: -1800.0,
            batteryResumeW = nested?.optDoubleSafeAny("battery_resume_w") ?: 200.0,
            peerActiveW = nested?.optDoubleSafeAny("peer_active_w") ?: 1000.0,
            gridRestoreV = nested?.optDoubleSafeAny("grid_restore_v") ?: 180.0,
            batteryReleaseGridV = nested?.optDoubleSafeAny("battery_release_grid_v") ?: 190.0,
            batteryReleaseSocPct = nested?.optDoubleSafeAny("battery_release_soc_pct") ?: 90.0,
        )
    }

    private fun parsePumpLogic(json: JSONObject): PumpLogicConfig {
        val nested = json.optJSONObject("pump_logic")
        return PumpLogicConfig(
            pvThresholdW = nested?.optDoubleSafeAny("pv_threshold_w") ?: 100.0,
            shutdownDelaySec = nested?.optInt("shutdown_delay_sec", 120) ?: 120,
            gridRestoreV = nested?.optDoubleSafeAny("grid_restore_v") ?: 180.0,
        )
    }

    private fun fetchStatusJson(baseUrlRaw: String, password: String): JSONObject? {
        val baseUrl = normalizeBaseUrl(baseUrlRaw)
        if (baseUrl.isEmpty()) return null

        val url = buildUrl(baseUrl, "/api/status", emptyMap()) ?: return null
        val firstAttempt = fetchJsonResult(url, statusClient)
        firstAttempt.json?.let { return it }

        // For polling, skip auth retries after timeout/no-response: modules are polled in
        // parallel, so one dead controller only delays its own card, not the others.
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

    private fun fetchTextWithAuth(
        baseUrlRaw: String,
        password: String,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): String? {
        val baseUrl = normalizeBaseUrl(baseUrlRaw)
        if (baseUrl.isEmpty()) return null

        if (path != TIME_SYNC_BROWSER_PATH && shouldSyncControllerTime(baseUrl)) {
            runCatching { syncControllerTimeWithAuth(baseUrl, password) }
        }

        val url = buildUrl(baseUrl, path, query) ?: return null

        val firstAttempt = fetchTextResult(url, client)
        firstAttempt.text?.let { return it }

        if (!firstAttempt.isUnauthorized || password.isBlank()) return null

        repeat(2) {
            authenticate(baseUrl, password)
            val retry = fetchTextResult(url, client)
            retry.text?.let { return it }
            if (!retry.isUnauthorized) return null
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

    private fun fetchTextResult(url: HttpUrl, httpClient: OkHttpClient): TextFetchResult {
        val req = Request.Builder()
            .url(url)
            .get()
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use TextFetchResult(httpCode = response.code)
                }
                val body = response.body?.string() ?: ""
                TextFetchResult(text = body, httpCode = response.code)
            }
        }.getOrElse { TextFetchResult() }
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

private data class TextFetchResult(
    val text: String? = null,
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

private const val PUMP_POWER_NOISE_FLOOR_W = 100.0
// Поточний місяць/рік у кеші вважаємо свіжим 2 хв (модуль рахує їх 8-20 с).
private const val CURRENT_PERIOD_TTL_MS = 2 * 60_000L
// Період "закритий", коли дані зняті не раніше ніж через пів години після його кінця.
private const val CLOSED_PERIOD_GRACE_MS = 30 * 60_000L
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
// 5с: при лагах ZeroTier (втрата пакетів + повтор TCP) 2с було замало, і модулі "блимали".
private const val CONTROLLER_STATUS_TIMEOUT_MS = 5_000L
private const val CONTROLLER_TIME_SYNC_THROTTLE_MS = 60_000L

private const val MODULE_INVERTER = "inverter"
private const val MODULE_LOAD = "load_controller"
private const val MODULE_GARAGE = "garage"
