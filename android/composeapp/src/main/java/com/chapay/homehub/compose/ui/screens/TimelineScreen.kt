package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapay.homehub.compose.data.AppConfig
import com.chapay.homehub.compose.data.StatusRepository
import com.chapay.homehub.compose.ui.components.DateNavHeader
import com.chapay.homehub.compose.ui.components.LockLandscapeOrientation
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
fun TimelineScreen(config: AppConfig, repository: StatusRepository, onBack: () -> Unit) {
    LockLandscapeOrientation()
    var date by remember { mutableStateOf(todayIso()) }
    var data by remember { mutableStateOf<TimelineData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<TimelineInterval?>(null) }

    // Той самий цикл, що і в EnergyChartScreen: вкладка лишається живою в
    // пам'яті між перемиканнями нижньої навігації (saveState/restoreState),
    // тож одноразового ефекту не досить - інакше хронологія "замерзає" на
    // моменті першого відкриття вкладки.
    LaunchedEffect(date, config) {
        // Нова дата/конфіг - старі лінії чужої доби не показуємо.
        data = null
        selected = null
        var firstLoad = true
        var timelineSeen = false
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
                .padding(12.dp),
        ) {
            DateNavHeader(date = date, onDateChange = { date = it })
            Spacer(Modifier.height(8.dp))
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
                TimelineChart(data = current, selected = selected, onSelect = { selected = it })
                Spacer(Modifier.height(8.dp))
                Text(
                    text = selected?.let { describeInterval(it) }
                        ?: if (current.intervals.isEmpty()) "за цю добу ще нічого не вмикалось" else "торкніться лінії, щоб побачити час",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected != null) TextPrimary else TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    ROW_DEFS.forEach { (label, color) ->
                        Row(
                            modifier = Modifier.padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.width(10.dp).height(10.dp)) {
                                Canvas(Modifier.fillMaxSize()) { drawCircle(color) }
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    }
                }
            }
        }
    }
}

private fun hms(sec: Int): String {
    val s = sec.coerceIn(0, SECONDS_IN_DAY)
    return "%02d:%02d:%02d".format(s / 3600 % 24, s / 60 % 60, s % 60)
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

@Composable
private fun TimelineChart(
    data: TimelineData,
    selected: TimelineInterval?,
    onSelect: (TimelineInterval?) -> Unit,
) {
    val rowHeight = 44.dp
    val chartHeight = rowHeight * ROW_DEFS.size + 24.dp

    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = SurfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .padding(12.dp)
                .pointerInput(data) {
                    detectTapGestures { tap ->
                        val labelWidth = 72.dp.toPx()
                        val plotWidth = size.width - labelWidth
                        val rowH = (size.height - 20.dp.toPx()) / ROW_DEFS.size
                        val row = (tap.y / rowH).toInt()
                        if (row !in ROW_DEFS.indices || plotWidth <= 0f) {
                            onSelect(null)
                            return@detectTapGestures
                        }
                        val tapSec = (tap.x - labelWidth) / plotWidth * SECONDS_IN_DAY
                        // Короткі лінії (секунди) тонші за палець - беремо найближчу
                        // в межах ~14dp від дотику.
                        val tolSec = 14.dp.toPx() / plotWidth * SECONDS_IN_DAY
                        val nearest = data.intervals
                            .filter { it.row == row }
                            .minByOrNull { distanceToInterval(tapSec, it) }
                        onSelect(
                            if (nearest != null && distanceToInterval(tapSec, nearest) <= tolSec) nearest else null,
                        )
                    }
                },
        ) {
            val labelWidth = 72.dp.toPx()
            val plotWidth = size.width - labelWidth
            val plotTop = 0f
            val rowH = (size.height - 20.dp.toPx()) / ROW_DEFS.size

            fun xFor(sec: Int) = labelWidth + (sec.toFloat() / SECONDS_IN_DAY) * plotWidth

            // Погодинні лінії сітки.
            for (h in 0..24) {
                val x = xFor(h * 3600)
                drawLine(
                    color = OutlineSubtle,
                    start = Offset(x, plotTop),
                    end = Offset(x, size.height - 20.dp.toPx()),
                    strokeWidth = 1f,
                )
                if (h % 3 == 0) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "$h",
                        x,
                        size.height - 4.dp.toPx(),
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#9BA3B4")
                            textSize = 9.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                        },
                    )
                }
            }

            ROW_DEFS.forEachIndexed { rowIndex, (label, color) ->
                val rowTop = plotTop + rowIndex * rowH
                val rowCenter = rowTop + rowH / 2

                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    labelWidth - 6.dp.toPx(),
                    rowCenter + 3.dp.toPx(),
                    android.graphics.Paint().apply {
                        this.color = android.graphics.Color.parseColor("#E6ECFF")
                        textSize = 9.sp.toPx()
                        textAlign = android.graphics.Paint.Align.RIGHT
                    },
                )

                // Фон рядка.
                drawRect(
                    color = color.copy(alpha = 0.06f),
                    topLeft = Offset(labelWidth, rowTop + 4.dp.toPx()),
                    size = Size(plotWidth, rowH - 8.dp.toPx()),
                )

                // Лінії "від-до". Мінімальна ширина 3dp, щоб секундні запуски
                // (на добовій шкалі це частки пікселя) були помітні.
                data.intervals.filter { it.row == rowIndex }.forEach { interval ->
                    val x0 = xFor(interval.startSec)
                    val width = (xFor(interval.endSec) - x0).coerceAtLeast(3.dp.toPx())
                    val topLeft = Offset(x0, rowTop + 6.dp.toPx())
                    val rectSize = Size(width, rowH - 12.dp.toPx())
                    drawRect(color = color.copy(alpha = 0.9f), topLeft = topLeft, size = rectSize)
                    if (interval == selected) {
                        drawRect(
                            color = androidx.compose.ui.graphics.Color.White,
                            topLeft = topLeft,
                            size = rectSize,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
            }

            // Сьогодні: тонка вертикаль "зараз".
            data.nowSec?.let { now ->
                val x = xFor(now)
                drawLine(
                    color = TextMuted.copy(alpha = 0.7f),
                    start = Offset(x, plotTop),
                    end = Offset(x, size.height - 20.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}
