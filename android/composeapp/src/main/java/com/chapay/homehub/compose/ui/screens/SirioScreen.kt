package com.chapay.homehub.compose.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.SirioCatalog
import com.chapay.homehub.compose.data.SirioEventLogStore
import com.chapay.homehub.compose.data.SirioLogEvent
import com.chapay.homehub.compose.data.SirioPumpConfig
import com.chapay.homehub.compose.data.SirioStatus
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.data.estimateSirioFlowLpm
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.StatusAlert
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHighest
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Протокол J12 уміє лише "крок ±1" - немає команди "встановити рівно". Тому
// введення цифрами в полі - це не одна команда, а послідовність кроків до
// цілі, обмежена цією межею (щоб явна помилка введення не "закрутила" параметр
// надовго/нескінченно).
private const val CONVERGE_STEP_CAP = 120
private const val CONVERGE_STEP_DELAY_MS = 280L

/**
 * Повний доступ до частотника насоса Sirio (окрема прошивка sirio_esp32,
 * власний HTTP API модуля ESP8266 - не частина Budynok-прошивок). Відкривається
 * з тапу по насосу на схемі. Зроблено максимально подібним до рідної
 * веб-морди Sirio: окремі вкладки (Панель/Насос/Параметри/Журнал), введення
 * числом у полі замість лише ±, та клієнтський журнал подій (сам пристрій не
 * віддає журнал із часом - лише лічильники e0..e13).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SirioScreen(
    config: AppConfig,
    repository: StatusRepository,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<SirioStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var paramValues by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var busyParam by remember { mutableStateOf<Int?>(null) }
    var advUnlocked by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf(config.sirioAdvancedPin) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var infoIndex by remember { mutableStateOf<Int?>(null) }
    var errInfoCode by remember { mutableStateOf<Int?>(null) }
    var tab by remember { mutableStateOf(0) }
    var pump by remember { mutableStateOf<SirioPumpConfig?>(null) }
    var pumpLoading by remember { mutableStateOf(false) }
    var pumpSaveMsg by remember { mutableStateOf<String?>(null) }
    var logEvents by remember { mutableStateOf<List<SirioLogEvent>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(config) {
        while (true) {
            val s = runCatching { repository.fetchSirioStatus(config) }.getOrNull()
            if (s != null) {
                status = s
                errorText = null
            } else if (status == null) {
                errorText = "немає зв'язку з модулем Sirio (перевір адресу в налаштуваннях і мережу)"
            }
            loading = false
            delay(2000L)
        }
    }

    // Користувацькі параметри - послідовно (кожне читання ~1.7с, протокол не дозволяє паралельно).
    LaunchedEffect(config) {
        for (i in SirioCatalog.USER_IDS) {
            val v = runCatching { repository.readSirioParam(config, i, false) }.getOrNull()
            if (v != null) paramValues = paramValues + (i to v)
        }
    }

    LaunchedEffect(tab) {
        if (tab == 3) {
            while (tab == 3) {
                logEvents = SirioEventLogStore.recent(context)
                delay(3000L)
            }
        }
    }

    LaunchedEffect(tab, config) {
        if (tab == 1 && pump == null && !pumpLoading) {
            pumpLoading = true
            pump = runCatching { repository.fetchSirioPump(config) }.getOrNull()
            pumpLoading = false
        }
    }

    fun refreshStatusSoon() {
        scope.launch {
            delay(300)
            val s = runCatching { repository.fetchSirioStatus(config) }.getOrNull()
            if (s != null) status = s
        }
    }

    fun stepParam(index: Int, up: Boolean, advanced: Boolean) {
        scope.launch {
            busyParam = index
            val ok = runCatching { repository.stepSirioParam(config, index, up, advanced) }.getOrDefault(false)
            if (ok) {
                delay(1700)
                val v = runCatching { repository.readSirioParam(config, index, advanced) }.getOrNull()
                if (v != null) paramValues = paramValues + (index to v)
            }
            busyParam = null
        }
    }

    fun applyParam(index: Int, advanced: Boolean, targetRaw: Int) {
        scope.launch {
            busyParam = index
            var current = paramValues[index]
            if (current == null) {
                current = runCatching { repository.readSirioParam(config, index, advanced) }.getOrNull()
            }
            if (current != null) {
                var cur = current
                var steps = 0
                while (cur != targetRaw && steps < CONVERGE_STEP_CAP) {
                    val up = targetRaw > cur
                    val ok = runCatching { repository.stepSirioParam(config, index, up, advanced) }.getOrDefault(false)
                    if (!ok) break
                    cur += if (up) 1 else -1
                    steps++
                    delay(CONVERGE_STEP_DELAY_MS)
                }
                val v = runCatching { repository.readSirioParam(config, index, advanced) }.getOrNull()
                if (v != null) paramValues = paramValues + (index to v)
                val def = SirioCatalog.PARAMS[index]
                SirioEventLogStore.add(context, "${def?.name ?: "параметр $index"}: нове значення ${SirioCatalog.formatParamValue(index, v ?: cur)}")
            }
            busyParam = null
        }
    }

    fun loadAdvanced() {
        scope.launch {
            for (i in SirioCatalog.ADV_IDS) {
                val v = runCatching { repository.readSirioParam(config, i, true) }.getOrNull()
                if (v != null) paramValues = paramValues + (i to v)
            }
        }
    }

    val tabTitles = listOf("Панель", "Насос", "Параметри", "Журнал")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Насос Sirio (частотник)") },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                            }
                        }
                    },
                )
                TabRow(selectedTabIndex = tab) {
                    tabTitles.forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
                status?.let { SirioStickyMetricsBar(it) }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxWidth().padding(innerPadding)) {
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                status == null -> Text(errorText ?: "немає даних", color = TextMuted, modifier = Modifier.padding(16.dp))
                else -> {
                    val s = status!!
                    when (tab) {
                        0 -> SirioPanelTab(
                            status = s,
                            errorText = errorText,
                            busy = busy,
                            onToggleRun = {
                                scope.launch {
                                    busy = true
                                    runCatching { repository.toggleSirioRun(config) }
                                    delay(400)
                                    refreshStatusSoon()
                                    busy = false
                                }
                            },
                            onSetpointStep = { up -> stepParam(0, up, false) },
                            onSetpointApply = { target ->
                                val raw = SirioCatalog.rawForDisplayValue(0, target) ?: return@SirioPanelTab
                                applyParam(0, false, raw)
                            },
                            onErrInfo = { code -> errInfoCode = code },
                        )
                        1 -> SirioPumpTab(
                            pump = pump,
                            loading = pumpLoading,
                            saveMsg = pumpSaveMsg,
                            estimatedFlow = estimateSirioFlowLpm(pump, s.pressBar, s.freqHz.toDouble()),
                            onSave = { updated ->
                                scope.launch {
                                    pumpSaveMsg = null
                                    val ok = runCatching { repository.setSirioPump(config, updated) }.getOrDefault(false)
                                    pump = updated
                                    pumpSaveMsg = if (ok) "Збережено" else "Помилка збереження"
                                    if (ok) SirioEventLogStore.add(context, "Оновлено паспортну криву насоса")
                                }
                            },
                        )
                        2 -> SirioParamsTab(
                            paramValues = paramValues,
                            busyParam = busyParam,
                            advUnlocked = advUnlocked,
                            pinInput = pinInput,
                            loginError = loginError,
                            onPinChange = { pinInput = it },
                            onLogin = {
                                scope.launch {
                                    val ok = runCatching { repository.loginSirioAdvanced(config.copy(sirioAdvancedPin = pinInput)) }
                                        .getOrDefault(false)
                                    if (ok) {
                                        advUnlocked = true
                                        loginError = null
                                        loadAdvanced()
                                    } else {
                                        loginError = "Невірний PIN"
                                    }
                                }
                            },
                            onStep = { idx, up, adv -> stepParam(idx, up, adv) },
                            onApply = { idx, adv, target ->
                                val raw = SirioCatalog.rawForDisplayValue(idx, target)
                                if (raw != null) applyParam(idx, adv, raw)
                            },
                            onApplyEnum = { idx, adv, raw -> applyParam(idx, adv, raw) },
                            onInfo = { idx -> infoIndex = idx },
                        )
                        3 -> SirioLogTab(events = logEvents)
                    }
                }
            }
        }
    }

    infoIndex?.let { idx ->
        val def = SirioCatalog.PARAMS[idx]
        val info = SirioCatalog.INFO[idx]
        AlertDialog(
            onDismissRequest = { infoIndex = null },
            title = { Text("${SirioCatalog.menuCode(idx)} ${def?.name ?: ""}") },
            text = {
                Column {
                    Text(info?.description ?: "Опис відсутній.", color = TextPrimary)
                    if (!info?.default.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("За замовчуванням: ${info?.default}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { infoIndex = null }) { Text("Закрити") } },
        )
    }

    errInfoCode?.let { code ->
        AlertDialog(
            onDismissRequest = { errInfoCode = null },
            title = { Text("E$code — ${SirioCatalog.ERROR_NAMES[code] ?: "Невідома"}") },
            text = { Text(SirioCatalog.ERROR_DESC[code] ?: "Опис - у програмному мануалі.") },
            confirmButton = { TextButton(onClick = { errInfoCode = null }) { Text("Закрити") } },
        )
    }
}

// ---- Закріплена панель показників (видно на всіх вкладках, під TabRow) ----

@Composable
private fun SirioStickyMetricsBar(status: SirioStatus) {
    val pressureLive = status.pressBar > 0.0
    val pressureUnit = if (status.barOrPsi) "PSI" else "бар"
    val freqLive = status.freqHz > 0
    val voltLive = status.volt > 0

    Surface(color = SurfaceContainerHighest, contentColor = TextPrimary) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Жива поточна - НЕ підміняти уставкою/freqMax: конфігураційні
            // значення видавались за робочі, хоч ніяк з ними не пов'язані.
            StickyMetric(Icons.Filled.WaterDrop, if (pressureLive) "%.1f %s".format(status.pressBar, pressureUnit) else "—", "тиск (поточний)")
            StickyMetric(Icons.Filled.Bolt, "%.1f А".format(status.ampA), "струм")
            StickyMetric(Icons.Filled.Speed, if (freqLive) "${status.freqHz} Гц" else "—", "частота (вихідна)")
            StickyMetric(Icons.Filled.Power, if (voltLive) "${status.volt} В" else "—", "напруга")
        }
    }
}

@Composable
private fun StickyMetric(icon: ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AccentPump, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(value, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

// ---- Вкладка "Панель" ----

@Composable
private fun SirioPanelTab(
    status: SirioStatus,
    errorText: String?,
    busy: Boolean,
    onToggleRun: () -> Unit,
    onSetpointStep: (Boolean) -> Unit,
    onSetpointApply: (Double) -> Unit,
    onErrInfo: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SirioHeroCard(
                status = status,
                busy = busy,
                onToggleRun = onToggleRun,
                onSetpointStep = onSetpointStep,
                onSetpointApply = onSetpointApply,
            )
        }
        if (errorText != null) {
            item { Text(errorText, color = StatusAlert, style = MaterialTheme.typography.labelSmall) }
        }
        item { StatTileGrid(status) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("Лічильники помилок (E0-E13)")
                val total = (0..13).filter { it != 7 }.sumOf { status.errorCounts[it] ?: 0 }
                if (total > 0) {
                    Text("усього: $total", color = StatusAlert, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { ErrorCountsRow(status = status, onInfo = onErrInfo) }
    }
}

/**
 * Круговий індикатор тиску - заповнення дуги пропорційне ЖИВОМУ тиску
 * відносно уставки (не самій уставці - ціль і факт не підмінюють одне одного),
 * тонке зовнішнє кільце обертається, доки насос працює (пульсує червоним при
 * аварії). Поки прошивка Sirio не віддає живий тиск (press завжди 0 - TODO у
 * sirio_esp32), індикатор чесно показує "—", а не вигаданий вимір.
 */
