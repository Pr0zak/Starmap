package com.starmap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starmap.app.aircraft.RadarKind
import com.starmap.app.sky.AircraftRender
import com.starmap.app.sky.RadarMath
import com.starmap.app.sky.SeenLog
import com.starmap.app.sky.positionInto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Side view under the scope: every plane by distance (x) and altitude (y), like an
 * approach chart, with a short tick showing whether it's climbing or descending.
 * Arrivals stepping down and departures climbing out show up as slopes.
 */
@Composable
internal fun RadarProfile(
    aircraft: List<AircraftRender>,
    maxRangeKm: Float,
    unit: RadarMath.Unit,
    selectedHex: String?,
    frame: () -> Long,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * density
            color = android.graphics.Color.argb(170, 139, 151, 168)
        }
    }
    val pos = remember { FloatArray(3) }
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC0B111B)),
    ) {
        frame() // redraw with the scope
        val left = 34f * density
        val bottom = size.height - 14f * density
        val top = 8f * density
        val right = size.width - 8f * density
        // Altitude axis to 40,000 ft, or higher if something is up there.
        val maxFt = maxOf(40_000.0, (aircraft.maxOfOrNull { it.altitudeMeters / 0.3048 } ?: 0.0) * 1.05)
        for (k in 0..4) {
            val ft = maxFt * k / 4
            val y = bottom - (bottom - top) * k / 4f
            drawLine(Color(0x14FFFFFF), Offset(left, y), Offset(right, y))
            drawContext.canvas.nativeCanvas.drawText(
                if (k == 0) "GND" else "${(ft / 1000).roundToInt()}k", 4f * density, y + 3f * density, paint,
            )
        }
        drawContext.canvas.nativeCanvas.drawText(
            "distance →  ${unit.format(maxRangeKm.toDouble())}", right - paint.measureText("distance →  ${unit.format(maxRangeKm.toDouble())}"),
            size.height - 2f * density, paint,
        )
        val now = System.currentTimeMillis()
        for (ac in aircraft) {
            ac.positionInto(now, pos)
            val d = hypot(pos[0], pos[1])
            if (d > maxRangeKm) continue
            val ft = ac.altitudeMeters / 0.3048
            val x = left + (right - left) * (d / maxRangeKm)
            val y = (bottom - (bottom - top) * (ft / maxFt)).toFloat()
            val c = aircraftColor(ac)
            val selected = ac.icaoHex == selectedHex
            drawCircle(c, (if (selected) 4.5f else 3.2f) * density, Offset(x, y))
            if (selected) drawCircle(Hud.Gold, 8f * density, Offset(x, y), style = Stroke(1.5f * density))
            val vr = ac.verticalRateFpm
            if (kotlin.math.abs(vr) > 300) {
                val dy = (if (vr > 0) -1 else 1) * 7f * density
                drawLine(c, Offset(x, y), Offset(x + 8f * density, y + dy), strokeWidth = 1.4f * density)
            }
        }
    }
}

/** "Seen today": everything the radar has logged since midnight, on this phone only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeenTodaySheet(log: SeenLog, unit: RadarMath.Unit, onClose: () -> Unit) {
    val day = remember { log.today() }
    val hm = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val hourFmt = remember { SimpleDateFormat("h a", Locale.getDefault()) }
    InWindowSheet(onDismiss = onClose) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
        ) {
            Text("Seen today", color = Hud.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            val kinds = day.entries.values.groupingBy { it.kind }.eachCount()
            Text(
                "${day.entries.size} aircraft" +
                    listOfNotNull(
                        kinds[RadarKind.HELICOPTER]?.let { "$it helicopters" },
                        kinds[RadarKind.MILITARY]?.let { "$it military" },
                        day.busiestHour?.let {
                            "busiest around " + hourFmt.format(Date(day.date.atTime(it, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
                        },
                    ).joinToString("") { " · $it" },
                color = Hud.TextDim, fontSize = 13.sp,
            )
            if (day.entries.isEmpty()) {
                Text(
                    "Nothing logged yet. Starmap records aircraft while the radar or the sky's aircraft layer is on.",
                    color = Hud.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
                )
                return@Column
            }

            // Traffic per hour.
            Spacer(Modifier.height(14.dp))
            Text("PER HOUR", color = Color(0xFF8B97A8), fontSize = 10.sp, letterSpacing = 1.sp)
            val max = (day.perHour.maxOrNull() ?: 0).coerceAtLeast(1)
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (h in 0 until 24) {
                    val n = day.perHour[h]
                    Box(
                        Modifier.weight(1f).fillMaxHeight(if (n == 0) 0.04f else 0.1f + 0.9f * n / max)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(if (n == 0) Color(0x22FFFFFF) else Hud.Gold.copy(alpha = 0.85f)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                for (l in listOf("12 AM", "6 AM", "12 PM", "6 PM")) {
                    Text(l, color = Hud.TextDim.copy(alpha = 0.6f), fontSize = 9.sp, modifier = Modifier.weight(1f))
                }
            }

            val rare = day.rareTypes
            if (rare.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("ONE-OFF TYPES TODAY", color = Color(0xFF8B97A8), fontSize = 10.sp, letterSpacing = 1.sp)
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (t in rare.take(24)) {
                        Text(
                            t, color = Hud.Text, fontSize = 12.sp,
                            modifier = Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("NEAREST PASSES", color = Color(0xFF8B97A8), fontSize = 10.sp, letterSpacing = 1.sp)
            for (e in day.entries.values.sortedBy { it.closestKm }.take(30)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.callsign.ifBlank { e.registration.ifBlank { e.hex.uppercase() } },
                            color = Hud.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            listOf(e.type.ifBlank { "—" }, hm.format(Date(e.firstSeen)) + "–" + hm.format(Date(e.lastSeen)))
                                .joinToString(" · "),
                            color = Hud.TextDim, fontSize = 12.sp,
                        )
                    }
                    Text(unit.format(e.closestKm), color = Hud.GoldSoft, fontSize = 13.sp, modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            Text(
                "Kept on this phone for 7 days. Nothing is uploaded.",
                color = Hud.TextDim.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
