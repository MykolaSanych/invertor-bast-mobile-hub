package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.chapay.homehub.compose.ui.components.ChartToolbarActions
import com.chapay.homehub.compose.ui.components.ChartViewport
import com.chapay.homehub.compose.ui.components.DateNavHeader
import com.chapay.homehub.compose.ui.components.VisibilityToggle
import com.chapay.homehub.compose.ui.components.chartGestures
import com.chapay.homehub.compose.ui.components.rememberChartViewport
import com.chapay.homehub.compose.ui.components.todayIso
import com.chapay.homehub.compose.ui.theme.AccentBoiler
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val SECONDS_IN_DAY = 86_400

// Проміжок, коли сигнал був "увімкнений": [startSec, endSec] у секундах доби.
// ongoing - лінія ще триває (кінець = "зараз" або кінець доби).
private data class TimelineInterval(val row: Int, val startSec: Int, val endSec: Int, val ongoing: Boolean)

private data class TimelineData(val intervals: List<TimelineInterval>, val nowSec: Int?)

// (підпис, колір) у порядку рядків; індекс = номер біта в прошивці:
// 0 бойлер, 1 насос, 2 мережа, 3 сонце.
private val ROW_DEFS = listOf(
    "БОЙЛЕР 1" to AccentBoiler,
    "НАСОС" to AccentPump,
    "МЕРЕЖА" to AccentGrid,
    "СОНЦЕ" to AccentPv,
)

// Найдрібніше вікно масштабування - 1 хвилина (секундні запуски насоса видно).
private const val MIN_VIEW_SECONDS = 60f

/**
 * Події прошивки [[сек, біт, стан], ...] -> проміжки "від-до". Лінія починається
 * зі стану 1 і закінчується станом 0; якщо не закінчилась - тягнеться до "зараз"
 * (сьогодні) або до кінця доби (минулі дні). Кожен біт обробляється окремо, тож
 * порядок рядків у файлі між бітами не має значення.
 */
private fun parseTimelineEvents(payload: JSONObject): TimelineData {
    val nowSec = if (payload.isNull("now")) null else payload.optInt("now", -1).takeIf { it in 0..SECONDS_IN_DAY }
    val events = payload.optJSONArray("e")
    val perBit = List(ROW_DEFS.size) { mutableListOf<Pair<Int, Int>>() }
    if (events != null) {
        for (i in 0 until events.length()) {
            val row = events.optJSONArray(i) ?: continue
            val sec = row.optInt(0, -1)
            val bit = row.optInt(1, -1)
            val value = row.optInt(2, -1)
            if (sec !in 0..SECONDS_IN_DAY || bit !in perBit.indices || value !in 0..1) continue
            perBit[bit].add(sec to value)
        }
    }

    val dayEnd = nowSec ?: SECONDS_IN_DAY
    val intervals = mutableListOf<TimelineInterval>()
    perBit.forEachIndexed { bit, list ->
        var start: Int? = null
        // sortedBy стабільне: події з однаковою секундою лишаються в порядку файлу.
        for ((sec, value) in list.sortedBy { it.first }) {
            if (value == 1) {
                if (start == null) start = sec
            } else if (start != null) {
                intervals.add(TimelineInterval(bit, start, sec, ongoing = false))
                start = null
            }
        }
        if (start != null) intervals.add(TimelineInterval(bit, start, maxOf(start, dayEnd), ongoing = true))
    }
    return TimelineData(intervals, nowSec)
}

