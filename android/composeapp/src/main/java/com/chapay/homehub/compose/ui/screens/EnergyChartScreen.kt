package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.ui.components.DateNavHeader
import com.chapay.homehub.compose.ui.components.LockLandscapeOrientation
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
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private data class HourPoint(val hour: Int, val pv: Double, val home: Double, val grid: Double)

private data class Series(val label: String, val color: Color, val values: List<Double>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyChartScreen(
    config: AppConfig,
    repository: StatusRepository,
    liveTotals: Triple<Double, Double, Double>?,
    onBack: () -> Unit,
) {
    LockLandscapeOrientation()
    var date by remember { mutableStateOf(todayIso()) }
    var points by remember { mutableStateOf<List<HourPoint>>(emptyList()) }
    var savedTotals by remember { mutableStateOf(Triple(0.0, 0.0, 0.0)) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Вкладки нижньої навігації лишаються живими в пам'яті (saveState/
    // restoreState), тож одноразовий LaunchedEffect не оновлював би дані при
    // поверненні на той самий день - цикл з оновленням раз на 30с. Спінер -
    // лише на першому завантаженні цієї (дата, конфіг) комбінації.
    LaunchedEffect(date, config) {
        var firstLoad = true
        while (true) {
            if (firstLoad) loading = true
            errorText = null
            val payload = runCatching { repository.fetchInverterDaily(config, date) }.getOrNull()
            if (payload == null) {
                errorText = "немає даних (модуль інвертора недоступний або дані за цю добу відсутні)"
                points = emptyList()
            } else {
                val hoursArr = payload.optJSONArray("hours")
                val parsed = mutableListOf<HourPoint>()
                if (hoursArr != null) {
                    for (i in 0 until hoursArr.length()) {
                        val row = hoursArr.optJSONObject(i) ?: continue
                        parsed.add(
                            HourPoint(
                                hour = row.optInt("hour", i),
                                pv = row.optDouble("pv", 0.0),
                                home = row.optDouble("home", 0.0),
                                grid = row.optDouble("grid", 0.0),
                            ),
                        )
                    }
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
            delay(30_000)
        }
    }

    val isToday = date == todayIso()
    // Історія (/api/daily) містить лише години, які прошивка вже встигла
    // записати у файл; лічильники "за добу" з /api/status - завжди живі.
    // Для сьогоднішнього дня в підсумках показуємо живі, щоб вони збігались із
    // дашбордом, а розбіжність з історією виносимо в підказку під графіком.
    val shownTotals = if (isToday && liveTotals != null) liveTotals else savedTotals
    val unsavedHomeWh = if (isToday && liveTotals != null) liveTotals.second - savedTotals.second else 0.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Графік енергії") },
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
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            DateNavHeader(date = date, onDateChange = { date = it })
            SummaryLegendRow(shownTotals)
            Spacer(Modifier.height(6.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                errorText != null -> Text(errorText.orEmpty(), color = TextMuted, modifier = Modifier.padding(16.dp))
                else -> {
                    EnergyLineChart(points, modifier = Modifier.fillMaxWidth().weight(1f))
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

/** Легенда і добові підсумки в одному рядку: колір-крапка + назва + значення. */
@Composable
private fun SummaryLegendRow(totals: Triple<Double, Double, Double>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("сонце", totals.first, AccentPv)
        LegendItem("будинок", totals.second, AccentBattery)
        LegendItem("мережа", totals.third, AccentGrid)
    }
}

@Composable
private fun LegendItem(label: String, value: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${value.roundToInt()} Вт·год",
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary,
        )
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
private fun EnergyLineChart(points: List<HourPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) {
        Text("немає погодинних даних", color = TextMuted, modifier = modifier.padding(16.dp))
        return
    }

    val series = listOf(
        Series("сонце", AccentPv, points.map { it.pv }),
        Series("будинок", AccentBattery, points.map { it.home }),
        Series("мережа", AccentGrid, points.map { it.grid }),
    )
    val axisMax = niceAxisMax(series.maxOf { s -> s.values.maxOrNull() ?: 0.0 })
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    val density = LocalDensity.current
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
                .pointerInput(points) {
                    val leftPx = 44.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun pick(x: Float) {
                            val plotW = size.width - leftPx
                            val step = plotW / (points.size - 1).coerceAtLeast(1)
                            selected = ((x - leftPx) / step).roundToInt().coerceIn(0, points.size - 1)
                        }
                        pick(down.position.x)
                        do {
                            val event = awaitPointerEvent()
                            event.changes.firstOrNull()?.let {
                                pick(it.position.x)
                                it.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            val leftMargin = 44.dp.toPx()
            val bottomMargin = 22.dp.toPx()
            val plotLeft = leftMargin
            val plotTop = 6.dp.toPx()
            val plotW = size.width - plotLeft
            val plotH = size.height - plotTop - bottomMargin
            val lastIndex = (points.size - 1).coerceAtLeast(1)
            val stepX = plotW / lastIndex

            fun xAt(i: Int) = plotLeft + i * stepX
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

            // Підписи осі X - кожні 2 години.
            points.forEachIndexed { i, p ->
                if (p.hour % 2 == 0) {
                    val label = "${p.hour}"
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        xAt(i) - axisPaint.measureText(label) / 2f,
                        size.height - 4.dp.toPx(),
                        axisPaint,
                    )
                }
            }

            // Лінії: тонкі (2dp), заокруглені з'єднання, кожна серія - своїм кольором.
            val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            series.forEach { s ->
                val path = Path()
                s.values.forEachIndexed { i, v ->
                    val x = xAt(i)
                    val y = yAt(v)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, s.color, style = lineStroke)
            }

            // Маркери на точках даних (діаметр >= 8dp), 2dp кільце кольору поверхні.
            val markerR = 4.dp.toPx()
            val ring = 2.dp.toPx()
            series.forEach { s ->
                s.values.forEachIndexed { i, v ->
                    if (v > 0.0) {
                        drawCircle(SurfaceContainer, radius = markerR + ring, center = Offset(xAt(i), yAt(v)))
                        drawCircle(s.color, radius = markerR, center = Offset(xAt(i), yAt(v)))
                    }
                }
            }

            // Перехрестя й підказка для вибраної години.
            selected?.let { idx ->
                val x = xAt(idx)
                drawLine(
                    TextMuted.copy(alpha = 0.6f),
                    Offset(x, plotTop),
                    Offset(x, plotTop + plotH),
                    strokeWidth = 1.dp.toPx(),
                )
                series.forEach { s ->
                    val cy = yAt(s.values[idx])
                    drawCircle(SurfaceContainer, radius = markerR + ring + 1.dp.toPx(), center = Offset(x, cy))
                    drawCircle(s.color, radius = markerR + 1.dp.toPx(), center = Offset(x, cy))
                }

                val title = "${points[idx].hour}:00"
                val rows = series.map { "${it.label}: ${it.values[idx].roundToInt()} Вт·год" }
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
