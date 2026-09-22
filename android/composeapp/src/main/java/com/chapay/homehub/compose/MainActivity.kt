package com.chapay.homehub.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.chapay.homehub.compose.ui.DashboardViewModel
import com.chapay.homehub.compose.ui.navigation.AppNavHost
import com.chapay.homehub.compose.ui.theme.HomeHubComposeTheme
import com.chapay.homehub.compose.ui.theme.Surface as SurfaceColor

class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HomeHubComposeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = SurfaceColor) {
                    AppNavHost(viewModel = viewModel)
                }
            }
        }
    }
}
