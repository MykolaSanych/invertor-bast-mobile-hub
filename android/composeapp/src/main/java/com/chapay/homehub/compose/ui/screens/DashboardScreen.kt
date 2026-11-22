package com.chapay.homehub.compose.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.chapay.homehub.compose.data.ConstellationLayoutStore
import com.chapay.homehub.compose.data.DeviceTileLayout
import com.chapay.homehub.compose.data.DeviceTileLayoutStore
import com.chapay.homehub.compose.data.GarageStatus
import com.chapay.homehub.compose.data.InverterStatus
import com.chapay.homehub.compose.data.LoadControllerStatus
import com.chapay.homehub.compose.data.TileGridPos
import com.chapay.homehub.compose.ui.DashboardUiState
import com.chapay.homehub.compose.ui.DashboardViewModel
import com.chapay.homehub.compose.ui.theme.AccentBattery
import com.chapay.homehub.compose.ui.theme.AccentBoiler
import com.chapay.homehub.compose.ui.theme.AccentGate
import com.chapay.homehub.compose.ui.theme.AccentGrid
import com.chapay.homehub.compose.ui.theme.AccentLoad
import com.chapay.homehub.compose.ui.theme.AccentPump
import com.chapay.homehub.compose.ui.theme.AccentPv
import com.chapay.homehub.compose.ui.theme.IndicatorOff
import com.chapay.homehub.compose.ui.theme.PV_PRESENT_VOLTAGE
import com.chapay.homehub.compose.ui.theme.StatusAlert
import com.chapay.homehub.compose.ui.theme.StatusGood
import com.chapay.homehub.compose.ui.theme.SurfaceContainer
import com.chapay.homehub.compose.ui.theme.SurfaceContainerHigh
import com.chapay.homehub.compose.ui.theme.TextMuted
import com.chapay.homehub.compose.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "Огляд" - жива панель замість сітки карток: сонце/мережа/споживання/АКБ -
 * вузли-сателіти навколо ядра (АКБ), з'єднані лініями з частинками, що біжать
 * (декоративно - сама система "жива"); кільце заряду АКБ обертається за
 * годинниковою, коли батарея заряджається, і проти - коли розряджається
 * (напрям рахується від знаку [pickBatteryPower], не прикраса). Дотик по
 * вузлу чи плитці пристрою відкриває ТІ САМІ реальні екрани керування, що
 * і раніше (лише інший вхід, не нова логіка). Схема/Журнал - поза цим
 * екраном, лишаються без змін.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onTriggerGate: () -> Unit,
    onToggleLight: () -> Unit,
    // module: "invertor-bast" / "load_controller" / "garage" - чий журнал відкрити.
    onOpenJournal: (module: String) -> Unit = {},
    // metric: "grid" / "pv" / "battery" - 24-годинний графік якої напруги відкрити.
    onOpenVoltageChart: (metric: String) -> Unit = {},
    // Насос Sirio - повний екран налаштувань (окрема прошивка, свій API). Поки
    // частотник фізично не введено в експлуатацію, насос - звичайне реле, як
    // і бойлери, - цей колбек лишається непідключеним тут (далі лишається у
    // виклику зі Схеми, яку не чіпаємо).
    onOpenSirio: () -> Unit = {},
    // Меню пристроїв/інвертора тепер - діалог (шторка) поверх цього самого
    // екрана, не окремий повноекранний маршрут (той лишається для Схеми) -
    // тому тут потрібен прямий доступ до viewModel (дії керування, стан
    // налаштувань інвертора), а не лише готові колбеки навігації.
    viewModel: DashboardViewModel,
) {
    val inverter = uiState.status.inverter
    val loadController = uiState.status.loadController
    val garage = uiState.status.garage
    // Модуль, що давно не відповів (лагає зв'язок), - картки тьмяніють, дані лишаються.
    val nowMs = System.currentTimeMillis()
    fun isStale(atMs: Long) = atMs > 0L && nowMs - atMs > STALE_DATA_MS
    val inverterStale = isStale(uiState.inverterAtMs)
    val loadStale = isStale(uiState.loadAtMs)
    val garageStale = isStale(uiState.garageAtMs)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inverterSettingsState by viewModel.inverterSettings.collectAsState()
    // Ворота - тепер компактна плитка (як і решта пристроїв, 4 в ряд), повне
    // керування (дія/світло) - у шторці знизу, а не окремою великою карткою.
    var gateSheetOpen by remember { mutableStateOf(false) }
    // Решта пристроїв/інвертора - та сама ідея: діалог (шторка) замість
    // повноекранного переходу. deviceKey - для [DeviceControlScreen]
    // ("grid"/"load"/"boiler1"/"pump"/"boiler2"), inverterFocus - для
    // [InverterOutputPriorityScreen]/[InverterChargerPriorityScreen]
    // ("output"/"charger").
    var openDeviceKey by remember { mutableStateOf<String?>(null) }
    var openInverterFocus by remember { mutableStateOf<String?>(null) }
    fun openInverterDialog(focus: String) {
        if (!inverterSettingsState.loading) {
            scope.launch {
                viewModel.loadInverterSettings()
                openInverterFocus = focus
            }
        }
    }

    // Плитки "Пристрої" - перетягувані (довге натискання + рух), прилипають
    // до найближчої клітинки сітки 4х2 (TILE_GRID_COLS х TILE_GRID_ROWS);
    // позиція зберігається локально й переживає перезапуск застосунку.
    var tileLayout by remember { mutableStateOf(DeviceTileLayoutStore.load(context)) }
    fun tilePos(key: String): TileGridPos = when (key) {
        "boiler1" -> tileLayout.boiler1
        "pump" -> tileLayout.pump
        "boiler2" -> tileLayout.boiler2
        else -> tileLayout.gate
    }
    fun withTilePos(l: DeviceTileLayout, key: String, pos: TileGridPos): DeviceTileLayout = when (key) {
        "boiler1" -> l.copy(boiler1 = pos)
        "pump" -> l.copy(pump = pos)
        "boiler2" -> l.copy(boiler2 = pos)
        else -> l.copy(gate = pos)
    }
    var draggingTileKey by remember { mutableStateOf<String?>(null) }
    // Дельта перетягування в dp (не в клітинках) - для плавного руху пальцем;
    // "прилипання" рахується лише в кінці жесту.
    var tileDragOffsetDp by remember { mutableStateOf(Offset.Zero) }
    fun endTileDrag(key: String) {
        val base = tilePos(key)
        val dCol = (tileDragOffsetDp.x / TILE_CELL_W_DP).roundToInt()
        val dRow = (tileDragOffsetDp.y / TILE_CELL_H_DP).roundToInt()
        val target = TileGridPos(
            (base.col + dCol).coerceIn(0, TILE_GRID_COLS - 1),
            (base.row + dRow).coerceIn(0, TILE_GRID_ROWS - 1),
        )
        if (target != base) {
            val occupantKey = listOf("boiler1", "pump", "boiler2", "gate").firstOrNull { it != key && tilePos(it) == target }
            var updated = withTilePos(tileLayout, key, target)
            if (occupantKey != null) updated = withTilePos(updated, occupantKey, base)
            tileLayout = updated
            DeviceTileLayoutStore.save(context, tileLayout)
        }
        draggingTileKey = null
        tileDragOffsetDp = Offset.Zero
    }

    Scaffold(
        containerColor = Color(0xFF0A0C14),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF181C28), Color(0xFF0A0C14), Color(0xFF05060A))),
                ),
        ) {
            // Декоративні зорі під усім вмістом; розмиті кольорові плями (аврора)
            // переїхали ВСЕРЕДИНУ EnergyConstellation - вони тепер прив'язані до
            // сонця/мережі/АКБ і рухаються разом із ними при перетягуванні.
            StarField()

            Column(modifier = Modifier.fillMaxSize()) {
                // Заголовок - уже не TopAppBar із суцільним тлом (був прямокутник
                // кольору #0A0C14 поверх неба): звичайний Row у тому самому Box,
                // що й градієнт/аврора/зорі, - вони просвічують крізь нього, сам
                // заголовок стає частиною фону, а не окремою панеллю над ним.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(top = 14.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("HomeHub", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionBadge(
                            inverterEnabled = uiState.config.inverterEnabled, inverterAtMs = uiState.inverterAtMs,
                            loadEnabled = uiState.config.loadControllerEnabled, loadAtMs = uiState.loadAtMs,
                            garageEnabled = uiState.config.garageEnabled, garageAtMs = uiState.garageAtMs,
                            commandError = uiState.commandError, commandErrorAtMs = uiState.commandErrorAtMs,
                        )
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Налаштування", tint = TextPrimary)
                        }
                    }
                }

                PullToRefreshBox(
                    isRefreshing = uiState.loading,
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        val pvVoltageNow = inverter?.pvVoltage
                        val gridVoltageNow = pickLineVoltage(inverter, loadController, garage)
                        EnergyConstellation(
                            pvW = pickPvW(inverter, loadController, garage),
                            gridW = pickGridPowerW(inverter, loadController, garage),
                            loadW = pickLoadW(inverter, loadController, garage),
                            batterySoc = pickBatterySoc(inverter, loadController, garage),
                            batteryPowerW = pickBatteryPower(inverter, loadController, garage),
                            // PV/АКБ/вихід - лише інвертор їх реально міряє (окремі DC/вихідні
                            // кола); мережева напруга - спільна шина, тож як і gridW/loadW,
                            // бере перше доступне з будь-якого з трьох модулів.
                            pvVoltage = pvVoltageNow,
                            gridVoltage = gridVoltageNow,
                            loadVoltage = inverter?.outputVoltage,
                            batteryVoltage = inverter?.batteryVoltage,
                            // "Активний" тут - за напругою (>=150В), не за firmware-прапорцем
                            // gridPresent чи окремим порогом pvIndicatorColor (той лишається
                            // для Схеми, яку не чіпаємо): нижче 150В значок чорніє і сяйво
                            // гасне на цьому самому вузлі.
                            pvOn = (pvVoltageNow ?: 0.0) >= NODE_ACTIVE_VOLTAGE,
                            gridOn = (gridVoltageNow ?: 0.0) >= NODE_ACTIVE_VOLTAGE,
                            inverterBusy = inverterSettingsState.loading,
                            onTapSun = { openInverterDialog("output") },
                            onTapGrid = { openDeviceKey = "grid" },
                            onTapLoad = { openDeviceKey = "load" },
                            onTapBattery = { openInverterDialog("charger") },
                            modifier = Modifier.fillMaxWidth().alpha(if (inverterStale) 0.6f else 1f),
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Пристрої", style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text("торкніться", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            // Сітка замість ряду - кожну плитку можна перетягнути пальцем
                            // (довге натискання + рух), прилипає до найближчої клітинки;
                            // позиція за замовчуванням - той самий порядок зліва направо.
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                Box(modifier = Modifier.size(width = (TILE_CELL_W_DP * TILE_GRID_COLS).dp, height = (TILE_CELL_H_DP * TILE_GRID_ROWS).dp)) {
                                    val boiler1On = loadController?.boiler1On == true
                                    // "Горить" (світіння/бліц іконки) - лише за РЕАЛЬНОЮ
                                    // потужністю бойлера (>200 Вт), а не за станом реле: реле
                                    // може бути "увімк." й за нуля Вт (наприклад, бак вже гарячий).
                                    val boiler1Burning = (loadController?.boilerPower ?: 0.0) > 200.0
                                    GridTile(
                                        pos = tileLayout.boiler1,
                                        isDragging = draggingTileKey == "boiler1",
                                        dragOffsetDp = tileDragOffsetDp,
                                        onDragStart = { draggingTileKey = "boiler1" },
                                        onDrag = { dx, dy -> tileDragOffsetDp += Offset(dx, dy) },
                                        onDragEnd = { endTileDrag("boiler1") },
                                        modifier = Modifier.align(Alignment.TopStart),
                                    ) {
                                        DeviceTile(
                                            name = "Бойлер 1",
                                            stateText = if (loadController == null) "--" else if (boiler1On) "увімк." else "вимк.",
                                            stateColor = if (boiler1On) StatusGood else TextMuted,
                                            iconBg = AccentBoiler.copy(alpha = if (boiler1Burning) 0.18f else 0.08f),
                                            glowColor = AccentBoiler,
                                            on = boiler1Burning,
                                            onClick = { openDeviceKey = "boiler1" },
                                            modifier = Modifier.fillMaxWidth().alpha(if (loadStale) 0.6f else 1f),
                                        ) { FlickerIcon(Icons.Filled.Whatshot, AccentBoiler, boiler1Burning) }
                                    }

                                    // Напис/колір плитки - стан РЕЛЕ (loadController.pumpOn), той
                                    // самий, за яким працюють бойлери (boiler1On/boiler2On) - а не
                                    // реальне споживання: реле може бути "увімк." навіть секунду
                                    // до того, як мотор розкрутиться й потече струм.
                                    val pumpRelayOn = loadController?.pumpOn == true
                                    // Гвинт обертається лише тоді, коли насос РЕАЛЬНО тягне струм
                                    // (як і поріг "активне навантаження" у самій прошивці) - це
                                    // окремо від стану реле вище, саме так і просив користувач.
                                    val pumpRunning = (loadController?.pumpPower ?: 0.0) > 50.0
                                    GridTile(
                                        pos = tileLayout.pump,
                                        isDragging = draggingTileKey == "pump",
                                        dragOffsetDp = tileDragOffsetDp,
                                        onDragStart = { draggingTileKey = "pump" },
                                        onDrag = { dx, dy -> tileDragOffsetDp += Offset(dx, dy) },
                                        onDragEnd = { endTileDrag("pump") },
                                        modifier = Modifier.align(Alignment.TopStart),
                                    ) {
                                        DeviceTile(
                                            name = "Насос",
                                            stateText = if (loadController == null) "--" else if (pumpRelayOn) "увімк." else "вимк.",
                                            stateColor = if (pumpRelayOn) StatusGood else TextMuted,
                                            iconBg = AccentPump.copy(alpha = if (pumpRelayOn) 0.18f else 0.08f),
                                            glowColor = AccentPump,
                                            on = pumpRelayOn,
                                            // Частотник Sirio ще не введено в експлуатацію - насос
                                            // поки що звичайне реле, тож плитка відкриває ті самі
                                            // функції керування, що й бойлери, а не повний екран Sirio.
                                            onClick = { openDeviceKey = "pump" },
                                            modifier = Modifier.fillMaxWidth().alpha(if (loadStale) 0.6f else 1f),
                                        ) { FanIcon(AccentPump, pumpRunning) }
                                    }

                                    val boiler2On = garage?.boiler2On == true
                                    val boiler2Burning = (garage?.boilerPower ?: 0.0) > 200.0
                                    GridTile(
                                        pos = tileLayout.boiler2,
                                        isDragging = draggingTileKey == "boiler2",
                                        dragOffsetDp = tileDragOffsetDp,
                                        onDragStart = { draggingTileKey = "boiler2" },
                                        onDrag = { dx, dy -> tileDragOffsetDp += Offset(dx, dy) },
                                        onDragEnd = { endTileDrag("boiler2") },
                                        modifier = Modifier.align(Alignment.TopStart),
                                    ) {
                                        DeviceTile(
                                            name = "Бойлер 2",
                                            stateText = if (garage == null) "--" else if (boiler2On) "увімк." else "вимк.",
                                            stateColor = if (boiler2On) StatusGood else TextMuted,
                                            iconBg = AccentBoiler.copy(alpha = if (boiler2Burning) 0.18f else 0.08f),
                                            glowColor = AccentBoiler,
                                            on = boiler2Burning,
                                            onClick = { openDeviceKey = "boiler2" },
                                            modifier = Modifier.fillMaxWidth().alpha(if (garageStale) 0.6f else 1f),
                                        ) { FlickerIcon(Icons.Filled.Whatshot, AccentBoiler, boiler2Burning) }
                                    }

                                    val gateStateKey = classifyGateState(garage)
                                    val gateNeedsAttention = gateStateKey != "closed" && gateStateKey != "disabled"
                                    val gateLightOn = garage?.garageLightOn == true
                                    // Квадратик - завжди за станом стулки (не залежить від світла):
                                    // зелений - зачинено (безпечно), червоний - будь-що інше
                                    // (відчинено/рух/невідомо - варто звернути увагу).
                                    val gateSquareColor = when {
                                        garage == null -> TextMuted
                                        gateStateKey == "closed" -> StatusGood
                                        else -> StatusAlert
                                    }
                                    GridTile(
                                        pos = tileLayout.gate,
                                        isDragging = draggingTileKey == "gate",
                                        dragOffsetDp = tileDragOffsetDp,
                                        onDragStart = { draggingTileKey = "gate" },
                                        onDrag = { dx, dy -> tileDragOffsetDp += Offset(dx, dy) },
                                        onDragEnd = { endTileDrag("gate") },
                                        modifier = Modifier.align(Alignment.TopStart),
                                    ) {
                                        DeviceTile(
                                            name = "Ворота",
                                            stateText = gateStateTextUk(garage?.gateState),
                                            stateColor = gateSquareColor,
                                            iconBg = gateSquareColor.copy(alpha = if (garage == null) 0.08f else 0.18f),
                                            // Увімкнене світло - сильніший сигнал поверх кольору
                                            // стулки: і світіння, і тло всієї плитки (tileTint)
                                            // жовтіють, щоб не забули його вимкнути.
                                            glowColor = if (gateLightOn) AccentPv else gateSquareColor,
                                            on = gateNeedsAttention || gateLightOn,
                                            tileTint = if (gateLightOn) AccentPv else null,
                                            onClick = { gateSheetOpen = true },
                                            modifier = Modifier.fillMaxWidth().alpha(if (garageStale) 0.6f else 1f),
                                        ) {
                                            Icon(
                                                Icons.Filled.Garage, contentDescription = null,
                                                tint = if (garage == null) TextMuted else gateSquareColor,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // Шторка воріт - повне керування (дія/світло/причина), відкривається
            // дотиком по плитці "Ворота" вище; GateCard - та сама логіка
            // (відлік світла, кольори кнопок), що й раніше, лише не завжди на екрані.
            if (gateSheetOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { gateSheetOpen = false },
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(14.dp),
                ) {
                    GateCard(
                        garage = garage,
                        onTriggerGate = onTriggerGate,
                        onToggleLight = onToggleLight,
                        onOpenJournal = { onOpenJournal("gate") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Меню пристрою (мережа/споживання/бойлери/насос) - той самий екран
            // [DeviceControlScreen], що й раніше, лише тепер у шторці поверх
            // "Дому", а не повноекранним переходом (Схема й далі відкриває його
            // через справжню навігацію - той маршрут не чіпали).
            if (openDeviceKey != null) {
                val key = openDeviceKey!!
                ModalSheet(onDismiss = { openDeviceKey = null }) {
                    DeviceControlScreen(
                        deviceKey = key,
                        uiState = uiState,
                        onSetMode = { mode ->
                            when (key) {
                                "grid" -> viewModel.setInverterGridMode(mode)
                                "load" -> viewModel.setInverterLoadMode(mode)
                                "boiler1" -> viewModel.setBoiler1Mode(mode)
                                "pump" -> viewModel.setPumpMode(mode)
                                "boiler2" -> viewModel.setBoiler2Mode(mode)
                            }
                        },
                        onSetLock = when (key) {
                            "load" -> viewModel::setLoadLock
                            "boiler1" -> viewModel::setBoiler1Lock
                            "pump" -> viewModel::setPumpLock
                            "boiler2" -> viewModel::setBoiler2Lock
                            else -> null
                        },
                        onSetAutoWindow = { enabled, start, end ->
                            when (key) {
                                "boiler1" -> viewModel.setBoiler1AutoWindow(enabled, start, end)
                                "pump" -> viewModel.setPumpAutoWindow(enabled, start, end)
                                "boiler2" -> viewModel.setBoiler2AutoWindow(enabled, start, end)
                            }
                        },
                        onOpenJournal = { openDeviceKey = null; onOpenJournal(key) },
                        onBack = { openDeviceKey = null },
                    )
                }
            }

            // Меню пріоритету інвертора (сонце/АКБ) - та сама пара екранів, що й
            // раніше, теж у шторці.
            if (openInverterFocus != null) {
                ModalSheet(onDismiss = { openInverterFocus = null }) {
                    if (openInverterFocus == "charger") {
                        InverterChargerPriorityScreen(
                            state = inverterSettingsState,
                            onRefresh = { scope.launch { viewModel.loadInverterSettings() } },
                            onSetCharger = viewModel::setInverterChargerPriority,
                            onOpenJournal = { openInverterFocus = null; onOpenJournal("battery") },
                            onBack = { openInverterFocus = null },
                        )
                    } else {
                        InverterOutputPriorityScreen(
                            state = inverterSettingsState,
                            onRefresh = { scope.launch { viewModel.loadInverterSettings() } },
                            onSetOutput = viewModel::setInverterOutputPriority,
                            onSetAuto = viewModel::setInverterOutputAuto,
                            onOpenJournal = { openInverterFocus = null; onOpenJournal("pv") },
                            onBack = { openInverterFocus = null },
                        )
                    }
                }
            }
        }
    }
}

/** Напівпрозора підкладка на весь екран + панель знизу (85% висоти, заокруглені
 * верхні кути) - для меню пристроїв/інвертора: діалог поверх "Дому", не новий
 * повноекранний екран. Дотик по підкладці - закрити. */
@Composable
private fun ModalSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(SurfaceContainer),
        ) {
            content()
        }
    }
}

/**
 * Одна плитка сітки "Пристрої" - позиціонується за [pos] (клітинка, не
 * пікселі); довге натискання + рух - перетягування (та сама логіка, що й у
 * [dragToReposition], але "прилипання" рахується в кінці жесту в DashboardScreen,
 * тому тут власний pointerInput з onDragStart).
 */
@Composable
private fun GridTile(
    pos: TileGridPos,
    isDragging: Boolean,
    dragOffsetDp: Offset,
    onDragStart: () -> Unit,
    onDrag: (dxDp: Float, dyDp: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .offset {
                val baseX = (pos.col * TILE_CELL_W_DP + 4f).dp.roundToPx()
                val baseY = (pos.row * TILE_CELL_H_DP + 4f).dp.roundToPx()
                val dx = if (isDragging) dragOffsetDp.x.dp.roundToPx() else 0
                val dy = if (isDragging) dragOffsetDp.y.dp.roundToPx() else 0
                IntOffset(baseX + dx, baseY + dy)
            }
            .zIndex(if (isDragging) 1f else 0f)
            .width((TILE_CELL_W_DP - 8f).dp)
            .dragToReposition(onDrag = onDrag, onDragEnd = onDragEnd, onDragStart = onDragStart),
    ) {
        content()
    }
}

/** Розмита кольорова плями на фоні, що повільно дрейфує - лише атмосфера, не дані. */
@Composable
internal fun AuroraBlob(
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    offsetX: androidx.compose.ui.unit.Dp,
    offsetY: androidx.compose.ui.unit.Dp,
    periodMs: Int,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "aurora")
    val dx by infinite.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dx",
    )
    Box(
        modifier = modifier
            .offset(x = offsetX, y = offsetY)
            .graphicsLayer { translationX = dx * 24f; translationY = dx * 16f }
            .size(size)
            .blur(radius = 64.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .background(
                Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)),
                CircleShape,
            ),
    )
}

