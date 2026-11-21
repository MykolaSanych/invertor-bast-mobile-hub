package com.chapay.homehub.compose.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chapay.homehub.compose.ui.DashboardViewModel
import com.chapay.homehub.compose.ui.components.LandscapeInPortrait
import com.chapay.homehub.compose.ui.screens.DashboardScreen
import com.chapay.homehub.compose.ui.screens.DeviceControlScreen
import com.chapay.homehub.compose.ui.screens.EnergyChartScreen
import com.chapay.homehub.compose.ui.screens.InverterChargerPriorityScreen
import com.chapay.homehub.compose.ui.screens.InverterOutputPriorityScreen
import com.chapay.homehub.compose.ui.screens.JournalScreen
import com.chapay.homehub.compose.ui.screens.NotificationSettingsScreen
import com.chapay.homehub.compose.ui.screens.SchemeScreen
import com.chapay.homehub.compose.ui.screens.SettingsScreen
import com.chapay.homehub.compose.ui.screens.SirioScreen
import com.chapay.homehub.compose.ui.screens.VoltageHistoryScreen
import kotlinx.coroutines.launch

private const val ROUTE_MAIN = "main"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_NOTIFICATIONS = "notification_settings"
private const val ROUTE_DEVICE = "device/{key}"
// Два ОКРЕМІ екрани (не один спільний з "фокусом") - клік по сонцю й клік по АКБ
// повинні відкривати дві різні дії керування, а не той самий екран з різною прокруткою.
private const val ROUTE_INVERTER_OUTPUT = "inverter_output_priority"
private const val ROUTE_INVERTER_CHARGER = "inverter_charger_priority"
private const val ROUTE_JOURNAL_DEVICE = "journal_device/{device}"
private const val ROUTE_VOLTAGE_HISTORY = "voltage_history/{metric}"
private const val ROUTE_SIRIO = "sirio"

// Чотири вкладки головного екрана - сторінки одного горизонтально-гортаного
// пейджера (без нижнього меню, перемикання - лише свайпом вліво/вправо; решта екранів
// лишаються "штовхнутими" зверху пейджера, як і раніше). Порядок зліва направо:
// Журнал - Схема - Дім (стартова) - Графік (разом з хронологією - один екран,
// щоб порівнювати навантаження з тим, яке обладнання в цей час працювало).
private const val PAGE_JOURNAL = 0
private const val PAGE_SCHEME = 1
private const val PAGE_DASHBOARD = 2
private const val PAGE_ENERGY = 3
private const val PAGE_COUNT = 4

