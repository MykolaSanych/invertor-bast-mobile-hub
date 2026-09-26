package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.chapay.homehub.compose.ui.DashboardUiState
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentBoiler
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentLoad
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlin.math.roundToInt

private data class SchemeNode(
    val id: String,
    val label: String,
    val valueText: String,
    val accent: Color,
    val icon: ImageVector,
    val xFraction: Float,
    val yFraction: Float,
    // Другий рядок під потужністю (напруга / SOC); порожній - не показуємо.
    val subText: String = "",
    // Колір піктограми, якщо вона - індикатор стану (сонце/мережа); за замовчуванням - акцент.
    val iconTint: Color = accent,
)

private data class SchemeLink(
    val fromId: String,
    val toId: String,
    val powerW: Double,
    val color: Color,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemeScreen(uiState: DashboardUiState, onBack: (() -> Unit)? = null) {
    val inverter = uiState.status.inverter
    val load = uiState.status.loadController
    val garage = uiState.status.garage

    val pvW = inverter?.pvW ?: 0.0
    val gridW = if ((inverter?.lineVoltage ?: 0.0) < 50.0) 0.0 else (inverter?.gridW ?: 0.0)
    val batteryW = inverter?.batteryPower ?: 0.0
    val loadW = inverter?.loadW ?: 0.0
    val boiler1W = load?.boilerPower ?: 0.0
    val pumpW = load?.pumpPower ?: 0.0
    val boiler2W = garage?.boilerPower ?: 0.0

    val nodes = listOf(
        SchemeNode(
            "pv", "СОНЦЕ", "${pvW.roundToInt()} Вт", AccentPv, Icons.Filled.WbSunny, 0.22f, 0.09f,
            subText = inverter?.let { "${it.pvVoltage.roundToInt()} В" } ?: "",
            iconTint = pvIndicatorColor(inverter),
        ),
        SchemeNode(
            "grid", "МЕРЕЖА", "${gridW.roundToInt()} Вт", AccentGrid, Icons.Filled.ElectricBolt, 0.78f, 0.09f,
            subText = inverter?.let { "${it.lineVoltage.roundToInt()} В" } ?: "",
            iconTint = gridIndicatorColor(inverter),
        ),
        SchemeNode("inverter", "ІНВЕРТОР", "", TextPrimary, Icons.Filled.Memory, 0.5f, 0.30f),
        SchemeNode(
            "battery", "АКБ", "${batteryW.roundToInt()} Вт", AccentBattery, Icons.Filled.BatteryChargingFull, 0.22f, 0.51f,
            subText = inverter?.let { "SOC ${it.batterySoc.roundToInt()}%" } ?: "",
        ),
        SchemeNode(
            "load", "БУДИНОК", "${loadW.roundToInt()} Вт", AccentLoad, Icons.Filled.Home, 0.78f, 0.51f,
            subText = inverter?.let { "${it.outputVoltage.roundToInt()} В" } ?: "",
        ),
        SchemeNode("boiler1", "БОЙЛЕР 1", "${boiler1W.roundToInt()} Вт", AccentBoiler, Icons.Filled.Whatshot, 0.18f, 0.74f),
        SchemeNode("pump", "НАСОС", "${pumpW.roundToInt()} Вт", AccentPump, Icons.Filled.Water, 0.5f, 0.74f),
        SchemeNode("boiler2", "БОЙЛЕР 2", "${boiler2W.roundToInt()} Вт", AccentBoiler, Icons.Filled.Whatshot, 0.82f, 0.74f),
    )
    val nodeById = nodes.associateBy { it.id }

    val links = listOf(
        SchemeLink("pv", "inverter", pvW, AccentPv),
        SchemeLink("grid", "inverter", gridW, AccentGrid),
        SchemeLink("inverter", "battery", batteryW, AccentBattery),
        SchemeLink("inverter", "load", loadW, AccentLoad),
        SchemeLink("load", "boiler1", boiler1W, AccentBoiler),
        SchemeLink("load", "pump", pumpW, AccentPump),
        SchemeLink("load", "boiler2", boiler2W, AccentBoiler),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Схема живлення") },
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()

            SchemeLinks(
                links = links,
                nodeById = nodeById,
                widthPx = widthPx,
                heightPx = heightPx,
            )

            nodes.forEach { node ->
                // Центруємо картку на вузлі за її реальним розміром: висота
                // різна (є/немає рядка напруги чи SOC), а лінії тягнуться
                // рівно в центр вузла.
                SchemeNodeChip(
                    node = node,
                    modifier = Modifier.layout { measurable, c ->
                        val p = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
                        layout(c.maxWidth, c.maxHeight) {
                            p.place(
                                (c.maxWidth * node.xFraction - p.width / 2f).roundToInt(),
                                (c.maxHeight * node.yFraction - p.height / 2f).roundToInt(),
                            )
                        }
                    },
                )
            }
        }
    }
}

