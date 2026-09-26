package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.data.UnifiedStatus
import com.chapay.homehub.compose.ui.components.ChartToolbarActions
import com.chapay.homehub.compose.ui.components.ChartViewport
import com.chapay.homehub.compose.ui.components.LockLandscapeOrientation
import com.chapay.homehub.compose.ui.components.NavGranularity
import com.chapay.homehub.compose.ui.components.PeriodNavHeader
import com.chapay.homehub.compose.ui.components.VisibilityToggle
import com.chapay.homehub.compose.ui.components.chartGestures
import com.chapay.homehub.compose.ui.components.rememberChartViewport
import com.chapay.homehub.compose.ui.components.todayIso
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentLoad
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private enum class EnergyPeriod(val label: String, val granularity: NavGranularity) {
    DAY("Доба", NavGranularity.DAY),
    MONTH("Місяць", NavGranularity.MONTH),
    YEAR("Рік", NavGranularity.YEAR),
}

// Точка графіка. x - позиція на осі періоду: хвилина початку інтервалу (доба),
// день місяця - 1 (місяць) або номер місяця - 1 (рік). Значення: середня
// потужність, Вт (доба), або енергія, кВт·год (місяць/рік).
private data class ChartPoint(val x: Int, val pv: Double, val home: Double, val grid: Double)

private data class Series(val label: String, val color: Color, val values: List<Double>)

// Що потрібно графіку від осі X залежно від періоду.
private class XAxis(
    val domainMax: Float,
    val pointWidth: Float,
    // Найбільша прогалина (в одиницях осі), яку лінія перекриває пунктиром: пропущені
    // точки - збій запису/зв'язку, а не справжній розрив. Більші прогалини - розрив.
    val maxBridge: Float,
    val minSpan: Float,
    val tickSteps: IntArray,
    val maxTicks: Float,
    val tickLabel: (Int) -> String,
    val pointTitle: (Int) -> String,
    // Вісь Y - енергія в кВт·год (дробові підписи), а не потужність у Вт.
    val energyUnits: Boolean,
) {
    val unit: String get() = if (energyUnits) "кВт·год" else "Вт"
}

private val MONTH_SHORT = listOf("Січ", "Лют", "Бер", "Кві", "Тра", "Чер", "Лип", "Сер", "Вер", "Жов", "Лис", "Гру")
private val MONTH_FULL = listOf(
    "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
    "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень",
)

// Кроки підписів осі X доби (хв).
private val MINUTE_TICK_STEPS = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 180)

private fun hhmm(minute: Int): String = "%d:%02d".format(minute / 60, minute % 60)

private fun daysIn(year: Int, month: Int): Int {
    val cal = java.util.Calendar.getInstance()
    cal.set(year, month - 1, 1)
    return cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
}

private fun buildAxis(period: EnergyPeriod, stepMinutes: Int, anchor: String): XAxis = when (period) {
    EnergyPeriod.DAY -> XAxis(
        domainMax = (1440 - stepMinutes).coerceAtLeast(1).toFloat(),
        pointWidth = stepMinutes.toFloat(),
        maxBridge = stepMinutes * 6f,
        minSpan = (stepMinutes * 2).toFloat(),
        tickSteps = MINUTE_TICK_STEPS,
        maxTicks = 8f,
        tickLabel = { hhmm(it) },
        pointTitle = { "${hhmm(it)}-${hhmm(it + stepMinutes)}" },
        energyUnits = false,
    )
    EnergyPeriod.MONTH -> {
        val year = anchor.take(4).toIntOrNull() ?: 2000
        val month = anchor.substring(5, 7).toIntOrNull() ?: 1
        XAxis(
            domainMax = (daysIn(year, month) - 1).coerceAtLeast(1).toFloat(),
            pointWidth = 1f,
            maxBridge = 3f,
            minSpan = 4f,
            tickSteps = intArrayOf(1, 2, 3, 5, 7),
            maxTicks = 12f,
            tickLabel = { (it + 1).toString() },
            pointTitle = { "%d.%02d.%d".format(it + 1, month, year) },
            energyUnits = true,
        )
    }
    EnergyPeriod.YEAR -> XAxis(
        domainMax = 11f,
        pointWidth = 1f,
        maxBridge = 1f,
        minSpan = 3f,
        tickSteps = intArrayOf(1),
        maxTicks = 12f,
        tickLabel = { MONTH_SHORT.getOrElse(it) { "" } },
        pointTitle = { "${MONTH_FULL.getOrElse(it) { "" }} ${anchor.take(4)}" },
        energyUnits = true,
    )
}

