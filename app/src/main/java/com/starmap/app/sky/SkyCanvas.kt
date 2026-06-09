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
import androidx.compose.ui.geometry.Size
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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private const val MIN_DEPTH = 0.15f

/**
 * Sky-background colour for a given Sun altitude (degrees): night → twilight → day.
 * Kept muted (a deep slate-blue at noon, not a bright sky) so labels and the HUD
 * stay readable against it.
 */
private fun skyTint(sunAltDeg: Float): Color {
    val t = ((sunAltDeg + 18f) / 18f).coerceIn(0f, 1f)
    val s = t * t * (3f - 2f * t)
    // Warm horizon glow peaking around civil twilight (Sun ≈ −2°).
    val warm = (1f - kotlin.math.abs(sunAltDeg + 2f) / 8f).coerceIn(0f, 1f)
    val r = (0.02f + (0.20f - 0.02f) * s + warm * 0.10f).coerceIn(0f, 1f)
    val g = (0.027f + (0.29f - 0.027f) * s + warm * 0.05f).coerceIn(0f, 1f)
    val b = (0.051f + (0.46f - 0.051f) * s).coerceIn(0f, 1f)
    return Color(r, g, b)
}

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

    // Follow: while active, keep the manual camera aimed at the search target.
    val following by viewModel.followActive
    androidx.compose.runtime.LaunchedEffect(following) {
        if (!following) return@LaunchedEffect
        while (true) {
            val mm = viewModel.model.value
            val tt = viewModel.searchTarget.value
            if (mm != null && tt != null) {
                resolveTargetEnu(mm, tt)?.let { enu ->
                    manualAlt = Math.toDegrees(asin(enu[2].coerceIn(-1f, 1f).toDouble())).toFloat()
                    manualAz = (((Math.toDegrees(atan2(enu[0].toDouble(), enu[1].toDouble())) + 360) % 360)).toFloat()
                }
            }
            awaitFrame()
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
    // Latest projection basis, so a tap (or the centre reticle) can identify objects.
    val projState = remember { ProjState() }

    // Live "what's under the centre reticle" identification.
    androidx.compose.runtime.LaunchedEffect(settings.centerIdentify, density) {
        if (!settings.centerIdentify) {
            viewModel.setCenterObject(null)
            return@LaunchedEffect
        }
        var lastName: String? = null
        while (true) {
            val mm = viewModel.model.value
            if (mm != null && projState.look != null) {
                val id = nearestObject(mm, projState, projState.cx, projState.cy, 26f * density)
                if (id?.name != lastName) {
                    lastName = id?.name
                    viewModel.setCenterObject(id)
                }
            }
            kotlinx.coroutines.delay(120)
        }
    }

    // Reusable text paints.
    val starPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val conPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val bodyPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val cardinalPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    // Constellation artwork: a lazily-decoded bitmap cache and a reusable warp matrix.
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val artCache = remember { HashMap<String, android.graphics.Bitmap?>() }
    val artMatrix = remember { android.graphics.Matrix() }
    val artPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) fov = (fov / zoom).coerceIn(12f, 90f)
                    if (viewModel.manualMode.value) {
                        if (viewModel.followActive.value) viewModel.setFollow(false)
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
                    if (hit != null && hypot(hit.x - offset.x, hit.y - offset.y) < 40f * density) {
                        viewModel.selectAircraft(hit.render)
                    } else {
                        val m = viewModel.model.value
                        val id = if (m != null) {
                            nearestObject(m, projState, offset.x, offset.y, 36f * density)
                        } else {
                            null
                        }
                        if (id != null) {
                            viewModel.selectObject(id)
                        } else {
                            viewModel.selectAircraft(null)
                            viewModel.selectObject(null)
                        }
                    }
                }
            },
    ) {
        frame // subscribe to the frame clock
        val night = settings.nightMode
        val arMode = settings.arMode // transparent background: the camera shows through
        val m = model
        if (m == null) {
            if (!arMode) drawRect(if (night) Color.Black else Color(0xFF05070D))
            return@Canvas
        }

        // Realistic sky tint from the Sun's altitude: night → twilight → day.
        val sunAltDeg = m.sun?.let { asin(it.enu[2].coerceIn(-1f, 1f)) * 57.29578f } ?: -90f
        val daylight = !night && !arMode && settings.showDaylightSky
        val skyColor = when {
            daylight -> skyTint(sunAltDeg)
            night -> Color.Black
            else -> Color(0xFF05070D)
        }
        if (!arMode) drawRect(skyColor)
        // How strongly the bright sky washes out faint stars/lines (0 = night, 1 = day).
        val dayWash = if (daylight) {
            val f = ((sunAltDeg + 12f) / 12f).coerceIn(0f, 1f)
            f * f * (3f - 2f * f) * 0.7f // cap so faint objects fade rather than vanish
        } else {
            0f
        }
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

        // Record this frame's projection so taps can identify objects (see tap handler).
        projState.look = look; projState.right = right; projState.up = up
        projState.cx = cx; projState.cy = cy; projState.focal = focal
        projState.width = size.width; projState.height = size.height; projState.margin = margin
        projState.showBelow = settings.showBelowHorizon

        fun drawEnuPolyline(line: FloatArray, color: Color, width: Float) {
            var hasPrev = false; var px = 0f; var py = 0f
            var i = 0
            while (i < line.size) {
                val vx = line[i]; val vy = line[i + 1]; val vz = line[i + 2]
                val depth = vx * look[0] + vy * look[1] + vz * look[2]
                if (depth >= MIN_DEPTH) {
                    val sx = cx + ((vx * right[0] + vy * right[1] + vz * right[2]) / depth) * focal
                    val sy = cy - ((vx * up[0] + vy * up[1] + vz * up[2]) / depth) * focal
                    if (hasPrev) {
                        drawLine(color, androidx.compose.ui.geometry.Offset(px, py),
                            androidx.compose.ui.geometry.Offset(sx, sy), strokeWidth = width)
                    }
                    px = sx; py = sy; hasPrev = true
                } else {
                    hasPrev = false
                }
                i += 3
            }
        }

        // --- Milky Way (soft galactic glow, underneath everything) ---
        if (m.milkyWayEnu.isNotEmpty()) {
            val mwN = m.milkyWayLevel.size
            var i = 0
            while (i < mwN) {
                val b = i * 3
                val vx = m.milkyWayEnu[b]; val vy = m.milkyWayEnu[b + 1]; val vz = m.milkyWayEnu[b + 2]
                if ((settings.showBelowHorizon || vz >= 0f)) {
                    val depth = vx * look[0] + vy * look[1] + vz * look[2]
                    if (depth >= MIN_DEPTH) {
                        val sx = cx + ((vx * right[0] + vy * right[1] + vz * right[2]) / depth) * focal
                        val sy = cy - ((vx * up[0] + vy * up[1] + vz * up[2]) / depth) * focal
                        if (sx >= -margin && sx <= size.width + margin &&
                            sy >= -margin && sy <= size.height + margin
                        ) {
                            val lv = m.milkyWayLevel[i].toInt()
                            val a = 0.06f + lv * 0.03f
                            val rad = (2.0f + lv * 0.6f) * density
                            val col = if (night) Color(0.5f, 0.12f, 0.10f, a) else Color(0.80f, 0.84f, 0.96f, a)
                            drawCircle(col, rad, androidx.compose.ui.geometry.Offset(sx, sy))
                        }
                    }
                }
                i++
            }
        }

        // --- Constellation artwork: warp each figure onto its 3 anchor stars ---
        if (settings.showConstellationArt && m.constellationArt.isNotEmpty()) {
            artPaint.alpha = if (night) 60 else 105
            artPaint.colorFilter = if (night) {
                android.graphics.PorterDuffColorFilter(0xFFCC5544.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY)
            } else {
                null
            }
            val dst = FloatArray(6)
            for (art in m.constellationArt) {
                // Skip only if the whole figure is behind us; otherwise clamp anchors at
                // the near plane so a figure straddling the view edge stretches off-screen
                // instead of popping out entirely.
                var maxDepth = -2f
                var k = 0
                while (k < 3) {
                    val b = k * 3
                    val d = art.anchorEnu[b] * look[0] + art.anchorEnu[b + 1] * look[1] +
                        art.anchorEnu[b + 2] * look[2]
                    if (d > maxDepth) maxDepth = d
                    k++
                }
                if (maxDepth < 0.05f) continue
                k = 0
                while (k < 3) {
                    val b = k * 3
                    val vx = art.anchorEnu[b]; val vy = art.anchorEnu[b + 1]; val vz = art.anchorEnu[b + 2]
                    val depth = vx * look[0] + vy * look[1] + vz * look[2]
                    val d = if (depth < 0.04f) 0.04f else depth
                    dst[k * 2] = cx + ((vx * right[0] + vy * right[1] + vz * right[2]) / d) * focal
                    dst[k * 2 + 1] = cy - ((vx * up[0] + vy * up[1] + vz * up[2]) / d) * focal
                    k++
                }
                val bmp = artCache.getOrPut(art.file) {
                    try {
                        appContext.assets.open("constellation_art/${art.file}").use {
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                            android.graphics.BitmapFactory.decodeStream(it, null, opts)
                        }
                    } catch (e: Exception) {
                        null
                    }
                } ?: continue
                val src = floatArrayOf(
                    art.imgFrac[0] * bmp.width, art.imgFrac[1] * bmp.height,
                    art.imgFrac[2] * bmp.width, art.imgFrac[3] * bmp.height,
                    art.imgFrac[4] * bmp.width, art.imgFrac[5] * bmp.height,
                )
                if (artMatrix.setPolyToPoly(src, 0, dst, 0, 3)) {
                    drawContext.canvas.nativeCanvas.drawBitmap(bmp, artMatrix, artPaint)
                }
            }
        }

        // --- Reference lines (grid under, then equator + ecliptic) ---
        val gridColor = if (night) Color(0x33AA4444) else Color(0x332E5C8A)
        for (gl in m.gridLines) drawEnuPolyline(gl, gridColor, density)
        if (m.equatorLine.isNotEmpty()) {
            drawEnuPolyline(m.equatorLine, if (night) Color(0x99AA4444) else Color(0x884E9BD0), 1.5f * density)
        }
        if (m.eclipticLine.isNotEmpty()) {
            drawEnuPolyline(m.eclipticLine, if (night) Color(0x99BB6644) else Color(0x99D4AF37), 1.5f * density)
        }

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

        // --- Messier deep-sky objects (shape by type) ---
        if (m.messier.isNotEmpty()) {
            bodyPaint.textSize = 11f * density
            for (d in m.messier) {
                if (!settings.showBelowHorizon && d.enu[2] < 0f) continue
                if (!project(d.enu, p)) continue
                val dx = p[0]; val dy = p[1]
                val color = when (d.category) {
                    "galaxy" -> if (night) Color(0xFFBB6666) else Color(0xFFE8A0C8)
                    "cluster" -> if (night) Color(0xFFBBAA66) else Color(0xFFFFE08A)
                    "nebula" -> if (night) Color(0xFF66AAAA) else Color(0xFF8AE0C0)
                    else -> if (night) Color(0xFF999999) else Color(0xFFCCCCCC)
                }
                val r = 4.5f * density
                val o = androidx.compose.ui.geometry.Offset(dx, dy)
                val st = Stroke(1.4f * density)
                when (d.category) {
                    "galaxy" -> drawOval(color, androidx.compose.ui.geometry.Offset(dx - r, dy - r * 0.55f),
                        Size(2 * r, 1.1f * r), style = st)
                    "cluster" -> drawCircle(color, r, o, style = st)
                    "nebula" -> drawRect(color, androidx.compose.ui.geometry.Offset(dx - r, dy - r),
                        Size(2 * r, 2 * r), style = st)
                    else -> drawCircle(color, 2f * density, o)
                }
                bodyPaint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText(d.name, dx + r + 4f * density, dy + 4f * density, bodyPaint)
            }
        }

        // --- Daylight wash: fade the faint sky (stars, lines, Milky Way) by day ---
        if (dayWash > 0.01f) {
            drawRect(skyColor.copy(alpha = dayWash))
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

        // --- Comet orbital-track paths ---
        if (m.cometPaths.isNotEmpty()) {
            val pathColor = if (night) Color(0x55AA7755) else Color(0x6685D6E6)
            for (seg in m.cometPaths) {
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

        // --- Comets (coma + anti-solar tail) ---
        if (m.comets.isNotEmpty()) {
            bodyPaint.textSize = 12f * density
            for (c in m.comets) {
                if (!settings.showBelowHorizon && c.enu[2] < 0f) continue
                if (!project(c.enu, p)) continue
                val hx = p[0]; val hy = p[1]
                val headColor = if (night) Color(0xFFCC6655) else Color(0xFFCFF2FF)
                // Tail: a point ~8° anti-sunward, projected, drawn as a fading taper.
                val cl = cos(0.14); val sl = sin(0.14)
                val tip = floatArrayOf(
                    (c.enu[0] * cl + c.tailEnu[0] * sl).toFloat(),
                    (c.enu[1] * cl + c.tailEnu[1] * sl).toFloat(),
                    (c.enu[2] * cl + c.tailEnu[2] * sl).toFloat(),
                )
                val tn = sqrt(tip[0] * tip[0] + tip[1] * tip[1] + tip[2] * tip[2])
                if (tn > 0f) { tip[0] /= tn; tip[1] /= tn; tip[2] /= tn }
                if (project(tip, q)) {
                    val tx = q[0]; val ty = q[1]
                    val segs = 6
                    for (k in 0 until segs) {
                        val f0 = k / segs.toFloat(); val f1 = (k + 1) / segs.toFloat()
                        drawLine(
                            headColor.copy(alpha = (1f - f0) * 0.5f),
                            androidx.compose.ui.geometry.Offset(hx + (tx - hx) * f0, hy + (ty - hy) * f0),
                            androidx.compose.ui.geometry.Offset(hx + (tx - hx) * f1, hy + (ty - hy) * f1),
                            strokeWidth = ((1f - f0) * 3.5f + 0.8f) * density,
                        )
                    }
                }
                val r = c.sizeDp * density
                drawCircle(headColor.copy(alpha = 0.22f), r * 2.2f, androidx.compose.ui.geometry.Offset(hx, hy))
                drawCircle(headColor, r, androidx.compose.ui.geometry.Offset(hx, hy))
                bodyPaint.color = (if (night) Color(0xAACC6655) else Color(0xCCCFF2FF)).toArgb()
                drawContext.canvas.nativeCanvas.drawText(
                    c.name, hx + r + 3f * density, hy + 4f * density, bodyPaint,
                )
            }
        }

        // --- Meteor shower radiants (starburst marker) ---
        if (m.radiants.isNotEmpty()) {
            val rc = if (night) Color(0xFFCC6677) else Color(0xFF9CFF8A)
            for (rad in m.radiants) {
                if (!settings.showBelowHorizon && rad.enu[2] < 0f) continue
                if (project(rad.enu, p)) {
                    val rx = p[0]; val ry = p[1]
                    val rr = 7f * density
                    var ang = 0
                    while (ang < 360) {
                        val a = Math.toRadians(ang.toDouble())
                        val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                        drawLine(
                            rc, androidx.compose.ui.geometry.Offset(rx + dx * rr * 0.4f, ry + dy * rr * 0.4f),
                            androidx.compose.ui.geometry.Offset(rx + dx * rr, ry + dy * rr),
                            strokeWidth = 1.6f * density,
                        )
                        ang += 45
                    }
                    bodyPaint.textSize = 12f * density
                    bodyPaint.color = rc.toArgb()
                    drawContext.canvas.nativeCanvas.drawText(rad.name, rx + rr + 4f * density, ry, bodyPaint)
                    bodyPaint.textSize = 10f * density
                    bodyPaint.color = (if (night) Color(0xAAAA5566) else Color(0xAA88CC77)).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        rad.sublabel, rx + rr + 4f * density, ry + 12f * density, bodyPaint,
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

                if (settings.showAircraftTrails && ac.trail.size >= 3) {
                    val trail = ac.trail
                    val n = trail.size / 3
                    val steps = 5 // interpolated dots between each pair of samples
                    var k = 0
                    while (k < n) {
                        val b = k * 3
                        val ax = trail[b]; val ay = trail[b + 1]; val az = trail[b + 2]
                        // Connect toward the next sample, or the aircraft itself for the last.
                        val nb = (k + 1) * 3
                        val bx2: Float; val by2: Float; val bz2: Float
                        if (k + 1 < n) {
                            bx2 = trail[nb]; by2 = trail[nb + 1]; bz2 = trail[nb + 2]
                        } else {
                            bx2 = ac.enu[0]; by2 = ac.enu[1]; bz2 = ac.enu[2]
                        }
                        var s = 0
                        while (s < steps) {
                            val t = s.toFloat() / steps
                            var ix = ax + (bx2 - ax) * t
                            var iy = ay + (by2 - ay) * t
                            var iz = az + (bz2 - az) * t
                            val inv = 1f / (sqrt(ix * ix + iy * iy + iz * iz) + 1e-6f)
                            ix *= inv; iy *= inv; iz *= inv
                            val td = ix * look[0] + iy * look[1] + iz * look[2]
                            if (td >= MIN_DEPTH) {
                                val px = cx + ((ix * right[0] + iy * right[1] + iz * right[2]) / td) * focal
                                val py = cy - ((ix * up[0] + iy * up[1] + iz * up[2]) / td) * focal
                                val frac = (k + t) / n // 0 (oldest) … 1 (newest)
                                drawCircle(
                                    color.copy(alpha = 0.14f + 0.55f * frac), 2f * density,
                                    androidx.compose.ui.geometry.Offset(px, py),
                                )
                            }
                            s++
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

        // --- Field-of-view guide rings, centred on the aim point ---
        if (settings.fovCirclesMode > 0) {
            val ringColor = if (night) Color(0x99CC4040) else Color(0x88E8A030)
            val center = androidx.compose.ui.geometry.Offset(cx, cy)
            val radiiDeg = when (settings.fovCirclesMode) {
                1 -> floatArrayOf(0.25f, 1.0f, 2.0f) // Telrad: 0.5°, 2°, 4° fields
                2 -> floatArrayOf(3.25f)             // 6.5° binocular field
                else -> floatArrayOf(0.5f)           // 1° eyepiece field
            }
            for (rd in radiiDeg) {
                val rPx = (focal * tan(Math.toRadians(rd.toDouble()))).toFloat()
                drawCircle(ringColor, rPx, center, style = Stroke(1.4f * density))
            }
            drawCircle(ringColor, 1.5f * density, center)
        }

        // --- Centre reticle: brightens when an object sits under it ---
        if (settings.centerIdentify) {
            val centered = viewModel.centerObject.value != null
            val rc = when {
                centered && night -> Color(0xCCFF7777)
                centered -> Color(0xCCFFE082)
                night -> Color(0x66CC5555)
                else -> Color(0x55C0CCE0)
            }
            val center = androidx.compose.ui.geometry.Offset(cx, cy)
            val rr = 9f * density
            val t = 4f * density
            drawCircle(rc, rr, center, style = Stroke(1.2f * density))
            drawLine(rc, androidx.compose.ui.geometry.Offset(cx - rr - t, cy), androidx.compose.ui.geometry.Offset(cx - rr + t, cy), strokeWidth = 1.2f * density)
            drawLine(rc, androidx.compose.ui.geometry.Offset(cx + rr - t, cy), androidx.compose.ui.geometry.Offset(cx + rr + t, cy), strokeWidth = 1.2f * density)
            drawLine(rc, androidx.compose.ui.geometry.Offset(cx, cy - rr - t), androidx.compose.ui.geometry.Offset(cx, cy - rr + t), strokeWidth = 1.2f * density)
            drawLine(rc, androidx.compose.ui.geometry.Offset(cx, cy + rr - t), androidx.compose.ui.geometry.Offset(cx, cy + rr + t), strokeWidth = 1.2f * density)
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

/** The last frame's projection basis, captured so a tap can re-project sky objects. */
private class ProjState {
    var look: FloatArray? = null
    var right: FloatArray? = null
    var up: FloatArray? = null
    var cx = 0f
    var cy = 0f
    var focal = 0f
    var width = 0f
    var height = 0f
    var margin = 0f
    var showBelow = false

    fun projectAt(arr: FloatArray, base: Int, out: FloatArray): Boolean {
        val lk = look ?: return false
        val rt = right ?: return false
        val u = up ?: return false
        val x = arr[base]; val y = arr[base + 1]; val z = arr[base + 2]
        val depth = x * lk[0] + y * lk[1] + z * lk[2]
        if (depth < MIN_DEPTH) return false
        out[0] = cx + ((x * rt[0] + y * rt[1] + z * rt[2]) / depth) * focal
        out[1] = cy - ((x * u[0] + y * u[1] + z * u[2]) / depth) * focal
        return out[0] >= -margin && out[0] <= width + margin &&
            out[1] >= -margin && out[1] <= height + margin
    }
}

/**
 * Finds the nearest identifiable sky object within [thresh] px of ([ox],[oy]).
 * Index-based so it allocates nothing per candidate (it runs every frame for
 * the centre reticle as well as on tap).
 */
private fun nearestObject(
    m: SkyModel,
    ps: ProjState,
    ox: Float,
    oy: Float,
    thresh: Float,
): IdentifiedObject? {
    val out = FloatArray(2)
    var bestD2 = thresh * thresh
    var best: IdentifiedObject? = null
    fun consider(arr: FloatArray, base: Int, name: String, kind: String, mag: Float?, target: SearchTarget?) {
        if (!ps.showBelow && arr[base + 2] < 0f) return
        if (!ps.projectAt(arr, base, out)) return
        val dx = out[0] - ox
        val dy = out[1] - oy
        val d2 = dx * dx + dy * dy
        if (d2 < bestD2) {
            bestD2 = d2
            best = identify(arr, base, name, kind, mag, target)
        }
    }
    m.sun?.let { consider(it.enu, 0, "Sun", "Star", null, SearchTarget.SpecialT("Sun")) }
    m.moon?.let { consider(it.enu, 0, "Moon", "Moon", null, SearchTarget.SpecialT("Moon")) }
    for (pl in m.planets) consider(pl.enu, 0, pl.name, "Planet", null, SearchTarget.PlanetT(pl.name))
    for (c in m.comets) consider(c.enu, 0, c.name, "Comet", c.magnitude, SearchTarget.CometT(c.name))
    for (a in m.asteroids) consider(a.enu, 0, a.name, "Asteroid", null, SearchTarget.AsteroidT(a.name))
    for (d in m.messier) {
        val label = if (d.common.isBlank()) d.name else "${d.name} · ${d.common}"
        consider(d.enu, 0, label, d.type, d.mag, SearchTarget.MessierT(d.name))
    }
    for (con in m.constellations) {
        consider(con.labelEnu, 0, con.name, "Constellation", null, SearchTarget.ConstellationT(con.name))
    }
    var s = 0
    while (s < m.satCount) {
        consider(m.satEnu, s * 3, m.satNames[s], "Satellite", null, SearchTarget.SpecialT(m.satNames[s]))
        s++
    }
    for ((idx, name) in m.labels) {
        if (idx < 0 || idx >= m.count) continue
        val mag = if (idx < m.starMag.size) m.starMag[idx] else null
        consider(m.starEnu, idx * 3, name, "Star", mag, SearchTarget.StarT(idx, name))
    }
    return best
}

private fun identify(
    arr: FloatArray,
    base: Int,
    name: String,
    kind: String,
    mag: Float?,
    target: SearchTarget?,
): IdentifiedObject {
    val alt = Math.toDegrees(asin(arr[base + 2].coerceIn(-1f, 1f).toDouble()))
    val az = (Math.toDegrees(atan2(arr[base].toDouble(), arr[base + 1].toDouble())) + 360.0) % 360.0
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val compass = dirs[(((az + 22.5) % 360.0) / 45.0).toInt() % 8]
    val magStr = if (mag != null && mag.isFinite() && kind != "Satellite") {
        " · mag %.1f".format(mag)
    } else {
        ""
    }
    val detail = "Alt %.0f° · Az %.0f° %s%s".format(alt, az, compass, magStr)
    return IdentifiedObject(name, kind, detail, target)
}
