package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.chapay.homehub.compose.ui.theme.StatusAlert
import com.chapay.homehub.compose.ui.theme.StatusGood
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    MetricCard(
                        title = "СОНЦЕ",
                        valueText = formatWatts(pickPvW(inverter, loadController, garage)),
                        unit = "Вт",
                        accent = AccentPv,
                        icon = Icons.Filled.WbSunny,
                        modifier = Modifier.aspectRatio(1f),
                        secondaryText = inverter?.let { "за добу: ${formatWatts(it.dailyPv)} Вт·год" },
                    )
                }
                item {
                    MetricCard(
                        title = "МЕРЕЖА",
                        valueText = formatVolts(pickLineVoltage(inverter, loadController, garage)),
                        unit = "В",
                        accent = AccentGrid,
                        icon = Icons.Filled.ElectricBolt,
                        modifier = Modifier.aspectRatio(1f),
                        secondaryText = inverter?.let { "режим: ${it.mode}" },
                        onClick = { onOpenDevice("grid") },
                    )
                }
                item {
                    MetricCard(
                        title = "АКБ",
                        valueText = formatPercent(pickBatterySoc(inverter, loadController, garage)),
                        unit = "%",
                        accent = AccentBattery,
                        icon = Icons.Filled.BatteryChargingFull,
                        modifier = Modifier.aspectRatio(1f),
                        secondaryText = "потужність: ${formatWatts(pickBatteryPower(inverter, loadController, garage))} Вт",
                    )
                }
                item {
                    MetricCard(
                        title = "НАВАНТАЖЕННЯ",
                        valueText = formatWatts(pickLoadW(inverter, loadController, garage)),
                        unit = "Вт",
                        accent = AccentLoad,
                        icon = Icons.Filled.Power,
                        modifier = Modifier.aspectRatio(1f),
                        secondaryText = inverter?.let { "режим: ${it.loadMode}" },
                        onClick = { onOpenDevice("load") },
                    )
                }
                item {
                    MetricCard(
                        title = "БОЙЛЕР 1",
                        valueText = formatWatts(loadController?.boilerPower ?: 0.0),
                        unit = "Вт",
                        accent = AccentBoiler,
                        icon = Icons.Filled.Whatshot,
                        modifier = Modifier.aspectRatio(1f),
                        secondaryText = loadController?.let { "режим: ${it.boiler1Mode} · ${boolTextUk(it.boiler1On)}" },
                        onClick = { onOpenDevice("boiler1") },
                    )
                }
                item {
                    MetricCard(
                        title = "НАСОС",
                        valueText = formatWatts(loadController?.pumpPower ?: 0.0),
                        unit = "Вт",
                        accent = AccentPump,
                        icon = Icons.Filled.Water,
                        modifier = Modifier.aspectRatio(1f),
                        secondaryText = loadController?.let { "режим: ${it.pumpMode} · ${boolTextUk(it.pumpOn)}" },
                        onClick = { onOpenDevice("pump") },
                    )
                }
                item {
                    MetricCard(
                        title = "БОЙЛЕР 2",
                        valueText = formatWatts(garage?.boilerPower ?: 0.0),
                        unit = "Вт",
                        accent = AccentBoiler,
                        icon = Icons.Filled.Whatshot,
                        modifier = Modifier.aspectRatio(1f),
                        secondaryText = garage?.let { "режим: ${it.boiler2Mode} · ${boolTextUk(it.boiler2On)}" },
                        onClick = { onOpenDevice("boiler2") },
                    )
                }
                item {
                    GateCard(
                        garage = garage,
                        onTriggerGate = onTriggerGate,
                        onToggleLight = onToggleLight,
                    )
                }
            }
        }
    }
}

@Composable
private fun GateCard(
    garage: GarageStatus?,
    onTriggerGate: () -> Unit,
    onToggleLight: () -> Unit,
) {
    Card(
        modifier = Modifier.aspectRatio(1f),
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
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onTriggerGate,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGate),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Керувати")
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onToggleLight,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceContainerHigh, contentColor = TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (garage?.garageLightOn == true) "Вимкнути світло" else "Увімкнути світло")
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

private fun pickBatterySoc(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.batterySoc, load?.batterySoc, garage?.batterySoc).firstOrNull { it.isFinite() }

private fun pickBatteryPower(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double =
    listOfNotNull(inverter?.batteryPower, load?.batteryPower, garage?.batteryPower).firstOrNull { it.isFinite() } ?: 0.0

private fun pickLoadW(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.loadW, load?.loadW, garage?.loadW).firstOrNull { it.isFinite() }