@Composable
private fun PressureGauge(status: SirioStatus, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "sirio-gauge")
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(650, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "pulse",
    )
    val live = status.pressBar > 0.0
    val value = if (live) status.pressBar else 0.0
    val scaleMax = maxOf(status.setPointPressBar * 1.4, 3.0)
    val fraction = (value / scaleMax).coerceIn(0.0, 1.0).toFloat()
    val color = if (status.errorActive) StatusAlert else AccentPump

    Box(modifier = modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val radius = (size.minDimension / 2) - stroke
            val topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius)
            val arcSize = Size(radius * 2, radius * 2)
            drawArc(
                color = OutlineSubtle,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = 270f * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (status.motorOn || status.errorActive) {
                val ringRadius = radius + stroke * 0.6f
                val ringTopLeft = Offset(size.width / 2 - ringRadius, size.height / 2 - ringRadius)
                drawArc(
                    color = color.copy(alpha = if (status.errorActive) pulse else 0.8f),
                    startAngle = if (status.errorActive) spin * 2.4f else spin,
                    sweepAngle = if (status.errorActive) 40f else 70f,
                    useCenter = false,
                    topLeft = ringTopLeft,
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = Stroke(stroke * 0.35f, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (live) "%.1f".format(value) else "—",
                style = MaterialTheme.typography.displayMedium,
                color = if (live) TextPrimary else TextMuted,
            )
            Text(if (status.barOrPsi) "PSI" else "бар", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            Text(if (live) "тиск" else "немає даних", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = TextMuted,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SirioHeroCard(
    status: SirioStatus,
    busy: Boolean,
    onToggleRun: () -> Unit,
    onSetpointStep: (Boolean) -> Unit,
    onSetpointApply: (Double) -> Unit,
) {
    var setpointText by remember(status.setPointPress10) { mutableStateOf("%.1f".format(status.setPointPressBar)) }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainer)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val (label, color) = when {
                status.errorActive -> "⚠ Аварія E${status.errorNumber}" + (SirioCatalog.ERROR_NAMES[status.errorNumber]?.let { " — $it" } ?: "") to StatusAlert
                status.motorOn -> "● Працює" to AccentPump
                else -> "○ Стоп" to TextMuted
            }
            Text(label, color = color, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            PressureGauge(status = status)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = setpointText,
                    onValueChange = { setpointText = it },
                    label = { Text("Уставка, ${if (status.barOrPsi) "PSI" else "бар"}") },
                    singleLine = true,
                    modifier = Modifier.width(130.dp),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onSetpointStep(false) }, enabled = !busy) { Text("–") }
                IconButton(onClick = { onSetpointStep(true) }, enabled = !busy) { Text("+") }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = { setpointText.replace(',', '.').toDoubleOrNull()?.let(onSetpointApply) },
                    enabled = !busy,
                ) { Text("Застосувати") }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onToggleRun,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (status.errorActive) StatusAlert else if (status.motorOn) StatusAlert.copy(alpha = 0.85f) else AccentPump,
                ),
            ) {
                Text(
                    when {
                        status.errorActive -> "⟳ Скинути аварію"
                        status.motorOn -> "⏹ Стоп"
                        else -> "▶ Пуск"
                    },
                )
            }
        }
    }
}

