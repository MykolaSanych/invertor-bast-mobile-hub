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
    val inverterBatterySoc: Double?,
    val gridRelayOn: Boolean?,
    val gridPresent: Boolean?,
    val gridVoltage: Double?,
    val gridRelayReason: String?,
    val gridMode: String?,
    val gridModeReason: String?,
    val inverterUptimeSec: Long?,
    val inverterRtcTime: String?,
    val loadMode: String?,
    val loadModeReason: String?,
    val loadControllerUptimeSec: Long?,
    val loadControllerRtcTime: String?,
    val boiler1Mode: String?,
    val boiler1ModeReason: String?,
    val pumpMode: String?,
    val pumpModeReason: String?,
    val boiler2Mode: String?,
    val boiler2ModeReason: String?,
    val garageUptimeSec: Long?,
    val garageRtcTime: String?,
    val gateState: String?,
    val gateReason: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pvActive", pvActive)
        put("pvW", pvW)
        put("inverterBatterySoc", inverterBatterySoc)
        put("gridRelayOn", gridRelayOn)
        put("gridPresent", gridPresent)
        put("gridVoltage", gridVoltage)
        put("gridRelayReason", gridRelayReason)
        put("gridMode", gridMode)
        put("gridModeReason", gridModeReason)
        put("inverterUptimeSec", inverterUptimeSec)
        put("inverterRtcTime", inverterRtcTime)
        put("loadMode", loadMode)
        put("loadModeReason", loadModeReason)
        put("loadControllerUptimeSec", loadControllerUptimeSec)
        put("loadControllerRtcTime", loadControllerRtcTime)
        put("boiler1Mode", boiler1Mode)
        put("boiler1ModeReason", boiler1ModeReason)
        put("pumpMode", pumpMode)
        put("pumpModeReason", pumpModeReason)
        put("boiler2Mode", boiler2Mode)
        put("boiler2ModeReason", boiler2ModeReason)
        put("garageUptimeSec", garageUptimeSec)
        put("garageRtcTime", garageRtcTime)
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
                inverterBatterySoc = inverter?.batterySoc,
                gridRelayOn = inverter?.gridRelayOn,
                gridPresent = inverter?.gridPresent,
                gridVoltage = inverter?.lineVoltage,
                gridRelayReason = inverter?.gridRelayReason,
                gridMode = inverter?.mode,
                gridModeReason = inverter?.modeReason,
                inverterUptimeSec = inverter?.uptimeSec,
                inverterRtcTime = inverter?.rtcTime,
                loadMode = inverter?.loadMode,
                loadModeReason = inverter?.loadModeReason,
                loadControllerUptimeSec = load?.uptimeSec,
                loadControllerRtcTime = load?.rtcTime,
                boiler1Mode = load?.boiler1Mode,
                boiler1ModeReason = load?.boiler1ModeReason,
                pumpMode = load?.pumpMode,
                pumpModeReason = load?.pumpModeReason,
                boiler2Mode = garage?.boiler2Mode,
                boiler2ModeReason = garage?.boiler2ModeReason,
                garageUptimeSec = garage?.uptimeSec,
                garageRtcTime = garage?.rtcTime,
                gateState = garage?.gateState,
                gateReason = garage?.gateReason,
            )
        }

        fun fromJson(json: JSONObject): StatusSnapshot = StatusSnapshot(
            pvActive = json.optNullableBoolean("pvActive"),
            pvW = json.optNullableDouble("pvW"),
            inverterBatterySoc = json.optNullableDouble("inverterBatterySoc"),
            gridRelayOn = json.optNullableBoolean("gridRelayOn"),
            gridPresent = json.optNullableBoolean("gridPresent"),
            gridVoltage = json.optNullableDouble("gridVoltage"),
            gridRelayReason = json.optNullableString("gridRelayReason"),
            gridMode = json.optNullableString("gridMode"),
            gridModeReason = json.optNullableString("gridModeReason"),
            inverterUptimeSec = json.optNullableLong("inverterUptimeSec"),
            inverterRtcTime = json.optNullableString("inverterRtcTime"),
            loadMode = json.optNullableString("loadMode"),
            loadModeReason = json.optNullableString("loadModeReason"),
            loadControllerUptimeSec = json.optNullableLong("loadControllerUptimeSec"),
            loadControllerRtcTime = json.optNullableString("loadControllerRtcTime"),
            boiler1Mode = json.optNullableString("boiler1Mode"),
            boiler1ModeReason = json.optNullableString("boiler1ModeReason"),
            pumpMode = json.optNullableString("pumpMode"),
            pumpModeReason = json.optNullableString("pumpModeReason"),
            boiler2Mode = json.optNullableString("boiler2Mode"),
            boiler2ModeReason = json.optNullableString("boiler2ModeReason"),
            garageUptimeSec = json.optNullableLong("garageUptimeSec"),
            garageRtcTime = json.optNullableString("garageRtcTime"),
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
    fun detect(
        context: Context,
        previous: StatusSnapshot,
        current: StatusSnapshot,
        config: AppConfig,
    ): List<LocalEvent> {
        val events = mutableListOf<LocalEvent>()

        appendUnexpectedRebootEvents(events, previous, current, config)

        if (config.inverterEnabled) {
            appendPvGenerationEventWithDebounce(
                context = context,
                events = events,
                current = current,
                emitNotification = config.notifyPvGeneration,
                config = config,
            )
        }

        if (config.inverterEnabled && config.notifyGridRelay) {
            if (previous.gridRelayOn != null && current.gridRelayOn != null && previous.gridRelayOn != current.gridRelayOn) {
                val title = if (current.gridRelayOn) "GRID relay turned ON" else "GRID relay turned OFF"
                events += LocalEvent(
                    title,
                    "Reason: ${current.gridRelayReason.normalizeGridReason(current.gridRelayOn, current.inverterBatterySoc)}",
                )
            }
        }

        if (config.inverterEnabled && config.notifyGridPresence) {
            if (previous.gridPresent != null && current.gridPresent != null && previous.gridPresent != current.gridPresent) {
                val title = if (current.gridPresent) "GRID appeared" else "GRID disappeared"
                val voltage = current.gridVoltage?.toInt() ?: 0
                events += LocalEvent(title, "Line voltage: ${voltage}V")
            }
        }

        if (config.inverterEnabled && config.notifyGridMode) {
            appendModeEvent(
                events = events,
                prevMode = previous.gridMode,
                currMode = current.gridMode,
                title = "GRID mode changed",
                reason = current.gridModeReason.normalizeGridReason(current.gridRelayOn, current.inverterBatterySoc),
                reasonAlreadyNormalized = true,
            )
        }
        if (config.inverterEnabled && config.notifyLoadMode) {
            appendModeEvent(
                events = events,
                prevMode = previous.loadMode,
                currMode = current.loadMode,
                title = "LOAD mode changed",
                reason = current.loadModeReason,
            )
        }
        if (config.loadControllerEnabled && config.notifyBoiler1Mode) {
            appendModeEvent(
                events = events,
                prevMode = previous.boiler1Mode,
                currMode = current.boiler1Mode,
                title = "BOILER1 mode changed",
                reason = current.boiler1ModeReason,
            )
        }
        if (config.loadControllerEnabled && config.notifyPumpMode) {
            appendModeEvent(
                events = events,
                prevMode = previous.pumpMode,
                currMode = current.pumpMode,
                title = "PUMP mode changed",
                reason = current.pumpModeReason,
            )
        }
        if (config.garageEnabled && config.notifyBoiler2Mode) {
            appendModeEvent(
                events = events,
                prevMode = previous.boiler2Mode,
                currMode = current.boiler2Mode,
                title = "BOILER2 mode changed",
                reason = current.boiler2ModeReason,
            )
        }

        if (config.garageEnabled && config.notifyGateState) {
            if (!previous.gateState.isNullOrBlank() &&
                !current.gateState.isNullOrBlank() &&
                previous.gateState != current.gateState
            ) {
                val body = "State: ${previous.gateState} -> ${current.gateState}. Reason: ${current.gateReason.normalizeReason()}"
                events += LocalEvent("Gate state changed", body)
            }
        }

        return events
    }

    private fun appendPvGenerationEventWithDebounce(
        context: Context,
        events: MutableList<LocalEvent>,
        current: StatusSnapshot,
        emitNotification: Boolean,
        config: AppConfig,
    ) {
        val currentPvActive = current.pvActive ?: return
        val debounceMs = pvTransitionDebounceMs(config)
        val store = PvTransitionDebounceStore.load(context)
        val now = System.currentTimeMillis()

        if (store.stableActive == null) {
            PvTransitionDebounceStore.save(
                context,
                store.copy(stableActive = currentPvActive, pendingActive = null, pendingSinceMs = 0L),
            )
            return
        }

        if (currentPvActive == store.stableActive) {
            if (store.pendingActive != null || store.pendingSinceMs != 0L) {
                PvTransitionDebounceStore.save(
                    context,
                    store.copy(pendingActive = null, pendingSinceMs = 0L),
                )
            }
            return
        }

        if (store.pendingActive != currentPvActive) {
            PvTransitionDebounceStore.save(
                context,
                store.copy(pendingActive = currentPvActive, pendingSinceMs = now),
            )
            return
        }

        val pendingSince = store.pendingSinceMs.takeIf { it > 0L } ?: now
        if (now - pendingSince < debounceMs) {
            return
        }

        PvTransitionDebounceStore.save(
            context,
            store.copy(
                stableActive = currentPvActive,
                pendingActive = null,
                pendingSinceMs = 0L,
            ),
        )

        if (!emitNotification) return

        val title = if (currentPvActive) "PV generation started" else "PV generation stopped"
        val reason = "PV=${current.pvW?.toInt() ?: 0}W, threshold ${PV_ACTIVE_THRESHOLD_W.toInt()}W"
        events += LocalEvent(title, "Reason: $reason")
    }

    private fun pvTransitionDebounceMs(config: AppConfig): Long {
        val baseSec = if (config.realtimeMonitorEnabled) {
            config.realtimePollIntervalSec.coerceIn(3, 60)
        } else {
            config.pollIntervalSec.coerceIn(2, 60)
        }
        return (baseSec * 1000L).coerceIn(PV_TRANSITION_DEBOUNCE_MIN_MS, PV_TRANSITION_DEBOUNCE_MAX_MS)
    }

    private fun appendModeEvent(
        events: MutableList<LocalEvent>,
        prevMode: String?,
        currMode: String?,
        title: String,
        reason: String?,
        reasonAlreadyNormalized: Boolean = false,
    ) {
        if (prevMode.isNullOrBlank() || currMode.isNullOrBlank()) return
        if (prevMode == currMode) return
        val reasonText = if (reasonAlreadyNormalized) {
            reason?.trim().takeUnless { it.isNullOrEmpty() } ?: "Manual change"
        } else {
            reason.normalizeReason()
        }
        events += LocalEvent(
            title,
            "$prevMode -> $currMode. Reason: $reasonText",
        )
    }

    private fun appendUnexpectedRebootEvents(
        events: MutableList<LocalEvent>,
        previous: StatusSnapshot,
        current: StatusSnapshot,
        config: AppConfig,
    ) {
        if (config.inverterEnabled &&
            isUnexpectedReboot(previous.inverterUptimeSec, current.inverterUptimeSec, current.inverterRtcTime)
        ) {
            events += buildRebootEvent("Inverter", previous.inverterUptimeSec, current.inverterUptimeSec)
        }
        if (config.loadControllerEnabled &&
            isUnexpectedReboot(previous.loadControllerUptimeSec, current.loadControllerUptimeSec, current.loadControllerRtcTime)
        ) {
            events += buildRebootEvent("Load controller", previous.loadControllerUptimeSec, current.loadControllerUptimeSec)
        }
        if (config.garageEnabled &&
            isUnexpectedReboot(previous.garageUptimeSec, current.garageUptimeSec, current.garageRtcTime)
        ) {
            events += buildRebootEvent("Garage controller", previous.garageUptimeSec, current.garageUptimeSec)
        }
    }

    private fun buildRebootEvent(
        moduleName: String,
        previousUptimeSec: Long?,
        currentUptimeSec: Long?,
    ): LocalEvent {
        val prev = previousUptimeSec ?: 0L
        val curr = currentUptimeSec ?: 0L
        return LocalEvent(
            "$moduleName: power failure suspected",
            "Unexpected reboot detected (uptime reset: ${prev}s -> ${curr}s)",
        )
    }

    private fun isUnexpectedReboot(previousUptimeSec: Long?, currentUptimeSec: Long?, rtcTime: String?): Boolean {
        val prev = previousUptimeSec ?: return false
        val curr = currentUptimeSec ?: return false
        if (prev < 300L) return false
        if (curr > 300L) return false
        if (curr >= prev) return false
        if ((prev - curr) < 120L) return false
        if (isPlannedNightlyRebootWindow(rtcTime)) return false
        return true
    }

    private fun isPlannedNightlyRebootWindow(rtcTime: String?): Boolean {
        val minuteOfDay = parseMinuteOfDay(rtcTime) ?: return false
        val plannedMinute = 1 // controllers reboot daily at 00:01
        return minuteOfDay in 0..(plannedMinute + 4)
    }

    private fun parseMinuteOfDay(rtcTime: String?): Int? {
        val raw = rtcTime?.trim().orEmpty()
        if (!Regex("""^\d{2}:\d{2}(:\d{2})?$""").matches(raw)) return null
        val hh = raw.substring(0, 2).toIntOrNull() ?: return null
        val mm = raw.substring(3, 5).toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return hh * 60 + mm
    }

    private fun String?.normalizeGridReason(gridRelayOn: Boolean?, batterySoc: Double?): String {
        val normalized = this?.trim().orEmpty().lowercase()
        if (normalized.contains("низький заряд") || normalized.contains("акб")) {
            return "низький рівень заряду батареї"
        }
        if (gridRelayOn == true && this.isStartupLikeReason() && (batterySoc ?: 999.0) < 70.0) {
            return "низький рівень заряду батареї"
        }
        return this.normalizeReason()
    }

    private fun String?.isStartupLikeReason(): Boolean {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) return false
        val normalized = value.lowercase()
        return normalized.contains("старт системи") ||
            normalized.contains("system start") ||
            normalized.contains("startup") ||
            normalized.contains("відновлено зі стану при старті")
    }

    private fun String?.normalizeReason(): String {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) return "Manual change"

        val normalized = value.lowercase().replace('_', ' ').replace('-', ' ').trim()
        if (normalized.isEmpty()) return "Manual change"

        if (normalized == "manual" || normalized.contains("manual") || normalized.contains("ruch")) {
            return "Manual change"
        }
        if (normalized == "manual pulse" || normalized == "pulse" || normalized.contains("impuls")) {
            return "Manual pulse"
        }

        val compact = normalized.replace(" ", "")
        val questionCount = compact.count { ch -> ch == '?' }
        val isQuestionNoise = compact.isNotEmpty() && questionCount == compact.length
        if (isQuestionNoise || questionCount >= 3) {
            return "Manual change"
        }

        if (normalized == "unknown" ||
            normalized == "uncnov" ||
            normalized == "---" ||
            normalized == "none" ||
            normalized == "null" ||
            normalized == "n/a" ||
            normalized == "na" ||
            normalized.contains("unknown") ||
            normalized.contains("uncnov")
        ) {
            return "Manual change"
        }

        return value
    }
}