// Довжина патерну пунктиру (16 риска + 12 проміжок) - крок, після якого фаза анімації
// зациклюється.
private const val DASH_PATTERN_LENGTH = 28f

// Швидкість "руху" пунктиру: росте з потужністю (корінь, щоб кіловати не розганяли лінію
// до нечитабельного миготіння), але має видиму нижню межу навіть для дрібної потужності.
private fun dashSpeedPxPerMs(powerW: Double): Float =
    0.010f + 0.00045f * kotlin.math.sqrt(kotlin.math.abs(powerW)).toFloat()

@Composable
private fun SchemeLinks(
    links: List<SchemeLink>,
    nodeById: Map<String, SchemeNode>,
    widthPx: Float,
    heightPx: Float,
) {
    // Один наскрізний "годинник" (мс від першої композиції), а не Animatable з фіксованою
    // тривалістю - бо кожна лінія тепер рухається зі своєю швидкістю, яка до того ж сама
    // міняється з часом (стежить за живою потужністю).
    var elapsedMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            elapsedMs += (nowNanos - lastFrameNanos) / 1_000_000f
            lastFrameNanos = nowNanos
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        links.forEach { link ->
            val from = nodeById[link.fromId] ?: return@forEach
            val to = nodeById[link.toId] ?: return@forEach
            val start = Offset(from.xFraction * widthPx, from.yFraction * heightPx)
            val end = Offset(to.xFraction * widthPx, to.yFraction * heightPx)
            val flowing = kotlin.math.abs(link.powerW) > 5.0

            if (flowing) {
                // Від'ємна потужність (зараз лише в акумулятора: розряджається, віддає
                // струм) - лінія рухається у зворотний бік, від "to" до "from", хоча сама
                // геометрія (кінці лінії) не змінюється.
                val reversed = link.powerW < 0.0
                val phase = (elapsedMs * dashSpeedPxPerMs(link.powerW)) % DASH_PATTERN_LENGTH
                drawLine(
                    color = link.color.copy(alpha = 0.85f),
                    start = start,
                    end = end,
                    strokeWidth = 5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), if (reversed) phase else -phase),
                )
            } else {
                drawLine(
                    color = OutlineSubtle,
                    start = start,
                    end = end,
                    strokeWidth = 2f,
                )
            }
        }
    }
}

@Composable
private fun SchemeNodeChip(node: SchemeNode, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.width(88.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        border = BorderStroke(1.dp, node.accent.copy(alpha = 0.4f)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = node.icon,
                contentDescription = null,
                tint = node.iconTint,
                modifier = Modifier
                    .size(22.dp)
                    .padding(bottom = 2.dp),
            )
            Text(
                text = node.label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
            )
            if (node.valueText.isNotEmpty()) {
                Text(
                    text = node.valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    maxLines = 1,
                )
            }
            if (node.subText.isNotEmpty()) {
                Text(
                    text = node.subText,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}
