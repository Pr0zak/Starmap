package com.starmap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starmap.app.BuildConfig
import com.starmap.app.events.AlertType
import com.starmap.app.settings.Settings
import com.starmap.app.settings.SettingsRepository.BoolSetting
import com.starmap.app.settings.SettingsRepository.FloatSetting
import com.starmap.app.sky.OfflineSync
import com.starmap.app.sky.SkyViewModel
import com.starmap.app.update.UpdateChecker
import kotlin.math.roundToInt

/** The settings pages reachable from the hub. [labels] feed the hub's search. */
enum class SettingsPage(
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val labels: List<String>,
) {
    Location(
        "Location", Icons.Filled.MyLocation, Color(0xFFFFD54F),
        listOf("Set location manually", "Latitude", "Longitude", "GPS"),
    ),
    SkyObjects(
        "Sky objects", Icons.Filled.Star, Color(0xFFFFE082),
        listOf(
            "Star magnitude limit", "Label brightness limit", "Field of view", "Star labels",
            "Constellation lines", "Constellation names", "Constellation figures", "Meteor shower radiants",
            "Deep-sky objects (Messier)", "Milky Way", "Planets", "Asteroids", "Asteroid paths", "Comets",
            "Comet paths", "Sun", "Moon", "Extended star catalog",
        ),
    ),
    Guides(
        "Lines & guides", Icons.Filled.Explore, Color(0xFF90CAF9),
        listOf(
            "Horizon line", "Compass directions", "Ecliptic", "Celestial equator", "RA/Dec grid",
            "Show objects below the horizon", "Atmospheric refraction", "Field-of-view rings",
        ),
    ),
    Traffic(
        "Aircraft & satellites", Icons.Filled.Flight, Color(0xFFFFC061),
        listOf("Space Station (ISS)", "Starlink satellites", "Show aircraft", "Aircraft range", "Fading trails", "Aircraft labels"),
    ),
    Landmarks(
        "Landmarks", Icons.Filled.Place, Color(0xFFE8A477),
        listOf("Show landmarks", "Landmark range", "Cities & towns", "Airports", "Towers & masts"),
    ),
    Display(
        "Display", Icons.Filled.DisplaySettings, Color(0xFFCE93D8),
        listOf("Night mode (red)", "Centre identify", "Screen orientation", "AR camera dimming"),
    ),
    Alerts(
        "Sky alerts", Icons.Filled.NotificationsActive, Color(0xFF7FE3A0),
        listOf("Sky alerts", "Notifications", "Meteor", "Eclipse", "Quiet hours"),
    ),
    Offline(
        "Offline data", Icons.Filled.Download, Color(0xFF80CBC4),
        listOf("Object info", "Sync offline data", "Space Station (ISS) download", "Starlink download", "Offline downloads"),
    ),
    About(
        "About & updates", Icons.Filled.Info, Color(0xFFB0BEC5),
        listOf("Check for updates", "Version", "Diagnostics", "Licenses"),
    ),
}

/**
 * Settings: a hub of categories (each with a one-line summary of its current state),
 * a search across every row, and where Starmap thinks you are at the top. Each
 * category opens its own page; Sky alerts and About open their own screens.
 */
