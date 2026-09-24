package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapay.homehub.compose.data.UnifiedStatus
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentBoiler
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlin.math.roundToInt

private data class PieSlice(val label: String, val value: Double, val color: Color)

// Другий бойлер - світліший відтінок першого: обидва "тепло", але розрізняються.
private val AccentBoiler2 = Color(0xFFF0B27A)

private fun finite(v: Double?): Double = if (v == null || v.isNaN() || v < 0.0) 0.0 else v

/** Скільки кВт·год / кВт замість Вт·год / Вт, коли значення велике. */
private fun formatAmount(value: Double, perDay: Boolean): String {
    val big = value >= 1000.0
    val number = if (big) "%.2f".format(value / 1000.0) else value.roundToInt().toString()
    val unit = when {
        perDay && big -> "кВт·год"
        perDay -> "Вт·год"
        big -> "кВт"
        else -> "Вт"
    }
    return "$number $unit"
}

/**
 * Кругова діаграма розподілу енергії.
 *  - "Джерела": сонце проти мережі.
 *  - "Споживачі": бойлер 1, бойлер 2, насос і "будинок" - решта навантаження,
 *    яку рахуємо різницею: загальне навантаження мінус виміряні бойлери й насос.
 * Період: "За добу" (Вт·год, лічильники модулів) або "Зараз" (миттєва потужність, Вт).
 * Розбивка по споживачах є лише для сьогодні - лічильники бойлерів/насоса модулі
 * за минулі доби не зберігають; джерела за минулу добу, місяць чи рік беруться з
 * історії інвертора (savedTotals, Вт·год).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyPieSection(
    status: UnifiedStatus,
    savedTotals: Triple<Double, Double, Double>,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    // Доба (є режим "Зараз") чи місяць/рік (лише підсумки за період із історії інвертора).
    dayMode: Boolean = true,
    periodLabel: String = "За добу",
) {
    var consumers by rememberSaveable { mutableStateOf(false) }
    var live by rememberSaveable { mutableStateOf(false) }
    val perDay = !(live && isToday)

    val inverter = status.inverter
    val load = status.loadController
    val garage = status.garage

    val slices: List<PieSlice>? = if (!consumers) {
        if (perDay) {
            val (pv, grid) = if (isToday && inverter != null) {
                finite(inverter.dailyPv) to finite(inverter.dailyGrid)
            } else {
                finite(savedTotals.first) to finite(savedTotals.third)
            }
            listOf(PieSlice("Сонце", pv, AccentPv), PieSlice("Мережа", grid, AccentGrid))
        } else {
            // Без напруги мережі показник потужності - лише шум датчика.
            val gridW = if ((inverter?.lineVoltage ?: 0.0) < 50.0) 0.0 else finite(inverter?.gridW)
            listOf(PieSlice("Сонце", finite(inverter?.pvW), AccentPv), PieSlice("Мережа", gridW, AccentGrid))
        }
    } else if (!isToday) {
        null
    } else {
        val total: Double
        val boiler1: Double
        val boiler2: Double
        val pump: Double
        if (perDay) {
            total = finite(inverter?.dailyHome)
            boiler1 = finite(load?.dailyBoiler)
            boiler2 = finite(garage?.dailyBoiler)
            pump = finite(load?.dailyPump)
        } else {
            total = finite(inverter?.loadW)
            boiler1 = finite(load?.boilerPower)
            boiler2 = finite(garage?.boilerPower)
            pump = finite(load?.pumpPower)
        }
        // "Будинок" - різниця: усе, що спожито понад виміряні бойлери й насос.
        val house = (total - boiler1 - boiler2 - pump).coerceAtLeast(0.0)
        listOf(
            PieSlice("Бойлер 1", boiler1, AccentBoiler),
            PieSlice("Бойлер 2", boiler2, AccentBoiler2),
            PieSlice("Насос", pump, AccentPump),
            PieSlice("Будинок", house, AccentBattery),
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !consumers, onClick = { consumers = false }, label = { Text("Джерела") })
            FilterChip(selected = consumers, onClick = { consumers = true }, label = { Text("Споживачі") })
            Spacer(Modifier.width(16.dp))
            FilterChip(selected = perDay, onClick = { live = false }, label = { Text(periodLabel) })
            if (dayMode) {
                FilterChip(
                    selected = !perDay,
                    onClick = { live = true },
                    enabled = isToday,
                    label = { Text("Зараз") },
                )
            }
        }

        if (slices == null) {
            Text(
                text = "Розбивка по споживачах (бойлери, насос, будинок) доступна лише за сьогодні: " +
                    "модулі не зберігають їхні лічильники за минулі дні, місяці й роки.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        val total = slices.sumOf { it.value }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Donut(slices = slices, total = total, centerText = formatAmount(total, perDay))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                slices.forEach { slice ->
                    val percent = if (total > 0.0) slice.value / total * 100.0 else 0.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(slice.color, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(slice.label, style = MaterialTheme.typography.labelLarge, color = TextMuted, modifier = Modifier.width(76.dp))
                        Text(
                            text = formatAmount(slice.value, perDay),
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary,
                            modifier = Modifier.width(110.dp),
                        )
                        Text(
                            text = "${percent.roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextMuted,
                        )
                    }
                }
                if (consumers) {
                    Text(
                        text = "«Будинок» = загальне навантаження мінус бойлери й насос",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun Donut(slices: List<PieSlice>, total: Double, centerText: String) {
    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
        val stroke = size.minDimension * 0.18f
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        if (total <= 0.0) {
            drawArc(OutlineSubtle, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
        } else {
            val visible = slices.filter { it.value > 0.0 }
            // Невеликий проміжок між секторами, якщо їх більше одного.
            val gap = if (visible.size > 1) 2f else 0f
            var angle = -90f
            visible.forEach { slice ->
                val sweep = (slice.value / total * 360.0).toFloat()
                drawArc(
                    color = slice.color,
                    startAngle = angle + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke),
                )
                angle += sweep
            }
        }

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#F0F2F8")
            textSize = 13.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        drawContext.canvas.nativeCanvas.drawText(centerText, size.width / 2f, size.height / 2f + paint.textSize / 3f, paint)
    }
}
