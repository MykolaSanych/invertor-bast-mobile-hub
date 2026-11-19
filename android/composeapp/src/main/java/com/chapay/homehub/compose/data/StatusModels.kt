package com.chapay.homehub.compose.data

data class InverterLoadLogicConfig(
    val pvThresholdW: Double,
    val shutdownDelaySec: Int,
    val gridRestoreV: Double,
    val overloadOffW: Double,
    val overloadResumeW: Double,
    val batteryDischargeOffW: Double,
    val batteryDischargeResumeW: Double,
) : java.io.Serializable

data class BoilerLogicConfig(
    val pvThresholdW: Double,
    val shutdownDelaySec: Int,
    val peerActiveW: Double,
    val gridRestoreV: Double,
    val overloadOffW: Double,
    val overloadResumeW: Double,
    val batteryDischargeOffW: Double,
    val batteryDischargeResumeW: Double,
) : java.io.Serializable

data class PumpLogicConfig(
    val pvThresholdW: Double,
    val shutdownDelaySec: Int,
    val gridRestoreV: Double,
) : java.io.Serializable

data class InverterStatus(
    val pvW: Double,
    val gridW: Double,
    val loadW: Double,
    val lineVoltage: Double,
    val pvVoltage: Double,
    val batteryVoltage: Double,
    val gridFrequency: Double,
    val outputVoltage: Double,
    val outputFrequency: Double,
    val inverterTemp: Double,
    val dailyPv: Double,
    val dailyHome: Double,
    val dailyGrid: Double,
    val lastUpdate: String,
    // NONE|ON|OFF - фіксація довгим натисканням (раніше було loadOnLocked: Boolean,
    // фіксація лише на ON; тепер симетрично з бойлерами/насосом).
    val loadLock: String,
    val loadOverloadActive: Boolean,
    val loadBatteryProtectActive: Boolean,
    val batterySoc: Double,
    val batteryPower: Double,
    val mode: String,
    val modeReason: String,
    val loadMode: String,
    val loadModeReason: String,
    val loadLogic: InverterLoadLogicConfig?,
    val gridRelayOn: Boolean,
    val gridPresent: Boolean,
    val gridRelayReason: String,
    val loadRelayOn: Boolean,
    val loadRelayReason: String,
    // Авто-перемикання пріоритету входу (SBU/сонце) - п.2 переробки.
    val outputPriorityAuto: Boolean,
    val outputPriorityAutoEtaSec: Int?,
    val wifiStrength: Double,
    val uptimeSec: Long,
    val rtcTime: String,
    val rtcDate: String,
    val updatedAtMs: Long,
    val bmeAvailable: Boolean,
    val bmeTemp: Double?,
    val bmeHum: Double?,
    val bmePress: Double?,
    val bmeExtAvailable: Boolean,
    val bmeExtTemp: Double?,
    val bmeExtHum: Double?,
    val bmeExtPress: Double?,
) : java.io.Serializable

data class LoadControllerStatus(
    val boiler1Mode: String,
    val boiler1ModeReason: String,
    val boiler1On: Boolean,
    val boiler1StateReason: String,
    val pumpMode: String,
    val pumpModeReason: String,
    val pumpOn: Boolean,
    val pumpStateReason: String,
    val boilerLock: String,
    val pumpLock: String,
    val overloadActive: Boolean,
    val batteryProtectActive: Boolean,
    val boilerRestartEtaSec: Int?,
    val boilerLogic: BoilerLogicConfig?,
    val pumpLogic: PumpLogicConfig?,
    val boiler1AutoWindowEnabled: Boolean,
    val boiler1AutoWindowStart: String,
    val boiler1AutoWindowEnd: String,
    val boiler1AutoWindowActive: Boolean,
    val pumpAutoWindowEnabled: Boolean,
    val pumpAutoWindowStart: String,
    val pumpAutoWindowEnd: String,
    val pumpAutoWindowActive: Boolean,
    val boilerCurrent: Double,
    val boilerPower: Double,
    val dailyBoiler: Double,
    val pumpCurrent: Double,
    val pumpPower: Double,
    val dailyPump: Double,
    val lineVoltage: Double,
    val pvW: Double,
    val gridW: Double,
    val loadW: Double,
    val batterySoc: Double,
    val batteryPower: Double,
    val wifiStrength: Double,
    val uptimeSec: Long,
    val rtcTime: String,
    val rtcDate: String,
    val updatedAtMs: Long,
    val bmeAvailable: Boolean,
    val bmeTemp: Double?,
    val bmeHum: Double?,
    val bmePress: Double?,
) : java.io.Serializable

