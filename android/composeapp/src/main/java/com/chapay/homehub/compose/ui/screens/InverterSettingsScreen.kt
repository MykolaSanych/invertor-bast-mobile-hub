package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.chapay.homehub.compose.ui.InverterSettingsUiState
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.StatusAlert
import com.chapay.homehub.compose.ui.theme.StatusGood
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary

private data class PriorityOption(val value: Int, val title: String, val subtitle: String)

// Voltronic PI30: POP - пріоритет джерела виходу, PCP - пріоритет зарядки акумулятора.
private val OUTPUT_OPTIONS = listOf(
    PriorityOption(0, "Мережа", "мережа → сонце → акумулятор"),
    PriorityOption(1, "Сонце", "сонце → мережа → акумулятор"),
    PriorityOption(2, "Сонце + акумулятор (SBU)", "сонце → акумулятор → мережа"),
)
private val CHARGER_OPTIONS = listOf(
    PriorityOption(0, "Спочатку мережа", "заряд від мережі, сонце - додатково"),
    PriorityOption(1, "Спочатку сонце", "від сонця, мережа - лише за нестачі"),
    PriorityOption(2, "Сонце + мережа", "обидва джерела одночасно"),
    PriorityOption(3, "Лише сонце", "мережа акумулятор не заряджає"),
)

private fun modeText(mode: String?): String = when (mode) {
    "P" -> "Увімкнення"
    "S" -> "Очікування"
    "L" -> "Живлення від мережі"
    "B" -> "Живлення від акумулятора"
    "F" -> "Аварія"
    "H" -> "Економний режим"
    null -> "невідомо"
    else -> "невідомо ($mode)"
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun InverterSettingsScreen(
    state: InverterSettingsUiState,
    onRefresh: () -> Unit,
    onSetOutput: (Int) -> Unit,
    onSetCharger: (Int) -> Unit,
    onBack: () -> Unit,
    // Яку секцію прокрутити у видиму область одразу при відкритті: "output" (картка
    // сонця) чи "charger" (картка АКБ). null - без автопрокрутки.
    focusSection: String? = null,
) {
    // Обрана, але ще не підтверджена зміна: (це вихід чи зарядка, значення, назва).
    var pending by remember { mutableStateOf<Triple<Boolean, Int, String>?>(null) }
    val settings = state.settings
    val chargerSectionRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(focusSection) {
        if (focusSection == "charger") {
            // Один кадр на розкладку, перш ніж просити прокрутити до ще не виміряної секції.
            kotlinx.coroutines.delay(50)
            scope.launch { chargerSectionRequester.bringIntoView() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Режими інвертора") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.loading && !state.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Оновити")
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Поточний режим роботи.
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainer), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("ПОТОЧНИЙ РЕЖИМ", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(modeText(settings?.mode), style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        if (state.loading || state.busy) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentPv)
                        }
                    }
                }
            }

            if (state.error != null) {
                Text(state.error, style = MaterialTheme.typography.bodyMedium, color = StatusAlert)
            }
            if (state.message != null) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = StatusGood)
            }

            PrioritySection(
                title = "ПРІОРИТЕТ ДЖЕРЕЛА ВИХОДУ",
                options = OUTPUT_OPTIONS,
                current = settings?.outputPriority,
                enabled = !state.loading && !state.busy,
                onPick = { option -> pending = Triple(true, option.value, "Пріоритет виходу: ${option.title}") },
            )
            PrioritySection(
                title = "ПРІОРИТЕТ ЗАРЯДКИ АКУМУЛЯТОРА",
                options = CHARGER_OPTIONS,
                current = settings?.chargerPriority,
                enabled = !state.loading && !state.busy,
                onPick = { option -> pending = Triple(false, option.value, "Пріоритет зарядки: ${option.title}") },
                modifier = Modifier.bringIntoViewRequester(chargerSectionRequester),
            )

            Text(
                "Зміна записується в пам'ять інвертора й одразу впливає на живлення будинку. " +
                    "Не перемикайте часто: пам'ять має обмежений ресурс записів.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }

    pending?.let { (isOutput, value, title) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Змінити режим інвертора?") },
            text = { Text("$title\n\nНалаштування буде записано в інвертор.") },
            confirmButton = {
                TextButton(onClick = {
                    if (isOutput) onSetOutput(value) else onSetCharger(value)
                    pending = null
                }) { Text("Змінити") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun PrioritySection(
    title: String,
    options: List<PriorityOption>,
    current: Int?,
    enabled: Boolean,
    onPick: (PriorityOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        options.forEach { option ->
            val selected = option.value == current
            Card(
                colors = CardDefaults.cardColors(containerColor = if (selected) SurfaceContainerHigh else SurfaceContainer),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (selected) AccentPv else OutlineSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled && !selected) { onPick(option) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) AccentPv else TextMuted,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(option.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(option.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
        }
        if (current == null) {
            Text("Поточне значення невідоме - натисніть «Оновити».", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}
