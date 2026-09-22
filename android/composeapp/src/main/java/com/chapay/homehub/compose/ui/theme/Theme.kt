package com.chapay.homehub.compose.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val HomeHubDarkColors = darkColorScheme(
    primary = AccentGrid,
    onPrimary = TextPrimary,
    secondary = AccentBattery,
    onSecondary = TextPrimary,
    tertiary = AccentPv,
    onTertiary = SurfaceDeepest,
    error = AccentLoad,
    onError = TextPrimary,
    background = Surface,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = SurfaceDeepest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = OutlineSubtle,
    outlineVariant = OutlineSubtle,
)

@Composable
fun HomeHubComposeTheme(
    // Застосунок навмисно лише темний (як і чинний неоновий дизайн) - але
    // системний параметр лишаємо параметризованим на майбутнє.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = HomeHubDarkColors,
        typography = HomeHubTypography,
        content = content,
    )
}