data class GarageStatus(
    val boiler2Mode: String,
    val boiler2ModeReason: String,
    val boiler2On: Boolean,
    val boiler2StateReason: String,
    val boilerLock: String,
    val overloadActive: Boolean,
    val batteryProtectActive: Boolean,
    val boilerRestartEtaSec: Int?,
    val boilerLogic: BoilerLogicConfig?,
    val boiler2AutoWindowEnabled: Boolean,
    val boiler2AutoWindowStart: String,
    val boiler2AutoWindowEnd: String,
    val boiler2AutoWindowActive: Boolean,
    val boilerCurrent: Double,
    val boilerPower: Double,
    val dailyBoiler: Double,
    val gateState: String,
    val gateReason: String,
    val gateSource: String,
    val gateOpenPin: Int,
    val gateClosedPin: Int,
    val garageLightOn: Boolean,
    val garageLightReason: String,
    // Секунди до автовимкнення після 10-хв таймера "світло після воріт";
    // -1 = цей таймер зараз неактивний.
    val garageLightAutoOffInSec: Int,
    val lineVoltage: Double,
    val pvW: Double,
    val gridW: Double,
    val loadW: Double,
    val batterySoc: Double,
    val batteryPower: Double,
    val wifiStrength: Double,
    val uptimeSec: Long,
    val rtcTime: String,
    val rtcDate: String,
    val updatedAtMs: Long,
    val bmeAvailable: Boolean,
    val bmeTemp: Double?,
    val bmeHum: Double?,
    val bmePress: Double?,
) : java.io.Serializable

data class UnifiedStatus(
    val inverter: InverterStatus? = null,
    val loadController: LoadControllerStatus? = null,
    val garage: GarageStatus? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val fromMulticast: Boolean = false,
) : java.io.Serializable

/**
 * Режим і налаштування пріоритетів інвертора (Voltronic PI30), як їх віддає /api/inverter/settings.
 * mode: літера QMOD (P/S/L/B/F/H); outputPriority: 0 мережа, 1 сонце, 2 SBU;
 * chargerPriority: 0 мережа, 1 сонце, 2 сонце+мережа, 3 лише сонце. null - не вдалось прочитати.
 */
data class InverterSettings(
    val mode: String?,
    val outputPriority: Int?,
    val chargerPriority: Int?,
    // Авто-перемикання SBU/сонце (п.2 переробки).
    val outputPriorityAuto: Boolean = false,
    val outputPriorityAutoEtaSec: Int? = null,
)

data class InverterSetResult(val ok: Boolean, val settings: InverterSettings?, val error: String?)

/**
 * Одна точка 24-годинної історії напруг (мережа/сонце/АКБ) - з денних файлів
 * на модулі (переживають перезапуск). epochSec - АБСОЛЮТНИЙ час вимірювання
 * (секунди з 1970, локальний час модуля/телефона), а не "скільки тому" - вісь
 * графіка показує реальний час замірів, а не відносний вік.
 */
data class VoltageHistoryPoint(val epochSec: Long, val grid: Double, val pv: Double, val battery: Double)

/** intervalSec - крок між точками буфера; points - від найстарішої до найновішої. */
data class VoltageHistoryResult(val intervalSec: Int, val points: List<VoltageHistoryPoint>)
