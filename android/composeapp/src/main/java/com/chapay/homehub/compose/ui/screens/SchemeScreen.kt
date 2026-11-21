package com.chapay.homehub.compose.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapay.homehub.compose.data.SchemeLayoutStore
import com.chapay.homehub.compose.ui.DashboardUiState
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentBoiler
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentLoad
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.IndicatorOff
import com.chapay.homehub.compose.ui.theme.OutlineSubtle
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlin.math.roundToInt

private data class SchemeNode(
    val id: String,
    val label: String,
    val valueText: String,
    val accent: Color,
    val icon: ImageVector,
    // Другий рядок під потужністю (напруга / SOC); порожній - не показуємо.
    val subText: String = "",
    // Колір піктограми, якщо вона - індикатор стану (сонце/мережа); за замовчуванням - акцент.
    val iconTint: Color = accent,
) {
    // "Активний" - той самий принцип, що й на "Домі": чорна піктограма
    // (pvIndicatorColor/gridIndicatorColor для сонця/мережі) означає "немає" -
    // тоді й світіння за іконкою не показуємо.
    val active: Boolean get() = iconTint != IndicatorOff
}

private data class SchemeLink(
    val fromId: String,
    val toId: String,
    val powerW: Double,
    val color: Color,
)

/**
 * "Схема" - та сама жива естетика, що й "Дім" (темний градієнт, зорі,
 * туманності, скляні світні вузли): плоский білястий Material-стиль із
 * рамками-картками навмисно прибрано, щоб екран не виділявся з решти
 * застосунку. Кожен вузол можна перетягнути пальцем (довге натискання + рух -
 * той самий жест, що й на "Домі"), позиція зберігається локально
 * ([SchemeLayoutStore]) в частках ширини/висоти екрана (не в dp) - працює
 * однаково на будь-якому розмірі екрана.
 */
