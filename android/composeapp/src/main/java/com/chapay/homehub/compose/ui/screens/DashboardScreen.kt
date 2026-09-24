package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.data.GarageStatus
import com.chapay.homehub.compose.data.InverterStatus
import com.chapay.homehub.compose.data.LoadControllerStatus
import com.chapay.homehub.compose.ui.DashboardUiState
import com.chapay.homehub.compose.ui.components.MetricCard
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentBoiler
import com.chapay.homehub.compose.ui.theme.AccentGate
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentLoad
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.IndicatorOff
import com.chapay.homehub.compose.ui.theme.PV_PRESENT_VOLTAGE
import com.chapay.homehub.compose.ui.theme.StatusAlert
import com.chapay.homehub.compose.ui.theme.StatusGood
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onTriggerGate: () -> Unit,
    onToggleLight: () -> Unit,
) {
    val inverter = uiState.status.inverter
    val loadController = uiState.status.loadController
    val garage = uiState.status.garage
    // Модуль, що давно не відповів (лагає зв'язок), - картки тьмяніють, дані лишаються.
    val nowMs = System.currentTimeMillis()
    fun isStale(atMs: Long) = atMs > 0L && nowMs - atMs > STALE_DATA_MS
    val inverterStale = isStale(uiState.inverterAtMs)
    val loadStale = isStale(uiState.loadAtMs)
    val garageStale = isStale(uiState.garageAtMs)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Мій дім") },
                actions = {
                    ConnectionBadge(lastSuccessAtMs = uiState.lastSuccessAtMs, hasError = uiState.error != null)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Налаштування")
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.loading,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CardRow {
                    MetricCard(
                        title = "СОНЦЕ",
                        stale = inverterStale,
                        valueText = formatWatts(pickPvW(inverter, loadController, garage)),
                        unit = "Вт",
                        accent = AccentPv,
                        icon = Icons.Filled.WbSunny,
                        iconTint = pvIndicatorColor(inverter),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        details = listOfNotNull(
                            inverter?.let { "напруга" to "${formatVolts(it.pvVoltage)} В" },
                            inverter?.let { "за добу" to "${formatWatts(it.dailyPv)} Вт·год" },
                            inverter?.let { "останні дані" to it.rtcTime },
                        ),
                    )
                    MetricCard(
                        title = "МЕРЕЖА",
                        stale = inverterStale,
                        valueText = formatWatts(pickGridPowerW(inverter, loadController, garage)),
                        unit = "Вт",
                        accent = AccentGrid,
                        icon = Icons.Filled.ElectricBolt,
                        iconTint = gridIndicatorColor(inverter),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        details = listOfNotNull(
                            inverter?.let { "режим" to it.mode },
                            inverter?.let { "напруга" to "${formatVolts(it.lineVoltage)} В" },
                            inverter?.let { "частота" to "${formatVolts(it.gridFrequency)} Гц" },
                            inverter?.let { "за добу" to "${formatWatts(it.dailyGrid)} Вт·год" },
                        ),
                        onClick = { onOpenDevice("grid") },
                    )
                }
                CardRow {
                    MetricCard(
                        title = "АКБ",
                        stale = inverterStale,
                        valueText = formatPercent(pickBatterySoc(inverter, loadController, garage)),
                        unit = "%",
                        accent = AccentBattery,
                        icon = Icons.Filled.BatteryChargingFull,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        details = listOfNotNull(
                            inverter?.let { "напруга" to "${formatVolts(it.batteryVoltage)} В" },
                            "потужність" to "${formatWatts(pickBatteryPower(inverter, loadController, garage))} Вт",
                            inverter?.let { "температура" to "${formatVolts(it.inverterTemp)} °C" },
                        ),
                    )
                    MetricCard(
                        title = "НАВАНТАЖЕННЯ",
                        stale = inverterStale,
                        valueText = formatWatts(pickLoadW(inverter, loadController, garage)),
                        unit = "Вт",
                        accent = AccentLoad,
                        icon = Icons.Filled.Power,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        details = listOfNotNull(
                            inverter?.let { "режим" to it.loadMode },
                            inverter?.let { "напруга" to "${formatVolts(it.outputVoltage)} В" },
                            inverter?.let { "за добу" to "${formatWatts(it.dailyHome)} Вт·год" },
                        ),
                        onClick = { onOpenDevice("load") },
                    )
                }
                CardRow {
                    MetricCard(
                        title = "БОЙЛЕР 1",
                        stale = loadStale,
                        valueText = formatWatts(loadController?.boilerPower ?: 0.0),
                        unit = "Вт",
                        accent = AccentBoiler,
                        icon = Icons.Filled.Whatshot,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        details = listOfNotNull(
                            loadController?.let { "режим" to it.boiler1Mode },
                            loadController?.let { "струм" to "${"%.2f".format(it.boilerCurrent)} А" },
                            loadController?.let { "за добу" to "${formatWatts(it.dailyBoiler)} Вт·год" },
                            loadController?.let { "стан" to boolTextUk(it.boiler1On) },
                        ),
                        onClick = { onOpenDevice("boiler1") },
                    )
                    MetricCard(
                        title = "НАСОС",
                        stale = loadStale,
                        valueText = formatWatts(loadController?.pumpPower ?: 0.0),
                        unit = "Вт",
                        accent = AccentPump,
                        icon = Icons.Filled.Water,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        details = listOfNotNull(
                            loadController?.let { "режим" to it.pumpMode },
                            loadController?.let { "струм" to "${"%.2f".format(it.pumpCurrent)} А" },
                            loadController?.let { "за добу" to "${formatWatts(it.dailyPump)} Вт·год" },
                            loadController?.let { "стан" to boolTextUk(it.pumpOn) },
                        ),
                        onClick = { onOpenDevice("pump") },
                    )
                }
                // Не в CardRow: цей рядок містить кнопки (GateCard), чия
                // реальна висота (мінімальна висота дотику Material3 Button)
                // систематично недооцінюється при IntrinsicSize.Max-вимірюванні,
                // через що картка обрізалась по нижній кнопці. Природна висота
                // тут не заважає (картка воріт і в WebView-додатку завжди
                // виглядала окремим, вищим блоком).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricCard(
                        title = "БОЙЛЕР 2",
                        stale = garageStale,
                        valueText = formatWatts(garage?.boilerPower ?: 0.0),
                        unit = "Вт",
                        accent = AccentBoiler,
                        icon = Icons.Filled.Whatshot,
                        modifier = Modifier.weight(1f),
                        details = listOfNotNull(
                            garage?.let { "режим" to it.boiler2Mode },
                            garage?.let { "струм" to "${"%.2f".format(it.boilerCurrent)} А" },
                            garage?.let { "за добу" to "${formatWatts(it.dailyBoiler)} Вт·год" },
                            garage?.let { "стан" to boolTextUk(it.boiler2On) },
                        ),
                        onClick = { onOpenDevice("boiler2") },
                    )
                    GateCard(
                        garage = garage,
                        onTriggerGate = onTriggerGate,
                        onToggleLight = onToggleLight,
                        modifier = Modifier.weight(1f).alpha(if (garageStale) 0.5f else 1f),
                    )
                }
            }
        }
    }
}

