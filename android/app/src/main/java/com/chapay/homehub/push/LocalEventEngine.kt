package com.chapay.homehub.push

import android.content.Context
import com.chapay.homehub.data.AppConfig
import com.chapay.homehub.data.UnifiedStatus
import org.json.JSONObject

data class LocalEvent(
    val title: String,
    val body: String,
)

data class StatusSnapshot(
    val pvActive: Boolean?,
    val pvW: Double?,
    val gridRelayOn: Boolean?,
    val gridRelayReason: String?,
    val gridMode: String?,
    val gridModeReason: String?,
    val loadMode: String?,
    val loadModeReason: String?,
    val boiler1Mode: String?,
    val boiler1ModeReason: String?,
    val pumpMode: String?,
    val pumpModeReason: String?,
    val boiler2Mode: String?,
    val boiler2ModeReason: String?,
    val gateState: String?,
    val gateReason: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pvActive", pvActive)
        put("pvW", pvW)
        put("gridRelayOn", gridRelayOn)
        put("gridRelayReason", gridRelayReason)
        put("gridMode", gridMode)
        put("gridModeReason", gridModeReason)
        put("loadMode", loadMode)
        put("loadModeReason", loadModeReason)
        put("boiler1Mode", boiler1Mode)
        put("boiler1ModeReason", boiler1ModeReason)
        put("pumpMode", pumpMode)
        put("pumpModeReason", pumpModeReason)
        put("boiler2Mode", boiler2Mode)
        put("boiler2ModeReason", boiler2ModeReason)
        put("gateState", gateState)
        put("gateReason", gateReason)
    }

    companion object {
        fun fromUnified(status: UnifiedStatus): StatusSnapshot {
            val inverter = status.inverter
            val load = status.loadController
            val garage = status.garage
            return StatusSnapshot(
                pvActive = inverter?.pvW?.let { it >= PV_ACTIVE_THRESHOLD_W },
                pvW = inverter?.pvW,
                gridRelayOn = inverter?.gridRelayOn,
                gridRelayReason = inverter?.gridRelayReason,
                gridMode = inverter?.mode,
                gridModeReason = inverter?.modeReason,
                loadMode = inverter?.loadMode,
                loadModeReason = inverter?.loadModeReason,
                boiler1Mode = load?.boiler1Mode,
                boiler1ModeReason = load?.boiler1ModeReason,
                pumpMode = load?.pumpMode,
                pumpModeReason = load?.pumpModeReason,
                boiler2Mode = garage?.boiler2Mode,
                boiler2ModeReason = garage?.boiler2ModeReason,
                gateState = garage?.gateState,
                gateReason = garage?.gateReason,
            )
        }

        fun fromJson(json: JSONObject): StatusSnapshot = StatusSnapshot(
            pvActive = json.optNullableBoolean("pvActive"),
            pvW = json.optNullableDouble("pvW"),
            gridRelayOn = json.optNullableBoolean("gridRelayOn"),
            gridRelayReason = json.optNullableString("gridRelayReason"),
            gridMode = json.optNullableString("gridMode"),
            gridModeReason = json.optNullableString("gridModeReason"),
            loadMode = json.optNullableString("loadMode"),
            loadModeReason = json.optNullableString("loadModeReason"),
            boiler1Mode = json.optNullableString("boiler1Mode"),
            boiler1ModeReason = json.optNullableString("boiler1ModeReason"),
            pumpMode = json.optNullableString("pumpMode"),
            pumpModeReason = json.optNullableString("pumpModeReason"),
            boiler2Mode = json.optNullableString("boiler2Mode"),
            boiler2ModeReason = json.optNullableString("boiler2ModeReason"),
            gateState = json.optNullableString("gateState"),
            gateReason = json.optNullableString("gateReason"),
        )
    }
}

object StatusSnapshotStore {
    private const val PREFS = "home_hub_bg_worker"
    private const val KEY_SNAPSHOT = "last_status_snapshot"

