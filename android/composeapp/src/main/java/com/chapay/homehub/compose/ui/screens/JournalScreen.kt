package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.ui.components.DateNavHeader
import com.chapay.homehub.compose.ui.components.todayIso
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlinx.coroutines.delay

private val LOG_LINE_REGEX = Regex("""^(\d{2}:\d{2}:\d{2})\s*\[([\w-]+)]\s*(.*)$""")

private data class LogEntry(val time: String, val module: String, val message: String)

private fun parseLogLine(raw: String): LogEntry {
    val match = LOG_LINE_REGEX.matchEntire(raw)
    return if (match != null) {
        val (time, module, message) = match.destructured
        LogEntry(time, module, message)
    } else {
        LogEntry("--:--:--", "?", raw)
    }
}

// Підпис модуля в журналі -> як показати картці в заголовку екрана.
private val MODULE_TITLES = mapOf(
    "invertor-bast" to "Журнал: інвертор",
    "load_controller" to "Журнал: контролер навантаження",
    "garage" to "Журнал: гараж",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    config: AppConfig,
    repository: StatusRepository,
    // Не null, коли екран відкрито з картки пристрою - показує лише його події
    // (module у [LogEntry]) і дає кнопку "назад". null - вкладка "Журнал", усі події.
    moduleFilter: String? = null,
    onBack: (() -> Unit)? = null,
) {
    var date by remember { mutableStateOf(todayIso()) }
    var allEntries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val entries = if (moduleFilter == null) allEntries else allEntries.filter { it.module == moduleFilter }
    var loading by remember { mutableStateOf(true) }

    // Та сама причина, що в TimelineScreen/EnergyChartScreen: вкладка
    // лишається живою в пам'яті між перемиканнями нижньої навігації.
    LaunchedEffect(date, config) {
        // Нова дата/конфіг - старі рядки чужої доби не показуємо.
        allEntries = emptyList()
        // Що вже є на телефоні - показуємо одразу, мережа лише оновлює.
        val cached = runCatching { repository.peekLoadControllerLogLines(config, date) }.getOrNull()
        if (cached != null) {
            allEntries = cached.map(::parseLogLine).asReversed()
            loading = false
        }
        var firstLoad = cached == null
        while (true) {
            if (firstLoad) loading = true
            val lines = runCatching { repository.fetchLoadControllerLogLines(config, date) }.getOrNull()
            // Тимчасовий збій зв'язку (лагає ZeroTier) не стирає вже показаний журнал.
            if (lines != null) {
                allEntries = lines.map(::parseLogLine).asReversed()
            }
            loading = false
            firstLoad = false
            // Коли даних ще нема - повторюємо швидко, не чекаємо 30с.
            delay(if (lines == null && allEntries.isEmpty()) 5_000 else 30_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(MODULE_TITLES[moduleFilter] ?: "Журнал подій") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            DateNavHeader(date = date, onDateChange = { date = it })
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                entries.isEmpty() -> Text(
                    text = "за цю добу подій немає",
                    color = TextMuted,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries) { entry -> LogEntryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.time, style = MaterialTheme.typography.labelMedium, color = AccentGrid)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.module,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.message, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
    }
}
