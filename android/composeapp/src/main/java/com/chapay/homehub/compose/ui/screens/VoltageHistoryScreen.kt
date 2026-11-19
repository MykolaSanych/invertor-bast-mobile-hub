package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.data.VoltageHistoryPoint
import com.chapay.homehub.compose.data.VoltageLocalHistoryStore
import com.chapay.homehub.compose.ui.components.ChartViewport
import com.chapay.homehub.compose.ui.components.chartGestures
import com.chapay.homehub.compose.ui.components.rememberChartViewport
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil

private const val DAY_SEC = 24L * 3600
private const val MIN_SPAN_SEC = 30f * 60 // не наближувати тісніше за 30 хв - лінія втрачає сенс

private data class VoltageMetricSpec(
    val title: String,
    val accent: Color,
    // Підпис значення в підказці на графіку (%.1f для В, %.0f для %, тощо) -
    // для АКБ тут не напруга, а SOC (%): інформативніше, ніж сама напруга,
    // яку й так видно поруч на картці.
    val formatValue: (Double) -> String,
    val valueOf: (VoltageHistoryPoint) -> Double,
)

private val VOLTAGE_METRICS = mapOf(
    "grid" to VoltageMetricSpec("Графік напруги: мережа", AccentGrid, { "%.1f В".format(it) }) { it.grid },
    "pv" to VoltageMetricSpec("Графік напруги: сонце", AccentPv, { "%.1f В".format(it) }) { it.pv },
    "battery" to VoltageMetricSpec("Графік заряду: АКБ", AccentBattery, { "%.0f%%".format(it) }) { it.battery },
)

private val clockFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
private fun formatClock(epochSec: Long): String = clockFormat.format(java.util.Date(epochSec * 1000))

/** Тривалість (без "тому") - для підпису охопленого історією інтервалу. */
private fun durationLabel(sec: Long): String {
    if (sec < 90) return "менше хвилини"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "$h год $m хв" else "$m хв"
}