    fun save(context: Context, snapshot: StatusSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, snapshot.toJson().toString())
            .apply()
    }

    fun load(context: Context): StatusSnapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { StatusSnapshot.fromJson(JSONObject(raw)) }.getOrNull()
    }
}

object LocalEventEngine {
    fun detect(previous: StatusSnapshot, current: StatusSnapshot, config: AppConfig): List<LocalEvent> {
        val events = mutableListOf<LocalEvent>()

        if (config.inverterEnabled && config.notifyPvGeneration) {
            if (previous.pvActive != null && current.pvActive != null && previous.pvActive != current.pvActive) {
                val title = if (current.pvActive) "PV генерація з'явилась" else "PV генерація зникла"
                val reason = "PV=${current.pvW?.toInt() ?: 0}W, поріг ${PV_ACTIVE_THRESHOLD_W.toInt()}W"
                events += LocalEvent(title, "Причина: $reason")
            }
        }

        if (config.inverterEnabled && config.notifyGridRelay) {
            if (previous.gridRelayOn != null && current.gridRelayOn != null && previous.gridRelayOn != current.gridRelayOn) {
                val title = if (current.gridRelayOn) "GRID увімкнувся" else "GRID вимкнувся"
                events += LocalEvent(title, "Причина: ${current.gridRelayReason.orUnknown()}")
            }
        }

        if (config.inverterEnabled && config.notifyGridMode) {
            appendModeEvent(
                events = events,
                prevMode = previous.gridMode,
                currMode = current.gridMode,
                title = "Зміна режиму GRID",
                reason = current.gridModeReason,
            )
        }
        if (config.inverterEnabled && config.notifyLoadMode) {
            appendModeEvent(
                events = events,
                prevMode = previous.loadMode,
                currMode = current.loadMode,
                title = "Зміна режиму LOAD",
                reason = current.loadModeReason,
            )
        }
        if (config.loadControllerEnabled && config.notifyBoiler1Mode) {
            appendModeEvent(
                events = events,
                prevMode = previous.boiler1Mode,
                currMode = current.boiler1Mode,
                title = "Зміна режиму BOILER1",
                reason = current.boiler1ModeReason,
            )
        }
        if (config.loadControllerEnabled && config.notifyPumpMode) {
            appendModeEvent(
                events = events,
                prevMode = previous.pumpMode,
                currMode = current.pumpMode,
                title = "Зміна режиму PUMP",
                reason = current.pumpModeReason,
            )
        }
        if (config.garageEnabled && config.notifyBoiler2Mode) {
            appendModeEvent(
                events = events,
                prevMode = previous.boiler2Mode,
                currMode = current.boiler2Mode,
                title = "Зміна режиму BOILER2",
                reason = current.boiler2ModeReason,
            )
        }

        if (config.garageEnabled && config.notifyGateState) {
            if (!previous.gateState.isNullOrBlank() &&
                !current.gateState.isNullOrBlank() &&
                previous.gateState != current.gateState
            ) {
                val body = "Стан: ${previous.gateState} -> ${current.gateState}. Причина: ${current.gateReason.orUnknown()}"
                events += LocalEvent("Зміна стану воріт", body)
            }
        }

        return events
    }

    private fun appendModeEvent(
        events: MutableList<LocalEvent>,
        prevMode: String?,
        currMode: String?,
        title: String,
        reason: String?,
    ) {
        if (prevMode.isNullOrBlank() || currMode.isNullOrBlank()) return
        if (prevMode == currMode) return
        events += LocalEvent(
            title,
            "$prevMode -> $currMode. Причина: ${reason.orUnknown()}",
        )
    }

    private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: "невідомо"
}

private const val PV_ACTIVE_THRESHOLD_W = 80.0

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "").trim()
    return value.takeIf { it.isNotEmpty() }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val raw = opt(key) ?: return null
    return when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

private fun JSONObject.optNullableBoolean(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    val raw = opt(key) ?: return null
    return when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> {
            when (raw.trim().lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> null
            }
        }
        else -> null
    }
}