/** Ледь помітні зорі, що мерехтять - два незалежних шари з різним періодом, щоб
 * мерехтіння не виглядало синхронним/механічним. Суто атмосфера, не дані. */
@Composable
internal fun StarField(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "stars")
    val twinkle1 by infinite.animateFloat(
        initialValue = 0.15f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "t1",
    )
    val twinkle2 by infinite.animateFloat(
        initialValue = 0.6f, targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(3100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "t2",
    )
    val points1 = remember { listOf(30 to 50, 150 to 25, 260 to 70, 340 to 40, 60 to 160, 320 to 190) }
    val points2 = remember { listOf(15 to 110, 180 to 90, 270 to 15, 360 to 150, 110 to 15, 230 to 230) }
    Canvas(modifier.fillMaxWidth().height(300.dp)) {
        points1.forEach { (x, y) -> drawCircle(Color.White.copy(alpha = twinkle1), 1.8.dp.toPx(), Offset(x.dp.toPx(), y.dp.toPx())) }
        points2.forEach { (x, y) -> drawCircle(Color.White.copy(alpha = twinkle2), 1.4.dp.toPx(), Offset(x.dp.toPx(), y.dp.toPx())) }
    }
}

/** М'яке кольорове світіння (розмита пляма) позаду активного елемента - те, що
 * у CSS-макеті було box-shadow/glow. Пульсує непомітно, щоб відчувалось "живим". */