/**
 * 24-годинний графік напруги (мережа/сонце/АКБ) - дані з денних файлів на
 * інверторі (переживають перезапуск модуля) + власна історія телефону як
 * підстраховка на прогалини. Масштаб/зсув - як у графіку енергії
 * ([EnergyChartScreen]): щипок наближує/віддаляє часову шкалу, вісь X
 * показує РЕАЛЬНИЙ час замірів, вісь Y підлаштовується під видиму хвилю.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoltageHistoryScreen(
    config: AppConfig,
    repository: StatusRepository,
    metric: String,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val spec = VOLTAGE_METRICS[metric] ?: VOLTAGE_METRICS.getValue("grid")
    var points by remember { mutableStateOf<List<VoltageHistoryPoint>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var cursorEpochSec by remember { mutableStateOf<Long?>(null) }
    // Вікно [якір-24год, якір] фіксується ОДНОразово при першому успішному
    // завантаженні цього екрана - інакше кожне періодичне оновлення (нижче)
    // зсувало б і межі, і будь-яке масштабування/зсув користувача.
    var domainAnchor by remember(metric) { mutableStateOf(0L) }

    LaunchedEffect(config, metric) {
        while (true) {
            val result = runCatching { repository.fetchInverterVoltageHistory(config) }.getOrNull()
            val devicePoints = result?.points.orEmpty()
            // Доповнюємо власною історією телефону (VoltageLocalHistoryStore) - лише
            // тим, що СТАРШЕ за найстарішу точку з пристрою: якщо модуль щойно
            // перезапустився й втратив частину своєї (навіть файлової) історії,
            // телефон, що весь цей час опитував його, заповнює цю прогалину.
            val nowEpochSec = System.currentTimeMillis() / 1000
            val minDeviceEpoch = devicePoints.minOfOrNull { it.epochSec } ?: Long.MAX_VALUE
            val localPoints = VoltageLocalHistoryStore.recentSamples(context, nowEpochSec * 1000)
                .map {
                    VoltageHistoryPoint(
                        epochSec = it.tsMs / 1000,
                        grid = it.grid,
                        pv = it.pv,
                        battery = it.battery,
                    )
                }
                .filter { it.epochSec < minDeviceEpoch - 60 }
            val merged = (devicePoints + localPoints).sortedBy { it.epochSec }
            if (merged.isNotEmpty()) {
                points = merged
                errorText = null
                if (domainAnchor == 0L) domainAnchor = nowEpochSec
            } else if (points.isEmpty()) {
                errorText = "немає даних (інвертор недоступний, або з моменту останнього " +
                    "перезапуску ще не минуло достатньо часу)"
            }
            loading = false
            delay(60_000L)
        }
    }

    val viewport = rememberChartViewport(
        metric to domainAnchor,
        (domainAnchor - DAY_SEC).toFloat(),
        domainAnchor.toFloat(),
        MIN_SPAN_SEC,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(spec.title) },
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
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(12.dp),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                points.size < 2 -> Text(
                    errorText ?: "накопичується історія: поки записано лише " +
                        "${durationLabel((points.maxOfOrNull { it.epochSec } ?: 0L).let { (System.currentTimeMillis() / 1000) - it })} " +
                        "даних (новий зразок - кожні 5 хв). Графік почне формуватись найближчим часом.",
                    color = TextMuted,
                    modifier = Modifier.padding(16.dp),
                )
                else -> {
                    val oldestEpoch = points.minOf { it.epochSec }
                    val coveredSec = domainAnchor - oldestEpoch
                    if (coveredSec < DAY_SEC - 600) {
                        Text(
                            "охоплено даними: ${durationLabel(coveredSec)} з 24 год " +
                                "(переживає перезапуск модуля - заповнюється з часом)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        )
                    }
                    VoltageLineChart(
                        points = points,
                        spec = spec,
                        viewport = viewport,
                        cursorEpochSec = cursorEpochSec,
                        onCursor = { cursorEpochSec = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoltageLineChart(
    points: List<VoltageHistoryPoint>,
    spec: VoltageMetricSpec,
    viewport: ChartViewport,
    cursorEpochSec: Long?,
    onCursor: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(points) { points.sortedBy { it.epochSec } }

    val density = LocalDensity.current
    val leftMarginPx = with(density) { 48.dp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    fun plotWidthPx() = (canvasSize.width - leftMarginPx).coerceAtLeast(1f)
    fun epochAt(px: Float): Long =
        (viewport.start + ((px - leftMarginPx) / plotWidthPx()).coerceIn(0f, 1f) * viewport.span).toLong()

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
                .padding(12.dp)
                .onSizeChanged { canvasSize = it }
                .chartGestures(
                    key = Unit,
                    dragMovesCursor = !viewport.isZoomed,
                    onCursor = { onCursor(epochAt(it.x)) },
                    onPan = { dx -> viewport.panFraction(-dx / plotWidthPx()) },
                    onZoom = { cx, factor ->
                        viewport.zoomAround(((cx - leftMarginPx) / plotWidthPx()).coerceIn(0f, 1f), factor)
                    },
                    onTap = { onCursor(epochAt(it.x)) },
                ),
        ) {
            val plotLeft = leftMarginPx
            val bottomMargin = 22.dp.toPx()
            val plotTop = 6.dp.toPx()
            val plotW = size.width - plotLeft
            val plotH = size.height - plotTop - bottomMargin
            val v0 = viewport.start
            val vSpan = viewport.span

            fun xAt(epochSec: Long): Float = plotLeft + (epochSec - v0) / vSpan * plotW

            // Вісь Y - лише з точок, видимих у поточному (можливо наближеному) вікні:
            // та сама "підлаштовка під хвилю", що й у графіку енергії. НЕ від нуля -
            // для напруги (мережа ~220В, АКБ ~48-58В) нульова база сплющила б
            // графік до непомітної лінії.
            val visible = sorted.filter { it.epochSec >= v0 - 60 && it.epochSec <= v0 + vSpan + 60 }
                .ifEmpty { sorted }
            // Нульові значення (немає мережі / ніч / перезапуск модуля) не беремо
            // в діапазон осі - інакше нуль тягне базу вниз і сплющує всю хвилю.
            val visibleValues = visible.map { spec.valueOf(it) }.filter { it > 0.0 }
            val rawMin = visibleValues.minOrNull() ?: 0.0
            val rawMax = visibleValues.maxOrNull() ?: 0.0
            val pad = ((rawMax - rawMin) * 0.12).coerceAtLeast(0.5)
            val axisMin = rawMin - pad
            val axisMax = (rawMax + pad).coerceAtLeast(axisMin + 0.1)

            fun yAt(v: Double): Float =
                plotTop + plotH - ((v - axisMin) / (axisMax - axisMin) * plotH).toFloat().coerceIn(0f, plotH)

            // Сітка й підписи осі Y.
            for (i in 0..4) {
                val value = axisMin + (axisMax - axisMin) * i / 4.0
                val y = yAt(value)
                drawLine(OutlineSubtle, Offset(plotLeft, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                val label = "%.1f".format(value)
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    plotLeft - 6.dp.toPx() - axisPaint.measureText(label),
                    y + axisPaint.textSize / 3f,
                    axisPaint,
                )
            }

            clipRect(left = plotLeft, top = 0f, right = size.width, bottom = size.height) {
                // Крок підписів осі X - залежить від масштабу (крок у секундах
                // ділить епоху рівно, тож для годинних+ кроків підписи стають на
                // реальних межах годин - Україна на цілій годинній зоні).
                val tickSteps = longArrayOf(900, 1800, 3600, 2 * 3600, 3 * 3600, 6 * 3600, 12 * 3600, 24 * 3600)
                val step = tickSteps.firstOrNull { vSpan / it <= 8f } ?: tickSteps.last()
                var tick = ceil(v0 / step).toLong() * step
                while (tick <= v0 + vSpan) {
                    val x = xAt(tick)
                    val label = formatClock(tick)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        x - axisPaint.measureText(label) / 2f,
                        size.height - 4.dp.toPx(),
                        axisPaint,
                    )
                    drawLine(OutlineSubtle, Offset(x, plotTop), Offset(x, plotTop + plotH), strokeWidth = 1f)
                    tick += step
                }

                val path = Path()
                var prevEpoch: Long? = null // епоха останньої НАНЕСЕНОЇ (ненульової) точки
                sorted.forEach { p ->
                    val value = spec.valueOf(p)
                    // Нуль = немає даних (немає мережі / ніч / перезапуск модуля):
                    // РВЕМО лінію, а не ведемо її по нулю. Скидаємо prevEpoch, щоб
                    // наступна валідна точка почала новий сегмент (moveTo).
                    if (value <= 0.0) {
                        prevEpoch = null
                        return@forEach
                    }
                    val x = xAt(p.epochSec)
                    val y = yAt(value)
                    val gap = prevEpoch?.let { p.epochSec - it }
                    if (gap == null || gap > 20 * 60) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                    prevEpoch = p.epochSec
                }
                drawPath(
                    path,
                    spec.accent,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                if (visible.size <= 60) {
                    val markerR = (4f / 3f).dp.toPx()
                    val ring = (2f / 3f).dp.toPx()
                    visible.forEach { p ->
                        val value = spec.valueOf(p)
                        if (value <= 0.0) return@forEach // у прогалині точок не ставимо
                        val c = Offset(xAt(p.epochSec), yAt(value))
                        drawCircle(SurfaceContainer, radius = markerR + ring, center = c)
                        drawCircle(spec.accent, radius = markerR, center = c)
                    }
                }
            }

            // Курсор + підказка на найближчій точці.
            // Курсор чіпляється лише до ненульових точок - у прогалині підказки нема.
            val nearest = cursorEpochSec?.let { c ->
                sorted.filter { spec.valueOf(it) > 0.0 }.minByOrNull { abs(it.epochSec - c) }
            }
            if (nearest != null) {
                val x = xAt(nearest.epochSec)
                if (x in plotLeft..size.width) {
                    val v = spec.valueOf(nearest)
                    val y = yAt(v)
                    val markerR = (4f / 3f).dp.toPx()
                    val ring = (2f / 3f).dp.toPx()
                    drawLine(
                        TextMuted.copy(alpha = 0.6f),
                        Offset(x, plotTop),
                        Offset(x, plotTop + plotH),
                        strokeWidth = 1.dp.toPx(),
                    )
                    drawCircle(SurfaceContainer, radius = markerR + ring, center = Offset(x, y))
                    drawCircle(spec.accent, radius = markerR, center = Offset(x, y))

                    val title = formatClock(nearest.epochSec)
                    val valueText = spec.formatValue(v)
                    val pad2 = 8.dp.toPx()
                    val lineH = tipTextPaint.textSize * 1.35f
                    val boxW = maxOf(tipTextPaint.measureText(valueText), tipTitlePaint.measureText(title)) + pad2 * 2
                    val boxH = pad2 * 2 + lineH * 2
                    val boxX = if (x + 12.dp.toPx() + boxW > size.width) x - 12.dp.toPx() - boxW else x + 12.dp.toPx()
                    val boxY = plotTop + 4.dp.toPx()
                    drawRoundRect(
                        color = SurfaceContainerHigh,
                        topLeft = Offset(boxX, boxY),
                        size = Size(boxW, boxH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    )
                    drawContext.canvas.nativeCanvas.drawText(title, boxX + pad2, boxY + pad2 + lineH * 0.8f, tipTitlePaint)
                    drawContext.canvas.nativeCanvas.drawText(valueText, boxX + pad2, boxY + pad2 + lineH * 1.8f, tipTextPaint)
                }
            }
        }
    }
}