private class LoadedPeriod(val points: List<ChartPoint>, val stepMinutes: Int, val totalsWh: Triple<Double, Double, Double>)

// /api/daily: 15-хв слоти (pv_w/home_w/grid_w) або, для старої прошивки, годинні Вт·год.
private fun parseDay(payload: JSONObject): LoadedPeriod {
    val slotsArr = payload.optJSONArray("slots")
    val parsed = mutableListOf<ChartPoint>()
    var step = 60
    if (slotsArr != null && slotsArr.length() > 0) {
        for (i in 0 until slotsArr.length()) {
            val row = slotsArr.optJSONObject(i) ?: continue
            parsed.add(
                ChartPoint(
                    x = row.optInt("m", i * 15),
                    pv = row.optDouble("pv_w", 0.0),
                    home = row.optDouble("home_w", 0.0),
                    grid = row.optDouble("grid_w", 0.0),
                ),
            )
        }
        step = 15
    } else {
        val hoursArr = payload.optJSONArray("hours")
        if (hoursArr != null) {
            for (i in 0 until hoursArr.length()) {
                val row = hoursArr.optJSONObject(i) ?: continue
                parsed.add(
                    ChartPoint(
                        x = row.optInt("hour", i) * 60,
                        pv = row.optDouble("pv", 0.0),
                        home = row.optDouble("home", 0.0),
                        grid = row.optDouble("grid", 0.0),
                    ),
                )
            }
        }
    }
    val t = payload.optJSONObject("totals")
    return LoadedPeriod(
        points = parsed,
        stepMinutes = step,
        totalsWh = Triple(
            t?.optDouble("dailyPV", 0.0) ?: 0.0,
            t?.optDouble("dailyHome", 0.0) ?: 0.0,
            t?.optDouble("dailyGrid", 0.0) ?: 0.0,
        ),
    )
}

// /api/monthly: {"month","days":[{"day","pv","home","grid",...}]} у Вт·год за добу, плюс
// архів на телефоні (закриті доби, які модуль міг уже видалити зі своєї пам'яті). Для доби
// беремо архів, а якщо його немає - те, що віддав модуль. Доба без жодного виміру (усі
// нулі) - "немає даних", а не "нуль": точку пропускаємо.
private fun parseMonth(
    payload: JSONObject?,
    archive: Map<String, Triple<Double, Double, Double>>,
    month: String,
): LoadedPeriod {
    val perDay = sortedMapOf<Int, Triple<Double, Double, Double>>()
    val days = payload?.optJSONArray("days")
    if (days != null) {
        for (i in 0 until days.length()) {
            val row = days.optJSONObject(i) ?: continue
            perDay[row.optInt("day", i + 1)] = Triple(row.optDouble("pv", 0.0), row.optDouble("home", 0.0), row.optDouble("grid", 0.0))
        }
    }
    archive.forEach { (date, t) ->
        if (date.startsWith(month) && date.length == 10) perDay[date.substring(8, 10).toIntOrNull() ?: return@forEach] = t
    }
    val points = mutableListOf<ChartPoint>()
    var pv = 0.0
    var home = 0.0
    var grid = 0.0
    perDay.forEach { (d, t) ->
        if (t.first == 0.0 && t.second == 0.0 && t.third == 0.0) return@forEach
        points.add(ChartPoint(d - 1, t.first / 1000.0, t.second / 1000.0, t.third / 1000.0))
        pv += t.first
        home += t.second
        grid += t.third
    }
    return LoadedPeriod(points, 0, Triple(pv, home, grid))
}

