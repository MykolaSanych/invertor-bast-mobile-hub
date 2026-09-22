package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.chapay.homehub.compose.ui.components.todayIso
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlin.math.roundToInt

private data class HourBar(val hour: Int, val pv: Double, val home: Double, val grid: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyChartScreen(config: AppConfig, repository: StatusRepository) {
    var date by remember { mutableStateOf(todayIso()) }
    var bars by remember { mutableStateOf<List<HourBar>>(emptyList()) }
    var totals by remember { mutableStateOf(Triple(0.0, 0.0, 0.0)) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(date, config) {
        loading = true
        errorText = null
        val payload = runCatching { repository.fetchInverterDaily(config, date) }.getOrNull()
        if (payload == null) {
            errorText = "немає даних (модуль інвертора недоступний або дані за цю добу відсутні)"
            bars = emptyList()
        } else {
            val hoursArr = payload.optJSONArray("hours")
            val parsed = mutableListOf<HourBar>()
            if (hoursArr != null) {
                for (i in 0 until hoursArr.length()) {
                    val row = hoursArr.optJSONObject(i) ?: continue
                    parsed.add(
                        HourBar(
                            hour = row.optInt("hour", i),
                            pv = row.optDouble("pv", 0.0),
                            home = row.optDouble("home", 0.0),
                            grid = row.optDouble("grid", 0.0),
                        ),
                    )
                }
            }
            bars = parsed
            val t = payload.optJSONObject("totals")
            totals = Triple(
                t?.optDouble("dailyPV", 0.0) ?: 0.0,
                t?.optDouble("dailyHome", 0.0) ?: 0.0,
                t?.optDouble("dailyGrid", 0.0) ?: 0.0,
            )
        }
        loading = false
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Графік енергії") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp),
        ) {
            DateNavHeader(date = date, onDateChange = { date = it })
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                errorText != null -> Text(errorText.orEmpty(), color = TextMuted, modifier = Modifier.padding(16.dp))
                else -> {
                    TotalsRow(totals)
                    Spacer(Modifier.height(12.dp))
                    Legend()
                    Spacer(Modifier.height(8.dp))
                    EnergyBarChart(bars)
                }
            }
        }
    }
}

@Composable
private fun TotalsRow(totals: Triple<Double, Double, Double>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TotalChip("сонце", totals.first, AccentPv)
        TotalChip("будинок", totals.second, AccentBattery)
        TotalChip("мережа", totals.third, AccentGrid)
    }
}

@Composable
private fun TotalChip(label: String, value: Double, color: androidx.compose.ui.graphics.Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Text(
                text = "${value.roundToInt()} Вт·год",
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun Legend() {
    Row {
        LegendDot("сонце", AccentPv)
        Spacer(Modifier.width(12.dp))
        LegendDot("будинок", AccentBattery)
        Spacer(Modifier.width(12.dp))
        LegendDot("мережа", AccentGrid)
    }
}

@Composable
private fun LegendDot(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun EnergyBarChart(bars: List<HourBar>) {
    if (bars.isEmpty()) {
        Text("немає погодинних даних", color = TextMuted, modifier = Modifier.padding(16.dp))
        return
    }
    val maxValue = (bars.maxOfOrNull { maxOf(it.pv, it.home, it.grid) } ?: 1.0).coerceAtLeast(1.0)
    val hourWidth = 34.dp
    val chartWidth = hourWidth * bars.size
    val chartHeightDp = 220.dp

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Canvas(
                modifier = Modifier
                    .width(chartWidth)
                    .height(chartHeightDp),
            ) {
                val plotHeight = size.height - 20.dp.toPx()
                val barGroupWidth = hourWidth.toPx()
                val barWidth = barGroupWidth / 4f

                bars.forEachIndexed { index, bar ->
                    val groupLeft = index * barGroupWidth
                    listOf(bar.pv to AccentPv, bar.home to AccentBattery, bar.grid to AccentGrid)
                        .forEachIndexed { seriesIndex, (value, color) ->
                            val barHeight = (value / maxValue * plotHeight).toFloat().coerceAtLeast(0f)
                            drawRect(
                                color = color,
                                topLeft = Offset(
                                    groupLeft + seriesIndex * barWidth + 2f,
                                    plotHeight - barHeight,
                                ),
                                size = androidx.compose.ui.geometry.Size(barWidth - 4f, barHeight),
                            )
                        }
                    if (index % 3 == 0) {
                        drawContext.canvas.nativeCanvas.drawText(
                            "${bar.hour}",
                            groupLeft + barGroupWidth / 2f,
                            size.height - 4.dp.toPx(),
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#9BA3B4")
                                textSize = 9.sp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                            },
                        )
                    }
                }
            }
        }
    }
}