@Composable
fun SettingsScreen(
    viewModel: SkyViewModel,
    settings: Settings,
    onOpenAlerts: () -> Unit,
    onOpenAbout: () -> Unit,
    initialPage: SettingsPage? = null,
    onBack: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val current = page
    if (current != null) {
        BackHandler { page = null }
        DetailScaffold(title = current.title, onBack = { page = null }) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 32.dp)) {
                SettingsGroup { SettingsPageContent(current, viewModel, settings) }
            }
        }
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    // The Offline data summary reads the cache size, which is only measured on request.
    LaunchedEffect(Unit) { viewModel.refreshOfflineSize() }
    fun open(p: SettingsPage) = when (p) {
        SettingsPage.Alerts -> onOpenAlerts()
        SettingsPage.About -> onOpenAbout()
        else -> page = p
    }

    DetailScaffold(title = "Settings", onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            GlassSearchField(query, { query = it }, "Search settings")
            Spacer(Modifier.height(12.dp))
            if (query.isNotBlank()) {
                val q = query.trim()
                val hits = SettingsPage.entries.flatMap { p ->
                    (listOf(p.title) + p.labels).filter { it.contains(q, ignoreCase = true) }.distinct().map { p to it }
                }
                if (hits.isEmpty()) {
                    Text(
                        "No settings match “$q”.",
                        color = Hud.TextDim, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    SettingsGroup {
                        for ((p, label) in hits) {
                            SettingsCategoryRow(p.icon, p.accent, label, "in ${p.title}") { open(p) }
                        }
                    }
                }
                return@Column
            }

            // Where Starmap thinks you are: the first thing to check when the sky looks wrong.
            val fix by viewModel.effectiveLocation.collectAsState()
            SettingsGroup {
                SettingsCategoryRow(
                    SettingsPage.Location.icon, SettingsPage.Location.accent,
                    title = when {
                        fix == null -> "Location unknown"
                        settings.manualLocation -> "Manual location"
                        else -> "Your location"
                    },
                    summary = fix?.let {
                        "%.3f, %.3f · %s".format(it.latitude, it.longitude, if (it.fromGps) "from GPS" else "set by hand")
                    } ?: "Waiting for GPS · tap to enter coordinates",
                ) { page = SettingsPage.Location }
            }
            Spacer(Modifier.height(10.dp))

            val alertPrefs by viewModel.alerts.prefs.collectAsState()
            val update by viewModel.updateResult
            SettingsGroup {
                for (p in SettingsPage.entries) {
                    if (p == SettingsPage.Location) continue
                    SettingsCategoryRow(
                        p.icon, p.accent, p.title,
                        summary = pageSummary(p, viewModel, settings, alertPrefs.enabled, AlertType.entries.count { alertPrefs[it] }),
                        badge = if (p == SettingsPage.About && update is UpdateChecker.Result.Available) {
                            "v" + (update as UpdateChecker.Result.Available).release.versionName
                        } else {
                            null
                        },
                    ) { open(p) }
                }
            }
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingSwitch("Night mode (red)", "Preserves dark adaptation", checked = settings.nightMode) {
                    viewModel.setBool(BoolSetting.NightMode, it)
                }
            }
        }
    }
}

/** The one-line "current state" summary under each hub row. */
private fun pageSummary(
    p: SettingsPage,
    vm: SkyViewModel,
    s: Settings,
    alertsOn: Boolean,
    alertKinds: Int,
): String {
    fun on(vararg pairs: Pair<Boolean, String>) = pairs.filter { it.first }.map { it.second }
    val sat = vm.satelliteManager
    return when (p) {
        SettingsPage.Location -> ""
        SettingsPage.SkyObjects -> {
            val bodies = on(s.showPlanets to "planets", s.showMoon to "Moon", s.showSun to "Sun",
                s.showMessier to "deep sky", s.showComets to "comets", s.showAsteroids to "asteroids")
            "Stars to mag %.1f".format(s.magnitudeLimit) +
                (if (bodies.isNotEmpty()) " · " + bodies.take(3).joinToString(", ") else "")
        }
        SettingsPage.Guides -> on(
            s.showConstellations to "constellations", s.showHorizon to "horizon", s.showCardinals to "compass",
            s.showEcliptic to "ecliptic", s.showEquator to "equator", s.showGrid to "grid",
        ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "All off" }
        SettingsPage.Traffic -> listOf(
            if (s.showAircraft) "Aircraft on" else "Aircraft off",
            when {
                !sat.isIssDownloaded -> "ISS not downloaded"
                s.showIss -> "ISS on"
                else -> "ISS off"
            },
        ).joinToString(" · ")
        SettingsPage.Landmarks -> if (s.showLandmarks) "On · ${s.landmarkRangeKm.roundToInt()} km" else "Off"
        SettingsPage.Display -> listOf(
            if (s.nightMode) "Night mode on" else "Night mode off",
            when (s.orientationMode) { 1 -> "portrait"; 2 -> "landscape"; else -> "auto-rotate" },
        ).joinToString(" · ")
        SettingsPage.Alerts -> if (alertsOn) "On · $alertKinds kinds" else "Off"
        SettingsPage.Offline -> buildList {
            add("Object info ${formatBytes(vm.offlineBytes.value)}")
            add(if (sat.isIssDownloaded) "ISS ✓" else "ISS —")
            add(if (sat.isStarlinkDownloaded) "Starlink ✓" else "Starlink —")
        }.joinToString(" · ")
        SettingsPage.About -> "Version ${BuildConfig.VERSION_NAME}"
    }
}

