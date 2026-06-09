package com.starmap.app.sky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.starmap.app.settings.Settings
import com.starmap.app.settings.SettingsRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.debounce
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.tan

private const val MIN_DEPTH = 0.15f

@OptIn(FlowPreview::class)
@Composable
fun SkyCanvas(viewModel: SkyViewModel, settings: Settings, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val model by viewModel.model

    // Field of view (vertical) is adjustable by pinch; persisted after a pause.
    var fov by remember { mutableFloatStateOf(settings.fovDeg) }
    LaunchedEffectKeyed(settings.fovDeg) { fov = settings.fovDeg }
    LaunchedPersistFov(viewModel, fovProvider = { fov })

    // Manual drag-to-look: a virtual camera the user pans instead of the sensors.
    val manualMode by viewModel.manualMode
    var manualAz by remember { mutableFloatStateOf(0f) }
    var manualAlt by remember { mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(manualMode) {
        if (manualMode) {
            // Start the virtual camera wherever the phone is currently pointing.
            val decl = model?.declinationDeg ?: 0f
            val lt = SkyRender.toTrueNorth(viewModel.orientation.basis.look, decl)
            manualAlt = Math.toDegrees(asin(lt[2].coerceIn(-1f, 1f).toDouble())).toFloat()
            manualAz = (((Math.toDegrees(atan2(lt[0].toDouble(), lt[1].toDouble())) + 360) % 360)).toFloat()
        }
    }

    // Drive ~60fps redraws.
    var frame by remember { mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            frame++
        }
    }

    // Tappable aircraft hit-boxes, refreshed each frame by the draw pass.
    val aircraftHits = remember { mutableListOf<AircraftHit>() }

    // Reusable text paints.
    val starPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val conPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val bodyPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val cardinalPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) fov = (fov / zoom).coerceIn(12f, 90f)
                    if (viewModel.manualMode.value) {
                        val degPerPx = fov / size.height
                        manualAz = (((manualAz - pan.x * degPerPx) % 360f) + 360f) % 360f
                        manualAlt = (manualAlt + pan.y * degPerPx).coerceIn(-89f, 89f)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val hit = aircraftHits.minByOrNull {
                        val dx = it.x - offset.x; val dy = it.y - offset.y
                        dx * dx + dy * dy
                    }
                    val near = hit != null && hypot(hit.x - offset.x, hit.y - offset.y) < 40f * density
                    viewModel.selectAircraft(if (near) hit!!.render else null)
                }
            },
    ) {
        frame // subscribe to the frame clock
        val night = settings.nightMode
        drawRect(if (night) Color.Black else Color(0xFF05070D))

        val m = model ?: return@Canvas
        val look: FloatArray
        val right: FloatArray
        val up: FloatArray
        if (manualMode) {
            val basis = SkyRender.lookBasis(manualAz, manualAlt)
            look = basis[0]; right = basis[1]; up = basis[2]
        } else {
            val b = viewModel.orientation.basis
            look = SkyRender.toTrueNorth(b.look, m.declinationDeg)
            right = SkyRender.toTrueNorth(b.right, m.declinationDeg)
            up = SkyRender.toTrueNorth(b.up, m.declinationDeg)
        }

        val cx = size.width / 2f
        val cy = size.height / 2f
        val focal = (size.height / 2f) / tan(Math.toRadians(fov.toDouble() / 2.0)).toFloat()
        val margin = 64f * density

        // Returns screen x/y in [out], or null if behind / off screen.
        fun project(v: FloatArray, out: FloatArray): Boolean {
            val depth = v[0] * look[0] + v[1] * look[1] + v[2] * look[2]
            if (depth < MIN_DEPTH) return false
            val xc = v[0] * right[0] + v[1] * right[1] + v[2] * right[2]
            val yc = v[0] * up[0] + v[1] * up[1] + v[2] * up[2]
            val sx = cx + (xc / depth) * focal
            val sy = cy - (yc / depth) * focal
            out[0] = sx; out[1] = sy
            return sx >= -margin && sx <= size.width + margin &&
                sy >= -margin && sy <= size.height + margin
        }

        val p = FloatArray(2)
        val q = FloatArray(2)

        // --- Constellation stick figures ---
        if (settings.showConstellations) {
            val lineColor = if (night) Color(0x55AA2222) else Color(0x554060A0)
            for (con in m.constellations) {
                for (seg in con.segments) {
                    var hasPrev = false
                    var px = 0f; var py = 0f
                    var i = 0
                    while (i < seg.size) {
                        val vx = seg[i]; val vy = seg[i + 1]; val vz = seg[i + 2]
                        val depth = vx * look[0] + vy * look[1] + vz * look[2]
                        if (depth >= MIN_DEPTH) {
                            val sx = cx + ((vx * right[0] + vy * right[1] + vz * right[2]) / depth) * focal
                            val sy = cy - ((vx * up[0] + vy * up[1] + vz * up[2]) / depth) * focal
                            if (hasPrev) {
                                drawLine(lineColor, androidx.compose.ui.geometry.Offset(px, py),
                                    androidx.compose.ui.geometry.Offset(sx, sy), strokeWidth = density)
                            }
                            px = sx; py = sy; hasPrev = true
                        } else {
                            hasPrev = false
                        }
                        i += 3
                    }
                }
            }
            if (settings.showConstellationNames) {
                conPaint.textSize = 12f * density
                conPaint.color = (if (night) Color(0xAA993333) else Color(0xAA7090C0)).toArgb()
                for (con in m.constellations) {
                    if (!settings.showBelowHorizon && con.labelEnu[2] < 0f) continue
                    if (project(con.labelEnu, p)) {
                        drawContext.canvas.nativeCanvas.drawText(con.name, p[0], p[1], conPaint)
                    }
                }
            }
        }

        // --- Stars ---
        val magLimit = settings.magnitudeLimit
        val labelLimit = settings.labelMagnitudeLimit
        starPaint.textSize = 12f * density
        for (i in 0 until m.count) {
            val mag = m.starMag[i]
            if (mag > magLimit) continue
            val base = i * 3
            val vz = m.starEnu[base + 2]
            if (!settings.showBelowHorizon && vz < 0f) continue
            val vx = m.starEnu[base]; val vy = m.starEnu[base + 1]
            val depth = vx * look[0] + vy * look[1] + vz * look[2]
            if (depth < MIN_DEPTH) continue
            val sx = cx + ((vx * right[0] + vy * right[1] + vz * right[2]) / depth) * focal
            if (sx < -margin || sx > size.width + margin) continue
            val sy = cy - ((vx * up[0] + vy * up[1] + vz * up[2]) / depth) * focal
            if (sy < -margin || sy > size.height + margin) continue

            val radius = SkyRender.magnitudeRadiusPx(mag, magLimit, density)
            val color = if (night) Color(1f, 0.25f, 0.2f) else SkyRender.starColor(m.starCi[i])
            drawCircle(color, radius, androidx.compose.ui.geometry.Offset(sx, sy))

            if (settings.showStarLabels && mag <= labelLimit) {
                m.labels[i]?.let { label ->
                    starPaint.color = (if (night) Color(0xCCBB4444) else Color(0xCCD8E0F0)).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        label, sx + radius + 3f * density, sy + 4f * density, starPaint,
                    )
                }
            }
        }

        // --- Planets ---
        if (settings.showPlanets) {
            bodyPaint.textSize = 13f * density
            for (pl in m.planets) {
                if (!settings.showBelowHorizon && pl.enu[2] < 0f) continue
                if (project(pl.enu, p)) {
                    val color = if (night) Color(1f, 0.3f, 0.25f) else Color(pl.colorArgb)
                    val r = pl.sizeDp * density
                    drawCircle(color, r, androidx.compose.ui.geometry.Offset(p[0], p[1]))
                    bodyPaint.color = (if (night) Color(0xCCBB4444) else Color(0xFFE8E8F0)).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        pl.name, p[0] + r + 3f * density, p[1] + 4f * density, bodyPaint,
                    )
                }
            }
        }

        // --- Asteroid orbital-track paths ---
        if (m.asteroidPaths.isNotEmpty()) {
            val pathColor = if (night) Color(0x55AA6644) else Color(0x66C8C0A0)
            for (seg in m.asteroidPaths) {
                var hasPrev = false; var px = 0f; var py = 0f
                var i = 0
                while (i < seg.size) {
                    val vx = seg[i]; val vy = seg[i + 1]; val vz = seg[i + 2]
                    val depth = vx * look[0] + vy * look[1] + vz * look[2]
                    if (depth >= MIN_DEPTH) {
                        val sx = cx + ((vx * right[0] + vy * right[1] + vz * right[2]) / depth) * focal
                        val sy = cy - ((vx * up[0] + vy * up[1] + vz * up[2]) / depth) * focal
                        if (hasPrev) {
                            drawLine(pathColor, androidx.compose.ui.geometry.Offset(px, py),
                                androidx.compose.ui.geometry.Offset(sx, sy), strokeWidth = density)
                        }
                        px = sx; py = sy; hasPrev = true
                    } else {
                        hasPrev = false
                    }
                    i += 3
                }
            }
        }

        // --- Asteroids ---
        if (m.asteroids.isNotEmpty()) {
            bodyPaint.textSize = 12f * density
            for (a in m.asteroids) {
                if (!settings.showBelowHorizon && a.enu[2] < 0f) continue
                if (project(a.enu, p)) {
                    val color = if (night) Color(1f, 0.3f, 0.25f) else Color(a.colorArgb)
                    val r = a.sizeDp * density
                    drawCircle(color, r, androidx.compose.ui.geometry.Offset(p[0], p[1]))
                    bodyPaint.color = (if (night) Color(0xAAAA5544) else Color(0xCCD0C8B0)).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        a.name, p[0] + r + 3f * density, p[1] + 4f * density, bodyPaint,
                    )
                }
            }
        }

        // --- Sun ---
        if (settings.showSun) m.sun?.let { sun ->
            if (!(!settings.showBelowHorizon && sun.enu[2] < 0f) && project(sun.enu, p)) {
                val r = 9f * density
                if (!night) {
                    drawCircle(Color(0x33FFD060), r * 3.2f, androidx.compose.ui.geometry.Offset(p[0], p[1]))
                }
                drawCircle(
                    if (night) Color(0xFF884400) else Color(0xFFFFE070),
                    r, androidx.compose.ui.geometry.Offset(p[0], p[1]),
                )
                bodyPaint.textSize = 14f * density
                bodyPaint.color = (if (night) Color(0xFFBB5500) else Color(0xFFFFE070)).toArgb()
                drawContext.canvas.nativeCanvas.drawText("Sun", p[0] + r + 4f * density, p[1], bodyPaint)
            }
        }

        // --- Moon (with phase) ---
        if (settings.showMoon) m.moon?.let { moon ->
            if (!(!settings.showBelowHorizon && moon.enu[2] < 0f) && project(moon.enu, p)) {
                val r = 8f * density
                drawMoon(p[0], p[1], r, moon, sunScreen = m.sun?.let { if (project(it.enu, q)) q else null }, night)
                bodyPaint.textSize = 14f * density
                bodyPaint.color = (if (night) Color(0xFFAA4444) else Color(0xFFE8E8F0)).toArgb()
                drawContext.canvas.nativeCanvas.drawText("Moon", p[0] + r + 4f * density, p[1], bodyPaint)
            }
        }

        // --- Satellites (ISS labelled, Starlink as faint dots) ---
        if (m.satCount > 0) {
            val issColor = if (night) Color(0xFFCC6666) else Color(0xFF66FFCC)
            val slColor = if (night) Color(0x88AA5555) else Color(0x99B0C4FF)
            bodyPaint.textSize = 12f * density
            for (i in 0 until m.satCount) {
                val base = i * 3
                val vx = m.satEnu[base]; val vy = m.satEnu[base + 1]; val vz = m.satEnu[base + 2]
                val depth = vx * look[0] + vy * look[1] + vz * look[2]
                if (depth < MIN_DEPTH) continue
                val sx = cx + ((vx * right[0] + vy * right[1] + vz * right[2]) / depth) * focal
                if (sx < -margin || sx > size.width + margin) continue
                val sy = cy - ((vx * up[0] + vy * up[1] + vz * up[2]) / depth) * focal
                if (sy < -margin || sy > size.height + margin) continue
                if (m.satIsIss[i]) {
                    drawCircle(issColor, 4f * density, androidx.compose.ui.geometry.Offset(sx, sy))
                    bodyPaint.color = issColor.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        m.satNames[i].take(16), sx + 6f * density, sy + 4f * density, bodyPaint,
                    )
                } else {
                    drawCircle(slColor, 1.6f * density, androidx.compose.ui.geometry.Offset(sx, sy))
                }
            }
        }

        // --- Aircraft (live ADS-B): planes amber, helicopters teal, fading trails ---
        aircraftHits.clear()
        if (m.aircraft.isNotEmpty()) {
            val planeColor = if (night) Color(0xFFCC8844) else Color(0xFFFFB060)
            val heliColor = if (night) Color(0xFFAA6699) else Color(0xFF55E0D0)
            bodyPaint.textSize = 11f * density
            for (ac in m.aircraft) {
                val v = ac.enu
                val depth = v[0] * look[0] + v[1] * look[1] + v[2] * look[2]
                if (depth < MIN_DEPTH) continue
                val sx = cx + ((v[0] * right[0] + v[1] * right[1] + v[2] * right[2]) / depth) * focal
                if (sx < -margin || sx > size.width + margin) continue
                val sy = cy - ((v[0] * up[0] + v[1] * up[1] + v[2] * up[2]) / depth) * focal
                if (sy < -margin || sy > size.height + margin) continue
                val heli = ac.isHelicopter
                val color = if (heli) heliColor else planeColor

                if (settings.showAircraftTrails) {
                    val trail = ac.trail
                    val n = trail.size / 3
                    var k = 0
                    while (k < n) {
                        val b = k * 3
                        val tx = trail[b]; val ty = trail[b + 1]; val tz = trail[b + 2]
                        val td = tx * look[0] + ty * look[1] + tz * look[2]
                        if (td >= MIN_DEPTH) {
                            val px = cx + ((tx * right[0] + ty * right[1] + tz * right[2]) / td) * focal
                            val py = cy - ((tx * up[0] + ty * up[1] + tz * up[2]) / td) * focal
                            val a = 0.06f + 0.4f * (k.toFloat() / n) // older = fainter
                            drawCircle(color.copy(alpha = a), 1.7f * density,
                                androidx.compose.ui.geometry.Offset(px, py))
                        }
                        k++
                    }
                }

                val s = 4f * density
                if (heli) {
                    drawCircle(color, 3f * density, androidx.compose.ui.geometry.Offset(sx, sy))
                    drawLine(color, androidx.compose.ui.geometry.Offset(sx - s, sy - s),
                        androidx.compose.ui.geometry.Offset(sx + s, sy - s), strokeWidth = 1.6f * density)
                } else {
                    val marker = Path().apply {
                        moveTo(sx, sy - s); lineTo(sx + s, sy); lineTo(sx, sy + s); lineTo(sx - s, sy); close()
                    }
                    drawPath(marker, color)
                }
                aircraftHits.add(AircraftHit(sx, sy, ac))

                if (settings.showAircraftLabels && ac.callsign.isNotBlank()) {
                    bodyPaint.color = color.toArgb()
                    val ft = (ac.altitudeMeters / 0.3048).toInt()
                    drawContext.canvas.nativeCanvas.drawText(
                        "${ac.callsign}  ${ft}ft", sx + 6f * density, sy + 4f * density, bodyPaint,
                    )
                }
            }
        }

        // --- Horizon line ---
        if (settings.showHorizon) {
            val horizonColor = if (night) Color(0xAA662222) else Color(0xAA2E7D4F)
            val hv = FloatArray(3)
            var prevOk = false
            var ppx = 0f; var ppy = 0f
            var az = 0
            while (az <= 360) {
                val a = Math.toRadians(az.toDouble())
                hv[0] = kotlin.math.sin(a).toFloat()
                hv[1] = cos(a).toFloat()
                hv[2] = 0f
                val depth = hv[0] * look[0] + hv[1] * look[1] + hv[2] * look[2]
                if (depth >= MIN_DEPTH) {
                    val sx = cx + ((hv[0] * right[0] + hv[1] * right[1]) / depth) * focal
                    val sy = cy - ((hv[0] * up[0] + hv[1] * up[1]) / depth) * focal
                    if (prevOk) {
                        drawLine(horizonColor, androidx.compose.ui.geometry.Offset(ppx, ppy),
                            androidx.compose.ui.geometry.Offset(sx, sy), strokeWidth = 2f * density)
                    }
                    ppx = sx; ppy = sy; prevOk = true
                } else {
                    prevOk = false
                }
                az += 2
            }
        }

        // --- Cardinal direction markers ---
        if (settings.showCardinals) {
            cardinalPaint.textSize = 16f * density
            cardinalPaint.color = (if (night) Color(0xFFCC4444) else Color(0xFFB0C4DE)).toArgb()
            val dirs = listOf(
                "N" to floatArrayOf(0f, 1f, 0f), "E" to floatArrayOf(1f, 0f, 0f),
                "S" to floatArrayOf(0f, -1f, 0f), "W" to floatArrayOf(-1f, 0f, 0f),
                "NE" to floatArrayOf(0.707f, 0.707f, 0f), "SE" to floatArrayOf(0.707f, -0.707f, 0f),
                "SW" to floatArrayOf(-0.707f, -0.707f, 0f), "NW" to floatArrayOf(-0.707f, 0.707f, 0f),
            )
            for ((label, v) in dirs) {
                if (project(v, p)) {
                    drawContext.canvas.nativeCanvas.drawText(label, p[0], p[1], cardinalPaint)
                }
            }
        }

        // --- Search target: reticle when on screen, edge arrow when not ---
        viewModel.searchTarget.value?.let { target ->
            val tenu = resolveTargetEnu(m, target)
            if (tenu != null) {
                val hi = if (night) Color(0xFFFF6B6B) else Color(0xFFFFD54F)
                val depth = tenu[0] * look[0] + tenu[1] * look[1] + tenu[2] * look[2]
                val xcam = tenu[0] * right[0] + tenu[1] * right[1] + tenu[2] * right[2]
                val ycam = tenu[0] * up[0] + tenu[1] * up[1] + tenu[2] * up[2]
                val sx = cx + (xcam / depth) * focal
                val sy = cy - (ycam / depth) * focal
                val onScreen = depth > MIN_DEPTH && sx in 0f..size.width && sy in 0f..size.height
                if (onScreen) {
                    val r = 22f * density
                    val o = androidx.compose.ui.geometry.Offset(sx, sy)
                    drawCircle(hi, r, o, style = Stroke(width = 2.5f * density))
                    fun tick(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(
                        hi, androidx.compose.ui.geometry.Offset(x0, y0),
                        androidx.compose.ui.geometry.Offset(x1, y1), strokeWidth = 2.5f * density,
                    )
                    tick(sx, sy - r - 7f * density, sx, sy - r + 3f * density)
                    tick(sx, sy + r - 3f * density, sx, sy + r + 7f * density)
                    tick(sx - r - 7f * density, sy, sx - r + 3f * density, sy)
                    tick(sx + r - 3f * density, sy, sx + r + 7f * density, sy)
                    bodyPaint.textSize = 15f * density
                    bodyPaint.color = hi.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        target.label, sx + r + 8f * density, sy + 5f * density, bodyPaint,
                    )
                } else {
                    val dx = xcam; val dy = -ycam
                    val len = hypot(dx, dy)
                    if (len > 1e-4f) {
                        val ux = dx / len; val uy = dy / len
                        val rad = min(cx, cy) - 36f * density
                        val ax = cx + ux * rad; val ay = cy + uy * rad
                        val s = 16f * density
                        val bx = ax - ux * s; val by = ay - uy * s
                        val px = -uy; val py = ux
                        val path = Path().apply {
                            moveTo(ax, ay)
                            lineTo(bx + px * s * 0.6f, by + py * s * 0.6f)
                            lineTo(bx - px * s * 0.6f, by - py * s * 0.6f)
                            close()
                        }
                        drawPath(path, hi)
                        cardinalPaint.textSize = 14f * density
                        cardinalPaint.color = hi.toArgb()
                        drawContext.canvas.nativeCanvas.drawText(
                            target.label,
                            ax - ux * 24f * density,
                            ay - uy * 24f * density + 5f * density,
                            cardinalPaint,
                        )
                    }
                }
            }
        }
    }
}

