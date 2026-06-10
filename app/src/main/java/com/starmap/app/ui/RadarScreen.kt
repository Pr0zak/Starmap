package com.starmap.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starmap.app.settings.Settings
import com.starmap.app.settings.SettingsRepository.BoolSetting
import com.starmap.app.settings.SettingsRepository.FloatSetting
import com.starmap.app.sky.AircraftRender
import com.starmap.app.sky.SkyModel
import com.starmap.app.sky.SkyViewModel
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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

    val azState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            azState.floatValue = viewModel.orientation.basis.azimuthDeg
            awaitFrame()
        }
    }

    // Local range so pinch is smooth; persisted (debounced) without recomposing.
    var rangeNm by remember { mutableFloatStateOf(settings.radarRangeNm) }
    LaunchedEffect(Unit) {
        snapshotFlow { rangeNm }.collectLatest {
            delay(400)
            viewModel.setFloat(FloatSetting.RadarRange, it)
        }
    }

    val aircraft = model?.aircraft ?: emptyList()
    val landmarks = model?.landmarks ?: emptyList()
    val selAc by viewModel.selectedAircraft
    val selectedHex = selAc?.icaoHex

    val hits = remember { mutableListOf<Pair<Offset, AircraftRender>>() }
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
        Canvas(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        var best: AircraftRender? = null
                        var bestD = 34f * density
                        for ((o, ac) in hits) {
                            val d = hypot(o.x - p.x, o.y - p.y)
                            if (d < bestD) { bestD = d; best = ac }
                        }
                        viewModel.selectAircraft(best)
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) rangeNm = (rangeNm / zoom).coerceIn(5f, 150f)
                    }
                },
        ) {
            hits.clear()
            val az = azState.floatValue
            val maxRangeKm = rangeNm * 1.852f
            val a = if (headingUp) Math.toRadians(az.toDouble()) else 0.0
            val ca = cos(a)
            val sa = sin(a)
            val cx = size.width / 2f
            val cy = size.height * 0.46f
            val r = minOf(size.width * 0.46f, size.height * 0.40f)
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

            // Aircraft: trail, then a chevron along heading, coloured by altitude.
            for (ac in aircraft) {
                val dist = ac.rangeKm.toFloat()
                val eKm = ac.enu[0] * dist
                val nKm = ac.enu[1] * dist
                if (hypot(eKm, nKm) > maxRangeKm) continue
                val o = proj(eKm, nKm)
                hits.add(o to ac)
                val ft = ac.altitudeMeters / 0.3048
                val col = if (ac.isEmergency) {
                    Color(0xFFFF5252)
                } else {
                    when {
                        ft < 1000 -> Color(0xFFBCAAA4)
                        ft < 10000 -> Color(0xFF8BC34A)
                        ft < 20000 -> Color(0xFF4DD0E1)
                        ft < 30000 -> Color(0xFF5C9DFF)
                        else -> Color(0xFFCE93D8)
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
                val tip = dir(theta, s)
                val bl = dir(theta + 2.6, s * 0.85f)
                val brr = dir(theta - 2.6, s * 0.85f)
                val path = Path().apply {
                    moveTo(tip.x, tip.y); lineTo(bl.x, bl.y); lineTo(brr.x, brr.y); close()
                }
                drawPath(path, col)
                if (ac.icaoHex == selectedHex) {
                    drawCircle(Color(0xFFFFD54F), s * 1.9f, o, style = Stroke(1.5f * density))
                }
                labelPaint.color = col.toArgb()
                val name = ac.callsign.ifBlank { ac.typeCode.ifBlank { "?" } }
                drawContext.canvas.nativeCanvas.drawText(
                    "$name  FL${(ft / 100).roundToInt()}", o.x + 7f * density, o.y - 4f * density, labelPaint,
                )
            }
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
            selAc?.let { ac ->
                Spacer(Modifier.height(8.dp))
                val route by viewModel.selectedRoute
                val photo by viewModel.selectedPhoto
                val photoStatus by viewModel.photoStatus
                val followHex by viewModel.followAircraftHex
                AircraftInfoCard(
                    ac, route, photo, photoStatus,
                    tracking = followHex == ac.icaoHex,
                    onTrack = { viewModel.followAircraft(if (followHex == ac.icaoHex) null else ac.icaoHex) },
                    onClose = { viewModel.selectAircraft(null) },
                )
            }
        }

        RadarDrawer(viewModel, aircraft, Modifier.align(Alignment.BottomCenter))
    }
}

/** Bottom drawer: a handle to expand/collapse, then the aircraft list by distance. */
@Composable
private fun RadarDrawer(
    viewModel: SkyViewModel,
    aircraft: List<AircraftRender>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val sorted = aircraft.sortedBy { it.rangeKm }
    Surface(color = Color(0xF20A0E13), modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.animateContentSize().fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.width(38.dp).height(4.dp)
                        .background(Color(0x55FFFFFF), RoundedCornerShape(2.dp)),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val nearest = sorted.firstOrNull()?.let { " · nearest ${(it.rangeKm * 0.539957).roundToInt()} nm" } ?: ""
                Text("${sorted.size} aircraft$nearest", color = Color(0xFFD8E0F0), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "▼ list" else "▲ list", color = Color(0xFFB6C2D2), fontSize = 12.sp)
            }
            if (expanded) {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(sorted) { ac -> AircraftRow(ac) { viewModel.selectAircraft(ac) } }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun AircraftRow(ac: AircraftRender, onClick: () -> Unit) {
    val nm = (ac.rangeKm * 0.539957).roundToInt()
    val ft = (ac.altitudeMeters / 0.3048).roundToInt()
    val gs = ac.groundSpeedKts.roundToInt()
    val arrow = if (ac.verticalRateFpm > 100) "↑" else if (ac.verticalRateFpm < -100) "↓" else "·"
    val name = ac.callsign.ifBlank { ac.registration.ifBlank { ac.typeCode.ifBlank { "Aircraft" } } }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name, color = if (ac.isEmergency) Color(0xFFFF6B6B) else Color(0xFFE6ECF5),
            fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        Text("$ft ft $arrow", color = Color(0xFFB6C2D2), fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Text("$gs kt", color = Color(0xFFB6C2D2), fontSize = 12.sp, modifier = Modifier.width(52.dp))
        Text("$nm nm", color = Color(0xFFD8E0F0), fontSize = 12.sp, modifier = Modifier.width(52.dp))
    }
}