@Composable
private fun SettingsPageContent(page: SettingsPage, viewModel: SkyViewModel, settings: Settings) {
    when (page) {
        SettingsPage.Location -> ManualLocationSection(viewModel, settings)
        SettingsPage.SkyObjects -> SkyObjectsPage(viewModel, settings)
        SettingsPage.Guides -> GuidesPage(viewModel, settings)
        SettingsPage.Traffic -> TrafficPage(viewModel, settings)
        SettingsPage.Landmarks -> LandmarksPage(viewModel, settings)
        SettingsPage.Display -> DisplayPage(viewModel, settings)
        SettingsPage.Offline -> {
            OfflineDataSection(viewModel)
            SatelliteDownloadRows(viewModel, settings)
        }
        SettingsPage.Alerts, SettingsPage.About -> Unit // separate screens
    }
}

/** What a naked-eye magnitude limit looks like in practice. */
private fun skyForMagnitude(mag: Float): String = when {
    mag <= 2f -> "a city-centre sky"
    mag <= 3f -> "a bright suburban sky"
    mag <= 4f -> "a suburban sky"
    mag <= 5f -> "a rural sky"
    mag <= 6.5f -> "a truly dark sky"
    else -> "binoculars"
}

@Composable
private fun SkyObjectsPage(viewModel: SkyViewModel, settings: Settings) {
    val count = remember(settings.magnitudeLimit, settings.useExtendedCatalog) {
        viewModel.starsBrighterThan(settings.magnitudeLimit)
    }
    SettingSlider(
        label = "Star magnitude limit",
        value = settings.magnitudeLimit,
        valueText = "≤ %.1f".format(settings.magnitudeLimit),
        range = 2f..8f,
        steps = 11,
        description = (count?.let { "≈ ${"%,d".format(it)} stars across the whole sky · " } ?: "") +
            "like ${skyForMagnitude(settings.magnitudeLimit)}",
        onChange = { viewModel.setFloat(FloatSetting.MagnitudeLimit, it) },
    )
    SettingSlider(
        label = "Label brightness limit",
        value = settings.labelMagnitudeLimit,
        valueText = "≤ %.1f".format(settings.labelMagnitudeLimit),
        range = 0f..5f,
        steps = 9,
        description = "Names only for stars brighter than this (lower = fewer labels)",
        onChange = { viewModel.setFloat(FloatSetting.LabelMagnitudeLimit, it) },
    )
    SettingSlider(
        label = "Field of view",
        value = settings.fovDeg,
        valueText = "${settings.fovDeg.toInt()}°",
        range = 12f..90f,
        description = "How much sky fits on screen; pinch the sky to change it too",
        onChange = { viewModel.setFloat(FloatSetting.Fov, it) },
    )
    SettingSwitch("Star labels", checked = settings.showStarLabels) {
        viewModel.setBool(BoolSetting.StarLabels, it)
    }
    SettingSwitch("Constellation lines", checked = settings.showConstellations) {
        viewModel.setBool(BoolSetting.Constellations, it)
    }
    SettingSwitch("Constellation names", checked = settings.showConstellationNames,
        enabled = settings.showConstellations, indent = true) {
        viewModel.setBool(BoolSetting.ConstellationNames, it)
    }
    SettingSwitch("Constellation figures", "Overlay the classic artwork (the lion, the hunter…)",
        checked = settings.showConstellationArt) {
        viewModel.setBool(BoolSetting.ConstellationArt, it)
    }
    SettingSwitch("Meteor shower radiants", "Showers active tonight, with peak dates",
        checked = settings.showMeteorShowers) {
        viewModel.setBool(BoolSetting.MeteorShowers, it)
    }
    SettingSwitch("Deep-sky objects (Messier)", "110 galaxies, nebulae & clusters",
        checked = settings.showMessier) {
        viewModel.setBool(BoolSetting.Messier, it)
    }
    SettingSwitch("Milky Way", "Soft galactic glow along the band", checked = settings.showMilkyWay) {
        viewModel.setBool(BoolSetting.MilkyWay, it)
    }
    SettingSwitch("Planets", "Mercury through Neptune", checked = settings.showPlanets) {
        viewModel.setBool(BoolSetting.Planets, it)
    }
    SettingSwitch("Asteroids", "Bright minor planets (Vesta, Ceres, Pallas…)", checked = settings.showAsteroids) {
        viewModel.setBool(BoolSetting.Asteroids, it)
    }
    SettingSwitch("Asteroid paths", "Trace each one's track over ±60 days",
        checked = settings.showAsteroidPaths, enabled = settings.showAsteroids, indent = true) {
        viewModel.setBool(BoolSetting.AsteroidPaths, it)
    }
    SettingSwitch("Comets", "Famous & current comets (Halley, Hale-Bopp, Pons-Brooks…)", checked = settings.showComets) {
        viewModel.setBool(BoolSetting.Comets, it)
    }
    SettingSwitch("Comet paths", "Trace each one's track over ±60 days",
        checked = settings.showCometPaths, enabled = settings.showComets, indent = true) {
        viewModel.setBool(BoolSetting.CometPaths, it)
    }
    SettingSwitch("Sun", checked = settings.showSun) { viewModel.setBool(BoolSetting.Sun, it) }
    SettingSwitch("Moon", checked = settings.showMoon) { viewModel.setBool(BoolSetting.Moon, it) }
    SettingSwitch(
        "Extended star catalog",
        "≈41,000 stars to magnitude 8 (built in). Turn off for a lighter naked-eye sky.",
        checked = settings.useExtendedCatalog,
    ) { viewModel.setBool(BoolSetting.ExtendedCatalog, it) }
}

