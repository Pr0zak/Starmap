package com.starmap.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NightBlue = Color(0xFF8FB7FF)
private val Amber = Color(0xFFFFD54F) // the app-wide "active / accent" colour
private val DeepSpace = Color(0xFF05070D)
private val PanelSurface = Color(0xFF12151E)

// A complete deep-space dark scheme so the built-in M3 components (Switch, Slider,
// Dialog, DropdownMenu, chips, cards) match the hand-drawn night UI instead of
// falling back to default purples.
private val StarmapDarkColors = darkColorScheme(
    primary = NightBlue,
    onPrimary = Color(0xFF06121F),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB7C6E8),
    onSecondary = Color(0xFF111722),
    secondaryContainer = Color(0xFF273043),
    onSecondaryContainer = Color(0xFFD9E2F5),
    tertiary = Amber,
    onTertiary = Color(0xFF231A00),
    tertiaryContainer = Color(0xFF574400),
    onTertiaryContainer = Color(0xFFFFE69A),
    background = DeepSpace,
    onBackground = Color(0xFFE2E6F0),
    surface = PanelSurface,
    onSurface = Color(0xFFE2E6F0),
    surfaceVariant = Color(0xFF1B2030),
    onSurfaceVariant = Color(0xFFAAB4C6),
    surfaceContainerLowest = Color(0xFF080B11),
    surfaceContainerLow = Color(0xFF11141D),
    surfaceContainer = Color(0xFF161B27),
    surfaceContainerHigh = Color(0xFF1C2230),
    surfaceContainerHighest = Color(0xFF232A3B),
    outline = Color(0xFF3A4253),
    outlineVariant = Color(0xFF272D3A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE2E6F0),
    inverseOnSurface = Color(0xFF12151E),
)

private val StarmapShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun StarmapTheme(content: @Composable () -> Unit) {
    // Stargazing happens at night, so the app is intentionally dark-only.
    MaterialTheme(
        colorScheme = StarmapDarkColors,
        typography = Typography(),
        shapes = StarmapShapes,
        content = content,
    )
}