@Composable
fun SchemeScreen(
    uiState: DashboardUiState,
    onBack: (() -> Unit)? = null,
    // id вузла, по якому тапнули ("pv"/"grid"/"inverter"/"battery"/"load"/
    // "boiler1"/"pump"/"boiler2") - відкриває його додаткові параметри.
    onNodeTap: (String) -> Unit = {},
) {
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
            "pv", "СОНЦЕ", "${pvW.roundToInt()} Вт", AccentPv, Icons.Filled.WbSunny,
            subText = inverter?.let { "${it.pvVoltage.roundToInt()} В" } ?: "",
            iconTint = pvIndicatorColor(inverter),
        ),
        SchemeNode(
            "grid", "МЕРЕЖА", "${gridW.roundToInt()} Вт", AccentGrid, Icons.Filled.ElectricBolt,
            subText = inverter?.let { "${it.lineVoltage.roundToInt()} В" } ?: "",
            iconTint = gridIndicatorColor(inverter),
        ),
        SchemeNode("inverter", "ІНВЕРТОР", "", TextPrimary, Icons.Filled.Memory),
        SchemeNode(
            "battery", "АКБ", "${batteryW.roundToInt()} Вт", AccentBattery, Icons.Filled.BatteryChargingFull,
            subText = inverter?.let { "SOC ${it.batterySoc.roundToInt()}%" } ?: "",
        ),
        SchemeNode(
            "load", "БУДИНОК", "${loadW.roundToInt()} Вт", AccentLoad, Icons.Filled.Home,
            subText = inverter?.let { "${it.outputVoltage.roundToInt()} В" } ?: "",
        ),
        SchemeNode("boiler1", "БОЙЛЕР 1", "${boiler1W.roundToInt()} Вт", AccentBoiler, Icons.Filled.Whatshot),
        SchemeNode("pump", "НАСОС", "${pumpW.roundToInt()} Вт", AccentPump, Icons.Filled.Water),
        SchemeNode("boiler2", "БОЙЛЕР 2", "${boiler2W.roundToInt()} Вт", AccentBoiler, Icons.Filled.Whatshot),
    )
    val links = listOf(
        SchemeLink("pv", "inverter", pvW, AccentPv),
        SchemeLink("grid", "inverter", gridW, AccentGrid),
        SchemeLink("inverter", "battery", batteryW, AccentBattery),
        SchemeLink("inverter", "load", loadW, AccentLoad),
        SchemeLink("load", "boiler1", boiler1W, AccentBoiler),
        SchemeLink("load", "pump", pumpW, AccentPump),
        SchemeLink("load", "boiler2", boiler2W, AccentBoiler),
    )

    val context = LocalContext.current
    var layout by remember { mutableStateOf(SchemeLayoutStore.load(context)) }
    fun posFor(id: String) = SchemeLayoutStore.posFor(layout, id)

    Scaffold(containerColor = Color(0xFF0A0C14)) { innerPadding ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(Brush.verticalGradient(listOf(Color(0xFF181C28), Color(0xFF0A0C14), Color(0xFF05060A)))),
    ) {
        StarField()

        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = TextPrimary)
                    }
                }
                Text("Схема", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val widthPx = constraints.maxWidth.toFloat()
                val heightPx = constraints.maxHeight.toFloat()
                val density = LocalDensity.current.density

                // Межі перетягування в частках екрана - щоб вузол не виїхав за край
                // (0.1/0.08 - приблизно половина ширини/висоти плашки вузла).
                fun dragNode(id: String): Pair<(Float, Float) -> Unit, () -> Unit> {
                    val onDrag: (Float, Float) -> Unit = { dxDp, dyDp ->
                        val (x, y) = posFor(id)
                        val newX = (x + dxDp * density / widthPx).coerceIn(0.10f, 0.90f)
                        val newY = (y + dyDp * density / heightPx).coerceIn(0.07f, 0.93f)
                        layout = SchemeLayoutStore.withPos(layout, id, newX, newY)
                    }
                    val onDragEnd: () -> Unit = { SchemeLayoutStore.save(context, layout) }
                    return onDrag to onDragEnd
                }

                // Туманності - лише за кількома ключовими вузлами (сонце/мережа/АКБ),
                // той самий прийом, що й на "Домі": велика розмита кольорова пляма,
                // центрована на вузлі; рухається разом із ним при перетягуванні.
                AuroraBlob(
                    color = AccentPv, size = 200.dp, periodMs = 22_000,
                    offsetX = 0.dp, offsetY = 0.dp,
                    modifier = Modifier.centerAtFraction(posFor("pv").first, posFor("pv").second, widthPx, heightPx),
                )
                AuroraBlob(
                    color = AccentGrid, size = 190.dp, periodMs = 19_000,
                    offsetX = 0.dp, offsetY = 0.dp,
                    modifier = Modifier.centerAtFraction(posFor("grid").first, posFor("grid").second, widthPx, heightPx),
                )
                AuroraBlob(
                    color = AccentBattery, size = 210.dp, periodMs = 26_000,
                    offsetX = 0.dp, offsetY = 0.dp,
                    modifier = Modifier.centerAtFraction(posFor("battery").first, posFor("battery").second, widthPx, heightPx),
                )

                SchemeLinks(
                    links = links,
                    positions = nodes.associate { it.id to posFor(it.id) },
                    widthPx = widthPx,
                    heightPx = heightPx,
                )

                // Лінії тягнуться рівно в точку (xFraction,yFraction) - тож чіп
                // розміщуємо так, щоб у ЦІЙ точці опинився центр саме кружечка-іконки
                // (52dp, перший елемент колонки), а не центр усієї колонки з підписами
                // під ним - інакше лінія "не дотягувалась" би до кружечка.
                val iconCircleSizePx = 52.dp.value * density
                nodes.forEach { node ->
                    val (x, y) = posFor(node.id)
                    val (onDrag, onDragEnd) = dragNode(node.id)
                    SchemeNodeChip(
                        node = node,
                        onTap = { onNodeTap(node.id) },
                        modifier = Modifier
                            .layout { measurable, c ->
                                val p = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
                                layout(c.maxWidth, c.maxHeight) {
                                    p.place(
                                        (widthPx * x - p.width / 2f).roundToInt(),
                                        (heightPx * y - iconCircleSizePx / 2f).roundToInt(),
                                    )
                                }
                            }
                            .dragToReposition(onDrag = onDrag, onDragEnd = onDragEnd),
                    )
                }

                ClimateCorner(
                    uiState = uiState,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
    }
}