private data class PvTransitionDebounceState(
    val stableActive: Boolean? = null,
    val pendingActive: Boolean? = null,
    val pendingSinceMs: Long = 0L,
)

private object PvTransitionDebounceStore {
    private const val PREFS = "home_hub_bg_worker"
    private const val KEY_STABLE_ACTIVE = "pv_debounce_stable_active"
    private const val KEY_HAS_STABLE = "pv_debounce_has_stable"
    private const val KEY_PENDING_ACTIVE = "pv_debounce_pending_active"
    private const val KEY_HAS_PENDING = "pv_debounce_has_pending"
    private const val KEY_PENDING_SINCE_MS = "pv_debounce_pending_since_ms"

    fun load(context: Context): PvTransitionDebounceState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stable = if (prefs.getBoolean(KEY_HAS_STABLE, false)) {
            prefs.getBoolean(KEY_STABLE_ACTIVE, false)
        } else {
            null
        }
        val pending = if (prefs.getBoolean(KEY_HAS_PENDING, false)) {
            prefs.getBoolean(KEY_PENDING_ACTIVE, false)
        } else {
            null
        }
        return PvTransitionDebounceState(
            stableActive = stable,
            pendingActive = pending,
            pendingSinceMs = prefs.getLong(KEY_PENDING_SINCE_MS, 0L),
        )
    }

    fun save(context: Context, state: PvTransitionDebounceState) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (state.stableActive == null) {
            edit.remove(KEY_HAS_STABLE)
            edit.remove(KEY_STABLE_ACTIVE)
        } else {
            edit.putBoolean(KEY_HAS_STABLE, true)
            edit.putBoolean(KEY_STABLE_ACTIVE, state.stableActive)
        }

        if (state.pendingActive == null) {
            edit.remove(KEY_HAS_PENDING)
            edit.remove(KEY_PENDING_ACTIVE)
        } else {
            edit.putBoolean(KEY_HAS_PENDING, true)
            edit.putBoolean(KEY_PENDING_ACTIVE, state.pendingActive)
        }

        edit.putLong(KEY_PENDING_SINCE_MS, state.pendingSinceMs)
        edit.apply()
    }
}

private const val PV_ACTIVE_THRESHOLD_W = 80.0
private const val PV_TRANSITION_DEBOUNCE_MIN_MS = 3_000L
private const val PV_TRANSITION_DEBOUNCE_MAX_MS = 15_000L

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

private fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val raw = opt(key) ?: return null
    return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}
