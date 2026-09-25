package com.starmap.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starmap.app.aircraft.RadarKind
import com.starmap.app.settings.Settings
import com.starmap.app.settings.SettingsRepository.IntSetting
import com.starmap.app.sky.AircraftRender
import com.starmap.app.sky.RadarMath
import com.starmap.app.sky.SkyViewModel
import kotlin.math.roundToInt

/**
 * The radar's bottom drawer. Collapsed: how many planes, what they're doing, and an
 * altitude histogram that doubles as the colour key. Open: search, sort and kind
 * filters, then the list.
 */
@Composable
internal fun RadarDrawer(
    viewModel: SkyViewModel,
    settings: Settings,
    aircraft: List<AircraftRender>,
    approaches: Map<String, RadarMath.Approach?>,
    selectedHex: String?,
    totalCount: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    roundedTop: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val unit = RadarMath.Unit.of(settings.radarUnits)
    val sort = settings.radarSort
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val rows = remember(aircraft, approaches, sort, q) {
        val matched = if (q.isEmpty()) aircraft else aircraft.filter { ac ->
            listOf(ac.callsign, ac.registration, ac.typeCode).any { it.contains(q, ignoreCase = true) }
        }
        when (sort) {
            // Planes that will come close first, nearest pass first; the rest by distance.
            1 -> matched.sortedWith(
                compareBy<AircraftRender>({ approaches[it.icaoHex] == null }, {
                    approaches[it.icaoHex]?.distanceKm?.toDouble() ?: it.rangeKm
                }),
            )
            2 -> matched.sortedByDescending { it.altitudeMeters }
            3 -> matched.sortedByDescending { it.groundSpeedKts }
            else -> matched.sortedBy { it.rangeKm }
        }
    }
    val climbing = aircraft.count { it.verticalRateFpm > 300 }
    val descending = aircraft.count { it.verticalRateFpm < -300 }
    val helis = aircraft.count { it.kind == RadarKind.HELICOPTER }

    Box(modifier = modifier.fillMaxWidth().bottomSheet(if (roundedTop) 22.dp else 0.dp)) {
        Column(
            modifier = Modifier.animateContentSize().fillMaxWidth()
                .navigationBarsPadding().padding(horizontal = 14.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(34.dp).height(4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp)))
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${aircraft.size}", color = Hud.Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(" aircraft", color = Hud.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                val summary = buildList {
                    if (totalCount > aircraft.size) add("${totalCount - aircraft.size} filtered")
                    if (climbing > 0) add("$climbing climbing")
                    if (descending > 0) add("$descending descending")
                    if (helis > 0) add("$helis heli" + if (helis > 1) "s" else "")
                }.joinToString(" · ")
                if (summary.isNotEmpty()) {
                    Text(
                        "  ·  $summary", color = Hud.TextDim, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(if (expanded) "Hide ▾" else "List ▸", color = Hud.Gold, fontSize = 12.sp)
            }
            AltitudeHistogram(aircraft, Modifier.fillMaxWidth().padding(bottom = if (expanded) 10.dp else 12.dp))

            if (expanded) {
                GlassSearchField(query, { query = it }, "Callsign, registration or type", sidePadding = 0.dp)
                ChipRow("SORT") {
                    listOf("Distance", "Closest pass", "Altitude", "Speed").forEachIndexed { i, label ->
                        FilterChipSmall(label, sort == i) { viewModel.setInt(IntSetting.RadarSort, i) }
                    }
                }
                ChipRow("SHOW") {
                    for ((bit, label) in KIND_CHIPS) {
                        val on = settings.radarKinds and bit != 0
                        FilterChipSmall(label, on) {
                            viewModel.setInt(IntSetting.RadarKinds, settings.radarKinds xor bit)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
                    val hc = Hud.TextDim.copy(alpha = 0.55f)
                    Text("CALLSIGN", color = hc, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    Text("ALT", color = hc, fontSize = 9.sp, letterSpacing = 1.sp, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                    Text("SPD", color = hc, fontSize = 9.sp, letterSpacing = 1.sp, textAlign = TextAlign.End, modifier = Modifier.width(46.dp))
                    Text(
                        if (sort == 1) "PASSES" else "DIST", color = hc, fontSize = 9.sp, letterSpacing = 1.sp,
                        textAlign = TextAlign.End, modifier = Modifier.width(58.dp),
                    )
                }
                HorizontalDivider(color = Hud.Hairline)
                if (rows.isEmpty()) {
                    Text(
                        if (q.isNotEmpty()) "Nothing matches “$q”." else "No aircraft of the kinds shown.",
                        color = Hud.TextDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(rows, key = { it.icaoHex }) { ac ->
                        val lastKm = if (sort == 1) approaches[ac.icaoHex]?.distanceKm?.toDouble() else ac.rangeKm
                        AircraftRow(ac, ac.icaoHex == selectedHex, lastKm?.let { unit.fromKm(it) }, sort == 1) {
                            viewModel.selectAircraft(ac)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private val KIND_CHIPS = listOf(
    RadarKind.AIRLINER to "Airliners",
    RadarKind.LIGHT to "Light",
    RadarKind.HELICOPTER to "Helis",
    RadarKind.MILITARY to "Military",
    RadarKind.OTHER to "Other",
)

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = Hud.TextDim.copy(alpha = 0.6f), fontSize = 9.5.sp, letterSpacing = 1.sp, modifier = Modifier.width(40.dp))
        content()
    }
}

@Composable
private fun FilterChipSmall(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = if (selected) Hud.Ink else Hud.TextDim,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(Hud.Gold)
                else Modifier.background(Color(0x14FFFFFF)).border(1.dp, Hud.Hairline, shape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

/**
 * How the traffic is spread in altitude: one bar per 6,000 ft band, coloured like the
 * blips, so it's also the colour key.
 */
@Composable
private fun AltitudeHistogram(aircraft: List<AircraftRender>, modifier: Modifier = Modifier) {
    val bins = remember(aircraft) {
        IntArray(10).also { b ->
            for (ac in aircraft) b[((ac.altitudeMeters / 0.3048) / 6000.0).toInt().coerceIn(0, 9)]++
        }
    }
    val max = (bins.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until 10) {
                val c = altColor(i * 6000.0 + 3000.0)
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight(0.18f + 0.82f * bins[i] / max)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(if (bins[i] == 0) c.copy(alpha = 0.25f) else c),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            val lc = Hud.TextDim.copy(alpha = 0.6f)
            Text("GND", color = lc, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("30k", color = lc, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("60k ft", color = lc, fontSize = 9.sp, letterSpacing = 1.sp)
        }
    }
}

/** One list row: colour dot, callsign, ALT/SPD, and distance (or closest pass). */
@Composable
private fun AircraftRow(ac: AircraftRender, selected: Boolean, lastValue: Double?, isPass: Boolean, onClick: () -> Unit) {
    val ft = (ac.altitudeMeters / 0.3048).roundToInt()
    val arrow = if (ac.verticalRateFpm > 100) " ↑" else if (ac.verticalRateFpm < -100) " ↓" else ""
    val name = ac.callsign.ifBlank { ac.registration.ifBlank { ac.typeCode.ifBlank { "Aircraft" } } }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .drawBehind { if (selected) drawRect(Hud.Gold, size = Size(3.dp.toPx(), size.height)) }
            .background(if (selected) Color(0x22FFD54F) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 11.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(aircraftColor(ac)))
        Text(
            name, color = if (ac.isEmergency) Color(0xFFFF6B6B) else Hud.Text,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Text("${"%,d".format(ft)}$arrow", color = Hud.Text, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
        Text("${ac.groundSpeedKts.roundToInt()}", color = Hud.TextDim, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(46.dp))
        Text(
            lastValue?.let { if (it < 10) "%.1f".format(it) else "${it.roundToInt()}" } ?: "—",
            color = if (isPass && lastValue != null) Hud.GoldSoft else Hud.Text,
            fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.width(58.dp),
        )
    }
}
