package com.starmap.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared "glass HUD" design tokens and helpers — the translucent, faintly-bordered,
 * softly-glowing surfaces used by the overlay UI on top of the sky/radar. Kept here so
 * the look stays consistent across MainScreen, RadarScreen and the info cards.
 */
object Hud {
    val Gold = Color(0xFFFFD54F)
    val GoldSoft = Color(0xFFFFE08A)
    val Ink = Color(0xFF0B0F18) // dark text/icon on a gold fill
    val Text = Color(0xFFE7ECF6)
    val TextDim = Color(0xCCBFCAD9)

    // Translucent navy fills — the sky shows through, which reads as "glass".
    val GlassTop = Color(0xD11C2438)
    val GlassBottom = Color(0xD10E1320)
    val Hairline = Color(0x3AAFC6F0) // cool 1px edge
    val HairlineGold = Color(0x66FFD54F)
}

/** Vertical-gradient translucent fill + hairline border, clipped to [shape]. The "glass plate". */
fun Modifier.glass(
    shape: Shape,
    top: Color = Hud.GlassTop,
    bottom: Color = Hud.GlassBottom,
    border: Color = Hud.Hairline,
    borderWidth: Dp = 1.dp,
): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(top, bottom)))
    .border(BorderStroke(borderWidth, border), shape)

/** A soft coloured halo behind an element (draw before the fill; uses an un-clipped shadow). */
fun Modifier.glow(shape: Shape, color: Color = Hud.Gold, radius: Dp = 14.dp): Modifier =
    this.shadow(
        elevation = radius,
        shape = shape,
        clip = false,
        ambientColor = color.copy(alpha = 0.55f),
        spotColor = color.copy(alpha = 0.85f),
    )

/**
 * A HUD icon button: the icon sits in a translucent rounded-square glass chip. When [active]
 * it tints gold, takes a gold hairline and a faint gold glow.
 */
@Composable
fun HudIconButton(
    icon: ImageVector,
    contentDescription: String?,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = modifier
            .size(42.dp)
            .then(if (active) Modifier.glow(shape, Hud.Gold, 10.dp) else Modifier)
            .glass(shape, border = if (active) Hud.HairlineGold else Hud.Hairline)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) Hud.Gold else Hud.Text,
            modifier = Modifier.size(20.dp),
        )
    }
}