@Composable
private fun GuidesPage(viewModel: SkyViewModel, settings: Settings) {
    SettingSwitch("Horizon line", checked = settings.showHorizon) { viewModel.setBool(BoolSetting.Horizon, it) }
    SettingSwitch("Compass directions", "N/E/S/W markers", checked = settings.showCardinals) {
        viewModel.setBool(BoolSetting.Cardinals, it)
    }
    SettingSwitch("Ecliptic", "The path of the Sun, Moon and planets", checked = settings.showEcliptic) {
        viewModel.setBool(BoolSetting.Ecliptic, it)
    }
    SettingSwitch("Celestial equator", checked = settings.showEquator) { viewModel.setBool(BoolSetting.Equator, it) }
    SettingSwitch("RA/Dec grid", "Coordinate grid", checked = settings.showGrid) { viewModel.setBool(BoolSetting.Grid, it) }
    SettingSwitch("Show objects below the horizon", "Draw things beneath the ground too",
        checked = settings.showBelowHorizon) {
        viewModel.setBool(BoolSetting.BelowHorizon, it)
    }
    SettingSwitch("Atmospheric refraction", "Lift objects near the horizon, as the air does",
        checked = settings.applyRefraction) {
        viewModel.setBool(BoolSetting.Refraction, it)
    }
    FovCirclesRow(settings.fovCirclesMode) { viewModel.setFovCircles(it) }
}

@Composable
private fun TrafficPage(viewModel: SkyViewModel, settings: Settings) {
    SatelliteRow(
        "Space Station (ISS)", "Show the ISS when it passes over", "Tiny download (one satellite)",
        ready = viewModel.satelliteManager.isIssDownloaded,
        busy = viewModel.issBusy.value,
        progress = null,
        checked = settings.showIss,
        onToggle = { viewModel.setBool(BoolSetting.Iss, it) },
        onDownload = { viewModel.downloadIss(thenShow = true) },
    )
    SatelliteRow(
        "Starlink satellites", "Show the Starlink fleet", "About 1 MB, thousands of satellites",
        ready = viewModel.satelliteManager.isStarlinkDownloaded,
        busy = viewModel.starlinkBusy.value,
        progress = viewModel.starlinkProgress.value,
        checked = settings.showStarlink,
        onToggle = { viewModel.setBool(BoolSetting.Starlink, it) },
        onDownload = { viewModel.downloadStarlink(thenShow = true) },
    )
    SettingSwitch(
        "Show aircraft",
        "Live nearby planes & helicopters (ADS-B) — needs internet. Tap one for details.",
        checked = settings.showAircraft,
    ) { viewModel.setBool(BoolSetting.Aircraft, it) }
    SettingSlider(
        label = "Aircraft range",
        value = settings.aircraftRangeNm,
        valueText = "${settings.aircraftRangeNm.toInt()} nm",
        range = 5f..80f,
        steps = 14,
        onChange = { viewModel.setFloat(FloatSetting.AircraftRange, it) },
    )
    SettingSwitch("Fading trails", "Dotted trail showing where each one has been",
        checked = settings.showAircraftTrails, enabled = settings.showAircraft, indent = true) {
        viewModel.setBool(BoolSetting.AircraftTrails, it)
    }
    SettingSwitch("Aircraft labels", "Callsign and altitude",
        checked = settings.showAircraftLabels, enabled = settings.showAircraft, indent = true) {
        viewModel.setBool(BoolSetting.AircraftLabels, it)
    }
}

