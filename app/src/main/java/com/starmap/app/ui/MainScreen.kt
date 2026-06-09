package com.starmap.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness1
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.starmap.app.aircraft.AircraftManager
import com.starmap.app.info.WikiManager
import com.starmap.app.settings.SettingsRepository
import com.starmap.app.settings.SettingsRepository.BoolSetting
import com.starmap.app.sky.AircraftRender
import com.starmap.app.sky.IdentifiedObject
import com.starmap.app.sky.ObjectDetail
import com.starmap.app.sky.SkyCanvas
import com.starmap.app.sky.SkyModel
import com.starmap.app.sky.SkyViewModel
import com.starmap.app.sky.resolveTargetEnu
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt

private enum class Screen { Sky, Search, Settings, Downloads, About }

@Composable
fun MainScreen(viewModel: SkyViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.Sky) }
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    // Apply the chosen screen orientation to the activity.
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(settings.orientationMode) {
        activity?.requestedOrientation = when (settings.orientationMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

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

    // Camera permission for AR mode, requested only when AR is switched on.
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (granted) viewModel.setBool(BoolSetting.ArMode, true)
    }

    when (screen) {
        Screen.Sky -> SkyScreen(
            viewModel = viewModel,
            settings = settings,
            hasLocationPermission = hasLocationPermission,
            arActive = settings.arMode && hasCameraPermission,
            onToggleAr = {
                when {
                    settings.arMode -> viewModel.setBool(BoolSetting.ArMode, false)
                    hasCameraPermission -> viewModel.setBool(BoolSetting.ArMode, true)
                    else -> cameraPermLauncher.launch(Manifest.permission.CAMERA)
                }
            },
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
        Screen.Search -> SearchScreen(viewModel) { screen = Screen.Sky }
    }
}

@Composable
private fun SkyScreen(
    viewModel: SkyViewModel,
    settings: com.starmap.app.settings.Settings,
    hasLocationPermission: Boolean,
    arActive: Boolean,
    onToggleAr: () -> Unit,
    onOpen: (Screen) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val location by viewModel.effectiveLocation.collectAsState()
    val model by viewModel.model
    val manualMode by viewModel.manualMode
    val liveTime by viewModel.liveTime
    var showTimePanel by remember { mutableStateOf(false) }

    // Auto-match the render field of view to the camera while AR is on; restore after.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var preArFov by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(arActive, configuration.orientation) {
        if (arActive) {
            val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            CameraFov.screenVerticalFovDeg(context, portrait)?.let { camFov ->
                if (preArFov == null) preArFov = settings.fovDeg
                viewModel.setFloat(SettingsRepository.FloatSetting.Fov, camFov.coerceIn(30f, 90f))
            }
        } else {
            preArFov?.let {
                viewModel.setFloat(SettingsRepository.FloatSetting.Fov, it)
                preArFov = null
            }
        }
    }

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
        if (arActive) {
            CameraPreview(modifier = Modifier.fillMaxSize())
        }
        SkyCanvas(viewModel = viewModel, settings = settings, modifier = Modifier.fillMaxSize())

        // Top HUD bar + active-search banner.
        Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${heading.roundToInt()}° ${compassLabel(heading)}",
                        color = Color(0xFFD8E0F0),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (manualMode) {
                        Text("Manual — drag to look around", color = Color(0xFFFFD54F), fontSize = 12.sp)
                    } else if (!viewModel.hasOrientationSensor) {
                        Text("No sensor — switch to manual look", color = Color(0xFFFFB4A0), fontSize = 12.sp)
                    } else if (accuracy in 0..1) {
                        Text("Wave the phone in a figure-8 to calibrate", color = Color(0xFFFFD089), fontSize = 12.sp)
                    }
                }
                IconButton(onClick = { viewModel.toggleManualMode() }) {
                    Icon(
                        Icons.Filled.PanTool, contentDescription = "Manual look",
                        tint = if (manualMode) Color(0xFFFFD54F) else Color(0xFFD8E0F0),
                    )
                }
                IconButton(onClick = onToggleAr) {
                    Icon(
                        Icons.Filled.CameraAlt, contentDescription = "Camera AR",
                        tint = if (arActive) Color(0xFFFFD54F) else Color(0xFFD8E0F0),
                    )
                }
                IconButton(onClick = { showTimePanel = !showTimePanel }) {
                    Icon(
                        Icons.Filled.Schedule, contentDescription = "Time machine",
                        tint = if (!liveTime) Color(0xFFFFD54F) else Color(0xFFD8E0F0),
                    )
                }
                IconButton(onClick = { onOpen(Screen.Search) }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color(0xFFD8E0F0))
                }
                OverflowMenu(onOpen)
            }
            SearchBanner(viewModel, model)
        }

        // Bottom status / setup prompts.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showTimePanel) {
                TimeBar(viewModel, model)
                Spacer(Modifier.height(8.dp))
            }
            val selAc by viewModel.selectedAircraft
            val selRoute by viewModel.selectedRoute
            val selPhoto by viewModel.selectedPhoto
            val selPhotoStatus by viewModel.photoStatus
            val followingState by viewModel.followActive
            val followHex by viewModel.followAircraftHex
            selAc?.let { ac ->
                AircraftInfoCard(
                    ac, selRoute, selPhoto, selPhotoStatus,
                    tracking = followHex == ac.icaoHex,
                    onTrack = { viewModel.followAircraft(if (followHex == ac.icaoHex) null else ac.icaoHex) },
                    onClose = { viewModel.selectAircraft(null) },
                )
                Spacer(Modifier.height(8.dp))
            }
            val selObj by viewModel.selectedObject
            val centerObj by viewModel.centerObject
            if (selAc == null) {
                (selObj ?: centerObj)?.let { obj ->
                    val hex = obj.aircraftHex
                    val followingThis = if (hex != null) followHex == hex else followingState
                    val openDetails = { viewModel.openObjectDetail(obj) }
                    val onFollow: (() -> Unit)? = when {
                        hex != null -> {
                            { viewModel.followAircraft(if (followHex == hex) null else hex) }
                        }
                        obj.target != null -> {
                            {
                                if (followingState) {
                                    viewModel.setFollow(false)
                                } else {
                                    viewModel.selectSearchTarget(obj.target)
                                    viewModel.setFollow(true)
                                }
                            }
                        }
                        else -> null
                    }
                    ObjectInfoCard(
                        obj,
                        following = followingThis,
                        onDetails = if (hex != null) null else openDetails,
                        onFollow = onFollow,
                        onClose = selObj?.let { { viewModel.selectObject(null) } },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
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

        val detail by viewModel.objectDetail
        detail?.let { d ->
            ObjectDetailDialog(
                d,
                onOpenLink = { url ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                onClose = { viewModel.closeObjectDetail() },
            )
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
private fun ObjectInfoCard(
    obj: IdentifiedObject,
    following: Boolean = false,
    onDetails: (() -> Unit)? = null,
    onFollow: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val (icon, accent) = objectVisual(obj)
    Surface(
        color = Color(0xF21B2030),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    obj.name, color = Color(0xFFF1F4FA), fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(obj.kind, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    obj.detail, color = Color(0xB3FFFFFF), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (onDetails != null) {
                IconButton(onClick = onDetails) {
                    Icon(Icons.Filled.Info, contentDescription = "Details", tint = Color(0xFFD8E0F0))
                }
            }
            if (onFollow != null) {
                IconButton(onClick = onFollow) {
                    Icon(
                        Icons.Filled.MyLocation, contentDescription = "Follow",
                        tint = if (following) Color(0xFFFFD54F) else Color(0xFFD8E0F0),
                    )
                }
            }
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFFD8E0F0))
                }
            }
        }
    }
}

/** Maps an identified object to a representative icon and accent colour. */
private fun objectVisual(obj: IdentifiedObject): Pair<ImageVector, Color> {
    val k = obj.kind
    return when {
        obj.name == "Sun" -> Icons.Filled.WbSunny to Color(0xFFFFB74D)
        k == "Moon" -> Icons.Filled.DarkMode to Color(0xFFB0BEC5)
        k == "Planet" -> Icons.Filled.Public to Color(0xFF80DEEA)
        k == "Comet" -> Icons.Filled.AutoAwesome to Color(0xFFB3E5FC)
        k == "Asteroid" -> Icons.Filled.Brightness1 to Color(0xFFD7CCC8)
        k == "Satellite" -> Icons.Filled.SatelliteAlt to Color(0xFF80CBC4)
        k == "Aircraft" -> Icons.Filled.Flight to Color(0xFFFFC061)
        k == "Helicopter" -> Icons.Filled.Flight to Color(0xFF7FD8C6)
        k == "Constellation" -> Icons.Filled.Hub to Color(0xFF90CAF9)
        k == "Star" -> Icons.Filled.Star to Color(0xFFFFE082)
        k.contains("Cluster", ignoreCase = true) -> Icons.Filled.BubbleChart to Color(0xFFCE93D8)
        else -> Icons.Filled.BlurOn to Color(0xFFCE93D8) // galaxies, nebulae & other deep-sky
    }
}

@Composable
private fun ObjectDetailDialog(
    detail: ObjectDetail,
    onOpenLink: (String) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            color = Color(0xFF141A28),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        detail.title, color = Color(0xFFF1F4FA), fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFFD8E0F0))
                    }
                }
                Spacer(Modifier.height(6.dp))
                when (detail) {
                    is ObjectDetail.Loading ->
                        Text("Loading…", color = Color(0x99FFFFFF), fontSize = 14.sp)
                    is ObjectDetail.Empty ->
                        Text("No description found.", color = Color(0x99FFFFFF), fontSize = 14.sp)
                    is ObjectDetail.Failed ->
                        Text("Couldn't load details · ${detail.message}", color = Color(0x99FFFFFF), fontSize = 14.sp)
                    is ObjectDetail.Loaded -> {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 460.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            detail.info.imageUrl?.let { url ->
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(url.replaceFirst("http://", "https://"))
                                        .setHeader("User-Agent", WikiManager.USER_AGENT)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(190.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            Text(
                                detail.info.extract, color = Color(0xDDFFFFFF),
                                fontSize = 14.sp, lineHeight = 20.sp,
                            )
                            Text(
                                "Text from Wikipedia (CC BY-SA)",
                                color = Color(0x66FFFFFF), fontSize = 10.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                // Always offer a Wikipedia link (the article, or a search for it).
                if (detail !is ObjectDetail.Loading) {
                    val pageLink = (detail as? ObjectDetail.Loaded)?.info?.pageUrl
                    val link = pageLink
                        ?: ("https://en.wikipedia.org/wiki/Special:Search?search=" + Uri.encode(detail.title))
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onOpenLink(link) }) {
                        Text(
                            if (pageLink != null) "Read more on Wikipedia ↗" else "Search Wikipedia ↗",
                            color = Color(0xFF8AB4F8),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeBar(viewModel: SkyViewModel, model: SkyModel?) {
    val live by viewModel.liveTime
    val rate by viewModel.timeFlowRate
    val millis = model?.timeMillis ?: System.currentTimeMillis()
    val fmt = remember {
        java.text.SimpleDateFormat("EEE d MMM yyyy · HH:mm", java.util.Locale.getDefault())
    }
    Surface(
        color = Color(0xE61B2030),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmt.format(java.util.Date(millis)),
                    color = Color(0xFFE8ECF6), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (live) "● LIVE" else "TIME TRAVEL",
                    color = if (live) Color(0xFF7FE3A0) else Color(0xFFFFD54F),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TimeChip("-1d") { viewModel.jumpTime(-86_400_000L) }
                TimeChip("-1h") { viewModel.jumpTime(-3_600_000L) }
                TimeChip("-5m") { viewModel.jumpTime(-300_000L) }
                TimeChip("Now", highlight = live) { viewModel.goLiveTime() }
                TimeChip("+5m") { viewModel.jumpTime(300_000L) }
                TimeChip("+1h") { viewModel.jumpTime(3_600_000L) }
                TimeChip("+1d") { viewModel.jumpTime(86_400_000L) }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Speed", color = Color(0x99FFFFFF), fontSize = 12.sp)
                TimeChip("Pause", highlight = !live && rate == 0L) { viewModel.setTimeFlowRate(0L) }
                TimeChip("1m/s", highlight = rate == 60_000L) { viewModel.setTimeFlowRate(60_000L) }
                TimeChip("1h/s", highlight = rate == 3_600_000L) { viewModel.setTimeFlowRate(3_600_000L) }
                TimeChip("1d/s", highlight = rate == 86_400_000L) { viewModel.setTimeFlowRate(86_400_000L) }
            }
        }
    }
}

@Composable
private fun TimeChip(label: String, highlight: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = if (highlight) Color(0xFF2E5C8A) else Color(0x33FFFFFF),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label, color = Color(0xFFE8ECF6), fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
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

@Composable
private fun SearchBanner(viewModel: SkyViewModel, model: SkyModel?) {
    val target by viewModel.searchTarget
    val t = target ?: return
    Surface(
        color = Color(0xE61B2030),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search, contentDescription = null,
                tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(t.label, color = Color(0xFFFFE9A8), fontWeight = FontWeight.SemiBold)
                val enu = model?.let { resolveTargetEnu(it, t) }
                Text(
                    if (enu == null) "Locating…" else describeDirection(enu),
                    color = Color(0xCCFFFFFF), fontSize = 12.sp,
                )
            }
            val following by viewModel.followActive
            IconButton(onClick = { viewModel.setFollow(!following) }) {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = "Follow",
                    tint = if (following) Color(0xFFFFD54F) else Color(0xFFD8E0F0),
                )
            }
            IconButton(onClick = { viewModel.selectSearchTarget(null) }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Color(0xFFD8E0F0))
            }
        }
    }
}

private fun describeDirection(enu: FloatArray): String {
    val alt = Math.toDegrees(asin(enu[2].coerceIn(-1f, 1f).toDouble())).roundToInt()
    val az = (((Math.toDegrees(atan2(enu[0].toDouble(), enu[1].toDouble())) + 360) % 360)).roundToInt()
    val updown = if (alt >= 0) "$alt° up" else "${-alt}° below horizon"
    return "$az° ${compassLabel(az.toFloat())} · $updown"
}

@Composable
private fun PhotoNote(text: String) {
    Text(
        text, color = Color(0x80FFFFFF), fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, end = 8.dp),
    )
}

