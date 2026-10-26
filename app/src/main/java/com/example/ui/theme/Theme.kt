package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EditorialDarkColorScheme = darkColorScheme(
    primary = BrushedAmberBronze,
    onPrimary = Color(0xFF241107),
    primaryContainer = BronzeContainerDark,
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = MineralTealLight,
    onSecondary = Color(0xFF072327),
    secondaryContainer = MineralTealContainerDark,
    onSecondaryContainer = Color(0xFFC8E9EE),
    tertiary = SageGreenLight,
    onTertiary = Color(0xFF0A2818),
    background = ObsidianBg,
    onBackground = IvoryText,
    surface = ObsidianSurface,
    onSurface = IvoryText,
    surfaceVariant = ObsidianCard,
    onSurfaceVariant = StoneTextMuted,
    error = Color(0xFFFFB4AB)
)

private val EditorialLightColorScheme = lightColorScheme(
    primary = TerracottaBronze,
    onPrimary = Color.White,
    primaryContainer = TerracottaLight,
    onPrimaryContainer = Color(0xFF3A1609),
    secondary = DeepMineralSlate,
    onSecondary = Color.White,
    secondaryContainer = MineralSlateContainer,
    onSecondaryContainer = Color(0xFF0E2225),
    tertiary = Color(0xFF3E6B52),
    onTertiary = Color.White,
    tertiaryContainer = SageContainer,
    onTertiaryContainer = Color(0xFF0E291B),
    background = AlabasterCream,
    onBackground = EspressoInk,
    surface = WarmSandstone,
    onSurface = EspressoInk,
    surfaceVariant = TravertineSurface,
    onSurfaceVariant = CharcoalMuted,
    error = CrimsonClay
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) EditorialDarkColorScheme else EditorialLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