@Composable
internal fun Glow(color: Color, modifier: Modifier = Modifier, baseAlpha: Float = 0.55f) {
    val infinite = rememberInfiniteTransition(label = "glow")
    val pulse by infinite.animateFloat(
        initialValue = baseAlpha * 0.65f, targetValue = baseAlpha,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glowPulse",
    )
    Box(
        modifier = modifier
            // Unbounded - інакше розмиття зрізається рівно по межі боксу (видимий
            // квадратний край замість плавного згасання за межі кола).
            .blur(radius = 22.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .background(Brush.radialGradient(listOf(color.copy(alpha = pulse), Color.Transparent)), CircleShape),
    )
}

@Composable
private fun FlickerIcon(icon: ImageVector, color: Color, on: Boolean) {
    val infinite = rememberInfiniteTransition(label = "flicker")
    val scale by infinite.animateFloat(
        initialValue = 0.94f, targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(620, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "flickerScale",
    )
    Icon(
        icon,
        contentDescription = null,
        tint = if (on) color else TextMuted,
        modifier = Modifier.size(20.dp).graphicsLayer {
            if (on) {
                scaleX = scale; scaleY = scale
            }
        },
    )
}

/**
 * Вентилятор насоса - 3 однакові "краплини"-еліпси, повернуті на 120° одна
 * від одної (той самий прийом, що й у фінальній версії макету: гарантовано
 * симетрично, без ручного підбору кривих, що раніше "кривили"). Обертання -
 * лише коли насос дійсно працює.
 */
@Composable
private fun FanIcon(color: Color, on: Boolean, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "spin")
    val angle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "spinAngle",
    )
    val tint = if (on) color else TextMuted
    Canvas(modifier.size(20.dp).graphicsLayer { if (on) rotationZ = angle }) {
        val r = size.minDimension / 2f
        val c = center
        repeat(3) { i ->
            rotate(degrees = i * 120f, pivot = c) {
                drawOval(
                    color = tint,
                    topLeft = Offset(c.x - r * 0.167f, c.y - r * 0.8f),
                    size = Size(r * 0.334f, r * 0.816f),
                )
            }
        }
    }
}