@Composable
private fun AircraftInfoCard(
    ac: AircraftRender,
    route: AircraftManager.Route?,
    photo: AircraftManager.Photo?,
    photoStatus: String?,
    tracking: Boolean,
    onTrack: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = if (ac.isEmergency) Color(0xF2401A1A) else Color(0xF21B2030),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Flight, contentDescription = null,
                    tint = if (ac.isHelicopter) Color(0xFF7FD8C6) else Color(0xFFFFC061),
                    modifier = Modifier.padding(end = 10.dp).size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        ac.callsign.ifBlank { "Aircraft" },
                        color = Color(0xFFFFE9A8), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (ac.registration.isNotBlank()) {
                        Text(ac.registration, color = Color(0x99FFFFFF), fontSize = 12.sp)
                    }
                }
                IconButton(onClick = onTrack) {
                    Icon(
                        Icons.Filled.MyLocation, contentDescription = "Track",
                        tint = if (tracking) Color(0xFFFFD54F) else Color(0xFFD8E0F0),
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFFD8E0F0))
                }
            }

            if (ac.isEmergency) {
                val em = ac.emergencyText.takeIf {
                    it.isNotBlank() && !it.equals("none", ignoreCase = true)
                }?.replaceFirstChar { it.uppercase() }
                Text(
                    "⚠ EMERGENCY" + (if (em != null) " · $em" else "") +
                        (if (ac.squawk in listOf("7500", "7600", "7700")) " · squawk ${ac.squawk}" else ""),
                    color = Color(0xFFFF8A8A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }

            if (photo != null) {
                var imgFailed by remember(photo.thumbnailUrl) { mutableStateOf(false) }
                if (imgFailed) {
                    PhotoNote("Photo failed to load")
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(photo.thumbnailUrl)
                            .setHeader("Referer", "https://www.planespotters.net/")
                            .crossfade(true)
                            .build(),
                        contentDescription = "Photo of ${ac.registration}",
                        contentScale = ContentScale.Crop,
                        onState = { st -> if (st is AsyncImagePainter.State.Error) imgFailed = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .padding(top = 8.dp, end = 8.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    if (photo.photographer.isNotBlank()) {
                        Text(
                            "📷 ${photo.photographer} / planespotters.net",
                            color = Color(0x66FFFFFF), fontSize = 10.sp,
                        )
                    }
                }
            } else if (photoStatus != null) {
                PhotoNote(photoStatus)
            }

            val kind = when {
                ac.isHelicopter && ac.typeCode.isNotBlank() -> "Helicopter · ${ac.typeCode}"
                ac.isHelicopter -> "Helicopter"
                ac.typeCode.isNotBlank() -> ac.typeCode
                else -> "Aircraft"
            }
            val kindLine = if (route?.airline?.isNotBlank() == true) "$kind · ${route.airline}" else kind
            Text(kindLine, color = Color(0xCCFFFFFF), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))

            val ft = (ac.altitudeMeters / 0.3048).toInt()
            val vr = ac.verticalRateFpm
            val vrStr = if (abs(vr) > 100) " ${if (vr > 0) "↑" else "↓"}${abs(vr).toInt()}fpm" else ""
            Text(
                "Alt ${"%,d".format(ft)} ft$vrStr · ${ac.groundSpeedKts.toInt()} kt · heading ${ac.trackDeg.toInt()}°",
                color = Color(0xCCFFFFFF), fontSize = 13.sp,
            )
            Text(
                "%.0f km (%.0f mi) away".format(ac.rangeKm, ac.rangeKm * 0.621371) +
                    (if (ac.squawk.isNotBlank() && !ac.isEmergency) " · squawk ${ac.squawk}" else ""),
                color = Color(0x99FFFFFF), fontSize = 12.sp,
            )
            route?.let {
                if (it.origin != "?" || it.destination != "?") {
                    Text(
                        "${it.origin}  →  ${it.destination}",
                        color = Color(0xFF9FE0C0), fontSize = 15.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun compassLabel(deg: Float): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = (((deg % 360f) + 360f) % 360f / 45f).roundToInt() % 8
    return dirs[idx]
}
