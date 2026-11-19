package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.ui.DashboardUiState
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentBoiler
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentLoad
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.StatusAlert
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary

private data class DeviceInfo(
    val title: String,
    val mode: String,
    val reason: String,
    val accent: Color,
    // AUTO/OFF/ON, або лише OFF/ON для мережевого реле (п.1 переробки - без автоматики).
    val availableModes: List<String>,
    // NONE|ON|OFF - фіксація довгим натисканням; для мережі завжди "NONE" (лока нема,
    // бо там і так немає AUTO, до якого можна було б "повертатись").
    val lockMode: String,
    // Текст активного захисту/паузи-перезапуску, якщо є (п.3/4/6 переробки).
    val protectionLabel: String?,
)

private data class AutoWindowInfo(
    val enabled: Boolean,
    val start: String,
    val end: String,
    val active: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(
    deviceKey: String,
    uiState: DashboardUiState,
    onSetMode: (String) -> Unit,
    onSetLock: ((String) -> Unit)? = null,
    onSetAutoWindow: ((enabled: Boolean, start: String, end: String) -> Unit)? = null,
    onBack: () -> Unit,
) {
    val inverter = uiState.status.inverter
    val load = uiState.status.loadController
    val garage = uiState.status.garage

    fun etaText(prefix: String, sec: Int?): String? =
        if (sec != null && sec > 0) "$prefix через " + (if (sec >= 60) "${sec / 60} хв" else "$sec с") else null

    val info = when (deviceKey) {
        "grid" -> DeviceInfo(
            "Мережа", inverter?.mode ?: "---", inverter?.modeReason ?: "---", AccentGrid,
            availableModes = listOf("OFF", "ON"), lockMode = "NONE", protectionLabel = null,
        )
        "load" -> DeviceInfo(
            "Навантаження", inverter?.loadMode ?: "---", inverter?.loadModeReason ?: "---", AccentLoad,
            availableModes = listOf("AUTO", "OFF", "ON"), lockMode = inverter?.loadLock ?: "NONE",
            protectionLabel = when {
                inverter?.loadOverloadActive == true -> "захист інвертора: перевантаження"
                inverter?.loadBatteryProtectActive == true -> "захист АКБ: розряд"
                else -> null
            },
        )
        "boiler1" -> DeviceInfo(
            "Бойлер 1", load?.boiler1Mode ?: "---", load?.boiler1ModeReason ?: "---", AccentBoiler,
            availableModes = listOf("AUTO", "OFF", "ON"), lockMode = load?.boilerLock ?: "NONE",
            protectionLabel = when {
                load?.overloadActive == true -> "захист інвертора: перевантаження"
                load?.batteryProtectActive == true -> "захист АКБ: розряд"
                else -> etaText("увімкнення можливе", load?.boilerRestartEtaSec)
            },
        )
        "pump" -> DeviceInfo(
            "Насос", load?.pumpMode ?: "---", load?.pumpModeReason ?: "---", AccentPump,
            availableModes = listOf("AUTO", "OFF", "ON"), lockMode = load?.pumpLock ?: "NONE",
            protectionLabel = when {
                load?.overloadActive == true -> "захист інвертора: перевантаження"
                load?.batteryProtectActive == true -> "захист АКБ: розряд"
                else -> null
            },
        )
        "boiler2" -> DeviceInfo(
            "Бойлер 2", garage?.boiler2Mode ?: "---", garage?.boiler2ModeReason ?: "---", AccentBoiler,
            availableModes = listOf("AUTO", "OFF", "ON"), lockMode = garage?.boilerLock ?: "NONE",
            protectionLabel = when {
                garage?.overloadActive == true -> "захист інвертора: перевантаження"
                garage?.batteryProtectActive == true -> "захист АКБ: розряд"
                else -> etaText("увімкнення можливе", garage?.boilerRestartEtaSec)
            },
        )
        else -> DeviceInfo(deviceKey, "---", "---", AccentBattery, listOf("AUTO", "OFF", "ON"), "NONE", null)
    }

    // Таймер (вікно АВТО) є лише в бойлерів і насоса - у load_controller/garage,
    // не в самого інвертора (grid/load).
    val autoWindow = when (deviceKey) {
        "boiler1" -> load?.let {
            AutoWindowInfo(it.boiler1AutoWindowEnabled, it.boiler1AutoWindowStart, it.boiler1AutoWindowEnd, it.boiler1AutoWindowActive)
        }
        "pump" -> load?.let {
            AutoWindowInfo(it.pumpAutoWindowEnabled, it.pumpAutoWindowStart, it.pumpAutoWindowEnd, it.pumpAutoWindowActive)
        }
        "boiler2" -> garage?.let {
            AutoWindowInfo(it.boiler2AutoWindowEnabled, it.boiler2AutoWindowStart, it.boiler2AutoWindowEnd, it.boiler2AutoWindowActive)
        }
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("поточний режим", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                            Text(info.mode, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                        }
                        if (info.lockMode != "NONE") {
                            Icon(Icons.Filled.Lock, contentDescription = "Зафіксовано", tint = info.accent)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("причина", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Text(info.reason, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    if (info.lockMode != "NONE") {
                        Spacer(Modifier.height(8.dp))
                        Text("зафіксовано вручну (${info.lockMode}) - не зміниться саме", style = MaterialTheme.typography.bodySmall, color = info.accent)
                    }
                    if (info.protectionLabel != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = StatusAlert, modifier = Modifier.width(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(info.protectionLabel, style = MaterialTheme.typography.bodySmall, color = StatusAlert)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("керування", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            if (onSetLock != null) {
                Text("довге натискання ON/OFF - фіксація назавжди", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                info.availableModes.forEachIndexed { index, mode ->
                    ModeButton(
                        mode = mode,
                        selected = info.mode == mode,
                        accent = info.accent,
                        onClick = { onSetMode(mode) },
                        onLongPress = if (onSetLock != null && mode != "AUTO") ({ onSetLock(mode) }) else null,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = if (index != info.availableModes.lastIndex) 8.dp else 0.dp),
                    )
                }
            }
            if (onSetLock != null && info.lockMode != "NONE") {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onSetLock("NONE") }) {
                    Text("Зняти фіксацію")
                }
            }

            if (autoWindow != null && onSetAutoWindow != null) {
                Spacer(Modifier.height(20.dp))
                AutoWindowSection(info = autoWindow, accent = info.accent, onSave = onSetAutoWindow)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeButton(
    mode: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var confirmLock by remember { mutableStateOf(false) }
    val lockHandler = onLongPress

    Surface(
        color = if (selected) accent else SurfaceContainerHigh,
        contentColor = if (selected) Color(0xFF0D0F16) else TextPrimary,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .height(ButtonDefaults.MinHeight)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (lockHandler != null) ({ confirmLock = true }) else null,
            ),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(mode, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }

    if (confirmLock && lockHandler != null) {
        AlertDialog(
            onDismissRequest = { confirmLock = false },
            title = { Text("Зафіксувати $mode?") },
            text = { Text("Реле лишиться в режимі $mode назавжди, доки фіксацію не знімуть вручну - навіть якщо з'явиться мережа чи спрацює автоматика.") },
            confirmButton = {
                TextButton(onClick = { lockHandler(); confirmLock = false }) { Text("Зафіксувати") }
            },
            dismissButton = { TextButton(onClick = { confirmLock = false }) { Text("Скасувати") } },
        )
    }
}

private val HM_REGEX = Regex("""^\d{2}:\d{2}$""")
private fun normalizeHm(value: String, fallback: String = "00:00"): String =
    if (HM_REGEX.matches(value.trim())) value.trim() else fallback

@Composable
private fun AutoWindowSection(info: AutoWindowInfo, accent: Color, onSave: (Boolean, String, String) -> Unit) {
    var editEnabled by remember(info.enabled) { mutableStateOf(info.enabled) }
    var editStart by remember(info.start) { mutableStateOf(normalizeHm(info.start)) }
    var editEnd by remember(info.end) { mutableStateOf(normalizeHm(info.end)) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = accent, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("таймер (вікно АВТО)", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (info.enabled) {
                    "${normalizeHm(info.start)}–${normalizeHm(info.end)} " +
                        "(${if (info.active) "зараз активне" else "зараз неактивне"})"
                } else {
                    "активне завжди"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = editEnabled,
                    onCheckedChange = { editEnabled = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = accent),
                )
                Spacer(Modifier.width(8.dp))
                Text("обмежити часом (лише в AUTO)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
            if (editEnabled) {
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = editStart,
                        onValueChange = { editStart = it },
                        label = { Text("з") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    OutlinedTextField(
                        value = editEnd,
                        onValueChange = { editEnd = it },
                        label = { Text("до") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSave(editEnabled, normalizeHm(editStart), normalizeHm(editEnd)) },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF0D0F16)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Зберегти")
            }
        }
    }
}