// Запасний шлях для старої прошивки без /api/timeline: 15-хвилинні знімки
// "було хоч раз" (/api/history) - кожен знімок описує вікно (m - інтервал, m].
private fun parseLegacyHistory(payload: JSONObject): TimelineData {
    val stepMin = payload.optInt("interval_min", 15).coerceAtLeast(1)
    val rows = payload.optJSONArray("samples")
    val intervals = mutableListOf<TimelineInterval>()
    if (rows != null) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val minute = row.optInt("m", -1)
            val flags = row.optInt("f", 0)
            if (minute !in 0..1439) continue
            for (bit in ROW_DEFS.indices) {
                if ((flags and (1 shl bit)) != 0) {
                    intervals.add(
                        TimelineInterval(bit, maxOf(0, (minute - stepMin) * 60), minute * 60, ongoing = false),
                    )
                }
            }
        }
    }
    return TimelineData(intervals, null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(config: AppConfig, repository: StatusRepository, onBack: (() -> Unit)? = null) {
    var date by remember { mutableStateOf(todayIso()) }
    var data by remember { mutableStateOf<TimelineData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<TimelineInterval?>(null) }
    // Приховані рядки (біти 0..3 = бойлер, насос, мережа, сонце): "око" в підписі
    // ховає рядок, щоб роздивитись решту.
    var hiddenMask by rememberSaveable { mutableStateOf(0) }
    val visibleRows = ROW_DEFS.indices.filter { (hiddenMask and (1 shl it)) == 0 }
    // Вертикальний курсор: не наближено - веде палець, наближено - дотик.
    var cursorSec by remember { mutableStateOf<Float?>(null) }
    val viewport = rememberChartViewport(date, 0f, SECONDS_IN_DAY.toFloat(), MIN_VIEW_SECONDS)

    // Той самий цикл, що і в EnergyChartScreen: вкладка лишається живою в
    // пам'яті між перемиканнями нижньої навігації (saveState/restoreState),
    // тож одноразового ефекту не досить - інакше хронологія "замерзає" на
    // моменті першого відкриття вкладки.
    LaunchedEffect(date, config) {
        // Нова дата/конфіг - старі лінії чужої доби не показуємо.
        data = null
        selected = null
        cursorSec = null
        // Що вже є на телефоні (закриті доби - назавжди) - показуємо одразу.
        val cached = runCatching { repository.peekLoadControllerTimeline(config, date) }.getOrNull()
        var timelineSeen = false
        if (cached != null) {
            timelineSeen = true
            data = parseTimelineEvents(cached)
            loading = false
        }
        var firstLoad = cached == null
        while (true) {
            if (firstLoad) loading = true
            val payload = runCatching { repository.fetchLoadControllerTimeline(config, date) }.getOrNull()
            // Тимчасовий збій зв'язку (лагає ZeroTier) не стирає вже показану
            // хронологію: оновлюємо лише коли модуль реально відповів.
            if (payload != null) {
                timelineSeen = true
                data = parseTimelineEvents(payload)
            } else if (!timelineSeen) {
                // Модуль ще на старій прошивці без /api/timeline - показуємо
                // 15-хвилинні знімки. Після першої успішної відповіді нового
                // формату назад не повертаємось, щоб вигляд не "блимав".
                val legacy = runCatching { repository.fetchLoadControllerHistory(config, date) }.getOrNull()
                if (legacy != null) data = parseLegacyHistory(legacy)
            }
            loading = false
            firstLoad = false
            // Коли даних ще нема - повторюємо швидко, не чекаємо 30с.
            delay(if (payload == null && data == null) 5_000 else 30_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Хронологія") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = { ChartToolbarActions(viewport) },
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
            val current = data
            if (loading && current == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (current == null) {
                Text(
                    text = "немає даних за цю добу",
                    color = TextMuted,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                TimelineChart(
                    data = current,
                    viewport = viewport,
                    visibleRows = visibleRows,
                    selected = selected,
                    cursorSec = cursorSec,
                    // Рух курсора скидає обрану лінію; дотик спершу ставить курсор, потім обирає.
                    onCursor = {
                        cursorSec = it
                        selected = null
                    },
                    onSelect = { selected = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Spacer(Modifier.height(4.dp))
                InfoLine(data = current, visibleRows = visibleRows, selected = selected, cursorSec = cursorSec)
                Spacer(Modifier.height(4.dp))
                ActiveTimeLegend(
                    data = current,
                    hiddenMask = hiddenMask,
                    onToggle = { row ->
                        hiddenMask = hiddenMask xor (1 shl row)
                        selected = null
                    },
                )
            }
        }
    }
}

private fun hms(sec: Int): String {
    val s = sec.coerceIn(0, SECONDS_IN_DAY)
    return "%02d:%02d:%02d".format(s / 3600 % 24, s / 60 % 60, s % 60)
}

private fun hm(sec: Int): String {
    val s = sec.coerceIn(0, SECONDS_IN_DAY)
    return "%02d:%02d".format(s / 3600 % 24, s / 60 % 60)
}

private fun durationText(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    return when {
        s < 60 -> "$s с"
        s < 3600 -> "${s / 60} хв ${"%02d".format(s % 60)} с"
        else -> "${s / 3600} год ${"%02d".format(s / 60 % 60)} хв"
    }
}

private fun describeInterval(i: TimelineInterval): String {
    val name = ROW_DEFS[i.row].first
    val end = if (i.ongoing) "триває" else hms(i.endSec)
    return "$name: ${hms(i.startSec)} – $end  (${durationText(i.endSec - i.startSec)})"
}

// Відстань (с) від моменту дотику до проміжку: 0, якщо дотик усередині.
private fun distanceToInterval(tapSec: Float, interval: TimelineInterval): Float = when {
    tapSec < interval.startSec -> interval.startSec - tapSec
    tapSec > interval.endSec -> tapSec - interval.endSec
    else -> 0f
}

/** Рядок під графіком: обрана лінія, або показання під курсором, або підказка. */
@Composable
private fun InfoLine(data: TimelineData, visibleRows: List<Int>, selected: TimelineInterval?, cursorSec: Float?) {
    if (selected != null) {
        Text(
            text = describeInterval(selected),
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
        )
    } else if (cursorSec != null) {
        val t = cursorSec.toInt()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hms(t), style = MaterialTheme.typography.labelLarge, color = TextPrimary)
            visibleRows.forEach { row ->
                val (label, color) = ROW_DEFS[row]
                val active = data.intervals.firstOrNull { it.row == row && t >= it.startSec && t <= it.endSec }
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .size(9.dp)
                        .then(
                            if (active != null) {
                                Modifier.background(color, CircleShape)
                            } else {
                                Modifier.border(1.5.dp, color.copy(alpha = 0.6f), CircleShape)
                            },
                        ),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (active != null) "$label з ${hm(active.startSec)}" else "$label вимк",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active != null) TextPrimary else TextMuted,
                )
            }
        }
    } else {
        Text(
            text = if (data.intervals.isEmpty()) {
                "за цю добу ще нічого не вмикалось"
            } else {
                "проведіть по графіку - курсор покаже стан у цей час; два пальці - масштаб і зсув"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}

/**
 * Активний час кожного пристрою за добу (сума тривалості всіх ліній і кількість
 * вмикань) разом з перемикачем видимості рядка ("око").
 */
@Composable
private fun ActiveTimeLegend(data: TimelineData, hiddenMask: Int, onToggle: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ROW_DEFS.forEachIndexed { row, (label, color) ->
            val mine = data.intervals.filter { it.row == row }
            val totalSec = mine.sumOf { (it.endSec - it.startSec).coerceAtLeast(0) }
            VisibilityToggle(
                label = label,
                color = color,
                visible = (hiddenMask and (1 shl row)) == 0,
                onToggle = { onToggle(row) },
                detail = if (mine.isEmpty()) "не вмикався" else "${durationText(totalSec)} · ${mine.size} вкл.",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// Кроки вертикальної сітки/підписів осі часу (с): вибираємо найменший, що дає <= ~10 підписів.
private val TICK_STEPS = intArrayOf(60, 120, 300, 600, 900, 1800, 3600, 7200, 10800)

private fun tickStepFor(spanSec: Float): Int =
    TICK_STEPS.firstOrNull { spanSec / it <= 10f } ?: TICK_STEPS.last()

@Composable
private fun TimelineChart(
    data: TimelineData,
    viewport: ChartViewport,
    visibleRows: List<Int>,
    selected: TimelineInterval?,
    cursorSec: Float?,
    onCursor: (Float?) -> Unit,
    onSelect: (TimelineInterval?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val labelWidthPx = with(density) { 72.dp.toPx() }
    val axisHeightPx = with(density) { 20.dp.toPx() }
    val touchTolPx = with(density) { 14.dp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Усе, що залежить від розміру полотна, читає canvasSize у момент виклику
    // (а не при створенні жесту), тож не застаріває після повороту/зміни розміру.
    fun plotWidthPx() = (canvasSize.width - labelWidthPx).coerceAtLeast(1f)
    fun secAt(x: Float): Float = viewport.start + ((x - labelWidthPx) / plotWidthPx()).coerceIn(0f, 1f) * viewport.span
    // Жести живуть довше за один кадр (ключ стабільний), тож свіжі дані читаємо через State.
    val latestData by rememberUpdatedState(data)
    val latestRows by rememberUpdatedState(visibleRows)

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
                    onCursor = { onCursor(secAt(it.x)) },
                    onPan = { dx -> viewport.panFraction(-dx / plotWidthPx()) },
                    onZoom = { cx, factor ->
                        viewport.zoomAround(((cx - labelWidthPx) / plotWidthPx()).coerceIn(0f, 1f), factor)
                    },
                    onTap = { tap ->
                        val rows = latestRows
                        val rowH = (canvasSize.height - axisHeightPx) / rows.size.coerceAtLeast(1)
                        val slot = (tap.y / rowH).toInt()
                        onCursor(secAt(tap.x))
                        if (slot !in rows.indices) {
                            onSelect(null)
                        } else {
                            val row = rows[slot]
                            val tapSec = secAt(tap.x)
                            // Короткі лінії (секунди) тонші за палець - беремо найближчу
                            // в межах ~14dp від дотику.
                            val tolSec = touchTolPx / plotWidthPx() * viewport.span
                            val nearest = latestData.intervals
                                .filter { it.row == row }
                                .minByOrNull { distanceToInterval(tapSec, it) }
                            onSelect(
                                if (nearest != null && distanceToInterval(tapSec, nearest) <= tolSec) nearest else null,
                            )
                        }
                    },
                ),
        ) {
            val labelWidth = labelWidthPx
            val plotWidth = size.width - labelWidth
            val plotBottom = size.height - axisHeightPx
            val rowH = plotBottom / visibleRows.size.coerceAtLeast(1)
            val v0 = viewport.start
            val span = viewport.span

            fun xFor(sec: Float) = labelWidth + (sec - v0) / span * plotWidth

            val axisPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#9BA3B4")
                textSize = 9.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#E6ECFF")
                textSize = 9.sp.toPx()
                textAlign = android.graphics.Paint.Align.RIGHT
                isAntiAlias = true
            }

            // Підписи рядків - поза обрізанням, ліворуч від області графіка.
            visibleRows.forEachIndexed { slot, row ->
                val label = ROW_DEFS[row].first
                val rowCenter = slot * rowH + rowH / 2
                drawContext.canvas.nativeCanvas.drawText(label, labelWidth - 6.dp.toPx(), rowCenter + 3.dp.toPx(), labelPaint)
            }

            clipRect(left = labelWidth, top = 0f, right = size.width, bottom = size.height) {
                // Вертикальна сітка й підписи часу - крок залежить від масштабу.
                val step = tickStepFor(span)
                var tick = (kotlin.math.ceil(v0 / step) * step).toInt()
                while (tick <= v0 + span) {
                    val x = xFor(tick.toFloat())
                    drawLine(OutlineSubtle, Offset(x, 0f), Offset(x, plotBottom), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText(hm(tick), x, size.height - 4.dp.toPx(), axisPaint)
                    tick += step
                }

                visibleRows.forEachIndexed { slot, rowIndex ->
                    val color = ROW_DEFS[rowIndex].second
                    val rowTop = slot * rowH

                    // Фон рядка.
                    drawRect(
                        color = color.copy(alpha = 0.06f),
                        topLeft = Offset(labelWidth, rowTop + 4.dp.toPx()),
                        size = Size(plotWidth, rowH - 8.dp.toPx()),
                    )

                    // Лінії "від-до". Мінімальна ширина 3dp, щоб секундні запуски
                    // були помітні навіть на добовому масштабі.
                    data.intervals.filter { it.row == rowIndex }.forEach { interval ->
                        if (interval.endSec < v0 || interval.startSec > v0 + span) return@forEach
                        val x0 = xFor(interval.startSec.toFloat())
                        val width = (xFor(interval.endSec.toFloat()) - x0).coerceAtLeast(3.dp.toPx())
                        val topLeft = Offset(x0, rowTop + 6.dp.toPx())
                        val rectSize = Size(width, rowH - 12.dp.toPx())
                        drawRect(color = color.copy(alpha = 0.9f), topLeft = topLeft, size = rectSize)
                        if (interval == selected) {
                            drawRect(
                                color = Color.White,
                                topLeft = topLeft,
                                size = rectSize,
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }

                // Сьогодні: тонка вертикаль "зараз".
                data.nowSec?.let { now ->
                    val x = xFor(now.toFloat())
                    drawLine(TextMuted.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
                }

                // Лінія спостереження.
                if (cursorSec != null) {
                    val x = xFor(cursorSec)
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(x, 0f), Offset(x, plotBottom), strokeWidth = 1.5.dp.toPx())
                }
            }
        }
    }
}
