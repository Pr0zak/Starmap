package com.starmap.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Surface
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    LaunchedEffect(weather) {
        if (weather == 0) return@LaunchedEffect
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
    val weatherFrames = weatherMaps?.frames(weather) ?: emptyList()
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
    LaunchedEffect(Unit) {
        while (true) {
            azState.floatValue = viewModel.orientation.basis.azimuthDeg
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
    var altMin by remember { mutableFloatStateOf(settings.radarAltMinFt) }
    var altMax by remember { mutableFloatStateOf(settings.radarAltMaxFt) }
    LaunchedEffect(Unit) {
        snapshotFlow { altMin to altMax }.collectLatest { (lo, hi) ->
            delay(400)
            viewModel.setFloat(FloatSetting.RadarAltMin, lo)
            viewModel.setFloat(FloatSetting.RadarAltMax, hi)
        }
    }

    val aircraft = model?.aircraft ?: emptyList()
    // Sort once per aircraft-list change rather than every frame (rangeKm is stable
    // between fetches); reuse one Path for the chevrons.
    val sortedAircraft = remember(aircraft) { aircraft.sortedBy { it.rangeKm } }
    val acPath = remember { Path() }
    val landmarks = model?.landmarks ?: emptyList()
    val selAc by viewModel.selectedAircraft
    val selectedHex = selAc?.icaoHex
    // The big detail card can be dismissed while keeping the aircraft selected
    // (highlighted, with its on-scope data block). Re-shown when the selection changes.
    var detailsHidden by remember { mutableStateOf(false) }
    LaunchedEffect(selectedHex) { detailsHidden = false }

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
    val labelPaint = remember {
        android.graphics.Paint().apply { isAntiAlias = true; textSize = 10f * density }
    }
    val ringPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 9f * density
            color = android.graphics.Color.argb(170, 130, 200, 150)
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
                host = weatherMaps?.host,
                frames = weatherFrames,
                frameIndex = frameIdx,
                onBuffered = { loaded, total -> weatherLoaded = loaded; weatherTotal = total },
            )
        }
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { viewModel.selectAircraft(nearestTo(it)); detailsHidden = false },
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
            hits.clear()
            labelPaint.textSize = 10f * density
            val az = azState.floatValue
            val maxRangeKm = rangeNm * 1.852f
            val a = if (headingUp) Math.toRadians(az.toDouble()) else 0.0
            val ca = cos(a)
            val sa = sin(a)
            val geom = radarGeometry(size.width, size.height, density)
            val cx = geom.cx
            val cy = geom.cy
            val r = geom.r
            val scale = r / maxRangeKm

            fun proj(eKm: Float, nKm: Float): Offset {
                val e2 = (eKm * ca - nKm * sa).toFloat()
                val n2 = (eKm * sa + nKm * ca).toFloat()
                return Offset(cx + e2 * scale, cy - n2 * scale)
            }

            // Range rings + distance labels.
            val ringColor = Color(0xFF1E5F37)
            for (i in 1..4) {
                drawCircle(ringColor, r * i / 4f, Offset(cx, cy), style = Stroke(1f * density))
                drawContext.canvas.nativeCanvas.drawText(
                    "${(rangeNm * i / 4f).roundToInt()}",
                    cx + 3f * density, cy - r * i / 4f - 2f * density, ringPaint,
                )
            }

            // Cardinal spokes + letters (rotate with heading-up).
            ringPaint.textAlign = android.graphics.Paint.Align.CENTER
            val rim = r + 13f * density
            for ((lbl, brg) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
                val ang = Math.toRadians(brg) - a
                drawLine(
                    Color(0x3320E060), Offset(cx, cy),
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
                    Color(0x3320E060),
                    Offset(cx + (sin(ang) * (r - 5f * density)).toFloat(), cy - (cos(ang) * (r - 5f * density)).toFloat()),
                    Offset(cx + (sin(ang) * r).toFloat(), cy - (cos(ang) * r).toFloat()),
                    strokeWidth = 1f * density,
                )
            }
            drawContext.canvas.nativeCanvas.drawText(
                "HDG ${az.roundToInt() % 360}°", cx, cy - r - 22f * density, ringPaint,
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${rangeNm.roundToInt()} nm  ·  pinch to zoom", cx, cy + r + 20f * density, ringPaint,
            )
            ringPaint.textAlign = android.graphics.Paint.Align.LEFT

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
                    labelPaint.color = col.copy(alpha = 0.8f).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(lm.name, o.x + 4f * density, o.y + 3f * density, labelPaint)
                }
            }

            // Aircraft: dead-reckoned blip, trail, velocity leader, chevron.
            val acPos = FloatArray(3)
            val acNow = System.currentTimeMillis()
            val taken = ArrayList<android.graphics.RectF>(48)
            val selRoute = viewModel.selectedRoute.value
            for (ac in if (showAircraft) sortedAircraft else emptyList()) {
                val ft = ac.altitudeMeters / 0.3048
                if (ft < altMin || ft > altMax) continue
                ac.positionInto(acNow, acPos)
                val eKm = acPos[0]
                val nKm = acPos[1]
                if (hypot(eKm, nKm) > maxRangeKm) continue
                val o = proj(eKm, nKm)
                hits.add(o to ac)
                val col = aircraftColor(ac)

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
                            drawLine(col.copy(alpha = 0.08f + 0.30f * frac), p0, to, strokeWidth = 1.2f * density)
                        }
                        prev = to
                    }
                    j += 3
                }
                prev?.let { drawLine(col.copy(alpha = 0.38f), it, o, strokeWidth = 1.2f * density) }

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
                }
                val vr = when {
                    ac.verticalRateFpm > 200 -> " ↑"
                    ac.verticalRateFpm < -200 -> " ↓"
                    else -> ""
                }
                if (ac.icaoHex == selectedHex) {
                    // Full data block beside the selected blip.
                    val lines = buildList {
                        add(ac.callsign.ifBlank { ac.registration.ifBlank { "Aircraft" } })
                        add("${ac.typeCode.ifBlank { "—" }}  FL${(ft / 100).roundToInt()}$vr")
                        add("${ac.groundSpeedKts.roundToInt()} kt  ·  ${(ac.rangeKm * 0.539957).roundToInt()} nm")
                        selRoute?.let {
                            val rt = "${it.origin}→${it.destination}"
                            if (rt.length > 1) add(rt)
                        }
                    }
                    labelPaint.textSize = 11f * density
                    var bw = 0f
                    for (ln in lines) bw = maxOf(bw, labelPaint.measureText(ln))
                    val lh = 13f * density
                    val bx = (o.x + 10f * density)
                        .coerceAtMost(size.width - bw - 6f * density).coerceAtLeast(4f * density)
                    val by = (o.y - 8f * density - lines.size * lh).coerceAtLeast(12f * density)
                    drawRect(
                        Color(0xD8090D12),
                        topLeft = Offset(bx - 4f * density, by - 11f * density),
                        size = Size(bw + 8f * density, lines.size * lh + 6f * density),
                    )
                    labelPaint.color = Color(0xFFFFE082).toArgb()
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
                    val text = "$name  FL${(ft / 100).roundToInt()}$vr"
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = "Exit radar", tint = Color(0xFFD8E0F0))
                }
                Text("RADAR", color = Color(0xFFB6C2D2), fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showBasemapMenu = !showBasemapMenu }) {
                    Icon(
                        Icons.Filled.Layers,
                        contentDescription = "Basemap layer",
                        tint = if (basemap != 0) Color(0xFFFFD54F) else Color(0xFFD8E0F0),
                    )
                }
                IconButton(onClick = { viewModel.setBool(BoolSetting.RadarAircraft, !showAircraft) }) {
                    Icon(
                        Icons.Filled.Flight, contentDescription = "Aircraft",
                        tint = if (showAircraft) Color(0xFFFFD54F) else Color(0xFF6B7686),
                    )
                }
                IconButton(onClick = { viewModel.setBool(BoolSetting.RadarLandmarks, !showPois) }) {
                    Icon(
                        Icons.Filled.Place, contentDescription = "Landmarks",
                        tint = if (showPois) Color(0xFFFFD54F) else Color(0xFF6B7686),
                    )
                }
                IconButton(onClick = { viewModel.setBool(BoolSetting.RadarHeadingUp, !headingUp) }) {
                    Icon(
                        Icons.Filled.Explore, contentDescription = "Heading up",
                        tint = if (headingUp) Color(0xFFFFD54F) else Color(0xFFD8E0F0),
                    )
                }
            }
            if (showBasemapMenu) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).width(232.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {},
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xF2161E2A),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("BASEMAP", color = Color(0xFF8B97A8), fontSize = 10.sp)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                BasemapChip("Off", basemap == 0) { viewModel.setRadarBasemap(0) }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Satellite", basemap == 1) { viewModel.setRadarBasemap(1) }
                                Spacer(Modifier.width(6.dp))
                                BasemapChip("Streets", basemap == 2) { viewModel.setRadarBasemap(2) }
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
                                if (weatherFrames.isEmpty()) {
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
            selAc?.takeIf { !detailsHidden }?.let { ac ->
                Spacer(Modifier.height(8.dp))
                val route by viewModel.selectedRoute
                val photo by viewModel.selectedPhoto
                val photoStatus by viewModel.photoStatus
                val followHex by viewModel.followAircraftHex
                AircraftInfoCard(
                    ac, route, photo, photoStatus,
                    tracking = followHex == ac.icaoHex,
                    onTrack = { viewModel.followAircraft(if (followHex == ac.icaoHex) null else ac.icaoHex) },
                    onClose = { detailsHidden = true },
                )
            }
        }

        AltitudeTape(
            altMin = altMin, altMax = altMax, valueRange = 0f..60000f,
            onChange = { lo, hi -> altMin = lo; altMax = hi },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.6f).padding(end = 2.dp),
        )

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (weather != 0) {
                WeatherLegend(modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            }
            if (basemap != 0 || weather != 0) {
                Text(
                    buildString {
                        if (basemap != 0) append("Map © Esri")
                        if (weather != 0) {
                            if (isNotEmpty()) append("   ·   ")
                            append("Weather © RainViewer")
                        }
                    },
                    color = Color(0x99B6C2D2), fontSize = 9.sp,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            // Weather timeline: pinned above the drawer so it stays visible while the
            // animation plays on the full scope.
            if (weather != 0 && weatherFrames.isNotEmpty()) {
                Surface(color = Color(0xF20B0F15), modifier = Modifier.fillMaxWidth()) {
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
                viewModel, aircraft, altMin, altMax, selectedHex,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
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

/**
 * Altimeter-style vertical tape on the right edge: a gradient bar (the altitude
 * colour legend) with two handles bracketing the visible altitude band. Drag a
 * handle to filter; the band brightens, everything outside dims.
 */
@Composable
private fun AltitudeTape(
    altMin: Float,
    altMax: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var dragMin by remember { mutableStateOf(true) }
    val lo = valueRange.start
    val span = (valueRange.endInclusive - lo).coerceAtLeast(1f)
    fun frac(v: Float) = ((v - lo) / span).coerceIn(0f, 1f)
    val valPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 9f * density
            textAlign = android.graphics.Paint.Align.RIGHT
            color = android.graphics.Color.argb(255, 255, 213, 79)
        }
    }
    Box(
        modifier = modifier.width(46.dp).pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { off ->
                    val h = size.height.toFloat()
                    val yMin = (1f - frac(altMin)) * h
                    val yMax = (1f - frac(altMax)) * h
                    dragMin = abs(off.y - yMin) <= abs(off.y - yMax)
                },
            ) { change, _ ->
                change.consume()
                val h = size.height.toFloat().coerceAtLeast(1f)
                val v = lo + (1f - (change.position.y / h).coerceIn(0f, 1f)) * span
                if (dragMin) onChange(v.coerceAtMost(altMax), altMax)
                else onChange(altMin, v.coerceAtLeast(altMin))
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height
            val barW = 9f * density
            val barLeft = size.width - barW - 3f * density
            val radius = CornerRadius(barW / 2f, barW / 2f)
            val brush = Brush.verticalGradient(
                colors = listOf(
                    altColor(valueRange.endInclusive.toDouble()),
                    altColor((lo + span * 0.75f).toDouble()),
                    altColor((lo + span * 0.5f).toDouble()),
                    altColor((lo + span * 0.25f).toDouble()),
                    altColor(lo.toDouble()),
                ),
                startY = 0f, endY = h,
            )
            // Dim full bar = the legend / out-of-band region.
            drawRoundRect(brush, Offset(barLeft, 0f), Size(barW, h), radius, alpha = 0.22f)
            // Bright active band between the handles.
            val yTop = (1f - frac(altMax)) * h
            val yBot = (1f - frac(altMin)) * h
            drawRoundRect(brush, Offset(barLeft, yTop), Size(barW, (yBot - yTop).coerceAtLeast(2f)), radius)
            // Handles + value labels.
            for ((y, v) in listOf(yTop to altMax, yBot to altMin)) {
                drawLine(
                    Color(0xFFFFD54F), Offset(barLeft - 4f * density, y),
                    Offset(barLeft + barW + 3f * density, y), strokeWidth = 2f * density,
                )
                drawCircle(Color(0xFFFFD54F), 3.5f * density, Offset(barLeft - 4f * density, y))
                drawContext.canvas.nativeCanvas.drawText(
                    "${(v / 1000).roundToInt()}k", barLeft - 9f * density, y + 3.5f * density, valPaint,
                )
            }
        }
    }
}

/** Bottom drawer: a handle to expand/collapse, then the aircraft list by distance. */
@Composable
private fun RadarDrawer(
    viewModel: SkyViewModel,
    aircraft: List<AircraftRender>,
    altMin: Float,
    altMax: Float,
    selectedHex: String?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val sorted = aircraft
        .filter { val f = it.altitudeMeters / 0.3048; f >= altMin && f <= altMax }
        .sortedBy { it.rangeKm }
    Surface(
        color = Color(0xF20B0F15),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.animateContentSize().fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.width(36.dp).height(4.dp)
                        .background(Color(0x44FFFFFF), RoundedCornerShape(2.dp)),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(bottom = if (expanded) 8.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${sorted.size} aircraft", color = Color(0xFFEAF0F8),
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                sorted.firstOrNull()?.let {
                    Text(
                        "  ·  nearest ${(it.rangeKm * 0.539957).roundToInt()} nm",
                        color = Color(0xFF8A96A6), fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "Hide ▾" else "List ▸", color = Color(0xFFFFD54F), fontSize = 12.sp)
            }
            if (expanded) {
                HorizontalDivider(color = Color(0x14FFFFFF))
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(sorted) { ac ->
                        AircraftRow(ac, ac.icaoHex == selectedHex) { viewModel.selectAircraft(ac) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AircraftRow(ac: AircraftRender, selected: Boolean, onClick: () -> Unit) {
    val nm = (ac.rangeKm * 0.539957).roundToInt()
    val ft = (ac.altitudeMeters / 0.3048).roundToInt()
    val gs = ac.groundSpeedKts.roundToInt()
    val arrow = if (ac.verticalRateFpm > 100) " ↑" else if (ac.verticalRateFpm < -100) " ↓" else ""
    val name = ac.callsign.ifBlank { ac.registration.ifBlank { ac.typeCode.ifBlank { "Aircraft" } } }
    val dot = aircraftColor(ac)
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) Color(0x26FFD54F) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 8.dp).size(7.dp).background(dot, CircleShape))
        Text(
            name, color = if (ac.isEmergency) Color(0xFFFF6B6B) else Color(0xFFE6ECF5),
            fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        Text("$ft ft$arrow", color = Color(0xFFB6C2D2), fontSize = 12.sp, modifier = Modifier.width(70.dp))
        Text("$gs kt", color = Color(0xFF8A96A6), fontSize = 12.sp, modifier = Modifier.width(50.dp))
        Text("$nm nm", color = Color(0xFFD8E0F0), fontSize = 12.sp, modifier = Modifier.width(50.dp))
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

private fun altColor(ft: Double): Color {
    val f = ft.toFloat()
    if (f <= ALT_STOPS.first().first) return ALT_STOPS.first().second
    for (i in 0 until ALT_STOPS.size - 1) {
        val (a, ca) = ALT_STOPS[i]
        val (b, cb) = ALT_STOPS[i + 1]
        if (f <= b) return lerp(ca, cb, ((f - a) / (b - a)).coerceIn(0f, 1f))
    }
    return ALT_STOPS.last().second
}

/** Blip/row colour: emergency red, gray on the ground, else the altitude ramp. */
private fun aircraftColor(ac: AircraftRender): Color = when {
    ac.isEmergency -> Color(0xFFFF5252)
    ac.altitudeMeters < 30.0 -> Color(0xFF9AA4B0)
    else -> altColor(ac.altitudeMeters / 0.3048)
}