@Composable
private fun DeviceTile(
    name: String,
    stateText: String,
    stateColor: Color,
    iconBg: Color,
    glowColor: Color,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Більшість плиток - нейтральний скляний фон; Ворота фарбують його в
    // жовтий, коли увімкнене світло в гаражі (сильніший сигнал поверх кольору
    // квадратика), - решта плиток цей параметр не передають.
    tileTint: Color? = null,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(tileTint?.copy(alpha = 0.16f) ?: Color.White.copy(alpha = 0.045f), RoundedCornerShape(18.dp))
            .border(1.dp, tileTint?.copy(alpha = 0.4f) ?: Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Контейнер іконки - ЗАВЖДИ 56dp (не лише коли є світіння), інакше
        // плитки увімк./вимк. пристроїв виходять різної висоти.
        Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (on) Glow(color = glowColor, modifier = Modifier.size(56.dp), baseAlpha = 0.45f)
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(iconBg),
                contentAlignment = Alignment.Center,
            ) { icon() }
        }
        Spacer(Modifier.height(5.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(stateText, style = MaterialTheme.typography.labelSmall, color = stateColor, maxLines = 1)
    }
}

/** Нижче цього порогу (Вт) вважаємо, що потоку по лінії/балансу немає - не варто
 * анімувати частинки/відблиск на шумі датчика в районі нуля. */
private const val MIN_FLOW_POWER_W = 2.0

/**
 * Довге натискання + рух на вузлі - перетягування (а не звичайний дотик,
 * щоб не плутати з [onClick] на тому самому елементі): [onDrag] отримує
 * дельту руху вже в dp (а не px), [onDragEnd] - коли палець відпустили чи
 * жест скасовано (тут - момент, коли варто зберегти/"прилипнути" до сітки),
 * [onDragStart] - на самому початку жесту (напр., позначити "яку плитку
 * зараз тягнуть", щоб підняти її над сусідніми - zIndex).
 */
