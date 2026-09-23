package com.chapay.homehub.compose.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chapay.homehub.compose.ui.DashboardViewModel
import com.chapay.homehub.compose.ui.screens.DashboardScreen
import com.chapay.homehub.compose.ui.screens.DeviceControlScreen
import com.chapay.homehub.compose.ui.screens.EnergyChartScreen
import com.chapay.homehub.compose.ui.screens.JournalScreen
import com.chapay.homehub.compose.ui.screens.SchemeScreen
import com.chapay.homehub.compose.ui.screens.SettingsScreen
import com.chapay.homehub.compose.ui.screens.TimelineScreen

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SCHEME = "scheme"
private const val ROUTE_TIMELINE = "timeline"
private const val ROUTE_JOURNAL = "journal"
private const val ROUTE_ENERGY = "energy"
private const val ROUTE_DEVICE = "device/{key}"

private data class BottomDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val BOTTOM_DESTINATIONS = listOf(
    BottomDestination(ROUTE_DASHBOARD, "Дім", Icons.Filled.Home),
    BottomDestination(ROUTE_SCHEME, "Схема", Icons.Filled.AccountTree),
    BottomDestination(ROUTE_TIMELINE, "Хронологія", Icons.Filled.Schedule),
    BottomDestination(ROUTE_JOURNAL, "Журнал", Icons.Filled.Article),
    BottomDestination(ROUTE_ENERGY, "Графік", Icons.Filled.BarChart),
)

@Composable
fun AppNavHost(
    viewModel: DashboardViewModel,
    navController: NavHostController = rememberNavController(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = BOTTOM_DESTINATIONS.any { it.route == currentRoute } &&
        currentRoute != ROUTE_TIMELINE && currentRoute != ROUTE_ENERGY

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BOTTOM_DESTINATIONS.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(ROUTE_DASHBOARD) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { outerPadding ->
        Box(Modifier.padding(bottom = outerPadding.calculateBottomPadding())) {
            NavHost(navController = navController, startDestination = ROUTE_DASHBOARD) {
                composable(ROUTE_DASHBOARD) {
                    DashboardScreen(
                        uiState = uiState,
                        onRefresh = viewModel::refreshNow,
                        onOpenDevice = { key -> navController.navigate("device/$key") },
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                        onTriggerGate = viewModel::triggerGate,
                        onToggleLight = viewModel::toggleGarageLight,
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
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        config = uiState.config,
                        onSave = viewModel::setConfig,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_SCHEME) {
                    SchemeScreen(uiState = uiState, onBack = { navController.popBackStack() })
                }
                composable(ROUTE_TIMELINE) {
                    TimelineScreen(
                        config = uiState.config,
                        repository = viewModel.repository,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_JOURNAL) {
                    JournalScreen(config = uiState.config, repository = viewModel.repository)
                }
                composable(ROUTE_ENERGY) {
                    val inv = uiState.status.inverter
                    EnergyChartScreen(
                        config = uiState.config,
                        repository = viewModel.repository,
                        liveTotals = inv?.let { Triple(it.dailyPv, it.dailyHome, it.dailyGrid) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