@Composable
fun AppNavHost(
    viewModel: DashboardViewModel,
    navController: NavHostController = rememberNavController(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val inverterSettingsState by viewModel.inverterSettings.collectAsState()
    val scope = rememberCoroutineScope()

    // focus: "output" (плитка сонця) чи "charger" (плитка АКБ) - які РІЗНІ дії керування
    // відкрити; це не той самий екран з різною прокруткою, а два окремі призначення.
    fun openInverterSettings(focus: String) {
        if (!inverterSettingsState.loading) {
            scope.launch {
                // Плитка сонця/АКБ: запит режимів відправляється ДО відкриття екрана
                // (він триває, поки на плитці крутиться індикатор), а не після.
                viewModel.loadInverterSettings()
                navController.navigate(if (focus == "charger") ROUTE_INVERTER_CHARGER else ROUTE_INVERTER_OUTPUT)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = ROUTE_MAIN) {
            composable(ROUTE_MAIN) {
                MainPager(
                    viewModel = viewModel,
                    onOpenDevice = { key -> navController.navigate("device/$key") },
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                    onOpenInverterSettings = ::openInverterSettings,
                    onOpenJournal = { device -> navController.navigate("journal_device/$device") },
                    onOpenVoltageChart = { metric -> navController.navigate("voltage_history/$metric") },
                    onOpenSirio = { navController.navigate(ROUTE_SIRIO) },
                )
            }
            composable(ROUTE_DEVICE) { backStack ->
                val key = backStack.arguments?.getString("key") ?: ""
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
                    // key тут - те саме значення, що й ключ журнального фільтра
                    // (grid/load/boiler1/pump/boiler2 - див. DEVICE_TITLES у JournalScreen).
                    onOpenJournal = { navController.navigate("journal_device/$key") },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_INVERTER_OUTPUT) {
                InverterOutputPriorityScreen(
                    state = inverterSettingsState,
                    onRefresh = { scope.launch { viewModel.loadInverterSettings() } },
                    onSetOutput = viewModel::setInverterOutputPriority,
                    onSetAuto = viewModel::setInverterOutputAuto,
                    onOpenJournal = { navController.navigate("journal_device/pv") },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_INVERTER_CHARGER) {
                InverterChargerPriorityScreen(
                    state = inverterSettingsState,
                    onRefresh = { scope.launch { viewModel.loadInverterSettings() } },
                    onSetCharger = viewModel::setInverterChargerPriority,
                    onOpenJournal = { navController.navigate("journal_device/battery") },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                ROUTE_JOURNAL_DEVICE,
                arguments = listOf(navArgument("device") { type = NavType.StringType }),
            ) { backStack ->
                val device = backStack.arguments?.getString("device") ?: ""
                JournalScreen(
                    config = uiState.config,
                    repository = viewModel.repository,
                    deviceFilter = device,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                ROUTE_VOLTAGE_HISTORY,
                arguments = listOf(navArgument("metric") { type = NavType.StringType }),
            ) { backStack ->
                val metric = backStack.arguments?.getString("metric") ?: "grid"
                LandscapeInPortrait {
                    VoltageHistoryScreen(
                        config = uiState.config,
                        repository = viewModel.repository,
                        metric = metric,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(ROUTE_SIRIO) {
                SirioScreen(
                    config = uiState.config,
                    repository = viewModel.repository,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    config = uiState.config,
                    onSave = viewModel::setConfig,
                    onBack = { navController.popBackStack() },
                    repository = viewModel.repository,
                    onOpenNotifications = { navController.navigate(ROUTE_NOTIFICATIONS) },
                )
            }
            composable(ROUTE_NOTIFICATIONS) {
                NotificationSettingsScreen(
                    config = uiState.config,
                    onSave = viewModel::setConfig,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Чотири головні вкладки як сторінки [HorizontalPager] замість колишнього нижнього меню:
 * гортаються свайпом ліворуч/праворуч. Графік (разом із хронологією, вбудованою в той
 * самий екран - [TimelineBody] всередині [EnergyChartScreen]) - "ландшафтний" екран
 * (широкий вміст), але сама сторінка лишається портретною: [LandscapeInPortrait] повертає
 * вміст на 90° на місці, замість реального повороту пристрою, - і саме тому свайп
 * пейджера (тепер фізично вертикальний усередині цієї вкладки) не конфліктує зі
 * скролом/масштабом/курсором графіків (фізично горизонтальним там), див. chartGestures.
 */
@Composable
private fun MainPager(
    viewModel: DashboardViewModel,
    onOpenDevice: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInverterSettings: (String) -> Unit,
    onOpenJournal: (String) -> Unit,
    onOpenVoltageChart: (String) -> Unit,
    onOpenSirio: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(initialPage = PAGE_DASHBOARD, pageCount = { PAGE_COUNT })

    Box(Modifier.fillMaxSize()) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            PAGE_DASHBOARD -> DashboardScreen(
                uiState = uiState,
                onRefresh = viewModel::refreshNow,
                onOpenSettings = onOpenSettings,
                onTriggerGate = viewModel::triggerGate,
                onToggleLight = viewModel::toggleGarageLight,
                onOpenJournal = onOpenJournal,
                onOpenVoltageChart = onOpenVoltageChart,
                onOpenSirio = onOpenSirio,
                viewModel = viewModel,
            )
            PAGE_SCHEME -> SchemeScreen(
                uiState = uiState,
                onNodeTap = { id ->
                    when (id) {
                        "pv" -> onOpenInverterSettings("output")
                        "battery" -> onOpenInverterSettings("charger")
                        "grid" -> onOpenDevice("grid")
                        "load" -> onOpenDevice("load")
                        "boiler1" -> onOpenDevice("boiler1")
                        "boiler2" -> onOpenDevice("boiler2")
                        "pump" -> onOpenSirio()
                    }
                },
            )
            PAGE_JOURNAL -> JournalScreen(config = uiState.config, repository = viewModel.repository)
            PAGE_ENERGY -> {
                val inv = uiState.status.inverter
                LandscapeInPortrait {
                    EnergyChartScreen(
                        config = uiState.config,
                        repository = viewModel.repository,
                        status = uiState.status,
                        liveTotals = inv?.let { Triple(it.dailyPv, it.dailyHome, it.dailyGrid) },
                    )
                }
            }
        }
    }
        // Крапочки - яка з 4 вкладок зараз (гортання - лише свайпом, без підписів);
        // лишаються в природній (не повернутій) орієнтації навіть на "ландшафтній"
        // вкладці графіка, бо вони - поза LandscapeInPortrait.
        PageIndicatorDots(
            pageCount = PAGE_COUNT,
            currentPage = pagerState.currentPage,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun PageIndicatorDots(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(pageCount) { i ->
            val active = i == currentPage
            Box(
                Modifier
                    .size(if (active) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (active) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.35f)),
            )
        }
    }
}