/** Draw the Moon disk with its illuminated fraction, lit side facing the Sun. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoon(
    x: Float,
    y: Float,
    r: Float,
    moon: MoonEnu,
    sunScreen: FloatArray?,
    night: Boolean,
) {
    val lit = if (night) Color(0xFF884444) else Color(0xFFEAEAF2)
    val shadow = if (night) Color(0xFF200808) else Color(0xFF1A1C24)
    val center = androidx.compose.ui.geometry.Offset(x, y)
    drawCircle(lit, r, center)

    val k = moon.illuminatedFraction.coerceIn(0f, 1f)
    if (k >= 0.99f) return

    // Direction from the Moon away from the Sun (where the shadow sits).
    var dx = 0f; var dy = 1f
    if (sunScreen != null) {
        val ux = x - sunScreen[0]
        val uy = y - sunScreen[1]
        val len = hypot(ux, uy)
        if (len > 1e-3f) { dx = ux / len; dy = uy / len }
    }
    val offset = 2f * r * (1f - k)
    val shadowCenter = androidx.compose.ui.geometry.Offset(x + dx * offset, y + dy * offset)
    val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
    clipPath(clip) {
        drawCircle(shadow, r, shadowCenter)
    }
}

// --- Small composable helpers kept out of the main body for readability ---

@Composable
private fun LaunchedEffectKeyed(key: Any?, block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { block() }
}

@OptIn(FlowPreview::class)
@Composable
private fun LaunchedPersistFov(viewModel: SkyViewModel, fovProvider: () -> Float) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        snapshotFlow { fovProvider() }
            .debounce(500)
            .collect { viewModel.setFloat(SettingsRepository.FloatSetting.Fov, it) }
    }
}

/** A tappable aircraft position recorded during the draw pass. */
private class AircraftHit(val x: Float, val y: Float, val render: AircraftRender)
