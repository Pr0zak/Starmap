package com.starmap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starmap.app.sky.SkyCanvas
import com.starmap.app.sky.SkyViewModel
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.roundToInt

private enum class Screen { Sky, Settings, Downloads, About }

@Composable
fun MainScreen(viewModel: SkyViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.Sky) }
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    // Start/stop the sensors with the lifecycle.
    DisposableEffectLifecycle(
        onResume = { viewModel.startSensors(); viewModel.startLocation() },
        onPause = { viewModel.stopSensors() },
    )

    // Request location permission on first launch unless using a manual location.
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasLocationPermission = result.values.any { it }
        if (hasLocationPermission) viewModel.startLocation()
    }
    LaunchedEffect(settings.manualLocation) {
        if (!settings.manualLocation && !hasLocationPermission) {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    when (screen) {
        Screen.Sky -> SkyScreen(
            viewModel = viewModel,
            settings = settings,
            hasLocationPermission = hasLocationPermission,
            onOpen = { screen = it },
            onRequestPermission = {
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
        )
        Screen.Settings -> SettingsScreen(viewModel, settings) { screen = Screen.Sky }
        Screen.Downloads -> DownloadsScreen(viewModel) { screen = Screen.Sky }
        Screen.About -> AboutScreen(viewModel) { screen = Screen.Sky }
    }
}

@Composable
private fun SkyScreen(
    viewModel: SkyViewModel,
    settings: com.starmap.app.settings.Settings,
    hasLocationPermission: Boolean,
    onOpen: (Screen) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val location by viewModel.effectiveLocation.collectAsState()
    val model by viewModel.model

    // Live heading read for the HUD.
    var heading by remember { mutableFloatStateOf(0f) }
    var accuracy by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            heading = viewModel.orientation.basis.azimuthDeg
            accuracy = viewModel.orientation.accuracy
            awaitFrame()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SkyCanvas(viewModel = viewModel, settings = settings, modifier = Modifier.fillMaxSize())

        // Top HUD bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${heading.roundToInt()}° ${compassLabel(heading)}",
                    color = Color(0xFFD8E0F0),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!viewModel.hasOrientationSensor) {
                    Text("No orientation sensor on this device", color = Color(0xFFFFB4A0), fontSize = 12.sp)
                } else if (accuracy in 0..1) {
                    Text("Wave the phone in a figure-8 to calibrate", color = Color(0xFFFFD089), fontSize = 12.sp)
                }
            }
            OverflowMenu(onOpen)
        }

        // Bottom status / setup prompts.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (location == null) {
                StatusCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocationOff, contentDescription = null, tint = Color(0xFFFFD089))
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("Waiting for your location", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (hasLocationPermission) {
                                    "Acquiring GPS fix… or set a location in Settings."
                                } else {
                                    "Grant location to align the sky, or set it manually in Settings."
                                },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            )
                            if (!hasLocationPermission) {
                                TextButton(onClick = onRequestPermission) { Text("Grant location") }
                            }
                        }
                    }
                }
            } else if (model != null) {
                val fix = location!!
                Text(
                    text = "%.3f, %.3f%s".format(
                        fix.latitude, fix.longitude, if (fix.fromGps) "" else " (manual)",
                    ),
                    color = Color(0x99FFFFFF),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(onOpen: (Screen) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = Color(0xFFD8E0F0))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                onClick = { expanded = false; onOpen(Screen.Settings) },
            )
            DropdownMenuItem(
                text = { Text("Offline downloads") },
                leadingIcon = { Icon(Icons.Filled.Download, null) },
                onClick = { expanded = false; onOpen(Screen.Downloads) },
            )
            DropdownMenuItem(
                text = { Text("About & updates") },
                leadingIcon = { Icon(Icons.Filled.Info, null) },
                onClick = { expanded = false; onOpen(Screen.About) },
            )
        }
    }
}

@Composable
private fun StatusCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun DisposableEffectLifecycle(onResume: () -> Unit, onPause: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_PAUSE -> onPause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

private fun compassLabel(deg: Float): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = (((deg % 360f) + 360f) % 360f / 45f).roundToInt() % 8
    return dirs[idx]
}
