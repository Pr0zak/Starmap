package com.starmap.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NightBlue = Color(0xFF8FB7FF)
private val DeepSpace = Color(0xFF05070D)
private val PanelSurface = Color(0xFF12151E)

private val StarmapDarkColors = darkColorScheme(
    primary = NightBlue,
    onPrimary = Color(0xFF06121F),
    secondary = Color(0xFFB7C6E8),
    background = DeepSpace,
    onBackground = Color(0xFFE2E6F0),
    surface = PanelSurface,
    onSurface = Color(0xFFE2E6F0),
    surfaceVariant = Color(0xFF1B2030),
)

private val StarmapLightColors = lightColorScheme(
    primary = Color(0xFF2A4D80),
    background = Color(0xFF0B0E16),
    surface = PanelSurface,
)

@Composable
fun StarmapTheme(useDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // The sky view is always dark; the app shell follows the system preference but
    // defaults to dark since stargazing happens at night.
    MaterialTheme(
        colorScheme = if (useDark) StarmapDarkColors else StarmapDarkColors,
        typography = Typography(),
        content = content,
    )
}