internal fun Modifier.dragToReposition(
    onDrag: (dxDp: Float, dyDp: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragStart: () -> Unit = {},
): Modifier =
    pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onDragStart() },
            onDrag = { change, amount -> change.consume(); onDrag(amount.x / density, amount.y / density) },
            onDragEnd = { onDragEnd() },
            onDragCancel = { onDragEnd() },
        )
    }

/**
 * Сонце/Мережа/Споживання - вузли-сателіти навколо ядра (АКБ), з'єднані
 * лініями з рухомими частинками (суто декоративно - показує, що система
 * "жива"). Кільце заряду АКБ навколо ядра - це РЕАЛЬНИЙ SOC, а напрям
 * обертання світлового відблиску на кільці рахується від знаку
 * [batteryPowerW] (додатній - заряджається, тож за годинниковою; від'ємний -
 * розряджається, проти). Дотик по будь-якому вузлу відкриває той самий
 * реальний екран, що й раніше відповідна картка; довге натискання + рух -
 * перетягує вузол/ядро в довільне місце канви, позиція зберігається локально
 * ([ConstellationLayoutStore]) і переживає перезапуск застосунку.
 */
@Composable
private fun EnergyConstellation(
    pvW: Double?,
    gridW: Double?,
    loadW: Double?,
    batterySoc: Double?,
    batteryPowerW: Double,
    pvVoltage: Double?,
    gridVoltage: Double?,
    loadVoltage: Double?,
    batteryVoltage: Double?,
    pvOn: Boolean,
    gridOn: Boolean,
    inverterBusy: Boolean,
    onTapSun: () -> Unit,
    onTapGrid: () -> Unit,
    onTapLoad: () -> Unit,
    onTapBattery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val charging = batteryPowerW >= 0.0
    // Система розрахована на 0..4 кВт - у цьому діапазоні й нормуємо "наскільки
    // це багато" для товщини променя/швидкості частинок/яскравості світіння/
    // швидкості відблиску на кільці. abs() - напрямок (заряд/розряд, імпорт/
    // експорт) уже показаний окремо (стрілка частинок, напрям обертання), тут
    // важлива лише МАГНІТУДА потоку.
    val maxLinkPowerW = 4000.0
    fun powerFraction(w: Double?): Float = (kotlin.math.abs(w ?: 0.0) / maxLinkPowerW).toFloat().coerceIn(0f, 1f)
    val pvFrac = powerFraction(pvW)
    val gridFrac = powerFraction(gridW)
    val loadFrac = powerFraction(loadW)
    val batteryFrac = powerFraction(batteryPowerW)

    // Нижче цього порогу вважаємо "потоку нема": інакше частинки "летіли" б і
    // відблиск на кільці крутився б навіть при 0 Вт - анімація заради анімації,
    // а не живий показник реального потоку.
    val pvFlowing = kotlin.math.abs(pvW ?: 0.0) > MIN_FLOW_POWER_W
    val gridFlowing = kotlin.math.abs(gridW ?: 0.0) > MIN_FLOW_POWER_W
    val loadFlowing = kotlin.math.abs(loadW ?: 0.0) > MIN_FLOW_POWER_W
    val batteryFlowing = kotlin.math.abs(batteryPowerW) > MIN_FLOW_POWER_W

    // Корінь, не лінійна частка: за лінійною кривою побутова потужність (звично
    // лише сотні Вт із 4 кВт шкали) давала майже непомітну різницю - корінь
    // помітно пришвидшує реакцію саме в цьому, реалістичному діапазоні, межі
    // 0..1 лишаються ті самі.
    fun responseCurve(frac: Float): Float = kotlin.math.sqrt(frac)
    // Період руху частинок: швидше - коли потужності більше (3200мс біля
    // нуля -> 600мс біля максимуму). Товщина лінії: 1.5dp -> 7dp.
    fun flowDurationMs(frac: Float): Int = (3200 - (3200 - 600) * responseCurve(frac)).toInt().coerceAtLeast(600)
    fun strokeWidthDp(frac: Float): Float = 1.5f + (7f - 1.5f) * responseCurve(frac)
    // Яскравість світіння джерела/споживача - від його ж віддачі/споживання,
    // з підлогою 0.22, щоб навіть мала потужність лишалась помітною, а не
    // зникала в нуль (що виглядало б як баг, а не "мало енергії").
    fun glowAlpha(frac: Float): Float = 0.22f + (0.72f - 0.22f) * responseCurve(frac)
    // Кількість частинок на лінії - від 1 (ледь є потік) до 5 (повна
    // потужність), та сама крива чутливості.
    fun particleCount(frac: Float): Int = (1 + responseCurve(frac) * 4f).roundToInt().coerceIn(1, 5)

    val infinite = rememberInfiniteTransition(label = "constellation")
    val sunPhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(flowDurationMs(pvFrac), easing = LinearEasing)),
        label = "sunFlow",
    )
    val gridPhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(flowDurationMs(gridFrac), easing = LinearEasing)),
        label = "gridFlow",
    )
    val loadPhase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(flowDurationMs(loadFrac), easing = LinearEasing)),
        label = "loadFlow",
    )
    // Швидкість обертання проблиску на кільці АКБ - від МОДУЛЯ балансу
    // (7000мс біля нуля, майже непомітно - до 800мс на повній потужності).
    val ringAngle by infinite.animateFloat(
        initialValue = 0f, targetValue = if (charging) 360f else -360f,
        animationSpec = infiniteRepeatable(tween((7000 - (7000 - 800) * batteryFrac).toInt().coerceAtLeast(800), easing = LinearEasing)),
        label = "ring",
    )
    val corePulse by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(2250, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "corePulse",
    )

    // Позиції вузлів/ядра (у dp, система координат канви 340x410) - за
    // замовчуванням асиметрична розкладка з [ConstellationLayoutStore.DEFAULT],
    // але користувач може перетягнути кожен вузол пальцем; збережене в
    // SharedPreferences переживає перезапуск застосунку.
    val context = LocalContext.current
    var layout by remember { mutableStateOf(ConstellationLayoutStore.load(context)) }
    fun persistLayout() = ConstellationLayoutStore.save(context, layout)
    // Межі перетягування - щоб вузол не виїхав за канву (42/24 - половина
    // ширини колонки й радіус іконки сателіта; 58 - радіус ядра; запас знизу
    // (305 замість 410-24) - під підпис+значення+напругу під іконкою).
    fun dragSun(dx: Float, dy: Float) {
        layout = layout.copy(sunX = (layout.sunX + dx).coerceIn(42f, 298f), sunY = (layout.sunY + dy).coerceIn(24f, 305f))
    }
    fun dragGrid(dx: Float, dy: Float) {
        layout = layout.copy(gridX = (layout.gridX + dx).coerceIn(42f, 298f), gridY = (layout.gridY + dy).coerceIn(24f, 305f))
    }
    fun dragLoad(dx: Float, dy: Float) {
        layout = layout.copy(loadX = (layout.loadX + dx).coerceIn(42f, 298f), loadY = (layout.loadY + dy).coerceIn(24f, 305f))
    }
    fun dragCore(dx: Float, dy: Float) {
        layout = layout.copy(coreX = (layout.coreX + dx).coerceIn(58f, 282f), coreY = (layout.coreY + dy).coerceIn(58f, 352f))
    }

    // Canvas і всі вузли-кнопки - діти ОДНОГО Box (340x410dp), кожен з явним
    // Alignment.TopStart перед offset(): offset() зміщує ВІД поточного
    // вирівнювання, а не від нуля контейнера, як position:absolute в CSS.
    // Без TopStart вузли центрувались би по ширині ЕКРАНА (зовнішній Box тут -
    // на всю ширину виклику), а не по цій канві, і "розповзались" праворуч/за
    // межі екрана - так і сталось у першій версії.
    Box(modifier = modifier.height(410.dp), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.size(width = 340.dp, height = 410.dp)) {
            // Туманності - прив'язані до сонця/мережі/АКБ (той самий колір), а не
            // до фіксованих координат екрана: рухаються разом з вузлом, коли його
            // перетягують. Малюються першими - позаду ліній/вузлів.
            // Туманність сонця/мережі - лише коли вузол активний (>=150В): інакше
            // і маленьке сяйво за іконкою, і ця велика фонова пляма мають гаснути
            // разом, а не тільки перше.
            if (pvOn) {
                AuroraBlob(
                    color = AccentPv, size = 240.dp, periodMs = 22_000,
                    offsetX = (layout.sunX - 120).dp, offsetY = (layout.sunY - 120).dp,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            if (gridOn) {
                AuroraBlob(
                    color = AccentGrid, size = 220.dp, periodMs = 19_000,
                    offsetX = (layout.gridX - 110).dp, offsetY = (layout.gridY - 110).dp,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            AuroraBlob(
                color = AccentBattery, size = 260.dp, periodMs = 26_000,
                offsetX = (layout.coreX - 130).dp, offsetY = (layout.coreY - 130).dp,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(layout.coreX.dp.toPx(), layout.coreY.dp.toPx())
                val sunC = Offset(layout.sunX.dp.toPx(), layout.sunY.dp.toPx())
                val gridC = Offset(layout.gridX.dp.toPx(), layout.gridY.dp.toPx())
                val loadC = Offset(layout.loadX.dp.toPx(), layout.loadY.dp.toPx())
                // Товщина кожної лінії - за потужністю саме ЦІЄЇ ланки (не спільна):
                // 1.5dp біля нуля -> 7dp на 4 кВт, тож різниця реально впадає в очі.
                val lineColorBase = Color(0xFF2A2F3F)
                drawLine(lineColorBase, sunC, center, strokeWidth = strokeWidthDp(pvFrac).dp.toPx(), cap = StrokeCap.Round)
                drawLine(lineColorBase, gridC, center, strokeWidth = strokeWidthDp(gridFrac).dp.toPx(), cap = StrokeCap.Round)
                drawLine(lineColorBase, loadC, center, strokeWidth = strokeWidthDp(loadFrac).dp.toPx(), cap = StrokeCap.Round)

                // Рухомі "частинки" на лінії - лише коли реально є потік (інакше
                // анімація на лінії з 0 Вт виглядала б як баг, не як дані); і
                // ШВИДКІСТЬ (фаза), і ЧИСЛО частинок ростуть із потужністю лінії,
                // тож різниця реально помітна, а не лише трохи швидший один дот.
                fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                if (pvFlowing) {
                    val n = particleCount(pvFrac)
                    for (i in 0 until n) {
                        val t = (sunPhase + i.toFloat() / n) % 1f
                        drawCircle(Color(0xFFF3C57A), 3.5.dp.toPx(), lerp(sunC, center, t))
                    }
                }
                if (loadFlowing) {
                    val n = particleCount(loadFrac)
                    for (i in 0 until n) {
                        val t = (loadPhase + i.toFloat() / n) % 1f
                        drawCircle(Color(0xFFF1919F), 3.dp.toPx(), lerp(center, loadC, t))
                    }
                }
                if (gridFlowing) {
                    val n = particleCount(gridFrac)
                    for (i in 0 until n) {
                        val t = (gridPhase + i.toFloat() / n) % 1f
                        drawCircle(Color(0xFF86A9EF).copy(alpha = 0.75f), 2.6.dp.toPx(), lerp(gridC, center, t))
                    }
                }

                val ringR = 68.dp.toPx()
                val ringStroke = 7.dp.toPx()
                val ringTopLeft = Offset(center.x - ringR, center.y - ringR)
                val ringSize = Size(ringR * 2, ringR * 2)
                drawArc(
                    color = Color(0xFF20242F), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = ringTopLeft, size = ringSize, style = Stroke(ringStroke),
                )
                val socFrac = ((batterySoc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                drawArc(
                    color = AccentBattery, startAngle = -90f, sweepAngle = 360f * socFrac, useCenter = false,
                    topLeft = ringTopLeft, size = ringSize, style = Stroke(ringStroke, cap = StrokeCap.Round),
                )
                // Відблиск - лише коли АКБ реально приймає/віддає помітну
                // потужність; на нульовому балансі кільце стоїть нерухомо.
                if (batteryFlowing) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.85f), startAngle = -90f + ringAngle, sweepAngle = 26f, useCenter = false,
                        topLeft = ringTopLeft, size = ringSize, style = Stroke(ringStroke, cap = StrokeCap.Round),
                    )
                }
            }

            // М'яке зелене світіння позаду ядра (той самий box-shadow-glow, що й у
            // CSS-макеті) - розмір 170dp, центр збігається з ядром.
            Glow(
                color = AccentBattery,
                modifier = Modifier.align(Alignment.TopStart).offset(x = (layout.coreX - 85).dp, y = (layout.coreY - 85).dp).size(170.dp),
                baseAlpha = 0.5f,
            )

            // Ядро (АКБ) - поверх Canvas, справжня кнопка; бокс 116dp - тож
            // top-left = центр - 58dp.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (layout.coreX - 58).dp, y = (layout.coreY - 58).dp)
                    .size(116.dp)
                    .graphicsLayer { scaleX = corePulse; scaleY = corePulse }
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF1B2A22), Color(0xFF121722))))
                    .border(1.dp, AccentBattery.copy(alpha = 0.35f), CircleShape)
                    .clickable(onClick = onTapBattery)
                    .dragToReposition(::dragCore, ::persistLayout),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("баланс зараз", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 10.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            (if (batteryPowerW >= 0) "+" else "") + formatWatts(batteryPowerW),
                            style = MaterialTheme.typography.titleLarge,
                            color = AccentBattery,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(" Вт", style = MaterialTheme.typography.labelSmall, color = AccentBattery, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Text(
                        "АКБ · ${formatPercent(batterySoc)}% · ${formatVoltsCompact(batteryVoltage)}В",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBattery,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Світіння позаду вузлів - 64dp (трохи більше за 48dp іконку), центр
            // збігається з іконкою (top-left = центр_іконки - 32dp); яскравість -
            // від потужності САМЕ цього джерела/споживача, не фіксована.
            if (pvOn) {
                Glow(color = AccentPv, modifier = Modifier.align(Alignment.TopStart).offset(x = (layout.sunX - 32).dp, y = (layout.sunY - 32).dp).size(64.dp), baseAlpha = glowAlpha(pvFrac))
            }
            if (gridOn) {
                Glow(color = AccentGrid, modifier = Modifier.align(Alignment.TopStart).offset(x = (layout.gridX - 32).dp, y = (layout.gridY - 32).dp).size(64.dp), baseAlpha = glowAlpha(gridFrac))
            }
            Glow(color = AccentLoad, modifier = Modifier.align(Alignment.TopStart).offset(x = (layout.loadX - 32).dp, y = (layout.loadY - 32).dp).size(64.dp), baseAlpha = glowAlpha(loadFrac))

            // Кожен вузол - Column шириною 84dp з горизонтально центрованою 48dp
            // іконкою-кружком; щоб ІКОНКА (не колонка) опинилась точно в точці
            // (sunC/gridC/loadC) з Canvas вище, top-left колонки = (точка.x - 42,
            // точка.y - 24) - 42 = половина ширини колонки, 24 = радіус іконки.
            // dragToReposition - на всій колонці (не лише іконці): легше вхопити
            // пальцем, а звичайний дотик по іконці й далі лише відкриває картку.
            ConstellationNode(
                icon = Icons.Filled.WbSunny,
                color = AccentPv,
                active = pvOn,
                busy = inverterBusy,
                label = "Сонце",
                valueText = "${formatWatts(pvW)} Вт",
                voltageText = "${formatVoltsCompact(pvVoltage)}В",
                onClick = onTapSun,
                modifier = Modifier.align(Alignment.TopStart)
                    .offset(x = (layout.sunX - 42).dp, y = (layout.sunY - 24).dp)
                    .dragToReposition(::dragSun, ::persistLayout),
            )
            ConstellationNode(
                icon = Icons.Filled.ElectricBolt,
                color = AccentGrid,
                active = gridOn,
                busy = false,
                label = "Мережа",
                valueText = if (gridOn) "${formatWatts(gridW)} Вт" else "немає",
                voltageText = if (gridOn) "${formatVoltsCompact(gridVoltage)}В" else null,
                onClick = onTapGrid,
                modifier = Modifier.align(Alignment.TopStart)
                    .offset(x = (layout.gridX - 42).dp, y = (layout.gridY - 24).dp)
                    .dragToReposition(::dragGrid, ::persistLayout),
            )
            ConstellationNode(
                icon = Icons.Filled.Home,
                color = AccentLoad,
                active = true,
                busy = false,
                label = "Споживання",
                valueText = "${formatWatts(loadW)} Вт",
                voltageText = "${formatVoltsCompact(loadVoltage)}В",
                onClick = onTapLoad,
                modifier = Modifier.align(Alignment.TopStart)
                    .offset(x = (layout.loadX - 42).dp, y = (layout.loadY - 24).dp)
                    .dragToReposition(::dragLoad, ::persistLayout),
            )
        }
    }
}

