package com.chapay.homehub.compose.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
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
)

private data class SchemeLink(
    val fromId: String,
    val toId: String,
    val powerW: Double,
    val color: Color,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemeScreen(uiState: DashboardUiState, onBack: () -> Unit) {
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
        SchemeNode("pv", "СОНЦЕ", "${pvW.roundToInt()} Вт", AccentPv, Icons.Filled.WbSunny, 0.22f, 0.09f),
        SchemeNode("grid", "МЕРЕЖА", "${gridW.roundToInt()} Вт", AccentGrid, Icons.Filled.ElectricBolt, 0.78f, 0.09f),
        SchemeNode("inverter", "ІНВЕРТОР", "", TextPrimary, Icons.Filled.Memory, 0.5f, 0.30f),
        SchemeNode("battery", "АКБ", "${batteryW.roundToInt()} Вт", AccentBattery, Icons.Filled.BatteryChargingFull, 0.22f, 0.51f),
        SchemeNode("load", "БУДИНОК", "${loadW.roundToInt()} Вт", AccentLoad, Icons.Filled.Home, 0.78f, 0.51f),
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
                SchemeNodeChip(
                    node = node,
                    modifier = Modifier
                        .offset(
                            x = (maxWidth * node.xFraction) - 44.dp,
                            y = (maxHeight * node.yFraction) - 30.dp,
                        ),
                )
            }
        }
    }
}

@Composable
private fun SchemeLinks(
    links: List<SchemeLink>,
    nodeById: Map<String, SchemeNode>,
    widthPx: Float,
    heightPx: Float,
) {
    val infinite = rememberInfiniteTransition(label = "scheme-flow")
    val dashPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 48f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dash-phase",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        links.forEach { link ->
            val from = nodeById[link.fromId] ?: return@forEach
            val to = nodeById[link.toId] ?: return@forEach
            val start = Offset(from.xFraction * widthPx, from.yFraction * heightPx)
            val end = Offset(to.xFraction * widthPx, to.yFraction * heightPx)
            val flowing = kotlin.math.abs(link.powerW) > 5.0

            if (flowing) {
                drawLine(
                    color = link.color.copy(alpha = 0.85f),
                    start = start,
                    end = end,
                    strokeWidth = 5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), -dashPhase),
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
                tint = node.accent,
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
        }
    }
}
