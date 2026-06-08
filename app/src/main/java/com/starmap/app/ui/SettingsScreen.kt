package com.starmap.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
            SettingSwitch("Planets", "Mercury through Neptune", checked = settings.showPlanets) {
                viewModel.setBool(BoolSetting.Planets, it)
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
            SettingSwitch("Show objects below the horizon", "Draw things beneath the ground too",
                checked = settings.showBelowHorizon) {
                viewModel.setBool(BoolSetting.BelowHorizon, it)
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

            SectionHeader("Display")
            SettingSwitch("Night mode (red)", "Preserves dark adaptation",
                checked = settings.nightMode) {
                viewModel.setBool(BoolSetting.NightMode, it)
            }

            SectionHeader("Catalog")
            val downloaded = viewModel.catalogManager.isExtendedDownloaded
            SettingSwitch(
                "Use extended catalog",
                if (downloaded) "Loads the downloaded extended star set" else "Download it first in Offline downloads",
                checked = settings.useExtendedCatalog,
                enabled = downloaded,
            ) {
                viewModel.setBool(BoolSetting.ExtendedCatalog, it)
            }

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
