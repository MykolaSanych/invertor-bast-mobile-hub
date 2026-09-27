package com.chapay.homehub.compose.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
import com.chapay.homehub.compose.ui.screens.SchemeScreen
import com.chapay.homehub.compose.ui.screens.SettingsScreen
import com.chapay.homehub.compose.ui.screens.TimelineScreen
import kotlinx.coroutines.launch

private const val ROUTE_MAIN = "main"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DEVICE = "device/{key}"
// Два ОКРЕМІ екрани (не один спільний з "фокусом") - клік по сонцю й клік по АКБ
// повинні відкривати дві різні дії керування, а не той самий екран з різною прокруткою.
private const val ROUTE_INVERTER_OUTPUT = "inverter_output_priority"
private const val ROUTE_INVERTER_CHARGER = "inverter_charger_priority"
private const val ROUTE_JOURNAL_DEVICE = "journal_device/{device}"

// П'ять вкладок головного екрана - тепер сторінки одного горизонтально-гортаного
// пейджера (без нижнього меню, перемикання - лише свайпом вліво/вправо; решта екранів
// лишаються "штовхнутими" зверху пейджера, як і раніше). Порядок зліва направо:
// Схема - Дім (стартова) - Графік - Хронологія - Журнал.
private const val PAGE_SCHEME = 0
private const val PAGE_DASHBOARD = 1
private const val PAGE_ENERGY = 2
private const val PAGE_TIMELINE = 3
private const val PAGE_JOURNAL = 4
private const val PAGE_COUNT = 5

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
                    inverterSettingsLoading = inverterSettingsState.loading,
                    onOpenJournal = { device -> navController.navigate("journal_device/$device") },
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
                    onSetAutoWindow = { enabled, start, end ->
                        when (key) {
                            "boiler1" -> viewModel.setBoiler1AutoWindow(enabled, start, end)
                            "pump" -> viewModel.setPumpAutoWindow(enabled, start, end)
                            "boiler2" -> viewModel.setBoiler2AutoWindow(enabled, start, end)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_INVERTER_OUTPUT) {
                InverterOutputPriorityScreen(
                    state = inverterSettingsState,
                    onRefresh = { scope.launch { viewModel.loadInverterSettings() } },
                    onSetOutput = viewModel::setInverterOutputPriority,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_INVERTER_CHARGER) {
                InverterChargerPriorityScreen(
                    state = inverterSettingsState,
                    onRefresh = { scope.launch { viewModel.loadInverterSettings() } },
                    onSetCharger = viewModel::setInverterChargerPriority,
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
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    config = uiState.config,
                    onSave = viewModel::setConfig,
                    onBack = { navController.popBackStack() },
                    repository = viewModel.repository,
                )
            }
        }
    }
}

/**
 * П'ять головних вкладок як сторінки [HorizontalPager] замість колишнього нижнього меню:
 * гортаються свайпом ліворуч/праворуч. Хронологія й графік - "ландшафтні" екрани
 * (широкий вміст), але сама сторінка лишається портретною: [LandscapeInPortrait] повертає
 * їхній вміст на 90° на місці, замість реального повороту пристрою, - і саме тому свайп
 * пейджера (тепер фізично вертикальний усередині цих двох вкладок) не конфліктує зі
 * скролом/масштабом/курсором графіка (фізично горизонтальним там), див. chartGestures.
 */
@Composable
private fun MainPager(
    viewModel: DashboardViewModel,
    onOpenDevice: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInverterSettings: (String) -> Unit,
    inverterSettingsLoading: Boolean,
    onOpenJournal: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState(initialPage = PAGE_DASHBOARD, pageCount = { PAGE_COUNT })

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            PAGE_DASHBOARD -> DashboardScreen(
                uiState = uiState,
                onRefresh = viewModel::refreshNow,
                onOpenDevice = onOpenDevice,
                onOpenSettings = onOpenSettings,
                onTriggerGate = viewModel::triggerGate,
                onToggleLight = viewModel::toggleGarageLight,
                onOpenInverterSettings = onOpenInverterSettings,
                inverterSettingsLoading = inverterSettingsLoading,
                onOpenJournal = onOpenJournal,
            )
            PAGE_SCHEME -> SchemeScreen(uiState = uiState)
            PAGE_TIMELINE -> LandscapeInPortrait {
                TimelineScreen(config = uiState.config, repository = viewModel.repository)
            }
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
}
