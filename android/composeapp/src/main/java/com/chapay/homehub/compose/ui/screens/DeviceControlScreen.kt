package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.ui.DashboardUiState
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentBoiler
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentLoad
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary

private data class DeviceInfo(
    val title: String,
    val mode: String,
    val reason: String,
    val accent: androidx.compose.ui.graphics.Color,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(
    deviceKey: String,
    uiState: DashboardUiState,
    onSetMode: (String) -> Unit,
    onBack: () -> Unit,
) {
    val inverter = uiState.status.inverter
    val load = uiState.status.loadController
    val garage = uiState.status.garage

    val info = when (deviceKey) {
        "grid" -> DeviceInfo("Мережа", inverter?.mode ?: "---", inverter?.modeReason ?: "---", AccentGrid)
        "load" -> DeviceInfo("Навантаження", inverter?.loadMode ?: "---", inverter?.loadModeReason ?: "---", AccentLoad)
        "boiler1" -> DeviceInfo("Бойлер 1", load?.boiler1Mode ?: "---", load?.boiler1ModeReason ?: "---", AccentBoiler)
        "pump" -> DeviceInfo("Насос", load?.pumpMode ?: "---", load?.pumpModeReason ?: "---", AccentPump)
        "boiler2" -> DeviceInfo("Бойлер 2", garage?.boiler2Mode ?: "---", garage?.boiler2ModeReason ?: "---", AccentBoiler)
        else -> DeviceInfo(deviceKey, "---", "---", AccentBattery)
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
                    Text("поточний режим", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Text(info.mode, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text("причина", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Text(info.reason, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("керування", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(8.dp))
            Row {
                listOf("AUTO", "OFF", "ON").forEach { mode ->
                    val selected = info.mode == mode
                    Button(
                        onClick = { onSetMode(mode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) info.accent else SurfaceContainerHigh,
                            contentColor = if (selected) androidx.compose.ui.graphics.Color(0xFF0D0F16) else TextPrimary,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = if (mode != "ON") 8.dp else 0.dp),
                    ) {
                        Text(mode)
                    }
                }
            }
        }
    }
}
