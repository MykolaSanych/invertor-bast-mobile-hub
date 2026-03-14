package com.chapay.homehub.push

import android.content.Context
import com.chapay.homehub.data.UnifiedStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class RollingStatusEntry(
    val atMs: Long,
    val inverterOnline: Boolean,
    val loadControllerOnline: Boolean,
    val garageOnline: Boolean,
    val pvW: Double?,
    val gridW: Double?,
    val loadW: Double?,
    val batterySoc: Double?,
    val batteryPower: Double?,
    val inverterLineVoltage: Double?,
    val loadLineVoltage: Double?,
    val garageLineVoltage: Double?,
    val gridRelayOn: Boolean?,
    val gridMode: String?,
    val loadRelayOn: Boolean?,
    val loadMode: String?,
    val boiler1On: Boolean?,
    val boiler1Mode: String?,
    val boiler1PowerW: Double?,
    val boiler1AutoWindowActive: Boolean?,
    val pumpOn: Boolean?,
    val pumpMode: String?,
    val pumpPowerW: Double?,
    val pumpAutoWindowActive: Boolean?,
    val boiler2On: Boolean?,
    val boiler2Mode: String?,
    val boiler2PowerW: Double?,
    val boiler2AutoWindowActive: Boolean?,
    val garageLightOn: Boolean?,
    val gateState: String?,
) {
    fun toStorageJson(): JSONObject = JSONObject().apply {
        put("t", atMs)
        put("io", inverterOnline)
        put("lo", loadControllerOnline)
        put("go", garageOnline)
        put("pv", pvW)
        put("gw", gridW)
        put("lw", loadW)
        put("bs", batterySoc)
        put("bp", batteryPower)
        put("iv", inverterLineVoltage)
        put("lv", loadLineVoltage)
        put("gv", garageLineVoltage)
        put("gr", gridRelayOn)
        put("gm", gridMode)
        put("lr", loadRelayOn)
        put("lm", loadMode)
        put("b1", boiler1On)
        put("b1m", boiler1Mode)
        put("b1p", boiler1PowerW)
        put("b1a", boiler1AutoWindowActive)
        put("p1", pumpOn)
        put("p1m", pumpMode)
        put("p1p", pumpPowerW)
        put("p1a", pumpAutoWindowActive)
        put("b2", boiler2On)
        put("b2m", boiler2Mode)
        put("b2p", boiler2PowerW)
        put("b2a", boiler2AutoWindowActive)
        put("gl", garageLightOn)
        put("gs", gateState)
    }

    fun toBridgeJson(): JSONObject = JSONObject().apply {
        put("atMs", atMs)
        put("inverterOnline", inverterOnline)
        put("loadControllerOnline", loadControllerOnline)
        put("garageOnline", garageOnline)
        put("pvW", pvW)
        put("gridW", gridW)
        put("loadW", loadW)
        put("batterySoc", batterySoc)
        put("batteryPower", batteryPower)
        put("inverterLineVoltage", inverterLineVoltage)
        put("loadLineVoltage", loadLineVoltage)
        put("garageLineVoltage", garageLineVoltage)
        put("gridRelayOn", gridRelayOn)
        put("gridMode", gridMode)
        put("loadRelayOn", loadRelayOn)
        put("loadMode", loadMode)
        put("boiler1On", boiler1On)
        put("boiler1Mode", boiler1Mode)
        put("boiler1PowerW", boiler1PowerW)
        put("boiler1AutoWindowActive", boiler1AutoWindowActive)
        put("pumpOn", pumpOn)
        put("pumpMode", pumpMode)
        put("pumpPowerW", pumpPowerW)
        put("pumpAutoWindowActive", pumpAutoWindowActive)
        put("boiler2On", boiler2On)
        put("boiler2Mode", boiler2Mode)
        put("boiler2PowerW", boiler2PowerW)
        put("boiler2AutoWindowActive", boiler2AutoWindowActive)
        put("garageLightOn", garageLightOn)
        put("gateState", gateState)
    }

    companion object {
        fun fromUnified(status: UnifiedStatus, fallbackAtMs: Long = System.currentTimeMillis()): RollingStatusEntry {
            val inverter = status.inverter
            val load = status.loadController
            val garage = status.garage
            val atMs = sequenceOf(
                inverter?.updatedAtMs,
                load?.updatedAtMs,
                garage?.updatedAtMs,
                status.updatedAtMs,
                fallbackAtMs,
            ).mapNotNull { it }.firstOrNull { it > 0L } ?: fallbackAtMs

            return RollingStatusEntry(
                atMs = atMs,
                inverterOnline = inverter != null,
                loadControllerOnline = load != null,
                garageOnline = garage != null,
                pvW = inverter?.pvW ?: load?.pvW ?: garage?.pvW,
                gridW = inverter?.gridW ?: load?.gridW ?: garage?.gridW,
                loadW = inverter?.loadW ?: load?.loadW ?: garage?.loadW,
                batterySoc = inverter?.batterySoc ?: load?.batterySoc ?: garage?.batterySoc,
                batteryPower = inverter?.batteryPower ?: load?.batteryPower ?: garage?.batteryPower,
                inverterLineVoltage = inverter?.lineVoltage,
                loadLineVoltage = load?.lineVoltage,
                garageLineVoltage = garage?.lineVoltage,
                gridRelayOn = inverter?.gridRelayOn,
                gridMode = inverter?.mode,
                loadRelayOn = inverter?.loadRelayOn,
                loadMode = inverter?.loadMode,
                boiler1On = load?.boiler1On,
                boiler1Mode = load?.boiler1Mode,
                boiler1PowerW = load?.boilerPower,
                boiler1AutoWindowActive = load?.boiler1AutoWindowActive,
                pumpOn = load?.pumpOn,
                pumpMode = load?.pumpMode,
                pumpPowerW = load?.pumpPower,
                pumpAutoWindowActive = load?.pumpAutoWindowActive,
                boiler2On = garage?.boiler2On,
                boiler2Mode = garage?.boiler2Mode,
                boiler2PowerW = garage?.boilerPower,
                boiler2AutoWindowActive = garage?.boiler2AutoWindowActive,
                garageLightOn = garage?.garageLightOn,
                gateState = garage?.gateState,
            )
        }

        fun fromStorageJson(json: JSONObject): RollingStatusEntry? {
            val atMs = json.optLong("t", 0L)
            if (atMs <= 0L) return null
            return RollingStatusEntry(
                atMs = atMs,
                inverterOnline = json.optBoolean("io", false),
                loadControllerOnline = json.optBoolean("lo", false),
                garageOnline = json.optBoolean("go", false),
                pvW = json.optNullableDouble("pv"),
                gridW = json.optNullableDouble("gw"),
                loadW = json.optNullableDouble("lw"),
                batterySoc = json.optNullableDouble("bs"),
                batteryPower = json.optNullableDouble("bp"),
                inverterLineVoltage = json.optNullableDouble("iv"),
                loadLineVoltage = json.optNullableDouble("lv"),
                garageLineVoltage = json.optNullableDouble("gv"),
                gridRelayOn = json.optNullableBoolean("gr"),
                gridMode = json.optNullableString("gm"),
                loadRelayOn = json.optNullableBoolean("lr"),
                loadMode = json.optNullableString("lm"),
                boiler1On = json.optNullableBoolean("b1"),
                boiler1Mode = json.optNullableString("b1m"),
                boiler1PowerW = json.optNullableDouble("b1p"),
                boiler1AutoWindowActive = json.optNullableBoolean("b1a"),
                pumpOn = json.optNullableBoolean("p1"),
                pumpMode = json.optNullableString("p1m"),
                pumpPowerW = json.optNullableDouble("p1p"),
                pumpAutoWindowActive = json.optNullableBoolean("p1a"),
                boiler2On = json.optNullableBoolean("b2"),
                boiler2Mode = json.optNullableString("b2m"),
                boiler2PowerW = json.optNullableDouble("b2p"),
                boiler2AutoWindowActive = json.optNullableBoolean("b2a"),
                garageLightOn = json.optNullableBoolean("gl"),
                gateState = json.optNullableString("gs"),
            )
        }
    }
}