/**
 * A satellite layer switch that downloads its data in place: until the orbital
 * elements are on the phone the row offers a Download button instead of a
 * disabled switch, and the layer turns on when the download finishes.
 */
@Composable
private fun SatelliteRow(
    label: String,
    onText: String,
    downloadText: String,
    ready: Boolean,
    busy: Boolean,
    progress: Float?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onDownload: () -> Unit,
) {
    if (ready && !busy) {
        SettingSwitch(label, onText, checked = checked, onChange = onToggle)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            Text(
                if (busy) "Downloading…" else downloadText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (busy && progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp),
                )
            }
        }
        if (!busy) OutlinedButton(onClick = onDownload) { Text("Download") }
    }
}

@Composable
private fun LandmarksPage(viewModel: SkyViewModel, settings: Settings) {
    SettingSwitch(
        "Show landmarks",
        "Nearby cities, airports & towers on the horizon (OpenStreetMap) — needs internet. " +
            "Aim the centre at one to read it.",
        checked = settings.showLandmarks,
    ) { viewModel.setBool(BoolSetting.Landmarks, it) }
    SettingSlider(
        label = "Landmark range",
        value = settings.landmarkRangeKm,
        valueText = "${settings.landmarkRangeKm.toInt()} km (${(settings.landmarkRangeKm * 0.621371f).toInt()} mi)",
        range = 5f..80f,
        steps = 14,
        onChange = { viewModel.setFloat(FloatSetting.LandmarkRange, it) },
    )
    SettingSwitch("Cities & towns", checked = settings.landmarkCities, enabled = settings.showLandmarks, indent = true) {
        viewModel.setBool(BoolSetting.LandmarkCities, it)
    }
    SettingSwitch("Airports", checked = settings.landmarkAirports, enabled = settings.showLandmarks, indent = true) {
        viewModel.setBool(BoolSetting.LandmarkAirports, it)
    }
    SettingSwitch("Towers & masts", checked = settings.landmarkTowers, enabled = settings.showLandmarks, indent = true) {
        viewModel.setBool(BoolSetting.LandmarkTowers, it)
    }
}

@Composable
private fun DisplayPage(viewModel: SkyViewModel, settings: Settings) {
    SettingSwitch("Night mode (red)", "Preserves dark adaptation", checked = settings.nightMode) {
        viewModel.setBool(BoolSetting.NightMode, it)
    }
    SettingSwitch("Centre identify", "Show what's under the centre reticle automatically",
        checked = settings.centerIdentify) {
        viewModel.setBool(BoolSetting.CenterIdentify, it)
    }
    OrientationRow(settings.orientationMode) { viewModel.setOrientation(it) }
    SettingSlider(
        label = "AR camera dimming",
        value = settings.arDim,
        valueText = if (settings.arDim <= 0f) "Off" else "${(settings.arDim * 100).roundToInt()}%",
        range = 0f..1f,
        description = "Darken the camera in AR so the overlay stands out",
        onChange = { viewModel.setFloat(FloatSetting.ArDim, it) },
    )
}