// /api/yearly: масиви з 12 місячних сум (Вт·год) і data_count (доби з даними), плюс архів на
// телефоні. Для місяця беремо те джерело, що покриває більше діб: після чистки модуль має
// менше днів у старих місяцях, ніж архів.
private fun parseYear(
    payload: JSONObject?,
    archive: Map<String, Triple<Double, Double, Double>>,
    year: String,
): LoadedPeriod {
    val pvArr = payload?.optJSONArray("pv")
    val homeArr = payload?.optJSONArray("home")
    val gridArr = payload?.optJSONArray("grid")
    val counts = payload?.optJSONArray("data_count")
    val points = mutableListOf<ChartPoint>()
    var pv = 0.0
    var home = 0.0
    var grid = 0.0
    for (i in 0 until 12) {
        val prefix = "%s-%02d".format(year, i + 1)
        val archiveDays = archive.filterKeys { it.startsWith(prefix) }.values
            .filter { it.first != 0.0 || it.second != 0.0 || it.third != 0.0 }
        val deviceCount = counts?.optInt(i, 0) ?: 0
        val p: Double
        val h: Double
        val g: Double
        if (archiveDays.isNotEmpty() && archiveDays.size >= deviceCount) {
            p = archiveDays.sumOf { it.first }
            h = archiveDays.sumOf { it.second }
            g = archiveDays.sumOf { it.third }
        } else {
            p = pvArr?.optDouble(i, 0.0) ?: 0.0
            h = homeArr?.optDouble(i, 0.0) ?: 0.0
            g = gridArr?.optDouble(i, 0.0) ?: 0.0
            if (deviceCount == 0 && p == 0.0 && h == 0.0 && g == 0.0) continue
        }
        points.add(ChartPoint(i, p / 1000.0, h / 1000.0, g / 1000.0))
        pv += p
        home += h
        grid += g
    }
    return LoadedPeriod(points, 0, Triple(pv, home, grid))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyChartScreen(
    config: AppConfig,
    repository: StatusRepository,
    status: UnifiedStatus,
    liveTotals: Triple<Double, Double, Double>?,
    onBack: () -> Unit,
) {
    LockLandscapeOrientation()
    var period by rememberSaveable { mutableStateOf(EnergyPeriod.DAY) }
    // Опорна дата "YYYY-MM-DD": для місяця/року береться її початок, тож період
    // не губиться при перемиканні Доба/Місяць/Рік.
    var anchor by rememberSaveable { mutableStateOf(todayIso()) }
    var points by remember { mutableStateOf<List<ChartPoint>>(emptyList()) }
    var stepMinutes by remember { mutableStateOf(60) }
    // Підсумки завантаженого періоду, Вт·год.
    var savedTotals by remember { mutableStateOf(Triple(0.0, 0.0, 0.0)) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // Вид: лінії або кругова діаграма розподілу.
    var pieMode by rememberSaveable { mutableStateOf(false) }
    // Які лінії показувати: сонце / навантаження / мережа - можна ховати окремо, щоб
    // роздивитись одну з них.
    var showPv by rememberSaveable { mutableStateOf(true) }
    var showHome by rememberSaveable { mutableStateOf(true) }
    var showGrid by rememberSaveable { mutableStateOf(true) }
    // Вертикальний курсор із підказкою (у одиницях осі X): не наближено - веде палець, наближено - дотик.
    var cursorX by remember { mutableStateOf<Float?>(null) }

    val periodKey = when (period) {
        EnergyPeriod.DAY -> anchor
        EnergyPeriod.MONTH -> anchor.take(7)
        EnergyPeriod.YEAR -> anchor.take(4)
    }

    // Вкладки нижньої навігації лишаються живими в пам'яті (saveState/
    // restoreState), тож одноразовий LaunchedEffect не оновлював би дані при
    // поверненні на той самий період - цикл з оновленням (доба - 30с, місяць/рік -
    // 2 хв: інвертор рахує їх скануванням файлів 8-20 с). Спінер - лише на першому
    // завантаженні цього (період, конфіг).
    LaunchedEffect(period, periodKey, config) {
        // Новий період/конфіг - старі точки чужого періоду не показуємо.
        points = emptyList()
        savedTotals = Triple(0.0, 0.0, 0.0)
        errorText = null
        cursorX = null

        // Розбір відповіді модуля разом з архівом на телефоні (для місяця/року).
        suspend fun buildLoaded(payload: JSONObject?): LoadedPeriod? {
            val archive = if (period == EnergyPeriod.DAY) {
                emptyMap()
            } else {
                runCatching { repository.archivedDailyTotals(config) }.getOrDefault(emptyMap())
            }
            return when (period) {
                EnergyPeriod.DAY -> payload?.let { parseDay(it) }
                EnergyPeriod.MONTH -> parseMonth(payload, archive, periodKey).takeIf { payload != null || it.points.isNotEmpty() }
                EnergyPeriod.YEAR -> parseYear(payload, archive, periodKey).takeIf { payload != null || it.points.isNotEmpty() }
            }
        }

        fun show(loaded: LoadedPeriod) {
            points = loaded.points
            if (period == EnergyPeriod.DAY) stepMinutes = loaded.stepMinutes
            savedTotals = loaded.totalsWh
        }

        // Що вже є на телефоні (кеш + архів) - показуємо одразу, не чекаючи повільний модуль
        // (місяць 8 с, рік ~20 с); мережа лише оновлює.
        val cached = runCatching {
            when (period) {
                EnergyPeriod.DAY -> repository.peekInverterDaily(config, periodKey)
                EnergyPeriod.MONTH -> repository.peekInverterMonthly(config, periodKey)
                EnergyPeriod.YEAR -> repository.peekInverterYearly(config, periodKey.toIntOrNull() ?: 0)
            }
        }.getOrNull()
        val first = buildLoaded(cached)
        if (first != null) {
            show(first)
            loading = false
        }
        var firstLoad = first == null
        while (true) {
            if (firstLoad) loading = true
            val payload = runCatching {
                when (period) {
                    EnergyPeriod.DAY -> repository.fetchInverterDaily(config, periodKey)
                    EnergyPeriod.MONTH -> repository.fetchInverterMonthly(config, periodKey)
                    EnergyPeriod.YEAR -> repository.fetchInverterYearly(config, periodKey.toIntOrNull() ?: 0)
                }
            }.getOrNull()
            val loaded = buildLoaded(payload)
            if (loaded == null) {
                // Тимчасовий збій зв'язку (лагає ZeroTier) не стирає вже показаний
                // графік - помилка лише якщо показувати ще нічого.
                if (points.isEmpty()) {
                    errorText = "немає даних (модуль інвертора недоступний або дані за цей період відсутні)"
                }
            } else {
                errorText = null
                show(loaded)
            }
            loading = false
            firstLoad = false
            // Коли даних ще нема - повторюємо швидко (для повільних місяця/року - через 15с).
            val fast = loaded == null && points.isEmpty()
            delay(
                when {
                    period == EnergyPeriod.DAY -> if (fast) 5_000L else 30_000L
                    else -> if (fast) 15_000L else 120_000L
                },
            )
        }
    }

    val isToday = anchor == todayIso()
    // Історія (/api/daily) містить лише години, які прошивка вже встигла
    // записати у файл; лічильники "за добу" з /api/status - завжди живі.
    // Для сьогоднішньої доби в підсумках показуємо живі, щоб вони збігались із
    // дашбордом, а розбіжність з історією виносимо в підказку під графіком.
    val dayLive = period == EnergyPeriod.DAY && isToday && liveTotals != null
    val shownTotals = if (dayLive) liveTotals!! else savedTotals
    val unsavedHomeWh = if (dayLive) liveTotals!!.second - savedTotals.second else 0.0

    val axis = buildAxis(period, stepMinutes, anchor)
    val viewport = rememberChartViewport(period to periodKey, 0f, axis.domainMax, axis.minSpan)

    fun totalText(wh: Double) =
        if (period == EnergyPeriod.DAY) "${wh.roundToInt()} Вт·год" else "%.1f кВт·год".format(wh / 1000.0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Графік енергії") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (!pieMode) ChartToolbarActions(viewport)
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeriodNavHeader(
                    anchor = anchor,
                    granularity = period.granularity,
                    onChange = { anchor = it },
                    modifier = Modifier.weight(1f),
                )
                SingleChoiceSegmentedButtonRow {
                    EnergyPeriod.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = period == item,
                            onClick = { period = item },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = EnergyPeriod.entries.size),
                        ) { Text(item.label, maxLines = 1) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !pieMode,
                        onClick = { pieMode = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Лінії", maxLines = 1) }
                    SegmentedButton(
                        selected = pieMode,
                        onClick = { pieMode = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Діаграма", maxLines = 1) }
                }
            }
            if (pieMode) {
                EnergyPieSection(
                    status = status,
                    savedTotals = savedTotals,
                    isToday = period == EnergyPeriod.DAY && isToday,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                    dayMode = period == EnergyPeriod.DAY,
                    periodLabel = when (period) {
                        EnergyPeriod.DAY -> "За добу"
                        EnergyPeriod.MONTH -> "За місяць"
                        EnergyPeriod.YEAR -> "За рік"
                    },
                )
            } else {
                SummaryLegendRow(
                    totals = listOf(totalText(shownTotals.first), totalText(shownTotals.second), totalText(shownTotals.third)),
                    unitNote = when (period) {
                        EnergyPeriod.DAY -> if (stepMinutes == 15) "Вт, середня за 15 хв" else "Вт, середня за годину"
                        EnergyPeriod.MONTH -> "кВт·год за добу"
                        EnergyPeriod.YEAR -> "кВт·год за місяць"
                    },
                    visible = listOf(showPv, showHome, showGrid),
                    onToggle = { index ->
                        when (index) {
                            0 -> showPv = !showPv
                            1 -> showHome = !showHome
                            else -> showGrid = !showGrid
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    errorText != null -> Text(errorText.orEmpty(), color = TextMuted, modifier = Modifier.padding(16.dp))
                    else -> {
                        EnergyLineChart(
                            points = points,
                            axis = axis,
                            viewport = viewport,
                            visible = listOf(showPv, showHome, showGrid),
                            cursorX = cursorX,
                            onCursor = { cursorX = it },
                            emptyText = when (period) {
                                EnergyPeriod.DAY -> "немає даних за цю добу"
                                EnergyPeriod.MONTH -> "немає даних за цей місяць"
                                EnergyPeriod.YEAR -> "немає даних за цей рік"
                            },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        if (unsavedHomeWh > 50.0) {
                            Text(
                                text = "В історію записано ${savedTotals.second.roundToInt()} з " +
                                    "${shownTotals.second.roundToInt()} Вт·год (навантаження): решта - поточна година " +
                                    "або ще не записана прошивкою.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Легенда, підсумки періоду й перемикачі видимості в одному рядку: "око" + назва +
 * значення. Натискання ховає/показує відповідну лінію графіка.
 */
@Composable
private fun SummaryLegendRow(
    totals: List<String>,
    unitNote: String,
    visible: List<Boolean>,
    onToggle: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VisibilityToggle("сонце", AccentPv, visible[0], { onToggle(0) }, detail = totals[0])
        VisibilityToggle("навантаження", AccentLoad, visible[1], { onToggle(1) }, detail = totals[1])
        VisibilityToggle("мережа", AccentGrid, visible[2], { onToggle(2) }, detail = totals[2])
        Spacer(Modifier.weight(1f))
        Text(unitNote, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

/** "Гарна" верхня межа осі Y: 1/2/2.5/5 x 10^k, поділена на 4 інтервали. */
private fun niceAxisMax(rawMax: Double): Double {
    if (rawMax <= 0.0) return 1.0
    val exp = 10.0.pow(kotlin.math.floor(log10(rawMax / 4.0)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * exp }.first { it * 4.0 >= rawMax }
    return step * 4.0
}

@Composable
private fun EnergyLineChart(
    points: List<ChartPoint>,
    axis: XAxis,
    viewport: ChartViewport,
    visible: List<Boolean>,
    cursorX: Float?,
    onCursor: (Float?) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        Text(emptyText, color = TextMuted, modifier = modifier.padding(16.dp))
        return
    }

    val series = listOf(
        Series("сонце", AccentPv, points.map { it.pv }),
        Series("навантаження", AccentLoad, points.map { it.home }),
        Series("мережа", AccentGrid, points.map { it.grid }),
    ).filterIndexed { i, _ -> visible.getOrElse(i) { true } }
    if (series.isEmpty()) {
        Text("усі лінії приховані - натисніть «око» біля назви, щоб показати", color = TextMuted, modifier = modifier.padding(16.dp))
        return
    }

    val density = LocalDensity.current
    val leftMarginPx = with(density) { 44.dp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Розмір полотна читаємо в момент виклику (жести живуть довше за кадр).
    fun plotWidthPx() = (canvasSize.width - leftMarginPx).coerceAtLeast(1f)
    fun xValueAt(px: Float): Float =
        viewport.start + ((px - leftMarginPx) / plotWidthPx()).coerceIn(0f, 1f) * viewport.span

    val axisPaint = remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#9BA3B4")
            textSize = with(density) { 11.sp.toPx() }
        }
    }
    val tipTitlePaint = remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#F0F2F8")
            textSize = with(density) { 12.sp.toPx() }
            isFakeBoldText = true
        }
    }
    val tipTextPaint = remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#F0F2F8")
            textSize = with(density) { 12.sp.toPx() }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        modifier = modifier,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                .onSizeChanged { canvasSize = it }
                .chartGestures(
                    key = Unit,
                    dragMovesCursor = !viewport.isZoomed,
                    onCursor = { onCursor(xValueAt(it.x)) },
                    onTap = { onCursor(xValueAt(it.x)) },
                    onPan = { dx -> viewport.panFraction(-dx / plotWidthPx()) },
                    onZoom = { cx, factor ->
                        viewport.zoomAround(((cx - leftMarginPx) / plotWidthPx()).coerceIn(0f, 1f), factor)
                    },
                ),
        ) {
            val plotLeft = leftMarginPx
            val bottomMargin = 22.dp.toPx()
            val plotTop = 6.dp.toPx()
            val plotW = size.width - plotLeft
            val plotH = size.height - plotTop - bottomMargin
            val v0 = viewport.start
            val vSpan = viewport.span

            // Вісь Y підганяється під видиме вікно (з сусідніми точками, щоб лінії
            // на краях не обрізались), тож при наближенні дрібні значення читаються.
            val visibleIdx = points.indices.filter {
                points[it].x + axis.pointWidth >= v0 && points[it].x <= v0 + vSpan
            }.ifEmpty { points.indices.toList() }
            val axisMax = niceAxisMax(series.maxOf { s -> visibleIdx.maxOfOrNull { s.values[it] } ?: 0.0 })

            fun xAt(x: Float) = plotLeft + (x - v0) / vSpan * plotW
            fun yAt(v: Double) = plotTop + plotH - (v / axisMax * plotH).toFloat().coerceIn(0f, plotH)

            // Сітка й підписи осі Y - тихі, не змагаються з даними.
            val gridStroke = 1.dp.toPx()
            for (i in 0..4) {
                val value = axisMax * i / 4.0
                val y = yAt(value)
                drawLine(OutlineSubtle, Offset(plotLeft, y), Offset(size.width, y), strokeWidth = gridStroke)
                val label = if (axis.energyUnits && axisMax < 20.0) "%.1f".format(value) else value.roundToInt().toString()
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    plotLeft - 6.dp.toPx() - axisPaint.measureText(label),
                    y + axisPaint.textSize / 3f,
                    axisPaint,
                )
            }

            clipRect(left = plotLeft, top = 0f, right = size.width, bottom = size.height) {
                // Підписи осі X - крок залежить від масштабу.
                val step = axis.tickSteps.firstOrNull { vSpan / it <= axis.maxTicks } ?: axis.tickSteps.last()
                var tick = (ceil(v0 / step) * step).toInt()
                while (tick <= v0 + vSpan) {
                    val x = xAt(tick.toFloat())
                    val label = axis.tickLabel(tick)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        x - axisPaint.measureText(label) / 2f,
                        size.height - 4.dp.toPx(),
                        axisPaint,
                    )
                    tick += step
                }

                // Лінії: тонкі (2dp). Сусідні точки з'єднуємо суцільно; коротку прогалину
                // (збій запису/зв'язку - до axis.maxBridge) перекриваємо пунктиром, щоб
                // лінія не "рвалась", але було видно, що там даних немає; довшу - розрив.
                val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val bridgeStroke = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Butt,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
                    ),
                )
                series.forEach { s ->
                    val solid = Path()
                    val bridge = Path()
                    s.values.forEachIndexed { i, v ->
                        val x = xAt(points[i].x.toFloat())
                        val y = yAt(v)
                        val gap = if (i > 0) points[i].x - points[i - 1].x else Int.MAX_VALUE
                        when {
                            i > 0 && gap <= axis.pointWidth * 3 / 2 -> solid.lineTo(x, y)
                            i > 0 && gap <= axis.maxBridge -> {
                                bridge.moveTo(xAt(points[i - 1].x.toFloat()), yAt(s.values[i - 1]))
                                bridge.lineTo(x, y)
                                solid.moveTo(x, y)
                            }
                            else -> solid.moveTo(x, y)
                        }
                    }
                    drawPath(solid, s.color, style = lineStroke)
                    drawPath(bridge, s.color.copy(alpha = 0.55f), style = bridgeStroke)
                }

                // Маркери лише коли видимих точок небагато, інакше зливаються. Малі (4/3dp) -
                // лінії лишаються головними.
                if (visibleIdx.size <= 30) {
                    val markerR = (4f / 3f).dp.toPx()
                    val ring = (2f / 3f).dp.toPx()
                    series.forEach { s ->
                        visibleIdx.forEach { i ->
                            val v = s.values[i]
                            if (v > 0.0) {
                                val c = Offset(xAt(points[i].x.toFloat()), yAt(v))
                                drawCircle(SurfaceContainer, radius = markerR + ring, center = c)
                                drawCircle(s.color, radius = markerR, center = c)
                            }
                        }
                    }
                }
            }

            // Лінія спостереження й підказка для найближчої до неї точки.
            val idx = cursorX?.let { cx -> points.indices.minByOrNull { abs(points[it].x - cx) } }
            if (idx != null) {
                val p = points[idx]
                val x = xAt(p.x.toFloat())
                if (x in plotLeft..size.width) {
                    val markerR = (4f / 3f).dp.toPx()
                    val ring = (2f / 3f).dp.toPx()
                    drawLine(
                        TextMuted.copy(alpha = 0.6f),
                        Offset(x, plotTop),
                        Offset(x, plotTop + plotH),
                        strokeWidth = 1.dp.toPx(),
                    )
                    series.forEach { s ->
                        val cy = yAt(s.values[idx])
                        drawCircle(SurfaceContainer, radius = markerR + ring, center = Offset(x, cy))
                        drawCircle(s.color, radius = markerR, center = Offset(x, cy))
                    }

                    val title = axis.pointTitle(p.x)
                    val rows = series.map {
                        val v = it.values[idx]
                        val text = if (axis.energyUnits) "%.1f".format(v) else v.roundToInt().toString()
                        "${it.label}: $text ${axis.unit}"
                    }
                    val pad = 8.dp.toPx()
                    val lineH = tipTextPaint.textSize * 1.35f
                    val boxW = (rows + title).maxOf { tipTextPaint.measureText(it) } + pad * 2 + 14.dp.toPx()
                    val boxH = pad * 2 + lineH * (rows.size + 1)
                    val boxX = if (x + 12.dp.toPx() + boxW > size.width) x - 12.dp.toPx() - boxW else x + 12.dp.toPx()
                    val boxY = plotTop + 4.dp.toPx()
                    drawRoundRect(
                        color = SurfaceContainerHigh,
                        topLeft = Offset(boxX, boxY),
                        size = Size(boxW, boxH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    )
                    drawContext.canvas.nativeCanvas.drawText(title, boxX + pad, boxY + pad + lineH * 0.8f, tipTitlePaint)
                    rows.forEachIndexed { r, text ->
                        val ty = boxY + pad + lineH * (r + 1) + lineH * 0.8f
                        drawCircle(series[r].color, 4.dp.toPx(), Offset(boxX + pad + 4.dp.toPx(), ty - tipTextPaint.textSize / 3f))
                        drawContext.canvas.nativeCanvas.drawText(text, boxX + pad + 14.dp.toPx(), ty, tipTextPaint)
                    }
                }
            }
        }
    }
}