object RollingStatusHistoryStore {
    private const val PREFS = "home_hub_rolling_status_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_WINDOW_MS = 6L * 60L * 60L * 1000L
    private const val MIN_SAMPLE_GAP_MS = 20L * 1000L
    private const val MAX_ENTRIES = 1600
    private val lock = Any()

    fun append(context: Context, status: UnifiedStatus, atMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val entries = loadEntriesUnsafe(context)
            val current = RollingStatusEntry.fromUnified(status, atMs)
            val last = entries.lastOrNull()
            if (last != null && !shouldAppend(last, current)) {
                return
            }

            entries += current
            prune(entries, current.atMs)
            saveEntriesUnsafe(context, entries)
        }
    }

    fun recentEntries(context: Context, hours: Int = 6): List<RollingStatusEntry> {
        synchronized(lock) {
            val all = loadEntriesUnsafe(context)
            val windowMs = hours.coerceIn(1, 6) * 60L * 60L * 1000L
            val nowMs = all.lastOrNull()?.atMs ?: System.currentTimeMillis()
            val fromMs = nowMs - windowMs
            return all.filter { it.atMs >= fromMs }
        }
    }

    fun countTransitions(context: Context, key: String, windowMs: Long): Int {
        synchronized(lock) {
            val all = loadEntriesUnsafe(context)
            if (all.size < 2) return 0
            val nowMs = all.lastOrNull()?.atMs ?: System.currentTimeMillis()
            val fromMs = nowMs - windowMs.coerceAtLeast(60_000L)
            val window = all.filter { it.atMs >= fromMs }
            if (window.size < 2) return 0

            var transitions = 0
            var prevValue: String? = transitionValue(window.first(), key)
            for (index in 1 until window.size) {
                val currentValue = transitionValue(window[index], key)
                if (currentValue != null && prevValue != null && currentValue != prevValue) {
                    transitions += 1
                }
                if (currentValue != null) {
                    prevValue = currentValue
                }
            }
            return transitions
        }
    }

    fun toJson(context: Context, hours: Int = 6): JSONObject {
        val safeHours = hours.coerceIn(1, 6)
        val items = JSONArray()
        recentEntries(context, safeHours).forEach { entry ->
            items.put(entry.toBridgeJson())
        }
        return JSONObject().apply {
            put("hours", safeHours)
            put("count", items.length())
            put("items", items)
        }
    }

    private fun transitionValue(entry: RollingStatusEntry, key: String): String? {
        return when (key) {
            "grid" -> entry.gridRelayOn?.let { if (it) "ON" else "OFF" }
            "load" -> entry.loadRelayOn?.let { if (it) "ON" else "OFF" }
            "boiler1" -> entry.boiler1On?.let { if (it) "ON" else "OFF" }
            "pump" -> entry.pumpOn?.let { if (it) "ON" else "OFF" }
            "boiler2" -> entry.boiler2On?.let { if (it) "ON" else "OFF" }
            "gate" -> entry.gateState?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun shouldAppend(previous: RollingStatusEntry, current: RollingStatusEntry): Boolean {
        val gapMs = current.atMs - previous.atMs
        if (gapMs >= MIN_SAMPLE_GAP_MS) return true

        if (previous.inverterOnline != current.inverterOnline ||
            previous.loadControllerOnline != current.loadControllerOnline ||
            previous.garageOnline != current.garageOnline
        ) {
            return true
        }

        if (previous.gridRelayOn != current.gridRelayOn ||
            previous.loadRelayOn != current.loadRelayOn ||
            previous.boiler1On != current.boiler1On ||
            previous.pumpOn != current.pumpOn ||
            previous.boiler2On != current.boiler2On ||
            previous.garageLightOn != current.garageLightOn
        ) {
            return true
        }

        if (previous.gridMode != current.gridMode ||
            previous.loadMode != current.loadMode ||
            previous.boiler1Mode != current.boiler1Mode ||
            previous.pumpMode != current.pumpMode ||
            previous.boiler2Mode != current.boiler2Mode ||
            previous.gateState != current.gateState
        ) {
            return true
        }

        return hasLargeMetricDelta(previous, current)
    }

    private fun hasLargeMetricDelta(previous: RollingStatusEntry, current: RollingStatusEntry): Boolean {
        if (diff(previous.pvW, current.pvW) >= 180.0) return true
        if (diff(previous.loadW, current.loadW) >= 220.0) return true
        if (diff(previous.gridW, current.gridW) >= 220.0) return true
        if (diff(previous.batteryPower, current.batteryPower) >= 220.0) return true
        if (diff(previous.boiler1PowerW, current.boiler1PowerW) >= 120.0) return true
        if (diff(previous.pumpPowerW, current.pumpPowerW) >= 120.0) return true
        if (diff(previous.boiler2PowerW, current.boiler2PowerW) >= 120.0) return true
        if (diff(previous.batterySoc, current.batterySoc) >= 1.0) return true
        if (diff(previous.inverterLineVoltage, current.inverterLineVoltage) >= 3.0) return true
        if (diff(previous.loadLineVoltage, current.loadLineVoltage) >= 3.0) return true
        if (diff(previous.garageLineVoltage, current.garageLineVoltage) >= 3.0) return true
        return false
    }

    private fun diff(a: Double?, b: Double?): Double {
        val x = a ?: return 0.0
        val y = b ?: return 0.0
        return abs(x - y)
    }

    private fun prune(entries: MutableList<RollingStatusEntry>, nowMs: Long) {
        val fromMs = nowMs - MAX_WINDOW_MS
        while (entries.isNotEmpty() && entries.first().atMs < fromMs) {
            entries.removeAt(0)
        }
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    private fun loadEntriesUnsafe(context: Context): MutableList<RollingStatusEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return mutableListOf()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        val out = mutableListOf<RollingStatusEntry>()
        for (index in 0 until arr.length()) {
            val obj = arr.optJSONObject(index) ?: continue
            RollingStatusEntry.fromStorageJson(obj)?.let(out::add)
        }
        return out
    }

    private fun saveEntriesUnsafe(context: Context, entries: List<RollingStatusEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(entry.toStorageJson())
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, arr.toString())
            .apply()
    }
}

private fun JSONObject.optNullableBoolean(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return optBoolean(key)
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val parsed = optDouble(key, Double.NaN)
    return parsed.takeIf { !it.isNaN() }
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
}
