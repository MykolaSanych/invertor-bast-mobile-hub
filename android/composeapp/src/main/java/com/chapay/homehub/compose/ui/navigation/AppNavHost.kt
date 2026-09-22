package com.chapay.homehub.compose.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chapay.homehub.compose.ui.DashboardViewModel
import com.chapay.homehub.compose.ui.screens.DashboardScreen
import com.chapay.homehub.compose.ui.screens.DeviceControlScreen
import com.chapay.homehub.compose.ui.screens.SchemeScreen
import com.chapay.homehub.compose.ui.screens.SettingsScreen

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SCHEME = "scheme"
private const val ROUTE_DEVICE = "device/{key}"

@Composable
fun AppNavHost(
    viewModel: DashboardViewModel,
    navController: NavHostController = rememberNavController(),
) {
    val uiState by viewModel.uiState.collectAsState()

    NavHost(navController = navController, startDestination = ROUTE_DASHBOARD) {
        composable(ROUTE_DASHBOARD) {
            DashboardScreen(
                uiState = uiState,
                onRefresh = viewModel::refreshNow,
                onOpenDevice = { key -> navController.navigate("device/$key") },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onOpenScheme = { navController.navigate(ROUTE_SCHEME) },
                onTriggerGate = viewModel::triggerGate,
                onToggleLight = viewModel::toggleGarageLight,
            )
        }
        composable(ROUTE_SCHEME) {
            SchemeScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_DEVICE) { backStackEntry ->
            val key = backStackEntry.arguments?.getString("key") ?: ""
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
    }
}
