package com.starmap.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.starmap.app.settings.Settings
import com.starmap.app.settings.SettingsRepository.BoolSetting
import com.starmap.app.settings.SettingsRepository.FloatSetting
import com.starmap.app.sky.OfflineSync
import com.starmap.app.sky.SkyViewModel

@Composable
fun SettingsScreen(viewModel: SkyViewModel, settings: Settings, onBack: () -> Unit) {
    DetailScaffold(title = "Settings", onBack = onBack) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            SectionHeader("Sky")
            SettingSlider(
                label = "Star magnitude limit",
                value = settings.magnitudeLimit,
                valueText = "≤ %.1f".format(settings.magnitudeLimit),
                range = 2f..8f,
                steps = 11,
                onChange = { viewModel.setFloat(FloatSetting.MagnitudeLimit, it) },
            )
            SettingSlider(
                label = "Label brightness limit",
                value = settings.labelMagnitudeLimit,
                valueText = "≤ %.1f".format(settings.labelMagnitudeLimit),
                range = 0f..5f,
                steps = 9,
                onChange = { viewModel.setFloat(FloatSetting.LabelMagnitudeLimit, it) },
            )
            SettingSlider(
                label = "Field of view",
                value = settings.fovDeg,
                valueText = "${settings.fovDeg.toInt()}°",
                range = 12f..90f,
                onChange = { viewModel.setFloat(FloatSetting.Fov, it) },
            )

            SectionHeader("Show in the sky")
            SettingSwitch("Star labels", checked = settings.showStarLabels) {
                viewModel.setBool(BoolSetting.StarLabels, it)
            }
            SettingSwitch("Constellation lines", checked = settings.showConstellations) {
                viewModel.setBool(BoolSetting.Constellations, it)
            }
            SettingSwitch("Constellation names", checked = settings.showConstellationNames,
                enabled = settings.showConstellations) {
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
            SettingSwitch("Milky Way", "Soft galactic glow along the band",
                checked = settings.showMilkyWay) {
                viewModel.setBool(BoolSetting.MilkyWay, it)
            }
            SettingSwitch("Planets", "Mercury through Neptune", checked = settings.showPlanets) {
                viewModel.setBool(BoolSetting.Planets, it)
            }
            SettingSwitch("Asteroids", "Bright minor planets (Vesta, Ceres, Pallas…)",
                checked = settings.showAsteroids) {
                viewModel.setBool(BoolSetting.Asteroids, it)
            }
            SettingSwitch("Asteroid paths", "Trace each one's track over ±60 days",
                checked = settings.showAsteroidPaths, enabled = settings.showAsteroids) {
                viewModel.setBool(BoolSetting.AsteroidPaths, it)
            }
            SettingSwitch("Comets", "Famous & current comets (Halley, Hale-Bopp, Pons-Brooks…)",
                checked = settings.showComets) {
                viewModel.setBool(BoolSetting.Comets, it)
            }
            SettingSwitch("Comet paths", "Trace each one's track over ±60 days",
                checked = settings.showCometPaths, enabled = settings.showComets) {
                viewModel.setBool(BoolSetting.CometPaths, it)
            }
            SettingSwitch("Sun", checked = settings.showSun) {
                viewModel.setBool(BoolSetting.Sun, it)
            }
            SettingSwitch("Moon", checked = settings.showMoon) {
                viewModel.setBool(BoolSetting.Moon, it)
            }

            SectionHeader("Horizon & compass")
            SettingSwitch("Horizon line", checked = settings.showHorizon) {
                viewModel.setBool(BoolSetting.Horizon, it)
            }
            SettingSwitch("Compass directions", "N/E/S/W markers", checked = settings.showCardinals) {
                viewModel.setBool(BoolSetting.Cardinals, it)
            }
            SettingSwitch("Ecliptic", "The path of the Sun, Moon and planets",
                checked = settings.showEcliptic) {
                viewModel.setBool(BoolSetting.Ecliptic, it)
            }
            SettingSwitch("Celestial equator", checked = settings.showEquator) {
                viewModel.setBool(BoolSetting.Equator, it)
            }
            SettingSwitch("RA/Dec grid", "Coordinate grid", checked = settings.showGrid) {
                viewModel.setBool(BoolSetting.Grid, it)
            }
            SettingSwitch("Show objects below the horizon", "Draw things beneath the ground too",
                checked = settings.showBelowHorizon) {
                viewModel.setBool(BoolSetting.BelowHorizon, it)
            }
            SettingSwitch("Atmospheric refraction", "Lift objects near the horizon, as the air does",
                checked = settings.applyRefraction) {
                viewModel.setBool(BoolSetting.Refraction, it)
            }

            SectionHeader("Satellites")
            val issReady = viewModel.satelliteManager.isIssDownloaded
            val starlinkReady = viewModel.satelliteManager.isStarlinkDownloaded
            SettingSwitch(
                "Space Station (ISS)",
                if (issReady) "Show the ISS when it passes over" else "Download ISS data in Offline downloads",
                checked = settings.showIss,
                enabled = issReady,
            ) { viewModel.setBool(BoolSetting.Iss, it) }
            SettingSwitch(
                "Starlink satellites",
                if (starlinkReady) "Show the Starlink fleet" else "Download Starlink data in Offline downloads",
                checked = settings.showStarlink,
                enabled = starlinkReady,
            ) { viewModel.setBool(BoolSetting.Starlink, it) }
            SectionHeader("Aircraft")
            SettingSwitch(
                "Show aircraft",
                "Live nearby planes & helicopters (ADS-B) — needs internet. Tap one for details.",
                checked = settings.showAircraft,
            ) { viewModel.setBool(BoolSetting.Aircraft, it) }
            SettingSlider(
                label = "Range",
                value = settings.aircraftRangeNm,
                valueText = "${settings.aircraftRangeNm.toInt()} nm",
                range = 5f..80f,
                steps = 14,
                onChange = { viewModel.setFloat(FloatSetting.AircraftRange, it) },
            )
            SettingSwitch(
                "Fading trails", "Dotted trail showing where each one has been",
                checked = settings.showAircraftTrails, enabled = settings.showAircraft,
            ) { viewModel.setBool(BoolSetting.AircraftTrails, it) }
            SettingSwitch(
                "Labels", "Callsign and altitude",
                checked = settings.showAircraftLabels, enabled = settings.showAircraft,
            ) { viewModel.setBool(BoolSetting.AircraftLabels, it) }
            SettingSwitch(
                "Radar (top-down) mode",
                "A bird's-eye scope of nearby aircraft & landmarks by range. Also on the top bar (✈).",
                checked = settings.radarMode,
            ) { viewModel.setBool(BoolSetting.RadarMode, it) }

            SectionHeader("Landmarks")
            SettingSwitch(
                "Show landmarks",
                "Nearby cities, airports & towers on the horizon (OpenStreetMap) — needs internet. " +
                    "Aim the centre at one to read it.",
                checked = settings.showLandmarks,
            ) { viewModel.setBool(BoolSetting.Landmarks, it) }
            SettingSlider(
                label = "Range",
                value = settings.landmarkRangeKm,
                valueText = "${settings.landmarkRangeKm.toInt()} km " +
                    "(${(settings.landmarkRangeKm * 0.621371f).toInt()} mi)",
                range = 5f..80f,
                steps = 14,
                onChange = { viewModel.setFloat(FloatSetting.LandmarkRange, it) },
            )
            SettingSwitch(
                "Cities & towns", checked = settings.landmarkCities, enabled = settings.showLandmarks,
            ) { viewModel.setBool(BoolSetting.LandmarkCities, it) }
            SettingSwitch(
                "Airports", checked = settings.landmarkAirports, enabled = settings.showLandmarks,
            ) { viewModel.setBool(BoolSetting.LandmarkAirports, it) }
            SettingSwitch(
                "Towers & masts", checked = settings.landmarkTowers, enabled = settings.showLandmarks,
            ) { viewModel.setBool(BoolSetting.LandmarkTowers, it) }

            SectionHeader("Display")
            SettingSwitch("Night mode (red)", "Preserves dark adaptation",
                checked = settings.nightMode) {
                viewModel.setBool(BoolSetting.NightMode, it)
            }
            SettingSwitch(
                "Centre identify", "Show what's under the centre reticle automatically",
                checked = settings.centerIdentify,
            ) {
                viewModel.setBool(BoolSetting.CenterIdentify, it)
            }
            OrientationRow(settings.orientationMode) { viewModel.setOrientation(it) }
            FovCirclesRow(settings.fovCirclesMode) { viewModel.setFovCircles(it) }

            SectionHeader("Catalog")
            SettingSwitch(
                "Extended star catalog",
                "≈41,000 stars to magnitude 8 (built in). Turn off for a lighter naked-eye sky.",
                checked = settings.useExtendedCatalog,
            ) {
                viewModel.setBool(BoolSetting.ExtendedCatalog, it)
            }

            SectionHeader("Offline data")
            OfflineDataSection(viewModel)

            SectionHeader("Updates")
            SettingSwitch("Check for updates on launch", checked = settings.autoCheckUpdates) {
                viewModel.setBool(BoolSetting.AutoCheckUpdates, it)
            }

            SectionHeader("Location")
            ManualLocationSection(viewModel, settings)
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
        Row(modifier = Modifier.padding(top = 8.dp)) {
            listOf("Auto", "Portrait", "Landscape").forEachIndexed { i, label ->
                if (mode == i) {
                    Button(onClick = { onSelect(i) }, modifier = Modifier.padding(end = 8.dp)) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(onClick = { onSelect(i) }, modifier = Modifier.padding(end = 8.dp)) {
                        Text(label)
                    }
                }
            }
        }
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
        Row(modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
            listOf("Off", "Telrad", "Binoculars", "1° eyepiece").forEachIndexed { i, label ->
                if (mode == i) {
                    Button(onClick = { onSelect(i) }, modifier = Modifier.padding(end = 8.dp)) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(onClick = { onSelect(i) }, modifier = Modifier.padding(end = 8.dp)) {
                        Text(label)
                    }
                }
            }
        }
    }
}
