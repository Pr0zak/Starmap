package com.starmap.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.starmap.app.aircraft.RadarKind
import com.starmap.app.aircraft.Metar
import com.starmap.app.landmark.LandmarkManager
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.starmap.app.settings.SettingsRepository.IntSetting
import com.starmap.app.sky.RadarMath
import com.starmap.app.sky.positionInto
import kotlin.math.atan2
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starmap.app.settings.Settings
import com.starmap.app.settings.SettingsRepository.BoolSetting
import com.starmap.app.settings.SettingsRepository.FloatSetting
import com.starmap.app.sky.AircraftRender
import com.starmap.app.sky.SkyModel
import com.starmap.app.sky.SkyViewModel
import com.starmap.app.sky.positionInto
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Top-down "radar" scope: the observer sits at the centre with concentric range
 * rings, and aircraft (with trails) plus ground landmarks are plotted by their
 * true bearing and distance. North-up by default, with a heading-up toggle that
 * rotates the scope to the way the phone points. Pinch to change range; a bottom
 * drawer lists the aircraft by distance.
 */
@Composable
fun RadarView(
    viewModel: SkyViewModel,
    settings: Settings,
    model: SkyModel?,
    onSelectMode: (SkyMode) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // System back leaves radar for the sky view (the old Close button is now the mode switcher).
    BackHandler { onExit() }

    val density = LocalDensity.current.density
    val headingUp = settings.radarHeadingUp
    val showPois = settings.radarLandmarks
    val showAircraft = settings.radarAircraft
    val basemap = settings.radarBasemap
    val basemapOpacity = settings.radarBasemapOpacity
    val weather = settings.radarWeather
    val weatherOpacity = settings.radarWeatherOpacity
    var showBasemapMenu by remember { mutableStateOf(false) }

    // Weather animation state (RainViewer frames over the last ~2 h).
    var weatherMaps by remember { mutableStateOf<WeatherTiles.Maps?>(null) }
    var frameIdx by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var weatherLoaded by remember { mutableIntStateOf(0) }
    var weatherTotal by remember { mutableIntStateOf(0) }
    // Clouds are a single latest image, refreshed every 10 minutes.
    var cloudStamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(weather) {
        if (weather != 2) return@LaunchedEffect
        while (true) {
            cloudStamp = System.currentTimeMillis() / 600_000L
            delay(10 * 60 * 1000L)
        }
    }
    LaunchedEffect(weather) {
        if (weather != 1) return@LaunchedEffect
        while (true) {
            // Refresh every few minutes so frame paths don't expire (RainViewer rolls
            // its ~2 h window); only re-cache when the newest frame actually changed.
            val m = WeatherTiles.fetch()
            if (m != null && m.rain.lastOrNull()?.path != weatherMaps?.rain?.lastOrNull()?.path) {
                weatherMaps = m
            }
            delay(5 * 60 * 1000L)
        }
    }
    val weatherFrames = when (weather) {
        2 -> if (cloudStamp > 0) listOf(WeatherTiles.Frame(cloudStamp * 600, "clouds-$cloudStamp")) else emptyList()
        else -> weatherMaps?.frames(weather) ?: emptyList()
    }
    val weatherBuffered = weatherTotal > 0 && weatherLoaded >= weatherTotal
    LaunchedEffect(weatherFrames.size, weather) {
        if (weatherFrames.isNotEmpty()) frameIdx = weatherFrames.lastIndex
    }
    // Don't animate until every frame is cached, so playback is smooth.
    LaunchedEffect(weatherBuffered) { if (!weatherBuffered) playing = false }
    LaunchedEffect(playing, weatherBuffered, weatherFrames.size) {
        if (playing && weatherBuffered && weatherFrames.isNotEmpty()) {
            while (true) {
                delay(550)
                frameIdx = (frameIdx + 1) % weatherFrames.size
            }
        }
    }
    val curFrame = weatherFrames.getOrNull(frameIdx.coerceIn(0, (weatherFrames.size - 1).coerceAtLeast(0)))

    val azState = remember { mutableFloatStateOf(0f) }
    // Frame clock: the scope redraws every frame for dead-reckoning, the sweep and the
    // selection pulse, even when the heading doesn't change.
    val frameClock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            azState.floatValue = viewModel.orientation.basis.azimuthDeg
            frameClock.longValue++
            awaitFrame()
        }
    }
    // Stable bearing provider so the map layers aren't recomposed every animation frame.
    val bearingProvider = remember { { azState.floatValue } }

    // Local range so pinch is smooth; persisted (debounced) without recomposing.
    var rangeNm by remember { mutableFloatStateOf(settings.radarRangeNm) }
    LaunchedEffect(Unit) {
        snapshotFlow { rangeNm }.collectLatest {
            delay(400)
            viewModel.setFloat(FloatSetting.RadarRange, it)
        }
    }
    val aircraft = model?.aircraft ?: emptyList()
    // Sort once per aircraft-list change rather than every frame (rangeKm is stable
    // between fetches); reuse one Path for the chevrons.
    val kinds = settings.radarKinds
    val sortedAircraft = remember(aircraft, kinds) {
        aircraft.filter { it.kind and kinds != 0 || it.isEmergency }.sortedBy { it.rangeKm }
    }
    val unit = RadarMath.Unit.of(settings.radarUnits)
    val labelMode = settings.radarLabelMode
    // Closest approach for each plane, worked out once per ADS-B update.
    val approaches = remember(sortedAircraft) {
        val now = System.currentTimeMillis()
        val pos = FloatArray(3)
        sortedAircraft.associate { ac ->
            ac.positionInto(now, pos)
            ac.icaoHex to RadarMath.closestApproach(pos[0], pos[1], ac.groundSpeedKts, ac.trackDeg)
        }
    }
    // The pass worth pointing out: nearest approach within 15 minutes and 20 km.
    val nextPass = remember(approaches) {
        sortedAircraft.mapNotNull { ac -> approaches[ac.icaoHex]?.let { ac to it } }
            .filter { (_, ap) -> ap.minutes <= 15f && ap.distanceKm <= 20f }
            .minByOrNull { it.second.distanceKm }
    }
    val acPath = remember { Path() }
    val landmarks = model?.landmarks ?: emptyList()
    val selAc by viewModel.selectedAircraft
    val selectedHex = selAc?.icaoHex
    // The big detail card can be dismissed while keeping the aircraft selected
    // (highlighted, with its on-scope data block). Re-shown when the selection changes.
    var detailsHidden by remember { mutableStateOf(false) }
    // The aircraft list collapses when a plane is picked, making room for its card.
    var listExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(selectedHex) {
        detailsHidden = false
        if (selectedHex != null) listExpanded = false
    }

    // Keep the scope clear of the top controls and the collapsed drawer (and the side
    // view when it's on). Every layer uses the same insets so they stay aligned.
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val insets = with(LocalDensity.current) {
        RadarInsets(
            top = (topInset + 60.dp).toPx(),
            bottom = (bottomInset + 104.dp + if (settings.radarProfile) 112.dp else 0.dp).toPx(),
        )
    }

    // Runways and the current wind at nearby airports. Runways are fetched once per
    // ~5 km of movement; METARs every 15 minutes.
    var runways by remember { mutableStateOf<List<LandmarkManager.Runway>>(emptyList()) }
    var metars by remember { mutableStateOf<List<Metar.Station>>(emptyList()) }
    val fixNow = model?.location
    val fixKey = fixNow?.let { (it.latitude * 20).roundToInt() to (it.longitude * 20).roundToInt() }
    LaunchedEffect(settings.radarAirports, fixKey) {
        val f = fixNow
        if (!settings.radarAirports || f == null) {
            runways = emptyList(); metars = emptyList()
            return@LaunchedEffect
        }
        LandmarkManager().fetchRunways(f.latitude, f.longitude, 60_000)?.let { runways = it }
        while (true) {
            Metar.fetch(f.latitude, f.longitude, 90.0)?.let { metars = it }
            delay(15 * 60 * 1000L)
        }
    }

    // Heads-up: a banner (and a short buzz) when something will pass close, flies low
    // nearby, or a helicopter or emergency shows up. Each plane is announced once per reason.
    var headsUp by remember { mutableStateOf<String?>(null) }
    val announced = remember { HashSet<String>() }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(sortedAircraft, settings.radarHeadsUp, settings.radarHeadsUpNm) {
        if (!settings.radarHeadsUp) return@LaunchedEffect
        val obsAltM = model?.location?.altitude ?: 0.0
        val closeKm = settings.radarHeadsUpNm * 1.852f
        val msgs = sortedAircraft.mapNotNull { ac ->
            val name = ac.callsign.ifBlank { ac.registration.ifBlank { "An aircraft" } }
            val ap = approaches[ac.icaoHex]
            val aglFt = (ac.altitudeMeters - obsAltM) / 0.3048
            when {
                ac.isEmergency -> "emg:${ac.icaoHex}" to "$name is squawking ${ac.squawk.ifBlank { "emergency" }}"
                ap != null && ap.distanceKm <= closeKm && ap.minutes <= 5f ->
                    "cpa:${ac.icaoHex}" to "$name passes ${unit.format(ap.distanceKm.toDouble())} away in ${ap.minutes.roundToInt().coerceAtLeast(1)} min"
                aglFt < 3000 && ac.rangeKm < 10 && ac.altitudeMeters > 30 ->
                    "low:${ac.icaoHex}" to "$name is low: ${"%,d".format(aglFt.roundToInt().coerceAtLeast(0))} ft above you, ${unit.format(ac.rangeKm)} away"
                ac.kind == RadarKind.HELICOPTER && ac.rangeKm < 15 ->
                    "heli:${ac.icaoHex}" to "Helicopter $name ${unit.format(ac.rangeKm)} away"
                else -> null
            }
        }.filter { announced.add(it.first) }
        msgs.firstOrNull()?.let { (_, text) ->
            headsUp = if (msgs.size > 1) "$text (+${msgs.size - 1} more)" else text
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(6_000)
            headsUp = null
        }
    }

    val hits = remember { mutableListOf<Pair<Offset, AircraftRender>>() }
    fun nearestTo(p: Offset): AircraftRender? {
        var best: AircraftRender? = null
        var bestD = 34f * density
        for ((o, ac) in hits) {
            val d = hypot(o.x - p.x, o.y - p.y)
            if (d < bestD) { bestD = d; best = ac }
        }
        return best
    }
    // A dark shadow layer keeps text readable over a bright basemap (satellite/streets).
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 10f * density
            setShadowLayer(3f * density, 0f, 1f * density, android.graphics.Color.argb(225, 0, 0, 0))
        }
    }
    val ringPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 9f * density
            color = android.graphics.Color.argb(215, 150, 220, 170)
            setShadowLayer(3f * density, 0f, 1f * density, android.graphics.Color.argb(205, 0, 0, 0))
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF05080C))) {
        // Optional online basemap (satellite / streets) beneath the scope.
        val fix = model?.location
        if (basemap != 0 && fix != null) {
            RadarBasemap(
                latitude = fix.latitude,
                longitude = fix.longitude,
                maxRangeKm = rangeNm * 1.852f,
                headingUp = headingUp,
                bearing = bearingProvider,
                mode = basemap,
                opacity = basemapOpacity,
                insets = insets,
            )
        }
        if (weather != 0 && fix != null && weatherFrames.isNotEmpty()) {
            RadarWeatherLayer(
                latitude = fix.latitude,
                longitude = fix.longitude,
                maxRangeKm = rangeNm * 1.852f,
                headingUp = headingUp,
                bearing = bearingProvider,
                mode = weather,
                opacity = weatherOpacity,
                host = if (weather == 2) "gibs" else weatherMaps?.host,
                frames = weatherFrames,
                frameIndex = frameIdx,
                onBuffered = { loaded, total -> weatherLoaded = loaded; weatherTotal = total },
                insets = insets,
            )
        }
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { viewModel.selectAircraft(nearestTo(it)); detailsHidden = false },
                        // Zoom so the nearest ten aircraft fill the scope.
                        onDoubleTap = {
                            val far = sortedAircraft.take(10).maxOfOrNull { it.rangeKm }
                            if (far != null) {
                                val need = far * 0.539957 * 1.15
                                rangeNm = (NICE_RANGES_NM.firstOrNull { it >= need } ?: 150f)
                            }
                        },
                        onLongPress = { p ->
                            nearestTo(p)?.let {
                                viewModel.selectAircraft(it)
                                viewModel.followAircraft(it.icaoHex)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) rangeNm = (rangeNm / zoom).coerceIn(5f, 150f)
                    }
                },
        ) {
            frameClock.longValue // redraw every frame
            hits.clear()
            labelPaint.textSize = 10f * density
            val az = azState.floatValue
            val maxRangeKm = rangeNm * 1.852f
            val a = if (headingUp) Math.toRadians(az.toDouble()) else 0.0
            val ca = cos(a)
            val sa = sin(a)
            val geom = radarGeometry(size.width, size.height, insets)
            val cx = geom.cx
            val cy = geom.cy
            val r = geom.r
            val scale = r / maxRangeKm

            fun proj(eKm: Float, nKm: Float): Offset {
                val e2 = (eKm * ca - nKm * sa).toFloat()
                val n2 = (eKm * sa + nKm * ca).toFloat()
                return Offset(cx + e2 * scale, cy - n2 * scale)
            }

            // Darken a visible basemap/weather layer so the scope, trails and labels
            // keep their contrast; scaled by how opaque the map is.
            val mapVis = maxOf(
                if (basemap != 0) basemapOpacity else 0f,
                if (weather != 0) weatherOpacity else 0f,
            )
            if (mapVis > 0f) {
                drawRect(Color(0xFF04070B), alpha = (0.18f + 0.42f * mapVis).coerceIn(0f, 0.62f))
            }

            // Range rings, labelled along the north-east diagonal in the chosen unit.
            val ringColor = Color(0xFF2E8B57)
            for (i in 1..4) {
                val rr = r * i / 4f
                drawCircle(ringColor, rr, Offset(cx, cy), style = Stroke(1.2f * density))
                val v = unit.fromKm(maxRangeKm * i / 4.0)
                drawContext.canvas.nativeCanvas.drawText(
                    if (v < 10) "%.1f".format(v) else "${v.roundToInt()}",
                    cx + rr * 0.7071f + 3f * density, cy - rr * 0.7071f - 3f * density, ringPaint,
                )
            }

            // Cardinal spokes + letters (rotate with heading-up).
            ringPaint.textAlign = android.graphics.Paint.Align.CENTER
            val rim = r - 12f * density // letters sit just inside the rim
            for ((lbl, brg) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
                val ang = Math.toRadians(brg) - a
                drawLine(
                    Color(0x5520E060), Offset(cx, cy),
                    Offset(cx + (sin(ang) * r).toFloat(), cy - (cos(ang) * r).toFloat()),
                    strokeWidth = 0.8f * density,
                )
                drawContext.canvas.nativeCanvas.drawText(
                    lbl, cx + (sin(ang) * rim).toFloat(), cy - (cos(ang) * rim).toFloat() + 3f * density, ringPaint,
                )
            }
            // Minor compass ticks every 30°.
            for (d in 30 until 360 step 30) {
                if (d % 90 == 0) continue
                val ang = Math.toRadians(d.toDouble()) - a
                drawLine(
                    Color(0x5520E060),
                    Offset(cx + (sin(ang) * (r - 5f * density)).toFloat(), cy - (cos(ang) * (r - 5f * density)).toFloat()),
                    Offset(cx + (sin(ang) * r).toFloat(), cy - (cos(ang) * r).toFloat()),
                    strokeWidth = 1f * density,
                )
            }
            if (headingUp) {
                drawContext.canvas.nativeCanvas.drawText(
                    "HDG ${az.roundToInt() % 360}°", cx, cy - r + 28f * density, ringPaint,
                )
            }
            ringPaint.textAlign = android.graphics.Paint.Align.LEFT

            // Where the phone (and the sky view) is pointing, as a faint wedge.
            run {
                val lookAz = if (viewModel.hasOrientationSensor) az else viewModel.viewDirection.azimuthDeg
                val vFov = Math.toRadians(settings.fovDeg.toDouble())
                val hFov = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.tan(vFov / 2) * size.width / size.height)).toFloat()
                val start = lookAz - hFov / 2f - Math.toDegrees(a).toFloat() - 90f
                drawArc(
                    Hud.Gold.copy(alpha = 0.07f), start, hFov, useCenter = true,
                    topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r),
                )
                for (edge in listOf(start, start + hFov)) {
                    val er = Math.toRadians(edge.toDouble())
                    drawLine(
                        Hud.Gold.copy(alpha = 0.35f), Offset(cx, cy),
                        Offset(cx + (cos(er) * r).toFloat(), cy + (sin(er) * r).toFloat()),
                        strokeWidth = 1f * density,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * density, 5f * density)),
                    )
                }
            }

            // Optional sweep beam, one turn every six seconds.
            val sweepDeg = if (settings.radarSweep) (System.currentTimeMillis() % 6000L) / 6000f * 360f else Float.NaN
            if (!sweepDeg.isNaN()) {
                rotate(sweepDeg - 90f, Offset(cx, cy)) {
                    drawArc(
                        Brush.sweepGradient(
                            0f to Color.Transparent, 0.885f to Color.Transparent, 1f to Color(0x553EE08A),
                            center = Offset(cx, cy),
                        ),
                        startAngle = -42f, sweepAngle = 42f, useCenter = true,
                        topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r),
                    )
                    drawLine(Color(0x996BFFB0), Offset(cx, cy), Offset(cx + r, cy), strokeWidth = 1.5f * density)
                }
            }

            // Sun and Moon bearings on the rim.
            fun rimMarker(enu: FloatArray?, label: String, color: Color) {
                if (enu == null) return
                val brg = atan2(enu[0].toDouble(), enu[1].toDouble()) - a
                val p = Offset(cx + (sin(brg) * r).toFloat(), cy - (cos(brg) * r).toFloat())
                drawCircle(Color(0xFF05080C), 8f * density, p)
                drawCircle(color.copy(alpha = if (enu[2] < 0f) 0.45f else 1f), 6f * density, p)
                labelPaint.color = color.toArgb()
                val inward = Offset(cx - p.x, cy - p.y).let { it / it.getDistance().coerceAtLeast(1f) }
                val tw = labelPaint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(
                    label + if (enu[2] < 0f) " ↓" else "",
                    p.x + inward.x * 16f * density - tw / 2f, p.y + inward.y * 16f * density + 4f * density, labelPaint,
                )
            }
            rimMarker(model?.sun?.enu, "Sun", Color(0xFFFFB74D))
            rimMarker(model?.moon?.enu, "Moon", Color(0xFFCFD8DC))

            drawCircle(Color(0xFFD8E0F0), 3f * density, Offset(cx, cy))

            // Ground landmarks (POIs).
            if (showPois) {
                for (lm in landmarks) {
                    val dist = lm.distanceKm
                    if (dist > maxRangeKm) continue
                    val o = proj(lm.enu[0] * dist, lm.enu[1] * dist)
                    val col = when (lm.type) {
                        "airport" -> Color(0xFF80C8FF)
                        "tower" -> Color(0xFFFF9E80)
                        else -> Color(0xFFFFE082)
                    }
                    drawCircle(col.copy(alpha = 0.85f), 2.5f * density, o)
                }
            }

            // Runways and surface wind at airports in range.
            if (settings.radarAirports && fix != null) {
                for (rw in runways) {
                    val (e1, n1) = groundEnu(fix.latitude, fix.longitude, rw.lat1, rw.lon1)
                    if (hypot(e1, n1) > maxRangeKm) continue
                    val (e2, n2) = groundEnu(fix.latitude, fix.longitude, rw.lat2, rw.lon2)
                    drawLine(
                        Color(0xD99FB6D8), proj(e1, n1), proj(e2, n2),
                        strokeWidth = 3f * density, cap = StrokeCap.Round,
                    )
                }
                for (st in metars) {
                    val (e, n) = groundEnu(fix.latitude, fix.longitude, st.lat, st.lon)
                    if (hypot(e, n) > maxRangeKm) continue
                    drawWindBarb(proj(e, n), st, a, density, labelPaint)
                }
            }

            // Aircraft: dead-reckoned blip, trail, velocity leader, chevron.
            val acPos = FloatArray(3)
            val acNow = System.currentTimeMillis()
            val taken = ArrayList<android.graphics.RectF>(48)
            val selRoute = viewModel.selectedRoute.value

            // Selected flight's great-circle route, the part near the scope.
            if (selRoute != null && fix != null &&
                !selRoute.origin.lat.isNaN() && !selRoute.destination.lat.isNaN()
            ) {
                var prevA: Offset? = null
                var i = 0
                while (i <= 96) {
                    val (plat, plon) = gcPoint(
                        selRoute.origin.lat, selRoute.origin.lon,
                        selRoute.destination.lat, selRoute.destination.lon, i / 96.0,
                    )
                    val (e, n) = groundEnu(fix.latitude, fix.longitude, plat, plon)
                    if (hypot(e, n) < maxRangeKm * 2.4f) {
                        val oa = proj(e, n)
                        prevA?.let { drawLine(Color(0x55FFD54F), it, oa, strokeWidth = 1.4f * density) }
                        prevA = oa
                    } else {
                        prevA = null
                    }
                    i++
                }
            }

            for (ac in if (showAircraft) sortedAircraft else emptyList()) {
                val ft = ac.altitudeMeters / 0.3048
                ac.positionInto(acNow, acPos)
                val eKm = acPos[0]
                val nKm = acPos[1]
                if (hypot(eKm, nKm) > maxRangeKm) continue
                val o = proj(eKm, nKm)
                hits.add(o to ac)
                val col = aircraftColor(ac)
                // Afterglow just behind the sweep beam.
                if (!sweepDeg.isNaN()) {
                    val blipDeg = ((Math.toDegrees(atan2((o.x - cx).toDouble(), (cy - o.y).toDouble())) + 360.0) % 360.0).toFloat()
                    val behind = ((sweepDeg - blipDeg) % 360f + 360f) % 360f
                    if (behind < 50f) drawCircle(col.copy(alpha = 0.45f * (1f - behind / 50f)), 11f * density, o)
                }
                // Closest approach, for the pass worth pointing out and the selected plane.
                if (ac.icaoHex == selectedHex || ac.icaoHex == nextPass?.first?.icaoHex) {
                    RadarMath.closestApproach(eKm, nKm, ac.groundSpeedKts, ac.trackDeg)?.let { ap ->
                        val cp = proj(ap.eastKm, ap.northKm)
                        val dash = PathEffect.dashPathEffect(floatArrayOf(3f * density, 4f * density))
                        drawLine(Hud.Gold.copy(alpha = 0.8f), o, cp, strokeWidth = 1.2f * density, pathEffect = dash)
                        drawLine(Hud.Gold.copy(alpha = 0.4f), Offset(cx, cy), cp, strokeWidth = 1f * density)
                        drawCircle(Hud.Gold, 5f * density, cp, style = Stroke(1.6f * density))
                        labelPaint.color = Color(0xFFFFE08A).toArgb()
                        val txt = "${unit.format(ap.distanceKm.toDouble())} · ${ap.minutes.roundToInt().coerceAtLeast(1)} min"
                        drawContext.canvas.nativeCanvas.drawText(txt, cp.x + 8f * density, cp.y - 6f * density, labelPaint)
                        taken.add(
                            android.graphics.RectF(
                                cp.x + 8f * density, cp.y - 16f * density,
                                cp.x + 8f * density + labelPaint.measureText(txt), cp.y - 3f * density,
                            ),
                        )
                    }
                }

                // Trail polyline (positions, fading toward the oldest sample).
                val tr = ac.trail
                val pts = tr.size / 3
                var prev: Offset? = null
                var j = 0
                while (j + 2 < tr.size) {
                    val te = tr[j]
                    val tnk = tr[j + 1]
                    if (te != 0f || tnk != 0f) {
                        val to = proj(te, tnk)
                        val p0 = prev
                        if (p0 != null) {
                            val frac = if (pts > 0) (j / 3).toFloat() / pts else 0f
                            drawLine(col.copy(alpha = 0.12f + 0.40f * frac), p0, to, strokeWidth = 1.4f * density)
                        }
                        prev = to
                    }
                    j += 3
                }
                prev?.let { drawLine(col.copy(alpha = 0.55f), it, o, strokeWidth = 1.4f * density) }

                val theta = Math.toRadians(ac.trackDeg) - a
                val s = 6f * density
                fun dir(t: Double, len: Float) =
                    Offset(o.x + (sin(t) * len).toFloat(), o.y - (cos(t) * len).toFloat())
                // Velocity leader line: roughly one minute ahead, scaled by ground speed.
                val leadPx = (ac.groundSpeedKts * 1.852 / 60.0).toFloat() * scale
                if (leadPx > 1f) {
                    drawLine(col.copy(alpha = 0.5f), o, dir(theta, leadPx), strokeWidth = 1.2f * density)
                }
                val tip = dir(theta, s)
                val bl = dir(theta + 2.6, s * 0.85f)
                val brr = dir(theta - 2.6, s * 0.85f)
                acPath.rewind()
                acPath.moveTo(tip.x, tip.y); acPath.lineTo(bl.x, bl.y); acPath.lineTo(brr.x, brr.y); acPath.close()
                drawPath(acPath, col)
                if (ac.icaoHex == selectedHex) {
                    val pulse = (sin(acNow / 280.0) * 0.5 + 0.5).toFloat()
                    drawCircle(
                        Color(0xFFFFD54F).copy(alpha = 0.45f + 0.55f * pulse),
                        s * (1.7f + 0.7f * pulse), o, style = Stroke(1.6f * density),
                    )
                    // Where it will be in 1, 2 and 5 minutes on its current heading.
                    val kmPerMin = (ac.groundSpeedKts * 1.852 / 60.0).toFloat()
                    if (kmPerMin > 0.2f) {
                        val end = dir(theta, kmPerMin * 5f * scale)
                        drawLine(
                            Hud.Gold.copy(alpha = 0.6f), o, end, strokeWidth = 1.2f * density,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * density, 5f * density)),
                        )
                        labelPaint.color = Color(0xFFFFE08A).toArgb()
                        for (m in intArrayOf(1, 2, 5)) {
                            val tp = dir(theta, kmPerMin * m * scale)
                            drawCircle(Hud.Gold, 3f * density, tp)
                            drawContext.canvas.nativeCanvas.drawText("${m}m", tp.x + 5f * density, tp.y + 12f * density, labelPaint)
                        }
                    }
                }
                val vr = when {
                    ac.verticalRateFpm > 200 -> " ↑"
                    ac.verticalRateFpm < -200 -> " ↓"
                    else -> ""
                }
                // The selected plane's card already says all this, so its block only
                // shows while the card is closed.
                val cardOpen = selAc != null && !detailsHidden
                if (ac.icaoHex == selectedHex && cardOpen) {
                    // nothing: the ring and the card identify it
                } else if (ac.icaoHex == selectedHex || labelMode == 2) {
                    // Full data block beside the selected blip.
                    val lines = buildList {
                        add(ac.callsign.ifBlank { ac.registration.ifBlank { "Aircraft" } })
                        add("${ac.typeCode.ifBlank { "—" }}  FL${(ft / 100).roundToInt()}$vr")
                        add("${ac.groundSpeedKts.roundToInt()} kt  ·  ${unit.format(ac.rangeKm)}")
                        if (ac.icaoHex == selectedHex) {
                            selRoute?.let {
                                val rt = "${it.origin.code}→${it.destination.code}"
                                if (rt.length > 1) add(rt)
                            }
                        }
                    }
                    labelPaint.textSize = (if (ac.icaoHex == selectedHex) 11f else 9.5f) * density
                    var bw = 0f
                    for (ln in lines) bw = maxOf(bw, labelPaint.measureText(ln))
                    val lh = 13f * density
                    val bx = (o.x + 10f * density)
                        .coerceAtMost(size.width - bw - 6f * density).coerceAtLeast(4f * density)
                    val by = (o.y - 8f * density - lines.size * lh).coerceAtLeast(12f * density)
                    val blockRect = android.graphics.RectF(
                        bx - 4f * density, by - 11f * density,
                        bx + bw + 4f * density, by + lines.size * lh,
                    )
                    // Unselected blocks (full-label mode) skip rather than overlap.
                    if (ac.icaoHex != selectedHex && taken.any { android.graphics.RectF.intersects(it, blockRect) }) {
                        labelPaint.textSize = 10f * density
                        continue
                    }
                    drawRect(
                        Color(0xD8090D12),
                        topLeft = Offset(bx - 4f * density, by - 11f * density),
                        size = Size(bw + 8f * density, lines.size * lh + 6f * density),
                    )
                    labelPaint.color = (if (ac.icaoHex == selectedHex) Color(0xFFFFE082) else col).toArgb()
                    var yy = by
                    for (ln in lines) {
                        drawContext.canvas.nativeCanvas.drawText(ln, bx, yy, labelPaint)
                        yy += lh
                    }
                    taken.add(
                        android.graphics.RectF(
                            bx - 4f * density, by - 11f * density,
                            bx + bw + 4f * density, by + lines.size * lh,
                        ),
                    )
                    labelPaint.textSize = 10f * density
                } else {
                    val name = ac.callsign.ifBlank { ac.typeCode.ifBlank { "?" } }
                    val text = if (labelMode == 0) name else "$name  FL${(ft / 100).roundToInt()}$vr"
                    val tw = labelPaint.measureText(text)
                    val lx = o.x + 7f * density
                    val ly = o.y - 4f * density
                    val rect = android.graphics.RectF(lx, ly - 9f * density, lx + tw, ly + 2f * density)
                    if (ac.isEmergency || taken.none { android.graphics.RectF.intersects(it, rect) }) {
                        taken.add(rect)
                        labelPaint.color = col.toArgb()
                        drawContext.canvas.nativeCanvas.drawText(text, lx, ly, labelPaint)
                    }
                }
            }

            // Landmark names go last, into whatever space the aircraft labels left:
            // towns before airports before masts, nearest first. The rest stay as dots.
            var hiddenLabels = 0
            if (showPois) {
                fun rank(type: String) = when (type) { "city" -> 0; "airport" -> 1; else -> 2 }
                labelPaint.textSize = 9.5f * density
                for (lm in landmarks.sortedWith(compareBy({ rank(it.type) }, { it.distanceKm }))) {
                    val dist = lm.distanceKm
                    if (dist > maxRangeKm) continue
                    val o = proj(lm.enu[0] * dist, lm.enu[1] * dist)
                    val tw = labelPaint.measureText(lm.name)
                    val lx = o.x + 5f * density
                    val ly = o.y + 3.5f * density
                    val rect = android.graphics.RectF(lx - 2f, ly - 9f * density, lx + tw + 2f, ly + 2.5f * density)
                    if (taken.none { android.graphics.RectF.intersects(it, rect) }) {
                        taken.add(rect)
                        val col = when (lm.type) {
                            "airport" -> Color(0xFF80C8FF)
                            "tower" -> Color(0xFFFF9E80)
                            else -> Color(0xFFFFE082)
                        }
                        labelPaint.color = col.copy(alpha = 0.85f).toArgb()
                        drawContext.canvas.nativeCanvas.drawText(lm.name, lx, ly, labelPaint)
                    } else {
                        hiddenLabels++
                    }
                }
                labelPaint.textSize = 10f * density
            }
            ringPaint.textAlign = android.graphics.Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(
                unit.format(maxRangeKm.toDouble()) +
                    (if (hiddenLabels > 0) "  ·  $hiddenLabels names hidden" else ""),
                cx, cy + r - 26f * density, ringPaint,
            )
            ringPaint.textAlign = android.graphics.Paint.Align.LEFT
        }

        // Tap anywhere outside the basemap/weather menu to dismiss it.
        if (showBasemapMenu) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { showBasemapMenu = false },
            )
        }

        // Top controls + the selected-aircraft card (reused from the sky view).
        Column(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeSwitcher(current = SkyMode.Radar, onSelect = onSelectMode)
                Spacer(Modifier.weight(1f))
                // Aircraft / landmark toggles live in the layers panel: with them here
                // the row was wider than a 411 dp phone and clipped the last button.
                HudIconButton(Icons.Filled.Layers, "Layers", active = showBasemapMenu || basemap != 0 || weather != 0) {
                    showBasemapMenu = !showBasemapMenu
                }
                HudIconButton(Icons.Filled.Explore, "Heading up", active = headingUp) {
                    viewModel.setBool(BoolSetting.RadarHeadingUp, !headingUp)
                }
            }
            if (showBasemapMenu) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.align(Alignment.TopCenter).width(280.dp)
                            .glass(RoundedCornerShape(14.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {},
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("SHOW", color = Color(0xFF8B97A8), fontSize = 10.sp)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                BasemapChip("Aircraft", showAircraft) {
                                    viewModel.setBool(BoolSetting.RadarAircraft, !showAircraft)
                                }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Landmarks", showPois) {
                                    viewModel.setBool(BoolSetting.RadarLandmarks, !showPois)
                                }
                            }
                            Row {
                                BasemapChip("Heads-up", settings.radarHeadsUp) {
                                    viewModel.setBool(BoolSetting.RadarHeadsUp, !settings.radarHeadsUp)
                                }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Side view", settings.radarProfile) {
                                    viewModel.setBool(BoolSetting.RadarProfile, !settings.radarProfile)
                                }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Sweep", settings.radarSweep) {
                                    viewModel.setBool(BoolSetting.RadarSweep, !settings.radarSweep)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("BASEMAP", color = Color(0xFF8B97A8), fontSize = 10.sp)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                BasemapChip("Off", basemap == 0) { viewModel.setRadarBasemap(0) }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Satellite", basemap == 1) { viewModel.setRadarBasemap(1) }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Streets", basemap == 2) { viewModel.setRadarBasemap(2) }
                            }
                            Row {
                                BasemapChip("Dark", basemap == 3) { viewModel.setRadarBasemap(3) }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Runways & wind", settings.radarAirports) {
                                    viewModel.setBool(BoolSetting.RadarAirports, !settings.radarAirports)
                                }
                            }
                            if (basemap != 0) {
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Opacity", color = Color(0xFFB6C2D2), fontSize = 12.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${(basemapOpacity * 100).roundToInt()}%",
                                        color = Color(0xFFFFD54F), fontSize = 12.sp,
                                    )
                                }
                                CleanSlider(
                                    value = basemapOpacity,
                                    onValueChange = {
                                        viewModel.setFloat(FloatSetting.RadarBasemapOpacity, it)
                                    },
                                    valueRange = 0.1f..1f,
                                )
                            }

                            Spacer(Modifier.height(16.dp))
                            Text("WEATHER", color = Color(0xFF8B97A8), fontSize = 10.sp)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                BasemapChip("Off", weather == 0) { viewModel.setRadarWeather(0) }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Rain", weather == 1) { viewModel.setRadarWeather(1) }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Clouds", weather == 2) { viewModel.setRadarWeather(2) }
                            }
                            if (weather != 0) {
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Opacity", color = Color(0xFFB6C2D2), fontSize = 12.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${(weatherOpacity * 100).roundToInt()}%",
                                        color = Color(0xFFFFD54F), fontSize = 12.sp,
                                    )
                                }
                                CleanSlider(
                                    value = weatherOpacity,
                                    onValueChange = {
                                        viewModel.setFloat(FloatSetting.RadarWeatherOpacity, it)
                                    },
                                    valueRange = 0.1f..1f,
                                )
                                if (weather == 1 && weatherFrames.isEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (weatherMaps == null) "Loading frames…" else "No data available",
                                        color = Color(0xFF8B97A8), fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Heads-up banner, else the next close pass as a tappable chip.
            val banner = headsUp
            if (banner != null) {
                RadarChip(banner, Icons.Filled.NotificationsActive, Hud.Gold) {}
            } else {
                nextPass?.let { (ac, ap) ->
                    val (_, apAz) = RadarMath.lookAngles(floatArrayOf(ap.eastKm, ap.northKm, 0f).let { v ->
                        val len = hypot(v[0], v[1]).coerceAtLeast(1e-3f); floatArrayOf(v[0] / len, v[1] / len, 0f)
                    })
                    RadarChip(
                        "${ac.callsign.ifBlank { ac.registration }} passes ${unit.format(ap.distanceKm.toDouble())} " +
                            "${compassLabel(apAz)} in ${ap.minutes.roundToInt().coerceAtLeast(1)} min",
                        Icons.Filled.Visibility, Hud.GoldSoft,
                    ) { viewModel.selectAircraft(ac) }
                }
            }
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // Label detail and units, tucked just above the drawer (hidden under a card).
            if (selAc == null || detailsHidden) Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallToggle("Labels: " + listOf("callsign", "+ altitude", "full")[labelMode]) {
                    viewModel.setInt(IntSetting.RadarLabelMode, (labelMode + 1) % 3)
                }
                SmallToggle(unit.label) { viewModel.setInt(IntSetting.RadarUnits, (settings.radarUnits + 1) % 3) }
                Spacer(Modifier.weight(1f))
                SmallToggle("Fit") {
                    sortedAircraft.take(10).maxOfOrNull { it.rangeKm }?.let { far ->
                        rangeNm = NICE_RANGES_NM.firstOrNull { it >= far * 0.539957 * 1.15 } ?: 150f
                    }
                }
            }
            // The selected aircraft's card sits just above the drawer, so the north
            // half of the scope and the Layers panel stay clear.
            selAc?.takeIf { !detailsHidden }?.let { ac ->
                val route by viewModel.selectedRoute
                val photo by viewModel.selectedPhoto
                val followHex by viewModel.followAircraftHex
                AircraftInfoCard(
                    ac, route, photo,
                    tracking = followHex == ac.icaoHex,
                    onTrack = { viewModel.followAircraft(if (followHex == ac.icaoHex) null else ac.icaoHex) },
                    onClose = { detailsHidden = true },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    compact = true,
                    distanceUnit = unit,
                    onFindInSky = {
                        // The sky only draws planes with its aircraft layer on.
                        if (!settings.showAircraft) viewModel.setBool(BoolSetting.Aircraft, true)
                        viewModel.followAircraft(ac.icaoHex)
                        onSelectMode(SkyMode.Sky)
                    },
                )
            }
            if (weather == 1) {
                WeatherLegend(modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            }
            if (weather == 2) {
                val sat = fix?.let { CloudTiles.satelliteFor(it.longitude) }
                Text(
                    if (sat != null) "Clouds from $sat infrared · colder tops show brighter"
                    else "No cloud imagery covers this area (GOES and Himawari only)",
                    color = Color(0xFFB6C2D2), fontSize = 10.sp,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            if (basemap != 0 || weather != 0) {
                Text(
                    buildString {
                        if (basemap != 0) append("Map © Esri")
                        if (weather != 0) {
                            if (isNotEmpty()) append("   ·   ")
                            append(if (weather == 2) "Clouds: NASA GIBS / NOAA" else "Weather © RainViewer")
                        }
                    },
                    color = Color(0x99B6C2D2), fontSize = 9.sp,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            // Weather timeline: pinned above the drawer so it stays visible while the
            // animation plays on the full scope.
            if (weather == 1 && weatherFrames.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().bottomSheet(22.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!weatherBuffered) {
                            val frac = if (weatherTotal > 0) weatherLoaded.toFloat() / weatherTotal else 0f
                            Text(
                                "Caching frames",
                                color = Color(0xFFB6C2D2), fontSize = 12.sp,
                                modifier = Modifier.padding(start = 4.dp, end = 10.dp),
                            )
                            LinearProgressIndicator(
                                progress = { frac },
                                color = Color(0xFFFFD54F),
                                trackColor = Color(0x33FFFFFF),
                                modifier = Modifier.weight(1f).padding(vertical = 18.dp),
                            )
                            Text(
                                "${(frac * 100).roundToInt()}%",
                                color = Color(0xFFB6C2D2), fontSize = 12.sp,
                                modifier = Modifier.width(44.dp).padding(start = 8.dp),
                            )
                        } else {
                            IconButton(onClick = { playing = !playing }) {
                                Icon(
                                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = Color(0xFFFFD54F),
                                )
                            }
                            if (weatherFrames.size >= 2) {
                                CleanSlider(
                                    value = frameIdx.toFloat().coerceIn(0f, (weatherFrames.size - 1).toFloat()),
                                    onValueChange = { playing = false; frameIdx = it.roundToInt() },
                                    valueRange = 0f..(weatherFrames.size - 1).toFloat(),
                                    steps = (weatherFrames.size - 2).coerceAtLeast(0),
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Text(
                                frameTimeLabel(curFrame?.timeSec),
                                color = Color(0xFFB6C2D2), fontSize = 11.sp,
                                modifier = Modifier.width(54.dp),
                            )
                        }
                    }
                }
            }
            RadarDrawer(
                viewModel, settings, sortedAircraft, approaches, selectedHex,
                totalCount = aircraft.size,
                expanded = listExpanded,
                onExpandedChange = { listExpanded = it },
                // Flat top when the weather timeline already caps the sheet stack above it.
                roundedTop = !(weather == 1 && weatherFrames.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A standard wind barb at an airport: the staff points to where the wind comes from,
 * with a pennant per 50 kt, a full feather per 10 and a half per 5. Calm is a circle.
 * [scopeRot] is the scope's heading-up rotation (radians).
 */
private fun DrawScope.drawWindBarb(
    at: Offset,
    st: Metar.Station,
    scopeRot: Double,
    density: Float,
    paint: android.graphics.Paint,
) {
    val c = Color(0xFFE7ECF6)
    val speed = st.speedKt
    val dir = st.dirDeg
    if (speed < 3 || dir == null) {
        drawCircle(c, 5f * density, at, style = Stroke(1.4f * density))
    } else {
        val th = Math.toRadians(dir.toDouble()) - scopeRot
        val ux = sin(th).toFloat()
        val uy = -cos(th).toFloat()
        val staff = 24f * density
        val tip = Offset(at.x + ux * staff, at.y + uy * staff)
        drawLine(c, at, tip, strokeWidth = 1.5f * density)
        // Feathers go on the clockwise side of the staff, from the tip inwards.
        val px = -uy
        val py = ux
        var remaining = ((speed + 2) / 5) * 5
        var pos = 0f
        val step = 4.5f * density
        val len = 9f * density
        while (remaining >= 50) {
            val b0 = Offset(tip.x - ux * pos, tip.y - uy * pos)
            val b1 = Offset(b0.x - ux * step, b0.y - uy * step)
            val path = Path().apply {
                moveTo(b0.x, b0.y); lineTo(b0.x + px * len, b0.y + py * len); lineTo(b1.x, b1.y); close()
            }
            drawPath(path, c)
            pos += step * 1.4f; remaining -= 50
        }
        while (remaining >= 10) {
            val b = Offset(tip.x - ux * pos, tip.y - uy * pos)
            drawLine(c, b, Offset(b.x + px * len + ux * 3f * density, b.y + py * len + uy * 3f * density), strokeWidth = 1.5f * density)
            pos += step; remaining -= 10
        }
        if (remaining >= 5) {
            if (pos == 0f) pos = step
            val b = Offset(tip.x - ux * pos, tip.y - uy * pos)
            drawLine(c, b, Offset(b.x + px * len / 2 + ux * 1.5f * density, b.y + py * len / 2 + uy * 1.5f * density), strokeWidth = 1.5f * density)
        }
    }
    drawCircle(c, 2.5f * density, at)
    val label = st.icao + "  " + when {
        speed < 3 -> "calm"
        dir == null -> "VRB ${speed} kt"
        else -> compassLabel(dir.toFloat()) + " $speed" + (st.gustKt?.let { "G$it" } ?: "") + " kt"
    }
    paint.color = c.copy(alpha = 0.9f).toArgb()
    drawContext.canvas.nativeCanvas.drawText(label, at.x + 6f * density, at.y + 14f * density, paint)
}

/** Range steps (nm) that "Fit" and double-tap snap to. */
private val NICE_RANGES_NM = floatArrayOf(5f, 10f, 15f, 20f, 25f, 30f, 40f, 50f, 60f, 80f, 100f, 120f, 150f).toList()

/** A glass status chip centred under the top controls. */
@Composable
private fun RadarChip(text: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .glass(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(text, color = Hud.Text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small glass button for radar view options (label detail, units, fit). */
@Composable
private fun SmallToggle(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Hud.TextDim,
        fontSize = 11.5.sp,
        modifier = Modifier
            .glass(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Frame time relative to now, e.g. "now", "−40m" (past), "+10m" (nowcast). */
private fun frameTimeLabel(timeSec: Long?): String {
    if (timeSec == null) return ""
    val mins = ((System.currentTimeMillis() / 1000 - timeSec) / 60).toInt()
    return when {
        mins in -1..1 -> "now"
        mins > 0 -> "−${mins}m"
        else -> "+${-mins}m"
    }
}

// Colour ramp matching the RainViewer rain scheme the layer renders with.
private val RAIN_RAMP = listOf(
    Color(0xFF88DDEE), Color(0xFF00A3E0), Color(0xFF0088BF),
    Color(0xFFFFE000), Color(0xFFFF9600), Color(0xFFD20000),
)

/** Compact rain-intensity legend. */
@Composable
private fun WeatherLegend(modifier: Modifier = Modifier) {
    val labelColor = Color(0xFFB6C2D2)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text("Rain  ", color = labelColor, fontSize = 9.sp)
        Text("Light ", color = labelColor, fontSize = 9.sp)
        Row(
            modifier = Modifier.clip(RoundedCornerShape(2.dp)),
        ) {
            for (c in RAIN_RAMP) {
                Box(Modifier.width(16.dp).height(8.dp).background(c))
            }
        }
        Text(" Heavy", color = labelColor, fontSize = 9.sp)
    }
}

@Composable
private fun BasemapChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.minimumInteractiveComponentSize().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF101418) else Color(0xFFD8E0F0),
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) Color(0xFFFFD54F) else Color(0x22FFFFFF))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Continuous altitude→colour ramp (low green → high pink), like FR24/tar1090. */
private val ALT_STOPS = arrayOf(
    0f to Color(0xFF66BB6A),
    12000f to Color(0xFF26C6DA),
    24000f to Color(0xFF5C9DFF),
    36000f to Color(0xFFB388FF),
    48000f to Color(0xFFFF7AA2),
)

internal fun altColor(ft: Double): Color {
    val f = ft.toFloat()
    if (f <= ALT_STOPS.first().first) return ALT_STOPS.first().second
    for (i in 0 until ALT_STOPS.size - 1) {
        val (a, ca) = ALT_STOPS[i]
        val (b, cb) = ALT_STOPS[i + 1]
        if (f <= b) return lerp(ca, cb, ((f - a) / (b - a)).coerceIn(0f, 1f))
    }
    return ALT_STOPS.last().second
}

/** (East, North) kilometres of (lat,lon) relative to the observer. */
private fun groundEnu(obsLat: Double, obsLon: Double, lat: Double, lon: Double): Pair<Float, Float> {
    val la1 = Math.toRadians(obsLat)
    val la2 = Math.toRadians(lat)
    val dLon = Math.toRadians(lon - obsLon)
    val y = sin(dLon) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    val bearing = atan2(y, x)
    val hav = sin((la2 - la1) / 2).pow(2) + cos(la1) * cos(la2) * sin(dLon / 2).pow(2)
    val dist = 2.0 * 6371.0 * asin(sqrt(hav).coerceIn(0.0, 1.0))
    return (dist * sin(bearing)).toFloat() to (dist * cos(bearing)).toFloat()
}

/** A point fraction [f] along the great circle between two lat/lon points (degrees). */
private fun gcPoint(lat1: Double, lon1: Double, lat2: Double, lon2: Double, f: Double): Pair<Double, Double> {
    val p1 = Math.toRadians(lat1)
    val l1 = Math.toRadians(lon1)
    val p2 = Math.toRadians(lat2)
    val l2 = Math.toRadians(lon2)
    val d = 2.0 * asin(
        sqrt(sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin((l2 - l1) / 2).pow(2)).coerceIn(0.0, 1.0),
    )
    if (d == 0.0) return lat1 to lon1
    val a = sin((1 - f) * d) / sin(d)
    val b = sin(f * d) / sin(d)
    val x = a * cos(p1) * cos(l1) + b * cos(p2) * cos(l2)
    val y = a * cos(p1) * sin(l1) + b * cos(p2) * sin(l2)
    val z = a * sin(p1) + b * sin(p2)
    return Math.toDegrees(atan2(z, sqrt(x * x + y * y))) to Math.toDegrees(atan2(y, x))
}

/** Blip/row colour: emergency red, gray on the ground, else the altitude ramp. */
internal fun aircraftColor(ac: AircraftRender): Color = when {
    ac.isEmergency -> Color(0xFFFF5252)
    ac.altitudeMeters < 30.0 -> Color(0xFF9AA4B0)
    else -> altColor(ac.altitudeMeters / 0.3048)
}