@Composable
private fun StatTileGrid(status: SirioStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Icons.Filled.Bolt, "Струм", "%.1f / %.1f А".format(status.ampA, status.ampMaxA), Modifier.weight(1f))
            StatTile(Icons.Filled.Thermostat, "Темп. IGBT", "${status.tempIgbt}°C", Modifier.weight(1f))
            StatTile(Icons.Filled.Speed, "Частота (макс.)", "${status.freqMax} Гц", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Icons.Filled.Timelapse, "Напрацювання", "${status.hPowerRunning} г", Modifier.weight(1f))
            StatTile(Icons.Filled.Power, "Під живленням", "${status.hPowerOn} г", Modifier.weight(1f))
            StatTile(Icons.Filled.Replay, "Пусків", "${status.nrStarts}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainer), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AccentPump, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text(value, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            }
        }
    }
}

@Composable
private fun ErrorCountsRow(status: SirioStatus, onInfo: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            for (code in 0..13) {
                if (code == 7) continue // у прошивці Sirio E7 не використовується
                val count = status.errorCounts[code]
                val nonZero = (count ?: 0) > 0
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "E$code",
                            color = if (nonZero) StatusAlert else TextMuted,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(32.dp),
                        )
                        Text(
                            SirioCatalog.ERROR_NAMES[code] ?: "",
                            color = if (nonZero) TextPrimary else TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            count?.toString() ?: "–",
                            color = if (nonZero) StatusAlert else TextMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        IconButton(onClick = { onInfo(code) }, modifier = Modifier.height(28.dp)) {
                            Icon(Icons.Filled.Info, contentDescription = "Опис", tint = TextMuted, modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---- Вкладка "Насос" (паспортна крива + оцінка витрати) ----

@Composable
private fun SirioPumpTab(
    pump: SirioPumpConfig?,
    loading: Boolean,
    saveMsg: String?,
    estimatedFlow: Double?,
    onSave: (SirioPumpConfig) -> Unit,
) {
    if (loading && pump == null) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (pump == null) {
        Text("Немає даних про насос", color = TextMuted, modifier = Modifier.padding(16.dp))
        return
    }
    var depthText by remember(pump) { mutableStateOf(pump.depth.toString()) }
    var fnText by remember(pump) { mutableStateOf(pump.fn.toString()) }
    var points by remember(pump) { mutableStateOf(pump.points.map { it.first.toString() to it.second.toString() }) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Оцінка поточної витрати", style = MaterialTheme.typography.titleSmall, color = TextMuted)
                    Text(
                        estimatedFlow?.let { "%.1f л/хв".format(it) } ?: "недоступно (прошивка Sirio ще не віддає press/freq)",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (estimatedFlow != null) TextPrimary else TextMuted,
                    )
                }
            }
        }
        item { SectionHeader("Паспортні дані насоса") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = depthText,
                            onValueChange = { depthText = it },
                            label = { Text("Глибина, м") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = fnText,
                            onValueChange = { fnText = it },
                            label = { Text("fn, Гц") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        item { SectionHeader("Крива Q-H (витрата л/хв -> напір м)") }
        itemsIndexed(points) { i, (q, h) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = q,
                    onValueChange = { v -> points = points.toMutableList().also { it[i] = v to h } },
                    label = { Text("Q") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = h,
                    onValueChange = { v -> points = points.toMutableList().also { it[i] = q to v } },
                    label = { Text("H") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { points = points.toMutableList().also { it.removeAt(i) } }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Видалити точку", tint = TextMuted)
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { points = points + ("0" to "0") }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Додати точку")
                }
            }
        }
        item {
            Column {
                Button(
                    onClick = {
                        val depth = depthText.replace(',', '.').toDoubleOrNull() ?: pump.depth
                        val fn = fnText.replace(',', '.').toDoubleOrNull() ?: pump.fn
                        val parsedPoints = points.mapNotNull { (q, h) ->
                            val qd = q.replace(',', '.').toDoubleOrNull()
                            val hd = h.replace(',', '.').toDoubleOrNull()
                            if (qd != null && hd != null) qd to hd else null
                        }
                        onSave(SirioPumpConfig(depth, fn, pump.qunit, parsedPoints))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Зберегти криву насоса") }
                if (saveMsg != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(saveMsg, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---- Вкладка "Параметри" ----

@Composable
private fun SirioParamsTab(
    paramValues: Map<Int, Int>,
    busyParam: Int?,
    advUnlocked: Boolean,
    pinInput: String,
    loginError: String?,
    onPinChange: (String) -> Unit,
    onLogin: () -> Unit,
    onStep: (Int, Boolean, Boolean) -> Unit,
    onApply: (Int, Boolean, Double) -> Unit,
    onApplyEnum: (Int, Boolean, Int) -> Unit,
    onInfo: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("Користувацькі параметри") }
        items(SirioCatalog.USER_IDS) { idx ->
            SirioParamRow(
                index = idx,
                raw = paramValues[idx],
                busy = busyParam == idx,
                onStep = { up -> onStep(idx, up, false) },
                onApply = { target -> onApply(idx, false, target) },
                onApplyEnum = { raw -> onApplyEnum(idx, false, raw) },
                onInfo = { onInfo(idx) },
            )
        }
        item { SectionHeader("Розширені параметри") }
        if (!advUnlocked) {
            item {
                AdvancedLoginRow(
                    pin = pinInput,
                    onPinChange = onPinChange,
                    error = loginError,
                    onLogin = onLogin,
                )
            }
        } else {
            items(SirioCatalog.ADV_IDS) { idx ->
                SirioParamRow(
                    index = idx,
                    raw = paramValues[idx],
                    busy = busyParam == idx,
                    onStep = { up -> onStep(idx, up, true) },
                    onApply = { target -> onApply(idx, true, target) },
                    onApplyEnum = { raw -> onApplyEnum(idx, true, raw) },
                    onInfo = { onInfo(idx) },
                )
            }
        }
    }
}

@Composable
private fun SirioParamRow(
    index: Int,
    raw: Int?,
    busy: Boolean,
    onStep: (Boolean) -> Unit,
    onApply: (Double) -> Unit,
    onApplyEnum: (Int) -> Unit,
    onInfo: () -> Unit,
) {
    val def = SirioCatalog.PARAMS[index]
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SirioCatalog.menuCode(index),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.width(36.dp),
                )
                Text(def?.name ?: "параметр $index", style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = onInfo, modifier = Modifier.height(24.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = "Опис", tint = TextMuted, modifier = Modifier.height(14.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            if (def?.enumValues != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    def.enumValues.forEach { (optRaw, label) ->
                        FilterChip(
                            selected = raw == optRaw,
                            onClick = { if (raw != null) onApplyEnum(optRaw) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            enabled = !busy && raw != null,
                        )
                    }
                }
            } else {
                var text by remember(raw) {
                    mutableStateOf(raw?.let { SirioCatalog.formatParamValue(index, it) } ?: "")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        suffix = def?.unit?.let { { Text(it) } },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { onStep(false) }, enabled = !busy && raw != null) { Text("–") }
                    IconButton(onClick = { onStep(true) }, enabled = !busy && raw != null) { Text("+") }
                    TextButton(
                        onClick = { text.replace(',', '.').toDoubleOrNull()?.let(onApply) },
                        enabled = !busy && raw != null,
                    ) { Text("OK") }
                }
            }
            if (busy) {
                Spacer(Modifier.height(2.dp))
                Text("виконується…", color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AdvancedLoginRow(
    pin: String,
    onPinChange: (String) -> Unit,
    error: String?,
    onLogin: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = TextMuted, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Потрібен PIN для розширених параметрів", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = onPinChange,
                    label = { Text("PIN") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onLogin) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Вхід")
                }
            }
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(error, color = StatusAlert, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---- Вкладка "Журнал" ----

@Composable
private fun SirioLogTab(events: List<SirioLogEvent>) {
    if (events.isEmpty()) {
        Text("Журнал порожній - події з'являться тут (пуск/стоп, аварії, зміни параметрів).", color = TextMuted, modifier = Modifier.padding(16.dp))
        return
    }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(events) { e ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    fmt.format(java.util.Date(e.tsMs)),
                    color = TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(110.dp),
                )
                Text(
                    e.text,
                    color = if (e.bad) StatusAlert else TextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider(color = OutlineSubtle)
        }
    }
}