/** Через скільки мс без відповіді модуля його картки вважаються застарілими (тьмяніють). */
private const val STALE_DATA_MS = 30_000L

/** Сонце: жовте, коли напруга PV-панелей >= [PV_PRESENT_VOLTAGE], інакше чорне. */
internal fun pvIndicatorColor(inverter: InverterStatus?): Color =
    if ((inverter?.pvVoltage ?: 0.0) >= PV_PRESENT_VOLTAGE) AccentPv else IndicatorOff

/** Блискавка мережі: синя, коли інвертор бачить напругу мережі (grid_present), інакше чорна. */
internal fun gridIndicatorColor(inverter: InverterStatus?): Color =
    if (inverter?.gridPresent == true) AccentGrid else IndicatorOff

/** Рядок із двох карток однакової висоти (за найвищою в парі), щоб сітка на
 * дашборді не "стрибала" через різну кількість рядків деталей у картках. */
@Composable
private fun CardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

// Кнопка показує ДІЮ, яку виконає натискання (як door-action-btn у чинному
// WebView-додатку), а не поточний стан - "відкрити", коли зараз зачинено,
// "зачинити", коли зараз відчинено, "стоп" у решті випадків (рух/невідомо).
private fun classifyGateState(garage: GarageStatus?): String {
    if (garage == null) return "disabled"
    val closedPin = garage.gateClosedPin
    if (closedPin >= 0) return if (closedPin == 0) "closed" else "open"
    val raw = garage.gateState.trim().lowercase()
    return when {
        raw.contains("open") -> "open"
        raw.contains("close") -> "closed"
        raw.contains("stop") -> "stopped"
        raw.contains("move") -> "moving"
        else -> "unknown"
    }
}

private data class GateActionUi(val label: String, val icon: ImageVector)

private fun gateActionUi(stateKey: String): GateActionUi = when (stateKey) {
    "closed" -> GateActionUi("Відкрити", Icons.Filled.ArrowUpward)
    "open" -> GateActionUi("Зачинити", Icons.Filled.ArrowDownward)
    else -> GateActionUi("Стоп", Icons.Filled.Stop)
}