// Розміщує елемент так, щоб його ЦЕНТР (не top-left) опинився точно в точці
// (xFraction*ширина, yFraction*висота) - за РЕАЛЬНИМ виміряним розміром
// елемента (не фіксованим числом): і вузли (різна висота - є/немає рядка
// напруги), і туманності (фіксований, але свій для кожної), і лінії між ними
// (SchemeLinks) рахують ту саму точку з тих самих часток - тому завжди
// збігаються.
private fun Modifier.centerAtFraction(xFraction: Float, yFraction: Float, widthPx: Float, heightPx: Float): Modifier =
    layout { measurable, c ->
        val p = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
        layout(c.maxWidth, c.maxHeight) {
            p.place(
                (widthPx * xFraction - p.width / 2f).roundToInt(),
                (heightPx * yFraction - p.height / 2f).roundToInt(),
            )
        }
    }

// Невеликий, не-картковий індикатор у кутку схеми: температура з усіх фізично
// підключених BME280 (внутрішній+зовнішній інвертора, навантаження, гараж) -
// без окремого екрана/картки, просто щоб показати, що дані є.
@Composable
private fun ClimateCorner(uiState: DashboardUiState, modifier: Modifier = Modifier) {
    val inverter = uiState.status.inverter
    val load = uiState.status.loadController
    val garage = uiState.status.garage

    val rows = buildList {
        if (inverter?.bmeAvailable == true && inverter.bmeTemp != null) {
            add("Інвертор" to inverter.bmeTemp)
        }
        if (inverter?.bmeExtAvailable == true && inverter.bmeExtTemp != null) {
            add("Інвертор (зовн.)" to inverter.bmeExtTemp)
        }
        if (load?.bmeAvailable == true && load.bmeTemp != null) {
            add("Навантаження" to load.bmeTemp)
        }
        if (garage?.bmeAvailable == true && garage.bmeTemp != null) {
            add("Гараж" to garage.bmeTemp)
        }
    }
    if (rows.isEmpty()) return

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        rows.forEach { (label, temp) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Thermostat,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$label: ${"%.1f".format(temp)}°C",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    maxLines = 1,
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
    positions: Map<String, Pair<Float, Float>>,
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
            val from = positions[link.fromId] ?: return@forEach
            val to = positions[link.toId] ?: return@forEach
            val start = Offset(from.first * widthPx, from.second * heightPx)
            val end = Offset(to.first * widthPx, to.second * heightPx)
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
private fun SchemeNodeChip(node: SchemeNode, onTap: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (node.active) Glow(color = node.accent, modifier = Modifier.size(52.dp), baseAlpha = 0.4f)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(node.accent.copy(alpha = if (node.active) 0.16f else 0.08f))
                    .border(1.5.dp, node.accent.copy(alpha = if (node.active) 0.45f else 0.2f), CircleShape)
                    .clickable(onClick = onTap),
                contentAlignment = Alignment.Center,
            ) {
                Icon(node.icon, contentDescription = "${node.label} - детальніше", tint = node.iconTint, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(node.label, style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
        if (node.valueText.isNotEmpty()) {
            Text(node.valueText, style = MaterialTheme.typography.labelSmall, color = node.accent, maxLines = 1)
        }
        if (node.subText.isNotEmpty()) {
            Text(node.subText, style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 9.sp, maxLines = 1)
        }
    }
}
