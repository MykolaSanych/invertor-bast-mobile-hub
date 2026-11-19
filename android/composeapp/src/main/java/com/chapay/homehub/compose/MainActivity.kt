package com.chapay.homehub.compose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.chapay.homehub.compose.ui.DashboardViewModel
import com.chapay.homehub.compose.ui.navigation.AppNavHost
import com.chapay.homehub.compose.ui.theme.HomeHubComposeTheme
import com.chapay.homehub.compose.ui.theme.Surface as SurfaceColor

class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    // Android 13+ - без цього дозволу сповіщення фонового сервісу просто не
    // показуються (сам сервіс при цьому працює нормально). Відмова - не критична,
    // просто не буде push-повідомлень, доки користувач не дозволить вручну.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ok або ні - UI сам не змінюється */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            HomeHubComposeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = SurfaceColor) {
                    AppNavHost(viewModel = viewModel)
                }
            }
        }
    }
}