private fun formatCountdownClock(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val m = safe / 60
    val s = safe % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun GateCard(
    garage: GarageStatus?,
    onTriggerGate: () -> Unit,
    onToggleLight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // Прошивка віддає лише "скільки секунд лишалось на момент опитування",
    // тому запам'ятовуємо абсолютний дедлайн один раз на кожне оновлення
    // статусу й відлічуємо локально щосекунди (як applyGarageLightAutoOffFromStatus
    // у чинному WebView-додатку) - інакше цифри стрибали б лише раз на цикл
    // опитування, а не плавно.
    var lightAutoOffAtMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(garage?.garageLightAutoOffInSec) {
        val sec = garage?.garageLightAutoOffInSec
        lightAutoOffAtMs = if (sec != null && sec >= 0) System.currentTimeMillis() + sec * 1000L else null
    }

    val disabled = garage == null
    val stateKey = classifyGateState(garage)
    val gateAction = gateActionUi(stateKey)
    val lightOn = garage?.garageLightOn == true
    val remainingSec = lightAutoOffAtMs?.let { (it - now) / 1000 }
    val lightLabel = when {
        disabled -> "світло --"
        lightOn && remainingSec != null && remainingSec > 0 -> "Вимкнути (${formatCountdownClock(remainingSec)})"
        lightOn -> "Вимкнути світло"
        else -> "Увімкнути світло"
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Garage, contentDescription = null, tint = AccentGate, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("ВОРОТА", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = gateStateTextUk(garage?.gateState),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "причина: ${gateReasonTextUk(garage?.gateReason)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Text(
                text = "світло: ${if (lightOn) "УВІМК" else "ВИМК"}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onTriggerGate,
                enabled = !disabled,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGate),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(gateAction.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (disabled) "--" else gateAction.label)
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onToggleLight,
                enabled = !disabled,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceContainerHigh, contentColor = TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(lightLabel)
            }
        }
    }
}

@Composable
private fun ConnectionBadge(lastSuccessAtMs: Long, hasError: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val ageSec = if (lastSuccessAtMs <= 0) Long.MAX_VALUE else (now - lastSuccessAtMs) / 1000
    val (color, label) = when {
        ageSec == Long.MAX_VALUE -> StatusAlert to "немає зв'язку"
        hasError && ageSec >= 20 -> StatusAlert to "помилка запиту"
        ageSec < 20 -> StatusGood to "живо"
        ageSec < 60 -> AccentPv to "$ageSec с тому"
        else -> StatusAlert to "давно (${ageSec}с)"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

private fun boolTextUk(value: Boolean) = if (value) "УВІМК" else "ВИМК"

private fun gateStateTextUk(raw: String?): String {
    val v = raw?.trim()?.lowercase() ?: return "---"
    return when {
        v.contains("open") -> "Відчинено"
        v.contains("close") -> "Зачинено"
        v.contains("stop") -> "Стоп"
        v.contains("move") -> "Рух"
        else -> "Невідомо"
    }
}

// Прошивка досі віддає причину як внутрішній рядок ("door closed"/"door
// open"/джерело команди на кшталт "remote trigger") - той самий пропуск є
// і в чинному WebView-додатку (uiText() там теж не перекладає ці значення).
// Тут - легкий локальний переклад найпоширеніших варіантів, решта показується
// як є (краще щось зрозуміле частково, ніж порожньо).
private fun gateReasonTextUk(raw: String?): String {
    val v = raw?.trim() ?: return "---"
    if (v.isEmpty() || v == "init") return "---"
    val lower = v.lowercase()
    return when {
        lower == "door closed" -> "ворота зачинено"
        lower == "door open" -> "ворота відчинено"
        lower == "remote trigger" -> "пульт"
        lower == "button" -> "кнопка"
        lower == "app" || lower == "mobile hub" || lower == "compose hub" -> "застосунок"
        else -> v
    }
}

private fun formatWatts(value: Double?): String {
    if (value == null || value.isNaN()) return "--"
    return value.roundToInt().toString()
}

private fun formatVolts(value: Double?): String {
    if (value == null || value.isNaN()) return "--"
    return "%.1f".format(value)
}

private fun formatPercent(value: Double?): String {
    if (value == null || value.isNaN()) return "--"
    return value.roundToInt().toString()
}

private fun pickPvW(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.pvW, load?.pvW, garage?.pvW).firstOrNull { it.isFinite() }

private fun pickLineVoltage(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.lineVoltage, load?.lineVoltage, garage?.lineVoltage).firstOrNull { it.isFinite() }

// Той самий захист, що й у чинному WebView-додатку (zeroGridPowerWhenNoVoltage):
// без напруги мережі показник потужності - лише шум датчика, а не реальний потік.
private fun pickGridPowerW(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? {
    val voltage = pickLineVoltage(inverter, load, garage) ?: return null
    val power = listOfNotNull(inverter?.gridW, load?.gridW, garage?.gridW).firstOrNull { it.isFinite() } ?: return null
    return if (voltage < 50.0) 0.0 else power
}

private fun pickBatterySoc(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.batterySoc, load?.batterySoc, garage?.batterySoc).firstOrNull { it.isFinite() }

private fun pickBatteryPower(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double =
    listOfNotNull(inverter?.batteryPower, load?.batteryPower, garage?.batteryPower).firstOrNull { it.isFinite() } ?: 0.0

private fun pickLoadW(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.loadW, load?.loadW, garage?.loadW).firstOrNull { it.isFinite() }