/** Satellite data status + refresh/delete on the Offline data page. */
@Composable
private fun SatelliteDownloadRows(viewModel: SkyViewModel, settings: Settings) {
    val mgr = viewModel.satelliteManager
    val message by viewModel.satMessage
    DownloadRow(
        "Space Station (ISS)", "One satellite · tiny",
        downloaded = mgr.isIssDownloaded, ageMillis = mgr.issAgeMillis,
        busy = viewModel.issBusy.value, progress = null,
        onGet = { viewModel.downloadIss(thenShow = !mgr.isIssDownloaded && !settings.showIss) },
        onDelete = { viewModel.deleteIss() },
    )
    DownloadRow(
        "Starlink",
        if (mgr.isStarlinkDownloaded) "%.1f MB · thousands of satellites".format(mgr.starlinkSizeBytes / 1_048_576.0)
        else "About 1 MB · thousands of satellites",
        downloaded = mgr.isStarlinkDownloaded, ageMillis = mgr.starlinkAgeMillis,
        busy = viewModel.starlinkBusy.value, progress = viewModel.starlinkProgress.value,
        onGet = { viewModel.downloadStarlink(thenShow = !mgr.isStarlinkDownloaded && !settings.showStarlink) },
        onDelete = { viewModel.deleteStarlink() },
    )
    Text(
        "Orbital elements come from Celestrak and drift after a few days, so refresh them now and then.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    message?.let {
        Text(it, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun DownloadRow(
    label: String,
    detail: String,
    downloaded: Boolean,
    ageMillis: Long,
    busy: Boolean,
    progress: Float?,
    onGet: () -> Unit,
    onDelete: () -> Unit,
) {
    val days = if (ageMillis >= 0) (ageMillis / 86_400_000L).toInt() else -1
    val stale = downloaded && days >= 7
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 16.sp)
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            StatusPill(
                when {
                    busy -> "Downloading"
                    !downloaded -> "Not downloaded"
                    days <= 0 -> "Updated today"
                    days == 1 -> "1 day old"
                    else -> "$days days old"
                },
                when {
                    !downloaded -> Hud.TextDim
                    stale -> Color(0xFFFFC061)
                    else -> Color(0xFF7FE3A0)
                },
            )
        }
        if (busy && progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (!busy) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stale || !downloaded) {
                    Button(onClick = onGet) { Text(if (downloaded) "Refresh" else "Download") }
                } else {
                    OutlinedButton(onClick = onGet) { Text("Refresh") }
                }
                if (downloaded) TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun ManualLocationSection(viewModel: SkyViewModel, settings: Settings) {
    val fix by viewModel.effectiveLocation.collectAsState()
    SettingSwitch(
        "Set location manually",
        "Use fixed coordinates instead of GPS",
        checked = settings.manualLocation,
    ) { enabled ->
        if (enabled) {
            val lat = if (settings.manualLat != 0.0) settings.manualLat else fix?.latitude ?: 0.0
            val lon = if (settings.manualLon != 0.0) settings.manualLon else fix?.longitude ?: 0.0
            viewModel.setManualLocation(true, lat, lon)
        } else {
            viewModel.setManualLocation(false, settings.manualLat, settings.manualLon)
        }
    }

    if (settings.manualLocation) {
        var latText by remember(settings.manualLat) { mutableStateOf(settings.manualLat.toString()) }
        var lonText by remember(settings.manualLon) { mutableStateOf(settings.manualLon.toString()) }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it },
                label = { Text("Latitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = lonText,
                onValueChange = { lonText = it },
                label = { Text("Longitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = {
                val lat = latText.toDoubleOrNull()
                val lon = lonText.toDoubleOrNull()
                if (lat != null && lon != null) viewModel.setManualLocation(true, lat, lon)
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("Apply location") }
    }
}

@Composable
private fun OfflineDataSection(viewModel: SkyViewModel) {
    val sync by viewModel.offlineSync
    val bytes by viewModel.offlineBytes
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshOfflineSize() }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Object info", fontSize = 16.sp)
        Text(
            "Photos and descriptions for objects, cached so they work offline.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        val status = when (val s = sync) {
            is OfflineSync.Running -> "Syncing ${s.done} / ${s.total}…"
            is OfflineSync.Done -> "Synced ${s.count} objects · ${formatBytes(bytes)} used"
            else -> "${formatBytes(bytes)} used"
        }
        Text(status, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = { viewModel.syncOfflineData() },
                enabled = sync !is OfflineSync.Running,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Text(if (sync is OfflineSync.Running) "Syncing…" else "Sync offline data")
            }
            OutlinedButton(
                onClick = { viewModel.clearOfflineData() },
                enabled = sync !is OfflineSync.Running && bytes > 0L,
            ) {
                Text("Clear")
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}

@Composable
private fun OrientationRow(mode: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Screen orientation", fontSize = 16.sp)
        SegmentedChoice(
            options = listOf("Auto", "Portrait", "Landscape"),
            selected = mode,
            modifier = Modifier.padding(top = 8.dp),
            onSelect = onSelect,
        )
    }
}

@Composable
private fun FovCirclesRow(mode: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Field-of-view rings", fontSize = 16.sp)
        Text(
            "Angular guides at screen centre",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        SegmentedChoice(
            options = listOf("Off", "Telrad", "Binoc", "1° eye"),
            selected = mode,
            modifier = Modifier.padding(top = 8.dp),
            onSelect = onSelect,
        )
    }
}
