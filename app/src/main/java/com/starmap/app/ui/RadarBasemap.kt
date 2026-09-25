package com.starmap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlin.math.cos
import kotlin.math.ln

/** Geometry of the radar scope within a [w]×[h] (px) canvas — must match the Canvas. */
internal data class RadarGeom(val cx: Float, val cy: Float, val r: Float)

/**
 * Screen space (px) the scope keeps clear of on each side: the top controls, the
 * collapsed drawer, and in landscape the list panel. Every radar layer uses the same
 * insets so the map, weather and rings line up.
 */
internal data class RadarInsets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

internal fun radarGeometry(w: Float, h: Float, insets: RadarInsets): RadarGeom {
    // The scope fills the free area between the controls and the drawer, nearly
    // edge to edge; ring and compass labels sit just inside the rim.
    val aw = (w - insets.left - insets.right).coerceAtLeast(1f)
    val ah = (h - insets.top - insets.bottom).coerceAtLeast(1f)
    val cx = insets.left + aw / 2f
    val cy = insets.top + ah / 2f
    val r = minOf(aw * 0.46f, ah * 0.47f)
    return RadarGeom(cx, cy, r)
}

// ESRI ArcGIS Online raster tiles use a {z}/{y}/{x} path. Free to use with attribution.
private fun esriTileSource(name: String, service: String) = object : OnlineTileSourceBase(
    name, 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/"),
    "Esri",
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return baseUrl + z + "/" + y + "/" + x
    }
}

private val SATELLITE = esriTileSource("EsriWorldImagery", "World_Imagery")
private val STREETS = esriTileSource("EsriWorldStreetMap", "World_Street_Map")
private val DARK = esriTileSource("EsriDarkGrayBase", "Canvas/World_Dark_Gray_Base")

/**
 * Online raster basemap (satellite or street map) drawn underneath the radar scope.
 *
 * The map is a square the size of the outer range ring, centred on the observer and
 * clipped to a circle so it fills the scope exactly. Its zoom is derived from the
 * scope's metres-per-pixel so a feature at N nm sits on the N-nm ring; when heading-up
 * is on, the map rotates with the phone. [mode] is 1 = satellite, 2 = streets, 3 = dark.
 */
@Composable
internal fun RadarBasemap(
    latitude: Double,
    longitude: Double,
    maxRangeKm: Float,
    headingUp: Boolean,
    bearing: () -> Float,
    mode: Int,
    opacity: Float,
    insets: RadarInsets,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid")
        }
        MapView(context).apply {
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTilesScaledToDpi(false)
            setUseDataConnection(true)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            // Map sits below the scope Canvas, which consumes all gestures.
            setOnTouchListener { _, _ -> true }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    // Drive heading-up rotation off the frame clock so the parent isn't recomposed
    // every frame. North-up keeps the map fixed.
    LaunchedEffect(mapView, headingUp) {
        if (!headingUp) {
            mapView.mapOrientation = 0f
            return@LaunchedEffect
        }
        var last = Float.NaN
        while (true) {
            val b = bearing()
            if (last.isNaN() || kotlin.math.abs(b - last) > 0.5f) {
                mapView.mapOrientation = b
                last = b
            }
            withFrameNanos { }
        }
    }

    Box(modifier.fillMaxSize().onSizeChanged { sizePx = it }) {
        val w = sizePx.width.toFloat()
        val h = sizePx.height.toFloat()
        if (w <= 0f || h <= 0f) return@Box
        val g = radarGeometry(w, h, insets)

        val metersPerPixel = maxRangeKm * 1000.0 / g.r
        val zoom = (ln(156543.03392 * cos(Math.toRadians(latitude)) / metersPerPixel) / ln(2.0))
            .coerceIn(3.0, 19.0)

        // The map view is centred on the observer; move it so that centre sits on the
        // scope's centre, and oversize it by the same amount so no edge shows.
        val dx = g.cx - w / 2f
        val dy = g.cy - h / 2f
        val extraW = with(LocalDensity.current) { (w + 2 * kotlin.math.abs(dx)).toDp() }
        val extraH = with(LocalDensity.current) { (h + 2 * kotlin.math.abs(dy)).toDp() }
        AndroidView(
            factory = { mapView },
            update = { mv ->
                mv.setTileSource(
                    when (mode) {
                        2 -> STREETS
                        3 -> DARK
                        else -> SATELLITE
                    },
                )
                mv.controller.setZoom(zoom)
                mv.setExpectedCenter(GeoPoint(latitude, longitude))
                mv.invalidate()
            },
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(extraW, extraH)
                .graphicsLayer { translationX = dx; translationY = dy }
                .alpha(opacity),
        )
    }
}
