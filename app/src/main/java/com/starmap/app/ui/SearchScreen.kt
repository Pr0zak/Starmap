package com.starmap.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starmap.app.sky.IdentifiedObject
import com.starmap.app.sky.RiseSet
import com.starmap.app.sky.SearchResult
import com.starmap.app.sky.SkyModel
import com.starmap.app.sky.SkyViewModel
import com.starmap.app.sky.resolveTargetEnu
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt

private val UpGreen = Color(0xFF7FE3A0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(viewModel: SkyViewModel, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { viewModel.search(query) }
    val model by viewModel.model
    val recent by viewModel.recentSearches
    val upNow = remember(model?.timeMillis?.div(60_000)) { viewModel.upNow() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun pick(r: SearchResult) {
        viewModel.rememberSearch(r.display)
        viewModel.selectSearchTarget(r.target)
        onDone()
    }

    DetailScaffold(title = "Search the sky", onBack = onDone) {
        // A Column so the field and the results/hint stack vertically — the scaffold
        // hosts content in a Box, so bare siblings would otherwise overlap.
        Column(modifier = Modifier.fillMaxSize()) {
            GlassSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Star, planet, constellation…",
                fieldModifier = Modifier.focusRequester(focus),
            )

            when {
                query.isBlank() -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                ) {
                    if (upNow.isNotEmpty()) {
                        SectionLabel("UP NOW")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (r in upNow) UpNowChip(r) { pick(r) }
                        }
                    }
                    val recentHits = remember(recent) {
                        recent.mapNotNull { name -> viewModel.search(name).firstOrNull { it.display == name } }
                    }
                    if (recentHits.isNotEmpty()) {
                        SectionLabel("RECENT")
                        for (r in recentHits) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { pick(r) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.History, contentDescription = null, tint = Hud.TextDim, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(r.display, color = Hud.Text, fontSize = 15.sp)
                            }
                        }
                    }
                    if (upNow.isEmpty() && recentHits.isEmpty()) {
                        Hint("Try “Orion”, “Jupiter”, “Betelgeuse”, “Andromeda”, “ISS”…")
                    }
                }
                results.isEmpty() -> Hint("No matches for “$query”")
                else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 6.dp)) {
                    items(results) { r ->
                        ResultRow(r, model) { pick(r) }
                        HorizontalDivider(color = Hud.Hairline.copy(alpha = 0.12f), modifier = Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: SearchResult, model: SkyModel?, onClick: () -> Unit) {
    val obj = remember(r) { asIdentified(r) }
    val (icon, accent) = objectVisual(obj)
    val status = remember(r, model?.timeMillis?.div(60_000)) { model?.let { visibility(it, r) } }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, accent, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(r.display, color = Hud.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Row {
                if (status?.up == true) Text("● Up · ", color = UpGreen, fontSize = 12.5.sp)
                Text(
                    listOfNotNull(status?.text, r.kind).joinToString(" · "),
                    color = Hud.TextDim.copy(alpha = 0.8f), fontSize = 12.5.sp,
                )
            }
        }
    }
}

@Composable
private fun UpNowChip(r: SearchResult, onClick: () -> Unit) {
    val (icon, accent) = objectVisual(asIdentified(r))
    Row(
        modifier = Modifier
            .glass(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(r.display, color = Hud.Text, fontSize = 13.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = Color(0xFF8B97A8), fontSize = 10.5.sp, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

/** Search results only know their kind; map that onto the card's icon/colour scheme. */
private fun asIdentified(r: SearchResult): IdentifiedObject = when (r.display) {
    "Moon" -> IdentifiedObject("Moon", "Moon", "", r.target)
    "Sun" -> IdentifiedObject("Sun", "Star", "", r.target)
    else -> IdentifiedObject(r.display, r.kind, "", r.target)
}

private class Visibility(val up: Boolean, val text: String)

/**
 * Where a result is right now: "52° S" when it's up, otherwise when it next rises.
 * Null when its layer isn't in the current sky model (e.g. comets switched off).
 */
private fun visibility(model: SkyModel, r: SearchResult): Visibility? {
    val enu = resolveTargetEnu(model, r.target) ?: return null
    val alt = Math.toDegrees(asin(enu[2].coerceIn(-1f, 1f).toDouble())).toFloat()
    val az = ((Math.toDegrees(atan2(enu[0].toDouble(), enu[1].toDouble())) + 360.0) % 360.0).toFloat()
    if (alt > 0f) return Visibility(true, "${alt.roundToInt()}° ${compassLabel(az)}")
    val times = RiseSet.forObject(asIdentified(r).copy(altDeg = alt, azDeg = az), model)
    val rises = times?.riseMillis?.let {
        "rises " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(it))
    }
    return Visibility(
        false,
        when {
            times?.neverRises == true -> "never rises here"
            rises != null -> "below horizon · $rises"
            else -> "below horizon"
        },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = Hud.TextDim.copy(alpha = 0.7f),
        modifier = Modifier.padding(16.dp),
    )
}
