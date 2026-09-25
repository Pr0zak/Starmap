package com.starmap.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.starmap.app.events.AlertPrefs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.starmap.app.events.AlertType
import com.starmap.app.events.SkyEvent
import com.starmap.app.events.SkyEventKind
import com.starmap.app.events.android.NotificationChannels
import com.starmap.app.settings.NotificationSettingsRepository.AlertBool
import com.starmap.app.settings.NotificationSettingsRepository.AlertInt
import com.starmap.app.settings.NotificationSettingsRepository.AlertFloat
import com.starmap.app.sky.AlertsController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Sky alerts — what Starmap will tell you about, and when.
 *
 * The master switch is intercepted rather than bound straight to the preference: on
 * Android 13 and later the app needs permission before a notification can reach anyone,
 * and writing "on" while the system silently drops every post is the worst of both
 * worlds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertsScreen(alerts: AlertsController, onShowEvent: (SkyEvent) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs by alerts.prefs.collectAsState()
    val state by alerts.state.collectAsState()
    val preview by alerts.preview.collectAsState()
    val testStatus by alerts.testStatus.collectAsState()

    var canPost by remember { mutableStateOf(canPostNotifications(context)) }
    var channelMuted by remember { mutableStateOf(false) }
    var deniedForGood by remember { mutableStateOf(false) }

    // The user may have changed any of this in Android's settings while we were away.
    DisposableEffectLifecycle(
        onResume = {
            canPost = canPostNotifications(context)
            channelMuted = NotificationChannels.blocked(context, NotificationChannels.TONIGHT)
        },
        onPause = {},
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        canPost = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        alerts.markPermissionRequested()
        if (canPost) alerts.setEnabled(true) else deniedForGood = state.permissionRequested
    }

    LaunchedEffect(Unit) { alerts.refreshPreview() }

    DetailScaffold(title = "Sky alerts", onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

                        SettingsGroup {
                SettingSwitch(
                    "Tell me when something's up",
                    when {
                        !prefs.enabled -> "A quiet nudge when there's something worth stepping outside for."
                        !canPost -> "Switched on, but Android is blocking Starmap's notifications."
                        else -> "On — Starmap checks a few times a day and stays out of your way otherwise."
                    },
                    // Deliberately not `&& canPost`: while Android is blocking notifications
                    // the row must still show the preference as on, or a tap reads as
                    // "switch on" and the user can never switch it off. The notice below
                    // explains the blocked state.
                    checked = prefs.enabled,
                ) { wanted ->
                    if (!wanted) {
                        alerts.setEnabled(false)
                    } else if (canPost) {
                        alerts.setEnabled(true)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Below Android 13 there is no permission to ask for — the user
                        // has switched Starmap's notifications off in system settings.
                        deniedForGood = true
                    }
                }

                if (prefs.enabled && !canPost) {
                    NoticeRow(
                        "Android is blocking Starmap's notifications, so nothing will reach you.",
                        "Open Android settings",
                    ) { openAppNotificationSettings(context) }
                } else if (deniedForGood && !canPost) {
                    NoticeRow(
                        "Notifications are switched off for Starmap. You can turn them back on in " +
                            "Android's settings.",
                        "Open Android settings",
                    ) { openAppNotificationSettings(context) }
                } else if (channelMuted && canPost) {
                    NoticeRow(
                        "\"Tonight's sky\" is muted in Android's settings, so these will arrive silently.",
                        "Open Android settings",
                    ) { openAppNotificationSettings(context) }
                }

                if (!state.hasLocation) {
                    NoticeRow(
                        "Starmap doesn't know where you are yet, so it can't work out what's visible " +
                            "from your sky. Open the star map once with location on, or set your " +
                            "coordinates by hand in Settings.",
                        null,
                    ) {}
                }
            }

            // What's coming up leads: it's the point of the screen. Tapping one shows it in the sky.
            SectionHeader("Coming up · next 30 days")
            when (val p = preview) {
                is AlertsController.PreviewState.Loading -> SettingsGroup { PreviewMessage("Working out what's coming…") }
                is AlertsController.PreviewState.NoLocation -> SettingsGroup {
                    PreviewMessage("Starmap needs to know where you are before it can work this out.")
                }
                is AlertsController.PreviewState.Ready -> when {
                    !prefs.anyTypeOn -> SettingsGroup {
                        PreviewMessage("Nothing switched on yet. Pick a few kinds below and they'll show up here.")
                    }
                    p.events.isEmpty() -> SettingsGroup {
                        PreviewMessage(
                            "Nothing in the next 30 days matches your settings. Loosen the limits below — or enjoy the quiet.",
                        )
                    }
                    else -> Column(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (e in p.events.take(12)) EventCard(e) { onShowEvent(e) }
                    }
                }
            }

            SectionHeader("Tell me about")
            FlowRow(
                modifier = Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (type in AlertType.entries) {
                    TypeChip(type.label, selected = prefs[type], enabled = prefs.enabled) {
                        alerts.setType(type, !prefs[type])
                    }
                }
            }
            // Per-kind thresholds live behind one fold, shown only for kinds that are on.
            val limitTypes = listOf(AlertType.MeteorPeak, AlertType.Conjunction, AlertType.DarkSkyNight, AlertType.IssPass)
                .filter { prefs[it] }
            if (limitTypes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SettingsGroup {
                    var showLimits by rememberSaveable { mutableStateOf(false) }
                    FoldRow("Limits", limitsSummary(prefs, limitTypes), showLimits) { showLimits = !showLimits }
                    if (showLimits) for (type in limitTypes) {
                        when (type) {
                            AlertType.MeteorPeak -> SettingSlider(
                                label = "Meteor showers busier than",
                                value = prefs.meteorMinZhr.toFloat(),
                                valueText = "${prefs.meteorMinZhr}/hour",
                                range = 0f..150f,
                                steps = 14,
                                onChange = { alerts.setInt(AlertInt.MeteorMinZhr, it.roundToInt()) },
                            )
                            AlertType.Conjunction -> SettingSlider(
                                label = "Pairings closer than",
                                value = prefs.conjunctionMaxSepDeg,
                                valueText = "≤ %.1f°".format(prefs.conjunctionMaxSepDeg),
                                range = 0.5f..10f,
                                steps = 18,
                                onChange = { alerts.setFloat(AlertFloat.ConjunctionMaxSep, it) },
                            )
                            AlertType.DarkSkyNight -> SettingSlider(
                                label = "Moonless: Moon under",
                                value = prefs.darkSkyMaxMoon,
                                valueText = "${(prefs.darkSkyMaxMoon * 100).roundToInt()}% lit",
                                range = 0f..0.6f,
                                steps = 11,
                                onChange = { alerts.setFloat(AlertFloat.DarkSkyMaxMoon, it) },
                            )
                            AlertType.IssPass -> SettingSlider(
                                label = "ISS passes that climb above",
                                value = prefs.issMinPeakAltDeg,
                                valueText = "${prefs.issMinPeakAltDeg.roundToInt()}° up",
                                range = 10f..80f,
                                steps = 13,
                                onChange = { alerts.setFloat(AlertFloat.IssMinPeakAlt, it) },
                            )
                            else -> Unit
                        }
                    }
                }
            }

            SectionHeader("Timing")
            SettingsGroup {
                var showTiming by rememberSaveable { mutableStateOf(false) }
                FoldRow("When and how often", timingSummary(prefs), showTiming) { showTiming = !showTiming }
                if (showTiming) {
                    ChoiceRow(
                        "Heads-up for big nights",
                        "Meteor peaks and the like arrive at dusk, so there's still time to get somewhere dark.",
                        options = listOf("On the night", "1 night before", "2 nights"),
                        selected = prefs.leadDays.coerceIn(0, 2),
                    ) { alerts.setInt(AlertInt.LeadDays, it) }

                    SettingSwitch(
                        "Quiet hours",
                        "Stay silent overnight. Anything that falls inside is skipped rather than saved " +
                            "for the morning, because by then it would be wrong.",
                        checked = prefs.quietEnabled,
                        enabled = prefs.enabled,
                    ) { alerts.setBool(AlertBool.QuietHours, it) }

                    if (prefs.quietEnabled) {
                        // One time range instead of two 0–23 sliders.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HourPicker(prefs.quietStartHour) { alerts.setInt(AlertInt.QuietStartHour, it) }
                            Text("  to  ", color = Hud.TextDim, fontSize = 14.sp)
                            HourPicker(prefs.quietEndHour) { alerts.setInt(AlertInt.QuietEndHour, it) }
                        }
                        SettingSwitch(
                            "…but let eclipses through",
                            "An eclipse won't wait until morning.",
                            checked = prefs.quietAllowEclipses,
                            enabled = prefs.enabled,
                            indent = true,
                        ) { alerts.setBool(AlertBool.QuietAllowEclipses, it) }
                        Text(
                            "Quiet hours only affect Starmap. Android's Do Not Disturb still applies.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }

                    ChoiceRow(
                        "Moonlight",
                        "A bright Moon washes out meteors and anything faint.",
                        options = listOf("Ignore", "Mention it", "Skip the alert"),
                        selected = prefs.moonlight.coerceIn(0, 2),
                    ) { alerts.setInt(AlertInt.Moonlight, it) }

                    SettingSlider(
                        label = "At most per night",
                        value = prefs.maxPerDay.toFloat(),
                        valueText = "${prefs.maxPerDay}",
                        range = 1f..5f,
                        steps = 3,
                        onChange = { alerts.setInt(AlertInt.MaxPerDay, it.roundToInt()) },
                    )
                }
            }

            SectionHeader("Check it works")
            SettingsGroup {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "Sends one straight away, using a real event if there's one coming, so you " +
                            "can see how it'll look. Ignores quiet hours.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { alerts.sendTest() }, enabled = canPost) {
                            Text("Send a test alert")
                        }
                    }
                    testStatus?.let {
                        Text(
                            it, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Text(
                        if (state.lastRunMillis > 0L) {
                            "Last looked for events ${relativeAgo(state.lastRunMillis)}."
                        } else {
                            "Starmap hasn't looked for events yet."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewMessage(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** One upcoming event: a date tile coloured by kind, the title, and what to expect. */
@Composable
private fun EventCard(event: SkyEvent, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(event.peakMillis).atZone(zone)
    val accent = eventAccent(event.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .width(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.13f))
                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(date).uppercase(),
                color = accent, fontSize = 9.5.sp, letterSpacing = 1.sp,
            )
            Text("${date.dayOfMonth}", color = Hud.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
            Text(
                DateTimeFormatter.ofPattern("MMM", Locale.getDefault()).format(date).uppercase(),
                color = Hud.TextDim.copy(alpha = 0.7f), fontSize = 9.5.sp,
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    previewTitle(event), color = Hud.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(relativeWhen(event.peakMillis), color = Hud.Gold, fontSize = 11.sp)
            }
            Text(
                event.body, color = Hud.TextDim.copy(alpha = 0.8f), fontSize = 12.5.sp, lineHeight = 17.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun eventAccent(kind: SkyEventKind): Color = when (kind) {
    SkyEventKind.MeteorPeak -> Color(0xFFCE93D8)
    SkyEventKind.LunarEclipse, SkyEventKind.SolarEclipse -> Color(0xFFFF8A80)
    SkyEventKind.PlanetConjunction, SkyEventKind.MoonConjunction,
    SkyEventKind.FullMoon, SkyEventKind.NewMoon, SkyEventKind.DarkNight -> Color(0xFFB0BEC5)
    SkyEventKind.Opposition, SkyEventKind.GreatestElongation -> Color(0xFFFFC061)
    SkyEventKind.Equinox, SkyEventKind.Solstice -> Color(0xFFFFE082)
    SkyEventKind.IssPass -> Color(0xFF80CBC4)
}

/** A toggle chip for one kind of alert. */
@Composable
private fun TypeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        (if (selected) "✓ " else "") + label,
        color = when {
            !enabled -> Hud.TextDim.copy(alpha = 0.4f)
            selected -> Hud.Ink
            else -> Hud.TextDim
        },
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(shape)
            .then(
                if (selected && enabled) {
                    Modifier.background(Hud.Gold)
                } else {
                    Modifier.background(Color(0x14FFFFFF)).border(1.dp, Hud.Hairline, shape)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** A row that folds a group of settings away behind a one-line summary. */
@Composable
private fun FoldRow(title: String, summary: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Hide" else "Show", tint = Hud.TextDim,
        )
    }
}

/** An hour-of-day button that opens a list of the 24 hours. */
@Composable
private fun HourPicker(hour: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(hourLabel(hour)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            for (h in 0 until 24) {
                DropdownMenuItem(text = { Text(hourLabel(h)) }, onClick = { open = false; onPick(h) })
            }
        }
    }
}

private fun limitsSummary(prefs: AlertPrefs, types: List<AlertType>): String = types.mapNotNull {
    when (it) {
        AlertType.MeteorPeak -> "meteors > ${prefs.meteorMinZhr}/h"
        AlertType.Conjunction -> "pairings ≤ %.1f°".format(prefs.conjunctionMaxSepDeg)
        AlertType.DarkSkyNight -> "Moon < ${(prefs.darkSkyMaxMoon * 100).roundToInt()}%"
        AlertType.IssPass -> "ISS > ${prefs.issMinPeakAltDeg.roundToInt()}°"
        else -> null
    }
}.joinToString(" · ").replaceFirstChar { it.uppercase() }

private fun timingSummary(prefs: AlertPrefs): String = listOfNotNull(
    when (prefs.leadDays.coerceIn(0, 2)) { 0 -> "On the night"; 1 -> "1 night before"; else -> "2 nights before" },
    if (prefs.quietEnabled) "quiet ${hourLabel(prefs.quietStartHour)}–${hourLabel(prefs.quietEndHour)}" else null,
    when (prefs.moonlight.coerceIn(0, 2)) { 1 -> "mention the Moon"; 2 -> "skip moonlit nights"; else -> null },
    "max ${prefs.maxPerDay} a night",
).joinToString(" · ")

@Composable
private fun NoticeRow(text: String, action: String?, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text, fontSize = 13.sp)
            if (action != null) {
                Text(
                    action,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).clickable(onClick = onAction),
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    description: String?,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 16.sp)
        if (description != null) {
            Text(
                description, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        SegmentedChoice(
            options = options,
            selected = selected,
            modifier = Modifier.padding(top = 8.dp),
            onSelect = onSelect,
        )
    }
}

/**
 * Whether a notification would actually be seen. Both halves matter: the runtime
 * permission exists only from Android 13, but on every version the user can switch the
 * app's notifications off entirely.
 */
private fun canPostNotifications(context: android.content.Context): Boolean {
    val runtimeOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return runtimeOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private fun openAppNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun hourLabel(hour: Int): String = when {
    hour == 0 -> "midnight"
    hour == 12 -> "noon"
    hour < 12 -> "$hour am"
    else -> "${hour - 12} pm"
}

/**
 * Titles are written for the moment the alert is delivered, when "tonight" is true. In a
 * list of things weeks away it is not, and "Partial lunar eclipse tonight — in 5 days"
 * reads as a contradiction, so the word is dropped for anything not happening today.
 */
internal fun previewTitle(event: SkyEvent): String {
    val hoursAway = (event.peakMillis - System.currentTimeMillis()) / 3_600_000.0
    if (hoursAway < 20) return event.title
    return event.title
        .replace(Regex(" (tonight|today|tomorrow night|in \\d+ nights)$"), "")
        .trim()
}

internal fun relativeWhen(atMillis: Long): String {
    val days = (atMillis - System.currentTimeMillis()) / 86_400_000.0
    return when {
        days < 1 -> "today"
        days < 2 -> "tomorrow"
        days < 7 -> "in ${days.toInt()} days"
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            .format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))
    }
}

private fun relativeAgo(atMillis: Long): String {
    val minutes = (System.currentTimeMillis() - atMillis) / 60_000
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes minutes ago"
        minutes < 120 -> "an hour ago"
        minutes < 1440 -> "${minutes / 60} hours ago"
        else -> "${minutes / 1440} days ago"
    }
}
