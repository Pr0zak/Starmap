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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness1
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
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
import com.starmap.app.events.android.EventDeepLink
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
import com.starmap.app.sky.RiseSet
import com.starmap.app.sky.AlertsController
import com.starmap.app.events.SkyEvent
import com.starmap.app.events.android.DeepLink
import com.starmap.app.update.UpdateChecker
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt

private enum class Screen { Sky, Search, Settings, Alerts, Downloads, About }

/** The three top-level viewing modes, surfaced as a segmented switcher. */
enum class SkyMode(val label: String) { Sky("Sky"), Radar("Radar"), Ar("AR") }

/** Segmented Sky · Radar · AR control. Selected segment is a glowing gold pill; the rest are translucent glass. */
@Composable
internal fun ModeSwitcher(
    current: SkyMode,
    onSelect: (SkyMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outer = RoundedCornerShape(13.dp)
    val pill = RoundedCornerShape(10.dp)
    Box(modifier = modifier.glass(outer).padding(4.dp)) {
        Row {
            for (mode in SkyMode.values()) {
                val selected = mode == current
                Box(
                    modifier = Modifier
                        .then(if (selected) Modifier.glow(pill, Hud.Gold, 12.dp) else Modifier)
                        .clip(pill)
                        .background(
                            if (selected) {
                                Brush.verticalGradient(listOf(Hud.GoldSoft, Hud.Gold))
                            } else {
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                        )
                        .clickable { onSelect(mode) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        mode.label,
                        color = if (selected) Hud.Ink else Hud.TextDim,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

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

    // Alerts can be reached from the menu or from Settings; back should retrace whichever.
    var alertsFrom by remember { mutableStateOf(Screen.Sky) }
    var aboutFrom by remember { mutableStateOf(Screen.Sky) }

    // A tapped sky alert should leave the user looking at the thing it told them about,
    // not merely with the app open. The target is stashed by MainActivity, which can
    // receive the intent well before there is any composition to hand it to.
    val loading by viewModel.loading
    val pendingAlert by EventDeepLink.pending.collectAsState()
    LaunchedEffect(loading, pendingAlert) {
        if (loading || pendingAlert == null) return@LaunchedEffect
        val target = EventDeepLink.take() ?: return@LaunchedEffect
        screen = Screen.Sky
        viewModel.search(target).firstOrNull()?.let { viewModel.selectSearchTarget(it.target) }
    }

    // Show an upcoming event in the sky: jump to when it can be seen and aim at it.
    val showEvent: (SkyEvent) -> Unit = { e ->
        val t = if (e.peakMillis in e.windowStartMillis..e.windowEndMillis) e.peakMillis else e.windowStartMillis
        viewModel.jumpTime(t - viewModel.currentSkyTimeMillis())
        val target = DeepLink.searchTarget(e)
        if (target.isNotBlank()) {
            viewModel.search(target).firstOrNull()?.let {
                viewModel.selectSearchTarget(it.target)
                viewModel.setFollow(true)
            }
        }
        screen = Screen.Sky
    }

    // System back returns to the sky from any detail screen (instead of exiting).
    BackHandler(enabled = screen != Screen.Sky) {
        screen = when (screen) {
            Screen.Alerts -> alertsFrom
            Screen.About -> aboutFrom
            else -> Screen.Sky
        }
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
            onShowEvent = showEvent,
            onOpen = {
                // Opened from the sky's menu, so back returns to the sky.
                alertsFrom = Screen.Sky
                aboutFrom = Screen.Sky
                screen = it
            },
            onRequestPermission = {
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
        )
        Screen.Settings -> SettingsScreen(
            viewModel,
            settings,
            onOpenAlerts = { alertsFrom = Screen.Settings; screen = Screen.Alerts },
            onOpenAbout = { aboutFrom = Screen.Settings; screen = Screen.About },
        ) { screen = Screen.Sky }
        Screen.Alerts -> AlertsScreen(viewModel.alerts, onShowEvent = showEvent) { screen = alertsFrom }
        // The old Offline downloads screen is now the Offline data page of Settings.
        Screen.Downloads -> SettingsScreen(
            viewModel,
            settings,
            onOpenAlerts = { alertsFrom = Screen.Downloads; screen = Screen.Alerts },
            onOpenAbout = { aboutFrom = Screen.Downloads; screen = Screen.About },
            initialPage = SettingsPage.Offline,
        ) { screen = Screen.Sky }
        Screen.About -> AboutScreen(viewModel) { screen = aboutFrom }
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
    onShowEvent: (SkyEvent) -> Unit,
    onOpen: (Screen) -> Unit,
    onRequestPermission: () -> Unit,
) {
    val location by viewModel.effectiveLocation.collectAsState()
    val model by viewModel.model
    val manualMode by viewModel.manualMode
    val liveTime by viewModel.liveTime
    var showTimePanel by remember { mutableStateOf(false) }
    // The object whose details sheet is open, for its facts and "Show in sky".
    var detailObj by remember { mutableStateOf<IdentifiedObject?>(null) }

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

    // Live view direction for the HUD: read from what the sky was last drawn with,
    // so it follows manual dragging as well as the sensors.
    var heading by remember { mutableFloatStateOf(0f) }
    var viewAlt by remember { mutableFloatStateOf(0f) }
    var accuracy by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            heading = viewModel.viewDirection.azimuthDeg
            viewAlt = viewModel.viewDirection.altitudeDeg
            accuracy = viewModel.orientation.accuracy
            awaitFrame()
        }
    }

    // The manual-mode hint shows for a few seconds after switching to manual, then
    // fades: the gold hand button already says which mode is on.
    var manualHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(manualMode) {
        manualHintVisible = manualMode
        if (manualMode) {
            delay(4_000)
            manualHintVisible = false
        }
    }
    val loadingCatalog by viewModel.loading
    // Work out what's coming up once we know where we are, for the "Tonight" chip.
    LaunchedEffect(location != null) {
        if (location != null && viewModel.alerts.preview.value !is AlertsController.PreviewState.Ready) {
            viewModel.alerts.refreshPreview()
        }
    }

    // Which top-level mode the switcher reflects, and how to move between them.
    // Radar is its own full-screen view; Sky and AR share the star-field Box below.
    val currentMode = when {
        arActive -> SkyMode.Ar
        settings.radarMode -> SkyMode.Radar
        else -> SkyMode.Sky
    }
    val selectMode: (SkyMode) -> Unit = selectMode@{ mode ->
        if (mode == currentMode) return@selectMode
        when (mode) {
            SkyMode.Sky -> {
                viewModel.setBool(BoolSetting.RadarMode, false)
                if (settings.arMode) viewModel.setBool(BoolSetting.ArMode, false)
            }
            SkyMode.Radar -> {
                if (settings.arMode) viewModel.setBool(BoolSetting.ArMode, false)
                viewModel.setBool(BoolSetting.RadarMode, true)
            }
            SkyMode.Ar -> {
                viewModel.setBool(BoolSetting.RadarMode, false)
                onToggleAr() // handles the camera-permission flow + enabling AR
            }
        }
    }

    if (settings.radarMode) {
        RadarView(
            viewModel = viewModel,
            settings = settings,
            model = model,
            onSelectMode = selectMode,
            onExit = { viewModel.setBool(BoolSetting.RadarMode, false) },
            modifier = Modifier.fillMaxSize(),
        )
        return
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
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    HeadingPill(heading, viewAlt, if (liveTime) null else model?.timeMillis)
                }
                HudIconButton(Icons.Filled.PanTool, "Manual look", active = manualMode) {
                    viewModel.toggleManualMode()
                }
                HudIconButton(Icons.Filled.Schedule, "Time machine", active = !liveTime) {
                    showTimePanel = !showTimePanel
                }
                HudIconButton(Icons.Filled.Search, "Search") { onOpen(Screen.Search) }
                val update by viewModel.updateResult
                OverflowMenu(onOpen, (update as? UpdateChecker.Result.Available)?.release?.versionName)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                ModeSwitcher(current = currentMode, onSelect = selectMode)
            }
            val hint = when {
                loadingCatalog || (location != null && model == null) -> HudHint("Loading the star catalogue…", Hud.TextDim, busy = true)
                manualMode && manualHintVisible -> HudHint("Drag to look around", Hud.GoldSoft)
                manualMode -> null
                !viewModel.hasOrientationSensor -> HudHint("No motion sensor · tap the hand to look around by dragging", Color(0xFFFFB4A0))
                accuracy in 0..1 -> HudHint("Wave the phone in a figure-8 to calibrate", Color(0xFFFFD089))
                else -> null
            }
            HintChip(hint)
            // The next thing worth looking up for, from the alerts engine. Tap to see it.
            val searchTarget by viewModel.searchTarget
            if (hint == null && searchTarget == null) {
                val preview by viewModel.alerts.preview.collectAsState()
                val next = (preview as? AlertsController.PreviewState.Ready)?.events
                    ?.firstOrNull { it.windowEndMillis > System.currentTimeMillis() }
                next?.let { TonightChip(it) { onShowEvent(it) } }
            }
            SearchBanner(viewModel, model)
        }

        // Bottom status / setup prompts.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showTimePanel) {
                TimeBar(viewModel)
                Spacer(Modifier.height(8.dp))
            }
            val selAc by viewModel.selectedAircraft
            val selRoute by viewModel.selectedRoute
            val selPhoto by viewModel.selectedPhoto
            val followingState by viewModel.followActive
            val followHex by viewModel.followAircraftHex
            selAc?.let { ac ->
                AircraftInfoCard(
                    ac, selRoute, selPhoto,
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
                    val openDetails = { detailObj = obj; viewModel.openObjectDetail(obj) }
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
                    val whenLine = remember(obj.name, model?.timeMillis?.div(60_000)) {
                        model?.let { m -> RiseSet.forObject(obj, m)?.let { RiseSet.describe(it) } }
                    }
                    ObjectInfoCard(
                        obj,
                        whenLine = whenLine,
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
            val landmarkMsg by viewModel.landmarkMessage
            landmarkMsg?.let {
                Text(it, color = Color(0xCCFFE082), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }

        val detail by viewModel.objectDetail
        detail?.let { d ->
            val target = detailObj?.target
            ObjectDetailSheet(
                d,
                obj = detailObj?.takeIf { it.name == d.title },
                model = model,
                onShowInSky = target?.let {
                    {
                        viewModel.closeObjectDetail()
                        viewModel.selectObject(null)
                        viewModel.selectSearchTarget(it)
                        viewModel.setFollow(true)
                    }
                },
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

/**
 * The heading pill: which way the view points (true north), the altitude it's aimed at,
 * and, while time-travelling, the moment being shown, so a screenshot of a past or
 * future sky can't be mistaken for the live one.
 */
@Composable
private fun HeadingPill(azimuth: Float, altitude: Float, travelMillis: Long?) {
    val fmt = remember { java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.getDefault()) }
    val az = azimuth.roundToInt() % 360
    val alt = altitude.roundToInt()
    Column(
        modifier = Modifier
            .glass(RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 6.dp),
    ) {
        Text(
            text = "$az° ${compassLabel(az.toFloat())}",
            color = Hud.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.sp,
        )
        Text(
            text = (if (alt >= 0) "↑ $alt°" else "↓ ${-alt}°") +
                (travelMillis?.let { "  ·  " + fmt.format(java.util.Date(it)) } ?: ""),
            color = if (travelMillis != null) Hud.Gold else Hud.TextDim,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private data class HudHint(val text: String, val color: Color, val busy: Boolean = false)

/** A small glass chip under the mode switcher for transient status and setup hints. */
@Composable
private fun HintChip(hint: HudHint?) {
    // Keep showing the last hint while it fades out.
    var last by remember { mutableStateOf(hint) }
    if (hint != null) last = hint
    AnimatedVisibility(
        visible = hint != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        val h = last ?: return@AnimatedVisibility
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .glass(RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (h.busy) {
                    CircularProgressIndicator(
                        color = Hud.Gold, strokeWidth = 2.dp,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(h.text, color = h.color, fontSize = 12.sp)
            }
        }
    }
}

/** The next upcoming sky event as a small glass chip under the mode switcher. */
@Composable
private fun TonightChip(event: SkyEvent, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .glass(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Hud.GoldSoft, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(previewTitle(event), color = Hud.Text, fontSize = 12.5.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(relativeWhen(event.peakMillis), color = Hud.Gold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OverflowMenu(onOpen: (Screen) -> Unit, updateVersion: String?) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        HudIconButton(Icons.Filled.MoreVert, "Menu", active = expanded) { expanded = true }
        // A waiting update shows as a gold dot on the menu button and a row at the top.
        if (updateVersion != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(8.dp)
                    .background(Hud.Gold, CircleShape),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (updateVersion != null) {
                DropdownMenuItem(
                    text = { Text("Update ready · v$updateVersion", color = Hud.Gold) },
                    leadingIcon = { Icon(Icons.Filled.Download, null, tint = Hud.Gold) },
                    onClick = { expanded = false; onOpen(Screen.About) },
                )
            }
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                onClick = { expanded = false; onOpen(Screen.Settings) },
            )
            DropdownMenuItem(
                text = { Text("Sky alerts") },
                leadingIcon = { Icon(Icons.Filled.NotificationsActive, null) },
                onClick = { expanded = false; onOpen(Screen.Alerts) },
            )
            DropdownMenuItem(
                text = { Text("Offline data") },
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
    whenLine: String? = null,
    following: Boolean = false,
    onDetails: (() -> Unit)? = null,
    onFollow: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val (icon, accent) = objectVisual(obj)
    Box(modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp))) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, accent)
                Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(obj.name, color = Hud.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(obj.kind, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Hud.TextDim)
                    }
                }
            }
            Column(modifier = Modifier.padding(start = 60.dp, end = 10.dp)) {
                val alt = obj.altDeg
                val az = obj.azDeg
                if (alt != null && az != null) {
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        StatTile("ALT", "${alt.roundToInt()}°")
                        StatTile("AZ", "${az.roundToInt() % 360}° ${compassLabel(az)}")
                        obj.mag?.let { StatTile("MAG", "%.1f".format(it)) }
                    }
                } else {
                    Text(
                        obj.detail, color = Hud.TextDim, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                for (line in listOfNotNull(obj.note, whenLine)) {
                    Text(
                        line, color = Hud.TextDim, fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (onDetails != null || onFollow != null) {
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        onDetails?.let { ActionPill(Icons.Filled.Info, "Details", onClick = it) }
                        onFollow?.let {
                            ActionPill(
                                Icons.Filled.MyLocation,
                                if (following) "Following" else "Follow",
                                active = following,
                                onClick = it,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A circular, softly-glowing icon badge tinted to an object's accent colour. */
@Composable
internal fun IconBadge(icon: ImageVector, accent: Color, size: Dp = 46.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .glow(CircleShape, accent, 8.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.10f))))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
    }
}

/** Maps an identified object to a representative icon and accent colour. */
internal fun objectVisual(obj: IdentifiedObject): Pair<ImageVector, Color> {
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

/**
 * Object details as a bottom sheet: the object stays visible above it. Facts the app
 * knows offline come first, then the Wikipedia summary. The photo slot shows a
 * shimmer while loading and disappears if the image can't be fetched.
 */
@Composable
private fun ObjectDetailSheet(
    detail: ObjectDetail,
    obj: IdentifiedObject?,
    model: SkyModel?,
    onShowInSky: (() -> Unit)?,
    onOpenLink: (String) -> Unit,
    onClose: () -> Unit,
) {
    InWindowSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (obj != null) {
                        val (icon, accent) = objectVisual(obj)
                        IconBadge(icon, accent, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(detail.title, color = Hud.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        obj?.let { Text(it.kind, color = objectVisual(it).second, fontSize = 12.sp) }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Hud.TextDim)
                    }
                }

                val facts = remember(obj, model?.timeMillis?.div(60_000)) { quickFacts(obj, model) }
                if (facts.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    for (row in facts.chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            for ((label, value) in row) {
                                StatTile(label, value, filled = true, modifier = Modifier.weight(1f))
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    val whenLine = remember(obj, model?.timeMillis?.div(60_000)) {
                        if (obj != null && model != null) RiseSet.forObject(obj, model)?.let { RiseSet.describe(it) } else null
                    }
                    whenLine?.let { Text(it, color = Hud.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp)) }
                }

                Spacer(Modifier.height(10.dp))
                when (detail) {
                    is ObjectDetail.Loading -> {
                        Shimmer(Modifier.fillMaxWidth().height(150.dp))
                        Spacer(Modifier.height(12.dp))
                        repeat(3) {
                            Shimmer(Modifier.fillMaxWidth(if (it == 2) 0.6f else 1f).height(12.dp), RoundedCornerShape(4.dp))
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    is ObjectDetail.Empty ->
                        Text("Wikipedia has no summary for this one.", color = Hud.TextDim, fontSize = 14.sp)
                    is ObjectDetail.Failed ->
                        Text("Couldn't load the description · ${detail.message}", color = Hud.TextDim, fontSize = 14.sp)
                    is ObjectDetail.Loaded -> {
                        detail.info.imageUrl?.let { url ->
                            var state by remember(url) { mutableIntStateOf(0) } // 0 loading, 1 ok, 2 failed
                            if (state != 2) {
                                Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp))) {
                                    if (state == 0) Shimmer(Modifier.fillMaxSize())
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(url.replaceFirst("http://", "https://"))
                                            .setHeader("User-Agent", WikiManager.USER_AGENT)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        onState = { st ->
                                            state = when (st) {
                                                is AsyncImagePainter.State.Success -> 1
                                                is AsyncImagePainter.State.Error -> 2
                                                else -> state
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                        Text(detail.info.extract, color = Color(0xDDFFFFFF), fontSize = 14.sp, lineHeight = 20.sp)
                        Text(
                            "Text from Wikipedia (CC BY-SA)",
                            color = Color(0x66FFFFFF), fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
            // Actions stay pinned under the scrolling text.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp),
            ) {
                onShowInSky?.let { ActionPill(Icons.Filled.MyLocation, "Show in sky", primary = true, onClick = it) }
                if (detail !is ObjectDetail.Loading) {
                    val pageLink = (detail as? ObjectDetail.Loaded)?.info?.pageUrl
                    val link = pageLink
                        ?: ("https://en.wikipedia.org/wiki/Special:Search?search=" + Uri.encode(detail.title))
                    ActionPill(Icons.Filled.Public, if (pageLink != null) "Wikipedia ↗" else "Search Wikipedia ↗") {
                        onOpenLink(link)
                    }
                }
            }
        }
    }
}

/** Offline facts for the details sheet: magnitude, position, and equatorial coordinates. */
private fun quickFacts(obj: IdentifiedObject?, model: SkyModel?): List<Pair<String, String>> {
    if (obj == null) return emptyList()
    val out = ArrayList<Pair<String, String>>()
    obj.mag?.let { out += "MAG" to "%.1f".format(it) }
    val alt = obj.altDeg
    val az = obj.azDeg
    if (alt != null && az != null) {
        out += "ALT" to "${alt.roundToInt()}°"
        out += "AZ" to "${az.roundToInt() % 360}° ${compassLabel(az)}"
        if (model != null && obj.aircraftHex == null) {
            val lst = com.starmap.app.astro.AstroMath.lstDegrees(
                com.starmap.app.astro.AstroMath.julianDay(model.timeMillis), model.location.longitude,
            )
            val (ra, dec) = RiseSet.raDecFromAltAz(alt.toDouble(), az.toDouble(), model.location.latitude, lst)
            val raH = ra / 15.0
            out += "RA" to "%dh %02dm".format(raH.toInt(), ((raH % 1) * 60).toInt())
            out += "DEC" to "%+.1f°".format(dec)
        }
    }
    obj.note?.let { if (obj.kind == "Moon") out += "PHASE" to it.substringAfterLast(" · ", it) }
    return out
}

/**
 * The time machine: the shown date and time (tap to pick a date), a ruler you drag
 * to scrub through the hours, one-day jumps, a time-lapse speed pill and "Now".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeBar(viewModel: SkyViewModel) {
    val live by viewModel.liveTime
    val rate by viewModel.timeFlowRate
    // Read the sky clock every frame so the ruler tracks a drag or time-lapse smoothly.
    var millis by remember { mutableLongStateOf(viewModel.currentSkyTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            millis = viewModel.currentSkyTimeMillis()
            awaitFrame()
        }
    }
    val fmt = remember { java.text.SimpleDateFormat("EEE d MMM yyyy · h:mm a", java.util.Locale.getDefault()) }
    var pickDate by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmt.format(java.util.Date(millis)),
                    color = Hud.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.weight(1f).clickable { pickDate = true },
                )
                Text(
                    if (live) "● LIVE" else "TIME TRAVEL",
                    color = if (live) Color(0xFF7FE3A0) else Hud.Gold,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            TimeRuler(millis) { viewModel.jumpTime(it) }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionPill(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "1 day") { viewModel.jumpTime(-86_400_000L) }
                Spacer(Modifier.width(6.dp))
                ActionPill(Icons.AutoMirrored.Filled.KeyboardArrowRight, "1 day") { viewModel.jumpTime(86_400_000L) }
                Spacer(Modifier.weight(1f))
                // One pill cycles the time-lapse speed, so the row fits a phone.
                val speeds = listOf(0L, 60_000L, 3_600_000L, 86_400_000L)
                val playing = !live && rate != 0L
                ActionPill(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    when (rate) {
                        60_000L -> "1 min/s"
                        3_600_000L -> "1 h/s"
                        86_400_000L -> "1 day/s"
                        else -> "Play"
                    },
                    active = playing,
                ) {
                    val next = speeds[(speeds.indexOf(if (playing) rate else 0L) + 1) % speeds.size]
                    viewModel.setTimeFlowRate(next)
                }
                Spacer(Modifier.width(6.dp))
                ActionPill(Icons.Filled.Schedule, "Now", primary = !live) { viewModel.goLiveTime() }
            }
        }
    }

    if (pickDate) {
        val zone = java.time.ZoneId.systemDefault()
        val shown = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = shown.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { sel ->
                        // Keep the time of day; change only the date.
                        val date = java.time.Instant.ofEpochMilli(sel).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        val target = shown.with(date).toInstant().toEpochMilli()
                        viewModel.jumpTime(target - viewModel.currentSkyTimeMillis())
                    }
                    pickDate = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/**
 * A horizontal time ruler centred on [millis]: 10-minute ticks, labelled hours, and a
 * fixed gold needle. Dragging it left moves time forward, like sliding a tape.
 */
@Composable
private fun TimeRuler(millis: Long, onScrub: (Long) -> Unit) {
    val density = LocalDensity.current.density
    val pxPerHour = 64f * density
    val hourFmt = remember { java.text.SimpleDateFormat("h a", java.util.Locale.getDefault()) }
    val dayFmt = remember { java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()) }
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val tz = remember { java.util.TimeZone.getDefault() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x0DFFFFFF))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    onScrub((-dx / pxPerHour * 3_600_000f).toLong())
                }
            },
    ) {
        val cx = size.width / 2f
        val step = 600_000L // 10 minutes
        val offset = tz.getOffset(millis).toLong()
        val halfSpan = (cx / pxPerHour * 3_600_000f).toLong() + step
        var k = Math.floorDiv(millis + offset - halfSpan, step)
        val kEnd = Math.floorDiv(millis + offset + halfSpan, step) + 1
        labelPaint.textSize = 10f * density
        while (k <= kEnd) {
            val localT = k * step
            val t = localT - offset
            val x = cx + (t - millis) / 3_600_000f * pxPerHour
            val isHour = localT % 3_600_000L == 0L
            val isMidnight = localT % 86_400_000L == 0L
            val h = size.height
            val top = if (isHour) h * 0.42f else h * 0.66f
            drawLine(
                Color.White.copy(alpha = if (isHour) 0.45f else 0.2f),
                Offset(x, top), Offset(x, h), strokeWidth = (if (isHour) 1.5f else 1f) * density,
            )
            if (isHour) {
                labelPaint.color = if (isMidnight) Hud.GoldSoft.toArgb() else android.graphics.Color.argb(170, 190, 202, 217)
                drawContext.canvas.nativeCanvas.drawText(
                    if (isMidnight) dayFmt.format(java.util.Date(t)) else hourFmt.format(java.util.Date(t)),
                    x, 13f * density, labelPaint,
                )
            }
            k++
        }
        drawLine(Hud.Gold.copy(alpha = 0.35f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 6f * density)
        drawLine(Hud.Gold, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f * density)
    }
}

@Composable
private fun StatusCard(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp))) {
        Box(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
internal fun DisposableEffectLifecycle(onResume: () -> Unit, onPause: () -> Unit) {
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
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .glass(RoundedCornerShape(14.dp)),
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
internal fun AircraftInfoCard(
    ac: AircraftRender,
    route: AircraftManager.Route?,
    photo: AircraftManager.Photo?,
    tracking: Boolean,
    onTrack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val cardMod = if (ac.isEmergency) {
        modifier.fillMaxWidth()
            .glass(shape, top = Color(0xD14A1C1C), bottom = Color(0xD12A1010), border = Color(0x55FF8A8A))
    } else {
        modifier.fillMaxWidth().glass(shape)
    }
    val accent = if (ac.isHelicopter) Color(0xFF7FD8C6) else Color(0xFFFFC061)
    Box(modifier = cardMod) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 14.dp, end = 6.dp)) {
            // A found photo replaces the icon badge as a thumbnail; no photo, no extra row.
            var photoOk by remember(photo?.thumbnailUrl) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    IconBadge(Icons.Filled.Flight, accent, size = 40.dp)
                    if (photo != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(photo.thumbnailUrl)
                                .setHeader("Referer", "https://www.planespotters.net/")
                                .crossfade(true)
                                .build(),
                            contentDescription = "Photo of ${ac.registration}",
                            contentScale = ContentScale.Crop,
                            onState = { st -> photoOk = st is AsyncImagePainter.State.Success },
                            modifier = Modifier
                                .size(width = 64.dp, height = 44.dp)
                                .clip(RoundedCornerShape(9.dp)),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    val title = ac.callsign.ifBlank { ac.registration.ifBlank { "Aircraft" } }
                    Text(title, color = Color(0xFFFFE9A8), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    // General-aviation flights use the registration as callsign, so only
                    // repeat it when it adds something.
                    val kind = when {
                        ac.isHelicopter && ac.typeCode.isNotBlank() -> "Helicopter · ${ac.typeCode}"
                        ac.isHelicopter -> "Helicopter"
                        else -> ac.typeCode
                    }
                    val sub = listOfNotNull(
                        ac.registration.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
                        kind.takeIf { it.isNotBlank() },
                        route?.airline?.takeIf { it.isNotBlank() },
                        ac.squawk.takeIf { it.isNotBlank() && !ac.isEmergency }?.let { "squawk $it" },
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, color = Hud.TextDim, fontSize = 12.5.sp)
                }
                IconButton(onClick = onTrack) {
                    Icon(
                        Icons.Filled.MyLocation, contentDescription = "Track",
                        tint = if (tracking) Hud.Gold else Hud.Text,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Hud.TextDim)
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
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            val ft = (ac.altitudeMeters / 0.3048).roundToInt()
            val vr = ac.verticalRateFpm
            val climbing = vr > 100
            val descending = vr < -100
            val miles = ac.rangeKm * 0.621371
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile(
                    "ALT", "%,d".format(ft), Modifier.weight(1.25f),
                    sub = if (climbing || descending) "ft · %+,d fpm".format(vr.roundToInt()) else "ft",
                    trend = if (climbing) " ↑" else if (descending) " ↓" else null,
                    trendColor = if (climbing) Color(0xFF7FE3A0) else Color(0xFFFFC061),
                    filled = true,
                )
                StatTile("SPD", "${ac.groundSpeedKts.roundToInt()}", Modifier.weight(1f), sub = "kt", filled = true)
                StatTile(
                    "HDG", "${ac.trackDeg.roundToInt() % 360}°", Modifier.weight(1f),
                    sub = compassLabel(ac.trackDeg.toFloat()), filled = true,
                )
                StatTile(
                    "DIST", if (miles < 10) "%.1f".format(miles) else "${miles.roundToInt()}", Modifier.weight(1f),
                    sub = "mi · ${ac.rangeKm.roundToInt()} km", filled = true,
                )
            }
            route?.let {
                if (it.origin.code != "?" || it.destination.code != "?") {
                    AirportRoute(it.origin, it.destination)
                }
            }
            if (photo != null && photoOk && photo.photographer.isNotBlank()) {
                Text(
                    "Photo ${photo.photographer} / planespotters.net",
                    color = Color(0x66FFFFFF), fontSize = 10.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * The flight route shown on the aircraft card: two airport codes with an arrow between.
 * A code with known details (full name / city) is underlined and tappable, expanding to
 * show that name and location below; tapping again collapses it.
 */
@Composable
private fun AirportRoute(origin: AircraftManager.Airport, destination: AircraftManager.Airport) {
    var expanded by remember(origin, destination) { mutableStateOf<AircraftManager.Airport?>(null) }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AirportCode(origin) { expanded = if (expanded == origin) null else origin }
            Text("  →  ", color = Color(0xFF9FE0C0), fontSize = 15.sp)
            AirportCode(destination) { expanded = if (expanded == destination) null else destination }
        }
        expanded?.let { ap ->
            if (ap.name.isNotBlank()) {
                Text(
                    "${ap.code} · ${ap.name}",
                    color = Color(0xCCFFFFFF), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (ap.location.isNotBlank()) {
                Text(ap.location, color = Color(0x99FFFFFF), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AirportCode(airport: AircraftManager.Airport, onClick: () -> Unit) {
    val hasDetail = airport.code != "?" && (airport.name.isNotBlank() || airport.location.isNotBlank())
    Text(
        airport.code,
        color = Color(0xFF9FE0C0), fontSize = 15.sp,
        textDecoration = if (hasDetail) TextDecoration.Underline else null,
        modifier = if (hasDetail) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

internal fun compassLabel(deg: Float): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = (((deg % 360f) + 360f) % 360f / 45f).roundToInt() % 8
    return dirs[idx]
}
