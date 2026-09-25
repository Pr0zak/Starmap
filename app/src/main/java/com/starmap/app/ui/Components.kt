package com.starmap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A simple detail screen shell with a back button and title. */
@Composable
fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp))
        }
        // Keep content clear of the gesture/navigation bar and the keyboard.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding()) {
            content()
        }
    }
}

@Composable
fun SettingSwitch(
    label: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    /** A sub-option of the row above: indented under a thin guide so it reads as nested. */
    indent: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (indent) Modifier.padding(start = 20.dp).drawBehind {
                drawLine(Hud.Hairline, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2.dp.toPx())
            } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            if (description != null) {
                Text(description, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A settings row that opens another screen, styled to match [SettingSwitch]. */
@Composable
fun SettingsLink(label: String, description: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            if (description != null) {
                Text(
                    description, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    /** A plain-language reading of the current value, under the label. */
    description: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 16.sp)
            Text(valueText, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        if (description != null) {
            Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        CleanSlider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            accent = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * A Slider without the Material 3 "expressive" extras (bar thumb, active/inactive
 * gap, and the stop-indicator dot at the track end) — just a small round thumb on a
 * thin continuous track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    accent: Color = Color(0xFFFFD54F),
) {
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        interactionSource = interaction,
        thumb = {
            Box(Modifier.size(14.dp).background(accent, CircleShape))
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(4.dp),
                colors = SliderDefaults.colors(
                    activeTrackColor = accent,
                    inactiveTrackColor = Color(0x33FFFFFF),
                    activeTickColor = Color(0x55000000),
                    inactiveTickColor = Color(0x55FFFFFF),
                ),
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

/** A modern single-choice segmented button row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedChoice(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = selected == i,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                label = { Text(label, fontSize = 13.sp, maxLines = 1) },
            )
        }
    }
}

/** Groups a settings section's rows on a glass plate, like the HUD's cards. */
@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .glass(RoundedCornerShape(16.dp))
            .padding(vertical = 4.dp),
        content = content,
    )
}

/** A settings-hub row: icon badge, title, a one-line summary of the current state. */
@Composable
fun SettingsCategoryRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    summary: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape)
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, fontSize = 16.sp, color = Hud.Text)
            Text(summary, fontSize = 12.sp, color = Hud.TextDim.copy(alpha = 0.75f), maxLines = 2)
        }
        badge?.let { StatusPill(it, Hud.Gold) }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Hud.TextDim.copy(alpha = 0.6f),
        )
    }
}

/** A small outlined status label ("Not downloaded", "Synced", "v2.3.0"). */
@Composable
fun StatusPill(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(end = 6.dp)
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The glass search field used by Search and Settings. */
@Composable
fun GlassSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .glass(RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Hud.Gold, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(placeholder, color = Hud.TextDim.copy(alpha = 0.6f), fontSize = 16.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Hud.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(Hud.Gold),
                modifier = fieldModifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Hud.TextDim)
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
    )
}
