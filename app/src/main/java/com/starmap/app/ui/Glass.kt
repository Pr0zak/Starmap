package com.starmap.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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

/**
 * A docked bottom-sheet surface: only the top corners are rounded, filled with a
 * near-opaque navy gradient (so list content stays legible over the map/scope) and a
 * single hairline accent along the top edge — no side or bottom border, so it reads as
 * docked to the screen edge rather than a floating card.
 */
fun Modifier.bottomSheet(topRadius: Dp = 22.dp): Modifier {
    val shape = RoundedCornerShape(topStart = topRadius, topEnd = topRadius)
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xF21B2436), Color(0xFF0B0F18))))
        .drawBehind {
            val r = topRadius.toPx()
            drawLine(Hud.Hairline, Offset(r, 0.5f), Offset(size.width - r, 0.5f), strokeWidth = 1f)
        }
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

/**
 * A labelled number: small caps label over a value, with an optional unit/detail line.
 * Used for ALT/AZ/MAG on the object card and ALT/SPD/HDG/DIST on the aircraft card.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    trend: String? = null,
    trendColor: Color = Hud.Text,
    filled: Boolean = false,
    /** Optional small chart under the value (e.g. the altitude history). */
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .then(
                if (filled) {
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x0DFFFFFF))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                } else {
                    Modifier
                },
            ),
    ) {
        Text(label, color = Color(0xFF8B97A8), fontSize = 9.5.sp, letterSpacing = 1.sp)
        Row {
            Text(value, color = Hud.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (trend != null) Text(trend, color = trendColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (sub != null) Text(sub, color = Color(0xFF8B97A8), fontSize = 10.sp, maxLines = 1)
        extra?.invoke()
    }
}

/** A labelled glass action button (icon + text). [active] turns it gold. */
@Composable
fun ActionPill(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.dp)
    val fg = if (primary) Hud.Ink else if (active) Hud.Gold else Hud.Text
    Row(
        modifier = Modifier
            .clip(shape)
            .then(
                if (primary) {
                    Modifier.background(Brush.verticalGradient(listOf(Hud.GoldSoft, Hud.Gold)))
                } else {
                    Modifier.background(Color(0x14FFFFFF))
                        .border(BorderStroke(1.dp, if (active) Hud.HairlineGold else Hud.Hairline), shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium)
    }
}

/** A softly pulsing placeholder block, for images and text that are still loading. */
@Composable
fun Shimmer(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    val alpha by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0.04f,
        targetValue = 0.11f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "shimmer-alpha",
    )
    Box(modifier.clip(shape).background(Color.White.copy(alpha = alpha)))
}

/**
 * A bottom sheet drawn inside the current window: a dimming scrim, then a docked
 * panel with a drag handle. Tap the scrim, press back or drag the panel down to
 * dismiss. Material's ModalBottomSheet opens a separate window whose system bars
 * don't follow the activity's dark style, so it isn't used here.
 */
@Composable
fun InWindowSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val density = LocalDensity.current
    var dragY by remember { mutableFloatStateOf(0f) }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val noRipple = remember { MutableInteractionSource() }
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visibleState = shown, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000))
                    .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .graphicsLayer { translationY = dragY.coerceAtLeast(0f) }
                    .bottomSheet(22.dp)
                    // Swallow taps so they don't fall through to the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dragY += it },
                        onDragStopped = {
                            if (dragY > with(density) { 110.dp.toPx() }) onDismiss()
                            dragY = 0f
                        },
                    ),
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp)))
                }
                content()
            }
        }
    }
}
