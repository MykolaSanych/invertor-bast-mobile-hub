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

// Заголовок екрана для кожного реле (ключі - ті самі, що onOpenDevice/CardKey на
// дашборді: "pv"/"grid"/"battery"/"load"/"boiler1"/"pump"/"boiler2"/"gate"/"light").
private val DEVICE_TITLES = mapOf(
    "pv" to "Журнал: сонце",
    "grid" to "Журнал: мережа",
    "battery" to "Журнал: АКБ",
    "load" to "Журнал: навантаження",
    "boiler1" to "Журнал: бойлер 1",
    "pump" to "Журнал: насос",
    "boiler2" to "Журнал: бойлер 2",
    "gate" to "Журнал: ворота",
    "light" to "Журнал: світло",
)

// Журнал - спільний для всіх трьох модулів (кожен пересилає свої події на
// load_controller), а module у [LogEntry] каже лише ЯКИЙ МОДУЛЬ це записав - на
// одному модулі "живе" по кілька карток (invertor-bast: сонце/мережа/АКБ/
// навантаження; load_controller: бойлер1/насос; garage: бойлер2/ворота/світло).
// Раніше кнопка журналу на картці фільтрувала лише за модулем, тобто картка
// "Насос" показувала й події бойлера 1 впереміш - тепер дивимось у текст
// повідомлення (він завжди містить ті самі стійкі українські слова, які самі ж
// прошивки й пишуть) і визначаємо САМЕ реле. Перевіряємо через contains, а не
// startsWith: інвертор пересилає рядок із власним вкладеним префіксом
// "[TYPE] повідомлення" (напр. "[РЕЖИМ] НАВАНТАЖЕННЯ режим: ..."), і після
// розбору головним регексом (за часом+модулем) цей внутрішній префікс лишається
// на початку message - живі логи це підтвердили. Системні/невідомі повідомлення
// (null) у фільтрованому вигляді не показуються взагалі - вони не стосуються
// жодного конкретного реле.
private fun classifyDevice(module: String, message: String): String? {
    val m = message.lowercase()
    return when (module) {
        "invertor-bast" -> when {
            m.contains("стан pv") -> "pv"
            m.contains("стан мережі") -> "grid"
            m.contains("стан акумулятора") -> "battery"
            // Мережа більше не має AUTO (переробка автоматики) - "мережа_авто
            // режим" ніколи не логується, лишаємо лише ручний "мережа режим".
            m.contains("мережа режим") -> "grid"
            // "навантаження_захист режим" (новий єдиний захисний контур:
            // перевантаження інвертора / розряд АКБ) не містив підрядка
            // "навантаження режим" (є "_захист" між словами) - події захисту
            // випадали з журналу картки "Навантаження". Перевіряємо саме
            // "навантаження" (без " режим"), щоб не залежати від точного
            // набору суфіксів (_авто/_захист/...) у назві пристрою - живі
            // логи (напр. "[РЕЖИМ] НАВАНТАЖЕННЯ режим: ...") підтвердили,
            // що це слово більше ніде в журналі інвертора не зустрічається.
            m.contains("навантаження") -> "load"
            // Загальний режим інвертора (QMOD: мережа/акумулятор/...) - той самий
            // показник, що вже є на картці "Мережа" (поле "режим").
            m.contains("зміна режиму") -> "grid"
            // Авто-перемикання пріоритету виходу (SBU/сонце) відкривається з
            // картки "Сонце" - туди й веде його журнал. "команда інвертору" -
            // сирий POP/PCP запис в EEPROM: POP міняє пріоритет ВИХОДУ (та сама
            // картка "Сонце"), PCP - пріоритет ЗАРЯДКИ (картка "АКБ").
            m.contains("авто-пріоритет") || m.contains("pop0") -> "pv"
            m.contains("pcp0") -> "battery"
            else -> null
        }
        "load_controller" -> when {
            m.contains("насос") -> "pump"
            m.contains("бойлер") -> "boiler1"
            else -> null
        }
        "garage" -> when {
            m.contains("світло") -> "light"
            // "ворота" (Н.в., напр. "ворота відчинені надто довго") і "воріт"
            // (Р.в., напр. "зміна стану воріт", "несправність датчиків воріт") -
            // різні відмінки того самого слова, з різним корінням літер: перевірка
            // лише на "ворот" не ловила жодної реальної події відчинення/закриття
            // воріт (вони логуються саме через appendDoorStateChangeLog() з
            // "зміна стану воріт"), тому картка "Ворота" завжди була порожньою.
            m.contains("ворот") || m.contains("воріт") -> "gate"
            m.contains("бойлер") -> "boiler2"
            else -> null
        }
        else -> null
    }
}

// GateCard на дашборді - це ОДНА картка й ОДНА кнопка журналу на ворота+світло
// разом (немає окремої картки/кнопки лише для світла), тож фільтр "gate"
// повинен показувати обидва - інакше події світла (класифікуються як "light")
// зникали з єдиного доступного для гаража журналу.
private fun matchesDeviceFilter(classified: String?, filter: String): Boolean =
    if (filter == "gate") classified == "gate" || classified == "light" else classified == filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    config: AppConfig,
    repository: StatusRepository,
    // Не null, коли екран відкрито з картки пристрою - показує лише події ЦЬОГО
    // реле (classifyDevice) і дає кнопку "назад". null - вкладка "Журнал", усі події.
    deviceFilter: String? = null,
    onBack: (() -> Unit)? = null,
) {
    var date by remember { mutableStateOf(todayIso()) }
    var allEntries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val entries = if (deviceFilter == null) {
        allEntries
    } else {
        allEntries.filter { matchesDeviceFilter(classifyDevice(it.module, it.message), deviceFilter) }
    }
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
                title = { Text(DEVICE_TITLES[deviceFilter] ?: "Журнал подій") },
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
