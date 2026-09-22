package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: AppConfig,
    onSave: (AppConfig) -> Unit,
    onBack: () -> Unit,
) {
    var inverterUrl by remember { mutableStateOf(config.inverterBaseUrl) }
    var inverterPass by remember { mutableStateOf(config.inverterPassword) }
    var inverterEnabled by remember { mutableStateOf(config.inverterEnabled) }

    var loadUrl by remember { mutableStateOf(config.loadControllerBaseUrl) }
    var loadPass by remember { mutableStateOf(config.loadControllerPassword) }
    var loadEnabled by remember { mutableStateOf(config.loadControllerEnabled) }

    var garageUrl by remember { mutableStateOf(config.garageBaseUrl) }
    var garagePass by remember { mutableStateOf(config.garagePassword) }
    var garageEnabled by remember { mutableStateOf(config.garageEnabled) }

    var pollSec by remember { mutableStateOf(config.pollIntervalSec.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            DeviceSection(
                title = "Інвертор",
                url = inverterUrl,
                onUrlChange = { inverterUrl = it },
                pass = inverterPass,
                onPassChange = { inverterPass = it },
                enabled = inverterEnabled,
                onEnabledChange = { inverterEnabled = it },
            )
            Spacer(Modifier.height(20.dp))
            DeviceSection(
                title = "Load controller",
                url = loadUrl,
                onUrlChange = { loadUrl = it },
                pass = loadPass,
                onPassChange = { loadPass = it },
                enabled = loadEnabled,
                onEnabledChange = { loadEnabled = it },
            )
            Spacer(Modifier.height(20.dp))
            DeviceSection(
                title = "Гараж",
                url = garageUrl,
                onUrlChange = { garageUrl = it },
                pass = garagePass,
                onPassChange = { garagePass = it },
                enabled = garageEnabled,
                onEnabledChange = { garageEnabled = it },
            )

            Spacer(Modifier.height(20.dp))
            Text("Інтервал опитування, с", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pollSec,
                onValueChange = { pollSec = it.filter { ch -> ch.isDigit() } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    onSave(
                        config.copy(
                            inverterBaseUrl = inverterUrl.trim(),
                            inverterPassword = inverterPass,
                            inverterEnabled = inverterEnabled,
                            loadControllerBaseUrl = loadUrl.trim(),
                            loadControllerPassword = loadPass,
                            loadControllerEnabled = loadEnabled,
                            garageBaseUrl = garageUrl.trim(),
                            garagePassword = garagePass,
                            garageEnabled = garageEnabled,
                            pollIntervalSec = pollSec.toIntOrNull()?.coerceIn(2, 60) ?: config.pollIntervalSec,
                        ),
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Зберегти")
            }
        }
    }
}

@Composable
private fun DeviceSection(
    title: String,
    url: String,
    onUrlChange: (String) -> Unit,
    pass: String,
    onPassChange: (String) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("Адреса (http://...)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = onPassChange,
            label = { Text("Пароль") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
