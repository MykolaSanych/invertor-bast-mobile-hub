package com.chapay.homehub.push

import android.content.Context
import com.chapay.homehub.data.AppConfig
import com.chapay.homehub.data.UnifiedStatus
import org.json.JSONObject

data class LocalEvent(
    val title: String,
    val body: String,
    val severity: String = "info",
    val kind: String = "event",
    val module: String = "hub",
    val sendNotification: Boolean = true,
    val atMs: Long = System.currentTimeMillis(),
)

data class StatusSnapshot(
    val inverterOnline: Boolean,
    val loadControllerOnline: Boolean,
    val garageOnline: Boolean,
    val pvActive: Boolean?,
    val pvW: Double?,
    val loadW: Double?,
    val inverterBatterySoc: Double?,
    val gridRelayOn: Boolean?,
    val gridPresent: Boolean?,
    val gridVoltage: Double?,
    val gridRelayReason: String?,
    val gridMode: String?,
    val gridModeReason: String?,
    val inverterLoadOverloadW: Double?,
    val inverterUptimeSec: Long?,
    val inverterRtcTime: String?,
    val loadMode: String?,
    val loadModeReason: String?,
    val boiler1PowerW: Double?,
    val pumpPowerW: Double?,
    val loadControllerUptimeSec: Long?,
    val loadControllerRtcTime: String?,
    val boiler1Mode: String?,
    val boiler1ModeReason: String?,
    val pumpMode: String?,
    val pumpModeReason: String?,
    val boiler2PowerW: Double?,
    val boiler2Mode: String?,
    val boiler2ModeReason: String?,
    val garageUptimeSec: Long?,
    val garageRtcTime: String?,
    val gateState: String?,
    val gateReason: String?,
    val gateSource: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("inverterOnline", inverterOnline)
        put("loadControllerOnline", loadControllerOnline)
        put("garageOnline", garageOnline)
        put("pvActive", pvActive)
        put("pvW", pvW)
        put("loadW", loadW)
        put("inverterBatterySoc", inverterBatterySoc)
        put("gridRelayOn", gridRelayOn)
        put("gridPresent", gridPresent)
        put("gridVoltage", gridVoltage)
        put("gridRelayReason", gridRelayReason)
        put("gridMode", gridMode)
        put("gridModeReason", gridModeReason)
        put("inverterLoadOverloadW", inverterLoadOverloadW)
        put("inverterUptimeSec", inverterUptimeSec)
        put("inverterRtcTime", inverterRtcTime)
        put("loadMode", loadMode)
        put("loadModeReason", loadModeReason)
        put("boiler1PowerW", boiler1PowerW)
        put("pumpPowerW", pumpPowerW)
        put("loadControllerUptimeSec", loadControllerUptimeSec)
        put("loadControllerRtcTime", loadControllerRtcTime)
        put("boiler1Mode", boiler1Mode)
        put("boiler1ModeReason", boiler1ModeReason)
        put("pumpMode", pumpMode)
        put("pumpModeReason", pumpModeReason)
        put("boiler2PowerW", boiler2PowerW)
        put("boiler2Mode", boiler2Mode)
        put("boiler2ModeReason", boiler2ModeReason)
        put("garageUptimeSec", garageUptimeSec)
        put("garageRtcTime", garageRtcTime)
        put("gateState", gateState)
        put("gateReason", gateReason)
        put("gateSource", gateSource)
    }

    companion object {
        fun fromUnified(status: UnifiedStatus): StatusSnapshot {
            val inverter = status.inverter
            val load = status.loadController
            val garage = status.garage
            return StatusSnapshot(
                inverterOnline = inverter != null,
                loadControllerOnline = load != null,
                garageOnline = garage != null,
                pvActive = inverter?.pvW?.let { it >= PV_ACTIVE_THRESHOLD_W },
                pvW = inverter?.pvW,
                loadW = inverter?.loadW,
                inverterBatterySoc = inverter?.batterySoc,
                gridRelayOn = inverter?.gridRelayOn,
                gridPresent = inverter?.gridPresent,
                gridVoltage = inverter?.lineVoltage,
                gridRelayReason = inverter?.gridRelayReason,
                gridMode = inverter?.mode,
                gridModeReason = inverter?.modeReason,
                inverterLoadOverloadW = inverter?.loadLogic?.overloadPowerW,
                inverterUptimeSec = inverter?.uptimeSec,
                inverterRtcTime = inverter?.rtcTime,
                loadMode = inverter?.loadMode,
                loadModeReason = inverter?.loadModeReason,
                boiler1PowerW = load?.boilerPower,
                pumpPowerW = load?.pumpPower,
                loadControllerUptimeSec = load?.uptimeSec,
                loadControllerRtcTime = load?.rtcTime,
                boiler1Mode = load?.boiler1Mode,
                boiler1ModeReason = load?.boiler1ModeReason,
                pumpMode = load?.pumpMode,
                pumpModeReason = load?.pumpModeReason,
                boiler2PowerW = garage?.boilerPower,
                boiler2Mode = garage?.boiler2Mode,
                boiler2ModeReason = garage?.boiler2ModeReason,
                garageUptimeSec = garage?.uptimeSec,
                garageRtcTime = garage?.rtcTime,
                gateState = garage?.gateState,
                gateReason = garage?.gateReason,
                gateSource = garage?.gateSource,
            )
        }

        fun fromJson(json: JSONObject): StatusSnapshot = StatusSnapshot(
            inverterOnline = json.optBoolean("inverterOnline", false),
            loadControllerOnline = json.optBoolean("loadControllerOnline", false),
            garageOnline = json.optBoolean("garageOnline", false),
            pvActive = json.optNullableBoolean("pvActive"),
            pvW = json.optNullableDouble("pvW"),
            loadW = json.optNullableDouble("loadW"),
            inverterBatterySoc = json.optNullableDouble("inverterBatterySoc"),
            gridRelayOn = json.optNullableBoolean("gridRelayOn"),
            gridPresent = json.optNullableBoolean("gridPresent"),
            gridVoltage = json.optNullableDouble("gridVoltage"),
            gridRelayReason = json.optNullableString("gridRelayReason"),
            gridMode = json.optNullableString("gridMode"),
            gridModeReason = json.optNullableString("gridModeReason"),
            inverterLoadOverloadW = json.optNullableDouble("inverterLoadOverloadW"),
            inverterUptimeSec = json.optNullableLong("inverterUptimeSec"),
            inverterRtcTime = json.optNullableString("inverterRtcTime"),
            loadMode = json.optNullableString("loadMode"),
            loadModeReason = json.optNullableString("loadModeReason"),
            boiler1PowerW = json.optNullableDouble("boiler1PowerW"),
            pumpPowerW = json.optNullableDouble("pumpPowerW"),
            loadControllerUptimeSec = json.optNullableLong("loadControllerUptimeSec"),
            loadControllerRtcTime = json.optNullableString("loadControllerRtcTime"),
            boiler1Mode = json.optNullableString("boiler1Mode"),
            boiler1ModeReason = json.optNullableString("boiler1ModeReason"),
            pumpMode = json.optNullableString("pumpMode"),
            pumpModeReason = json.optNullableString("pumpModeReason"),
            boiler2PowerW = json.optNullableDouble("boiler2PowerW"),
            boiler2Mode = json.optNullableString("boiler2Mode"),
            boiler2ModeReason = json.optNullableString("boiler2ModeReason"),
            garageUptimeSec = json.optNullableLong("garageUptimeSec"),
            garageRtcTime = json.optNullableString("garageRtcTime"),
            gateState = json.optNullableString("gateState"),
            gateReason = json.optNullableString("gateReason"),
            gateSource = json.optNullableString("gateSource"),
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
                sendNotification = config.notifyPvGeneration,
                config = config,
            )
        }

        if (config.inverterEnabled) {
            if (previous.gridRelayOn != null && current.gridRelayOn != null && previous.gridRelayOn != current.gridRelayOn) {
                val title = if (current.gridRelayOn) "GRID relay turned ON" else "GRID relay turned OFF"
                val metricContext = buildMetricContext(current, "inverter")
                events += LocalEvent(
                    title,
                    buildString {
                        append(
                            "Reason: ${
                                current.gridRelayReason.normalizeGridReason(current.gridRelayOn, current.inverterBatterySoc)
                            }",
                        )
                        if (metricContext.isNotEmpty()) {
                            append(". ")
                            append(metricContext)
                        }
                    },
                    kind = "grid_relay",
                    module = "inverter",
                    sendNotification = config.notifyGridRelay,
                )
            }
        }

        if (config.inverterEnabled) {
            if (previous.gridPresent != null && current.gridPresent != null && previous.gridPresent != current.gridPresent) {
                val title = if (current.gridPresent) "GRID appeared" else "GRID disappeared"
                val voltage = current.gridVoltage?.toInt() ?: 0
                events += LocalEvent(
                    title,
                    "Line voltage: ${voltage}V",
                    kind = "grid_presence",
                    module = "inverter",
                    sendNotification = config.notifyGridPresence,
                )
            }
        }

        if (config.inverterEnabled) {
            appendModeEvent(
                events = events,
                prevMode = previous.gridMode,
                currMode = current.gridMode,
                title = "GRID mode changed",
                reason = current.gridModeReason.normalizeGridReason(current.gridRelayOn, current.inverterBatterySoc),
                current = current,
                module = "inverter",
                kind = "grid_mode",
                sendNotification = config.notifyGridMode,
                reasonAlreadyNormalized = true,
            )
        }
        if (config.inverterEnabled) {
            appendModeEvent(
                events = events,
                prevMode = previous.loadMode,
                currMode = current.loadMode,
                title = "LOAD mode changed",
                reason = current.loadModeReason,
                current = current,
                module = "inverter",
                kind = "load_mode",
                sendNotification = config.notifyLoadMode,
            )
        }
        if (config.loadControllerEnabled) {
            appendModeEvent(
                events = events,
                prevMode = previous.boiler1Mode,
                currMode = current.boiler1Mode,
                title = "BOILER1 mode changed",
                reason = current.boiler1ModeReason,
                current = current,
                module = "load_controller",
                kind = "boiler1_mode",
                sendNotification = config.notifyBoiler1Mode,
            )
        }
        if (config.loadControllerEnabled) {
            appendModeEvent(
                events = events,
                prevMode = previous.pumpMode,
                currMode = current.pumpMode,
                title = "PUMP mode changed",
                reason = current.pumpModeReason,
                current = current,
                module = "load_controller",
                kind = "pump_mode",
                sendNotification = config.notifyPumpMode,
            )
        }
        if (config.garageEnabled) {
            appendModeEvent(
                events = events,
                prevMode = previous.boiler2Mode,
                currMode = current.boiler2Mode,
                title = "BOILER2 mode changed",
                reason = current.boiler2ModeReason,
                current = current,
                module = "garage",
                kind = "boiler2_mode",
                sendNotification = config.notifyBoiler2Mode,
            )
        }

        if (config.garageEnabled) {
            val prevGateState = previous.gateState.normalizeGateState()
            val currGateState = current.gateState.normalizeGateState()
            if (prevGateState != null && currGateState != null && prevGateState != currGateState) {
                val source = normalizeGateSource(current.gateSource, current.gateReason)
                val body = "State: $prevGateState -> $currGateState. Source: $source. Reason: ${current.gateReason.normalizeReason()}"
                events += LocalEvent(
                    "Gate state changed",
                    body,
                    kind = "gate_state",
                    module = "garage",
                    sendNotification = config.notifyGateState,
                )
            }
        }

        appendPowerAlertEvents(events, previous, current, config)
        appendLogicInstabilityEvents(context, events, current, config)

        return events
    }

    private fun appendPvGenerationEventWithDebounce(
        context: Context,
        events: MutableList<LocalEvent>,
        current: StatusSnapshot,
        sendNotification: Boolean,
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

        val title = if (currentPvActive) "PV generation started" else "PV generation stopped"
        val reason = "PV=${current.pvW?.toInt() ?: 0}W, threshold ${PV_ACTIVE_THRESHOLD_W.toInt()}W"
        events += LocalEvent(
            title,
            "Reason: $reason",
            kind = "pv_generation",
            module = "inverter",
            sendNotification = sendNotification,
        )
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
        current: StatusSnapshot,
        module: String,
        kind: String,
        sendNotification: Boolean,
        reasonAlreadyNormalized: Boolean = false,
    ) {
        if (prevMode.isNullOrBlank() || currMode.isNullOrBlank()) return
        if (prevMode == currMode) return
        val reasonText = if (reasonAlreadyNormalized) {
            reason?.trim().takeUnless { it.isNullOrEmpty() } ?: "Manual change"
        } else {
            reason.normalizeReason()
        }
        val metricContext = buildMetricContext(current, module)
        events += LocalEvent(
            title,
            buildString {
                append("$prevMode -> $currMode. Reason: $reasonText")
                if (metricContext.isNotEmpty()) {
                    append(". ")
                    append(metricContext)
                }
            },
            kind = kind,
            module = module,
            sendNotification = sendNotification,
        )
    }

    private fun appendPowerAlertEvents(
        events: MutableList<LocalEvent>,
        previous: StatusSnapshot,
        current: StatusSnapshot,
        config: AppConfig,
    ) {
        if (!config.inverterEnabled || !current.inverterOnline) return
        val threshold = current.inverterLoadOverloadW ?: 4500.0
        val previousLoad = previous.loadW ?: return
        val currentLoad = current.loadW ?: return
        if (previousLoad <= threshold && currentLoad > threshold) {
            val metricContext = buildMetricContext(current, "inverter")
            events += LocalEvent(
                title = "Load overload threshold exceeded",
                body = buildString {
                    append("Load ${currentLoad.toInt()}W > ${threshold.toInt()}W")
                    if (metricContext.isNotEmpty()) {
                        append(". ")
                        append(metricContext)
                    }
                },
                severity = "alert",
                kind = "power_overload",
                module = "inverter",
                sendNotification = config.notifyPowerOverload,
            )
        }
    }

    private fun appendLogicInstabilityEvents(
        context: Context,
        events: MutableList<LocalEvent>,
        current: StatusSnapshot,
        config: AppConfig,
    ) {
        val windowMs = 30L * 60L * 1000L
        val cooldownMs = 20L * 60L * 1000L
        appendLogicInstabilityEvent(
            context = context,
            events = events,
            enabled = config.inverterEnabled && current.inverterOnline,
            logicKey = "grid",
            title = "GRID logic unstable",
            module = "inverter",
            sendNotification = config.notifyLogicUnstable,
            windowMs = windowMs,
            cooldownMs = cooldownMs,
        )
        appendLogicInstabilityEvent(
            context = context,
            events = events,
            enabled = config.inverterEnabled && current.inverterOnline,
            logicKey = "load",
            title = "LOAD logic unstable",
            module = "inverter",
            sendNotification = config.notifyLogicUnstable,
            windowMs = windowMs,
            cooldownMs = cooldownMs,
        )
        appendLogicInstabilityEvent(
            context = context,
            events = events,
            enabled = config.loadControllerEnabled && current.loadControllerOnline,
            logicKey = "boiler1",
            title = "BOILER1 logic unstable",
            module = "load_controller",
            sendNotification = config.notifyLogicUnstable,
            windowMs = windowMs,
            cooldownMs = cooldownMs,
        )
        appendLogicInstabilityEvent(
            context = context,
            events = events,
            enabled = config.loadControllerEnabled && current.loadControllerOnline,
            logicKey = "pump",
            title = "PUMP logic unstable",
            module = "load_controller",
            sendNotification = config.notifyLogicUnstable,
            windowMs = windowMs,
            cooldownMs = cooldownMs,
        )
        appendLogicInstabilityEvent(
            context = context,
            events = events,
            enabled = config.garageEnabled && current.garageOnline,
            logicKey = "boiler2",
            title = "BOILER2 logic unstable",
            module = "garage",
            sendNotification = config.notifyLogicUnstable,
            windowMs = windowMs,
            cooldownMs = cooldownMs,
        )
    }

    private fun appendLogicInstabilityEvent(
        context: Context,
        events: MutableList<LocalEvent>,
        enabled: Boolean,
        logicKey: String,
        title: String,
        module: String,
        sendNotification: Boolean,
        windowMs: Long,
        cooldownMs: Long,
    ) {
        if (!enabled) return
        val transitions = RollingStatusHistoryStore.countTransitions(context, logicKey, windowMs)
        if (transitions < 4) return
        val throttleKey = "logic_unstable:$logicKey"
        if (!AlertThrottleStore.shouldEmit(context, throttleKey, cooldownMs)) return
        events += LocalEvent(
            title = title,
            body = "$transitions state changes detected in the last ${(windowMs / 60_000L).toInt()} min.",
            severity = "alert",
            kind = "logic_unstable",
            module = module,
            sendNotification = sendNotification,
        )
    }

    private fun buildMetricContext(current: StatusSnapshot, module: String): String {
        return when (module) {
            "inverter" -> listOfNotNull(
                current.pvW?.let { "PV=${it.toInt()}W" },
                current.loadW?.let { "LOAD=${it.toInt()}W" },
                current.inverterBatterySoc?.let { "BAT=${it.toInt()}%" },
                current.gridVoltage?.let { "GRID=${it.toInt()}V" },
            ).joinToString(", ")
            "load_controller" -> listOfNotNull(
                current.boiler1PowerW?.let { "Boiler=${it.toInt()}W" },
                current.pumpPowerW?.let { "Pump=${it.toInt()}W" },
                current.pvW?.let { "PV=${it.toInt()}W" },
            ).joinToString(", ")
            "garage" -> listOfNotNull(
                current.boiler2PowerW?.let { "Boiler=${it.toInt()}W" },
                current.pvW?.let { "PV=${it.toInt()}W" },
            ).joinToString(", ")
            else -> ""
        }
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
            events += buildRebootEvent(
                moduleName = "Inverter",
                module = "inverter",
                previousUptimeSec = previous.inverterUptimeSec,
                currentUptimeSec = current.inverterUptimeSec,
                sendNotification = config.notifyModuleOffline,
            )
        }
        if (config.loadControllerEnabled &&
            isUnexpectedReboot(previous.loadControllerUptimeSec, current.loadControllerUptimeSec, current.loadControllerRtcTime)
        ) {
            events += buildRebootEvent(
                moduleName = "Load controller",
                module = "load_controller",
                previousUptimeSec = previous.loadControllerUptimeSec,
                currentUptimeSec = current.loadControllerUptimeSec,
                sendNotification = config.notifyModuleOffline,
            )
        }
        if (config.garageEnabled &&
            isUnexpectedReboot(previous.garageUptimeSec, current.garageUptimeSec, current.garageRtcTime)
        ) {
            events += buildRebootEvent(
                moduleName = "Garage controller",
                module = "garage",
                previousUptimeSec = previous.garageUptimeSec,
                currentUptimeSec = current.garageUptimeSec,
                sendNotification = config.notifyModuleOffline,
            )
        }
    }

    private fun buildRebootEvent(
        moduleName: String,
        module: String,
        previousUptimeSec: Long?,
        currentUptimeSec: Long?,
        sendNotification: Boolean,
    ): LocalEvent {
        val prev = previousUptimeSec ?: 0L
        val curr = currentUptimeSec ?: 0L
        return LocalEvent(
            "$moduleName: power failure suspected",
            "Unexpected reboot detected (uptime reset: ${prev}s -> ${curr}s)",
            severity = "alert",
            kind = "unexpected_reboot",
            module = module,
            sendNotification = sendNotification,
        )
    }

    private fun isUnexpectedReboot(previousUptimeSec: Long?, currentUptimeSec: Long?, rtcTime: String?): Boolean {
        val prev = previousUptimeSec ?: return false
        val curr = currentUptimeSec ?: return false
        if (curr <= 0L) return false
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

    private fun String?.normalizeGateState(): String? {
        val normalized = this?.trim().orEmpty().lowercase()
        if (normalized.isEmpty()) return null
        return when {
            normalized.contains("closed") ||
                normalized.contains("close") ||
                normalized.contains("зачинен") ||
                normalized.contains("закрит") -> "closed"
            normalized.contains("open") ||
                normalized.contains("відчин") ||
                normalized.contains("відкрит") -> "open"
            else -> null
        }
    }

    private fun normalizeGateSource(source: String?, reason: String?): String {
        val normalized = source
            ?.trim()
            .orEmpty()
            .lowercase()
            .replace('-', '_')
            .replace(' ', '_')

        if (normalized == "button" || normalized.contains("button")) return "button"
        if (normalized == "web" ||
            normalized == "garage_web" ||
            normalized == "mobile_hub" ||
            normalized == "android_widget" ||
            normalized.contains("web") ||
            normalized.contains("hub") ||
            normalized.contains("widget")
        ) {
            return "web"
        }
        if (normalized.isNotEmpty()) return "remote"

        val reasonNorm = reason?.trim().orEmpty().lowercase()
        if (reasonNorm.contains("button")) return "button"
        if (reasonNorm.contains("web") || reasonNorm.contains("hub") || reasonNorm.contains("widget")) return "web"
        return "remote"
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

private object AlertThrottleStore {
    private const val PREFS = "home_hub_alert_throttle"

    fun shouldEmit(context: Context, key: String, cooldownMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousAtMs = prefs.getLong(key, 0L)
        if (previousAtMs > 0L && nowMs - previousAtMs < cooldownMs) {
            return false
        }
        prefs.edit().putLong(key, nowMs).apply()
        return true
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
