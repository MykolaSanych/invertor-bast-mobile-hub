package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.nativeCanvas
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

private data class TimelineSample(val minute: Int, val flags: Int)

private val ROW_DEFS = listOf(
    Triple("БОЙЛЕР 1", 0x01, AccentBoiler),
    Triple("НАСОС", 0x02, AccentPump),
    Triple("МЕРЕЖА", 0x04, AccentGrid),
    Triple("СОНЦЕ", 0x08, AccentPv),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(config: AppConfig, repository: StatusRepository, onBack: () -> Unit) {
    LockLandscapeOrientation()
    var date by remember { mutableStateOf(todayIso()) }
    var samples by remember { mutableStateOf<List<TimelineSample>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(date, config) {
        loading = true
        val payload = runCatching { repository.fetchLoadControllerHistory(config, date) }.getOrNull()
        val rows = payload?.optJSONArray("samples")
        val parsed = mutableListOf<TimelineSample>()
        if (rows != null) {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                parsed.add(TimelineSample(row.optInt("m", -1), row.optInt("f", 0)))
            }
        }
        samples = parsed.filter { it.minute in 0..1439 }.sortedBy { it.minute }
        loading = false
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
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (samples.isEmpty()) {
                Text(
                    text = "немає даних за цю добу",
                    color = TextMuted,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                TimelineChart(samples = samples)
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    ROW_DEFS.forEach { (label, _, color) ->
                        Row(
                            modifier = Modifier.padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(10.dp)
                                    .height(10.dp)
                                    .padding(end = 2.dp),
                            ) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawCircle(color)
                                }
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

@Composable
private fun TimelineChart(samples: List<TimelineSample>) {
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
                .padding(12.dp),
        ) {
            val labelWidth = 72.dp.toPx()
            val plotWidth = size.width - labelWidth
            val plotTop = 0f
            val rowH = (size.height - 20.dp.toPx()) / ROW_DEFS.size
            val minutesInDay = 1440f

            fun xFor(minute: Int) = labelWidth + (minute / minutesInDay) * plotWidth

            // Погодинні лінії сітки.
            for (h in 0..24) {
                val x = xFor(h * 60)
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

            ROW_DEFS.forEachIndexed { rowIndex, (label, bit, color) ->
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
                    size = androidx.compose.ui.geometry.Size(plotWidth, rowH - 8.dp.toPx()),
                )

                var segmentStartMinute: Int? = null
                for (sample in samples) {
                    val isOn = (sample.flags and bit) != 0
                    if (isOn && segmentStartMinute == null) {
                        segmentStartMinute = sample.minute
                    }
                    if (!isOn && segmentStartMinute != null) {
                        drawRect(
                            color = color.copy(alpha = 0.9f),
                            topLeft = Offset(xFor(segmentStartMinute), rowTop + 6.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(
                                (xFor(sample.minute) - xFor(segmentStartMinute)).coerceAtLeast(2f),
                                rowH - 12.dp.toPx(),
                            ),
                        )
                        segmentStartMinute = null
                    }
                }
                if (segmentStartMinute != null) {
                    val lastMinute = samples.last().minute
                    drawRect(
                        color = color.copy(alpha = 0.9f),
                        topLeft = Offset(xFor(segmentStartMinute), rowTop + 6.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(
                            (xFor(lastMinute) - xFor(segmentStartMinute)).coerceAtLeast(2f),
                            rowH - 12.dp.toPx(),
                        ),
                    )
                }
            }
        }
    }
}
