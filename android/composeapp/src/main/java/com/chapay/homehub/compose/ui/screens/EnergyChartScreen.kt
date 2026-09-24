package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.chapay.homehub.compose.ui.components.DateNavHeader
import com.chapay.homehub.compose.ui.components.LockLandscapeOrientation
import com.chapay.homehub.compose.ui.components.VisibilityToggle
import com.chapay.homehub.compose.ui.components.chartGestures
import com.chapay.homehub.compose.ui.components.rememberChartViewport
import com.chapay.homehub.compose.ui.components.todayIso
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// Точка графіка: середня потужність (Вт) за інтервал, що починається з minute.
// Для 15-хвилинних слотів це pv_w/home_w/grid_w; для старої прошивки без слотів
// - годинні Вт·год (числово рівні середній потужності за годину).
private data class ChartPoint(val minute: Int, val pv: Double, val home: Double, val grid: Double)

private data class Series(val label: String, val color: Color, val values: List<Double>)

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
    var date by remember { mutableStateOf(todayIso()) }
    var points by remember { mutableStateOf<List<ChartPoint>>(emptyList()) }
    var stepMinutes by remember { mutableStateOf(60) }
    var savedTotals by remember { mutableStateOf(Triple(0.0, 0.0, 0.0)) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // Вид: лінії потужності або кругова діаграма розподілу.
    var pieMode by rememberSaveable { mutableStateOf(false) }
    // Які лінії показувати: сонце / будинок / мережа - можна ховати окремо, щоб
    // роздивитись одну з них.
    var showPv by rememberSaveable { mutableStateOf(true) }
    var showHome by rememberSaveable { mutableStateOf(true) }
    var showGrid by rememberSaveable { mutableStateOf(true) }
    // Вертикальний курсор із підказкою: не наближено - веде палець, наближено - дотик.
    var cursorMinute by remember { mutableStateOf<Float?>(null) }

    // Вкладки нижньої навігації лишаються живими в пам'яті (saveState/
    // restoreState), тож одноразовий LaunchedEffect не оновлював би дані при
    // поверненні на той самий день - цикл з оновленням раз на 30с. Спінер -
    // лише на першому завантаженні цієї (дата, конфіг) комбінації.
    LaunchedEffect(date, config) {
        // Нова дата/конфіг - старі точки чужої доби не показуємо.
        points = emptyList()
        savedTotals = Triple(0.0, 0.0, 0.0)
        errorText = null
        cursorMinute = null
        var firstLoad = true
        var lastFetchFailed = false
        while (true) {
            if (firstLoad) loading = true
            val payload = runCatching { repository.fetchInverterDaily(config, date) }.getOrNull()
            lastFetchFailed = payload == null
            if (payload == null) {
                // Тимчасовий збій зв'язку (лагає ZeroTier) не стирає вже показаний
                // графік - помилка лише якщо показувати ще нічого.
                if (points.isEmpty()) {
                    errorText = "немає даних (модуль інвертора недоступний або дані за цю добу відсутні)"
                }
            } else {
                errorText = null
                val slotsArr = payload.optJSONArray("slots")
                val parsed = mutableListOf<ChartPoint>()
                if (slotsArr != null && slotsArr.length() > 0) {
                    for (i in 0 until slotsArr.length()) {
                        val row = slotsArr.optJSONObject(i) ?: continue
                        parsed.add(
                            ChartPoint(
                                minute = row.optInt("m", i * 15),
                                pv = row.optDouble("pv_w", 0.0),
                                home = row.optDouble("home_w", 0.0),
                                grid = row.optDouble("grid_w", 0.0),
                            ),
                        )
                    }
                    stepMinutes = 15
                } else {
                    val hoursArr = payload.optJSONArray("hours")
                    if (hoursArr != null) {
                        for (i in 0 until hoursArr.length()) {
                            val row = hoursArr.optJSONObject(i) ?: continue
                            parsed.add(
                                ChartPoint(
                                    minute = row.optInt("hour", i) * 60,
                                    pv = row.optDouble("pv", 0.0),
                                    home = row.optDouble("home", 0.0),
                                    grid = row.optDouble("grid", 0.0),
                                ),
                            )
                        }
                    }
                    stepMinutes = 60
                }
                points = parsed
                val t = payload.optJSONObject("totals")
                savedTotals = Triple(
                    t?.optDouble("dailyPV", 0.0) ?: 0.0,
                    t?.optDouble("dailyHome", 0.0) ?: 0.0,
                    t?.optDouble("dailyGrid", 0.0) ?: 0.0,
                )
            }
            loading = false
            firstLoad = false
            // Коли даних ще нема - повторюємо швидко, не чекаємо 30с.
            delay(if (lastFetchFailed && points.isEmpty()) 5_000 else 30_000)
        }
    }

    val isToday = date == todayIso()
    // Історія (/api/daily) містить лише години, які прошивка вже встигла
    // записати у файл; лічильники "за добу" з /api/status - завжди живі.
    // Для сьогоднішнього дня в підсумках показуємо живі, щоб вони збігались із
    // дашбордом, а розбіжність з історією виносимо в підказку під графіком.
    val shownTotals = if (isToday && liveTotals != null) liveTotals else savedTotals
    val unsavedHomeWh = if (isToday && liveTotals != null) liveTotals.second - savedTotals.second else 0.0

    // Вісь X - 0..(1440 - крок), щоб останній інтервал доби лягав на правий край.
    val spanMinutes = (1440 - stepMinutes).coerceAtLeast(1)
    val viewport = rememberChartViewport(date, 0f, spanMinutes.toFloat(), (stepMinutes * 2).toFloat())

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
                DateNavHeader(date = date, onDateChange = { date = it }, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !pieMode,
                        onClick = { pieMode = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Лінії") }
                    SegmentedButton(
                        selected = pieMode,
                        onClick = { pieMode = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Діаграма") }
                }
            }
            if (pieMode) {
                EnergyPieSection(
                    status = status,
                    savedTotals = savedTotals,
                    isToday = isToday,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
                )
            } else {
                SummaryLegendRow(
                    totals = shownTotals,
                    unitNote = if (stepMinutes == 15) "Вт, середня за 15 хв" else "Вт, середня за годину",
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
                            stepMinutes = stepMinutes,
                            viewport = viewport,
                            visible = listOf(showPv, showHome, showGrid),
                            cursorMinute = cursorMinute,
                            onCursor = { cursorMinute = it },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        if (unsavedHomeWh > 50.0) {
                            Text(
                                text = "В історію записано ${savedTotals.second.roundToInt()} з " +
                                    "${shownTotals.second.roundToInt()} Вт·год (будинок): решта - поточна година " +
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
 * Легенда, добові підсумки й перемикачі видимості в одному рядку: "око" + назва +
 * значення. Натискання ховає/показує відповідну лінію графіка.
 */
@Composable
private fun SummaryLegendRow(
    totals: Triple<Double, Double, Double>,
    unitNote: String,
    visible: List<Boolean>,
    onToggle: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VisibilityToggle("сонце", AccentPv, visible[0], { onToggle(0) }, detail = "${totals.first.roundToInt()} Вт·год")
        VisibilityToggle("будинок", AccentBattery, visible[1], { onToggle(1) }, detail = "${totals.second.roundToInt()} Вт·год")
        VisibilityToggle("мережа", AccentGrid, visible[2], { onToggle(2) }, detail = "${totals.third.roundToInt()} Вт·год")
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

private fun hhmm(minute: Int): String = "%d:%02d".format(minute / 60, minute % 60)

// Кроки підписів осі X (хв): найменший, що дає <= ~8 підписів у видимому вікні.
private val MINUTE_TICK_STEPS = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 180)

private fun minuteTickStep(spanMinutes: Float): Int =
    MINUTE_TICK_STEPS.firstOrNull { spanMinutes / it <= 8f } ?: MINUTE_TICK_STEPS.last()

@Composable
private fun EnergyLineChart(
    points: List<ChartPoint>,
    stepMinutes: Int,
    viewport: ChartViewport,
    visible: List<Boolean>,
    cursorMinute: Float?,
    onCursor: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        Text("немає даних за цю добу", color = TextMuted, modifier = modifier.padding(16.dp))
        return
    }

    val series = listOf(
        Series("сонце", AccentPv, points.map { it.pv }),
        Series("будинок", AccentBattery, points.map { it.home }),
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
    fun minuteAt(x: Float): Float =
        viewport.start + ((x - leftMarginPx) / plotWidthPx()).coerceIn(0f, 1f) * viewport.span

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
                    onCursor = { onCursor(minuteAt(it.x)) },
                    onTap = { onCursor(minuteAt(it.x)) },
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
                points[it].minute + stepMinutes >= v0 && points[it].minute <= v0 + vSpan
            }.ifEmpty { points.indices.toList() }
            val axisMax = niceAxisMax(series.maxOf { s -> visibleIdx.maxOfOrNull { s.values[it] } ?: 0.0 })

            fun xAt(minute: Float) = plotLeft + (minute - v0) / vSpan * plotW
            fun yAt(v: Double) = plotTop + plotH - (v / axisMax * plotH).toFloat().coerceIn(0f, plotH)

            // Сітка й підписи осі Y - тихі, не змагаються з даними.
            val gridStroke = 1.dp.toPx()
            for (i in 0..4) {
                val value = axisMax * i / 4.0
                val y = yAt(value)
                drawLine(OutlineSubtle, Offset(plotLeft, y), Offset(size.width, y), strokeWidth = gridStroke)
                val label = value.roundToInt().toString()
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    plotLeft - 6.dp.toPx() - axisPaint.measureText(label),
                    y + axisPaint.textSize / 3f,
                    axisPaint,
                )
            }

            clipRect(left = plotLeft, top = 0f, right = size.width, bottom = size.height) {
                // Підписи осі X - крок залежить від масштабу.
                val step = minuteTickStep(vSpan)
                var tick = (ceil(v0 / step) * step).toInt()
                while (tick <= v0 + vSpan) {
                    val x = xAt(tick.toFloat())
                    val label = hhmm(tick)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        x - axisPaint.measureText(label) / 2f,
                        size.height - 4.dp.toPx(),
                        axisPaint,
                    )
                    tick += step
                }

                // Лінії: тонкі (2dp). Розрив, якщо між сусідніми точками пропущено
                // інтервали - не вигадуємо значень, яких прошивка не мала.
                val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                series.forEach { s ->
                    val path = Path()
                    s.values.forEachIndexed { i, v ->
                        val x = xAt(points[i].minute.toFloat())
                        val y = yAt(v)
                        val contiguous = i > 0 && points[i].minute - points[i - 1].minute <= stepMinutes * 3 / 2
                        if (contiguous) path.lineTo(x, y) else path.moveTo(x, y)
                    }
                    drawPath(path, s.color, style = lineStroke)
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
                                val c = Offset(xAt(points[i].minute.toFloat()), yAt(v))
                                drawCircle(SurfaceContainer, radius = markerR + ring, center = c)
                                drawCircle(s.color, radius = markerR, center = c)
                            }
                        }
                    }
                }
            }

            // Лінія спостереження й підказка для найближчої до неї точки.
            val idx = cursorMinute?.let { m -> points.indices.minByOrNull { abs(points[it].minute - m) } }
            if (idx != null) {
                val p = points[idx]
                val x = xAt(p.minute.toFloat())
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

                    val title = "${hhmm(p.minute)}-${hhmm(p.minute + stepMinutes)}"
                    val rows = series.map { "${it.label}: ${it.values[idx].roundToInt()} Вт" }
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