@Composable
private fun ConstellationNode(
    icon: ImageVector,
    color: Color,
    active: Boolean,
    busy: Boolean,
    label: String,
    valueText: String,
    voltageText: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (active) 0.16f else 0.08f))
                .border(1.5.dp, color.copy(alpha = if (active) 0.45f else 0.2f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Неактивний (нижче порогу напруги) - значок чорний, не приглушено-сірий,
            // як і в чинних індикаторах сонця/мережі деінде в застосунку.
            Icon(icon, contentDescription = "$label - детальніше", tint = if (active) color else IndicatorOff, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(if (busy) "…" else valueText, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        if (!busy && voltageText != null) {
            Text(voltageText, style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 9.sp, maxLines = 1)
        }
    }
}

/** Через скільки мс без відповіді модуля його картки вважаються застарілими (тьмяніють). */
private const val STALE_DATA_MS = 30_000L

// Нижче цієї напруги вузол "Сонце"/"Мережа" на сузір'ї вважається неактивним:
// значок чорніє, сяйво гасне. Свій поріг саме для цього вузла (не той самий,
// що PV_PRESENT_VOLTAGE чи firmware-прапорець gridPresent, які лишаються для
// Схеми - її не чіпаємо).
private const val NODE_ACTIVE_VOLTAGE = 150.0

// Сітка плиток "Пристрої" (dp): 4 колонки х 2 ряди - сьогодні зайнятий лише
// верхній ряд (як і раніше), другий - вільний, щоб було куди перетягнути.
// Ширина сітки (340dp) навмисно збігається з канвою сузір'я вище - однакове
// вирівнювання по центру екрана.
private const val TILE_GRID_COLS = 4
private const val TILE_GRID_ROWS = 2
private const val TILE_CELL_W_DP = 85f
private const val TILE_CELL_H_DP = 118f

/** Сонце: жовте, коли напруга PV-панелей >= [PV_PRESENT_VOLTAGE], інакше чорне. */
internal fun pvIndicatorColor(inverter: InverterStatus?): Color =
    if ((inverter?.pvVoltage ?: 0.0) >= PV_PRESENT_VOLTAGE) AccentPv else IndicatorOff

/** Блискавка мережі: синя, коли інвертор бачить напругу мережі (grid_present), інакше чорна. */
internal fun gridIndicatorColor(inverter: InverterStatus?): Color =
    if (inverter?.gridPresent == true) AccentGrid else IndicatorOff

// Кнопка показує ДІЮ, яку виконає натискання (як door-action-btn у чинному
// WebView-додатку), а не поточний стан - "відкрити", коли зараз зачинено,
// "зачинити", коли зараз відчинено, "стоп" у решті випадків (рух/невідомо).
private fun classifyGateState(garage: GarageStatus?): String {
    if (garage == null) return "disabled"
    val closedPin = garage.gateClosedPin
    if (closedPin >= 0) return if (closedPin == 0) "closed" else "open"
    val raw = garage.gateState.trim().lowercase()
    return when {
        raw.contains("open") -> "open"
        raw.contains("close") -> "closed"
        raw.contains("stop") -> "stopped"
        raw.contains("move") -> "moving"
        else -> "unknown"
    }
}

private data class GateActionUi(val label: String, val icon: ImageVector)

private fun gateActionUi(stateKey: String): GateActionUi = when (stateKey) {
    "closed" -> GateActionUi("Відкрити", Icons.Filled.ArrowUpward)
    "open" -> GateActionUi("Зачинити", Icons.Filled.ArrowDownward)
    else -> GateActionUi("Стоп", Icons.Filled.Stop)
}

private fun formatCountdownClock(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val m = safe / 60
    val s = safe % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun GateCard(
    garage: GarageStatus?,
    onTriggerGate: () -> Unit,
    onToggleLight: () -> Unit,
    onOpenJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // Прошивка віддає лише "скільки секунд лишалось на момент опитування",
    // тому запам'ятовуємо абсолютний дедлайн один раз на кожне оновлення
    // статусу й відлічуємо локально щосекунди (як applyGarageLightAutoOffFromStatus
    // у чинному WebView-додатку) - інакше цифри стрибали б лише раз на цикл
    // опитування, а не плавно.
    var lightAutoOffAtMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(garage?.garageLightAutoOffInSec) {
        val sec = garage?.garageLightAutoOffInSec
        lightAutoOffAtMs = if (sec != null && sec >= 0) System.currentTimeMillis() + sec * 1000L else null
    }

    val disabled = garage == null
    val stateKey = classifyGateState(garage)
    val gateAction = gateActionUi(stateKey)
    val lightOn = garage?.garageLightOn == true
    val remainingSec = lightAutoOffAtMs?.let { (it - now) / 1000 }
    val lightLabel = when {
        disabled -> "світло --"
        lightOn && remainingSec != null && remainingSec > 0 -> "Вимкнути (${formatCountdownClock(remainingSec)})"
        lightOn -> "Вимкнути світло"
        else -> "Увімкнути світло"
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.045f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)).padding(1.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Garage, contentDescription = null, tint = AccentGate, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ВОРОТА", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                }
                IconButton(
                    onClick = onOpenJournal,
                    modifier = Modifier
                        .size(28.dp)
                        .background(SurfaceContainerHigh.copy(alpha = 0.6f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.Article,
                        contentDescription = "Журнал подій",
                        tint = TextMuted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = gateStateTextUk(garage?.gateState),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "причина: ${gateReasonTextUk(garage?.gateReason)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Text(
                text = "світло: ${if (lightOn) "УВІМК" else "ВИМК"}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            // Колір кнопки воріт показує ПОТОЧНИЙ СТАН (а не дію, як підпис):
            // зелена - зачинено (безпечно), червона - будь-що інше (відчинено/
            // рух/невідомо - варто звернути увагу), сіра - немає даних.
            val gateButtonColor = when {
                disabled -> SurfaceContainerHigh
                stateKey == "closed" -> StatusGood
                else -> StatusAlert
            }
            val gateButtonContentColor = if (disabled) TextPrimary else Color(0xFF0D0F16)
            Button(
                onClick = onTriggerGate,
                enabled = !disabled,
                colors = ButtonDefaults.buttonColors(containerColor = gateButtonColor, contentColor = gateButtonContentColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(gateAction.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (disabled) "--" else gateAction.label)
            }
            Spacer(Modifier.height(6.dp))
            // Кнопка світла - жовта, коли увімкнено, темна (як і решта), коли вимкнено.
            val lightButtonColor = if (lightOn) AccentPv else SurfaceContainerHigh
            val lightButtonContentColor = if (lightOn) Color(0xFF0D0F16) else TextPrimary
            Button(
                onClick = onToggleLight,
                enabled = !disabled,
                colors = ButtonDefaults.buttonColors(containerColor = lightButtonColor, contentColor = lightButtonContentColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(lightLabel)
            }
        }
    }
}

// Раніше індикатор рахувався від "хоч один із трьох модулів відповів у
// цьому циклі" - тож коли один модуль насправді не відповідав (а два інші
// справно опитувались), "живо" лишалось зеленим годинами: саме так виглядав
// баг "дані не йдуть, а індикатор зелений, і кнопки не працюють".
@Composable
private fun ConnectionBadge(
    inverterEnabled: Boolean, inverterAtMs: Long,
    loadEnabled: Boolean, loadAtMs: Long,
    garageEnabled: Boolean, garageAtMs: Long,
    commandError: String?, commandErrorAtMs: Long,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    // За КОЖНИМ увімкненим модулем окремо (не за "хоч одним") - інакше
    // конкретний модуль (і саме його кнопки) міг мовчати як завгодно довго,
    // лишаючись непоміченим, поки інший модуль справно відповідав.
    val enabledAtMs = buildList {
        if (inverterEnabled) add(inverterAtMs)
        if (loadEnabled) add(loadAtMs)
        if (garageEnabled) add(garageAtMs)
    }
    val commandErrorVisible = commandError != null && now - commandErrorAtMs < 8000L
    val (color, label) = when {
        enabledAtMs.isEmpty() -> StatusAlert to "немає ввімкнених модулів"
        enabledAtMs.all { it <= 0L } -> StatusAlert to "немає зв'язку"
        enabledAtMs.any { it <= 0L || now - it > STALE_DATA_MS } -> StatusAlert to "не всі модулі на зв'язку"
        commandErrorVisible -> AccentPv to (commandError ?: "помилка команди")
        else -> StatusGood to "живо"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

private fun boolTextUk(value: Boolean) = if (value) "УВІМК" else "ВИМК"

private fun gateStateTextUk(raw: String?): String {
    val v = raw?.trim()?.lowercase() ?: return "---"
    return when {
        v.contains("open") -> "Відчинено"
        v.contains("close") -> "Зачинено"
        v.contains("stop") -> "Стоп"
        v.contains("move") -> "Рух"
        else -> "Невідомо"
    }
}

// Прошивка досі віддає причину як внутрішній рядок ("door closed"/"door
// open"/джерело команди на кшталт "remote trigger") - той самий пропуск є
// і в чинному WebView-додатку (uiText() там теж не перекладає ці значення).
// Тут - легкий локальний переклад найпоширеніших варіантів, решта показується
// як є (краще щось зрозуміле частково, ніж порожньо).
private fun gateReasonTextUk(raw: String?): String {
    val v = raw?.trim() ?: return "---"
    if (v.isEmpty() || v == "init") return "---"
    val lower = v.lowercase()
    return when {
        lower == "door closed" -> "ворота зачинено"
        lower == "door open" -> "ворота відчинено"
        lower == "remote trigger" -> "пульт"
        lower == "button" -> "кнопка"
        lower == "app" || lower == "mobile hub" || lower == "compose hub" -> "застосунок"
        else -> v
    }
}

private fun formatWatts(value: Double?): String {
    if (value == null || value.isNaN()) return "--"
    return value.roundToInt().toString()
}

private fun formatVolts(value: Double?): String {
    if (value == null || value.isNaN()) return "--"
    return "%.1f".format(value)
}

// Напруга на сателітах/ядрі - ціле число (компактніше, щоб влізло поруч із
// потужністю у вузькій 84dp колонці), не десяткова, як [formatVolts].
private fun formatVoltsCompact(value: Double?): String {
    if (value == null || value.isNaN()) return "--"
    return value.roundToInt().toString()
}

private fun formatPercent(value: Double?): String {
    if (value == null || value.isNaN()) return "--"
    return value.roundToInt().toString()
}

private fun pickPvW(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.pvW, load?.pvW, garage?.pvW).firstOrNull { it.isFinite() }

private fun pickLineVoltage(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.lineVoltage, load?.lineVoltage, garage?.lineVoltage).firstOrNull { it.isFinite() }

// Той самий захист, що й у чинному WebView-додатку (zeroGridPowerWhenNoVoltage):
// без напруги мережі показник потужності - лише шум датчика, а не реальний потік.
private fun pickGridPowerW(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? {
    val voltage = pickLineVoltage(inverter, load, garage) ?: return null
    val power = listOfNotNull(inverter?.gridW, load?.gridW, garage?.gridW).firstOrNull { it.isFinite() } ?: return null
    return if (voltage < 50.0) 0.0 else power
}

private fun pickBatterySoc(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.batterySoc, load?.batterySoc, garage?.batterySoc).firstOrNull { it.isFinite() }

private fun pickBatteryPower(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double =
    listOfNotNull(inverter?.batteryPower, load?.batteryPower, garage?.batteryPower).firstOrNull { it.isFinite() } ?: 0.0

private fun pickLoadW(inverter: InverterStatus?, load: LoadControllerStatus?, garage: GarageStatus?): Double? =
    listOfNotNull(inverter?.loadW, load?.loadW, garage?.loadW).firstOrNull { it.isFinite() }
