package com.starmap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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

internal fun radarGeometry(w: Float, h: Float, density: Float): RadarGeom {
    // Full-screen scope: the circle fills the width (the altitude tape overlays the
    // right edge rather than reserving a strip) and uses most of the height. A slight
    // over-scan past the side edges keeps the scope feeling full-bleed.
    val cx = w / 2f
    val cy = h * 0.5f
    val r = minOf(w * 0.53f, h * 0.43f)
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

/**
 * Online raster basemap (satellite or street map) drawn underneath the radar scope.
 *
 * The map is a square the size of the outer range ring, centred on the observer and
 * clipped to a circle so it fills the scope exactly. Its zoom is derived from the
 * scope's metres-per-pixel so a feature at N nm sits on the N-nm ring; when heading-up
 * is on, the map rotates with the phone. [mode] is 1 = satellite, 2 = streets.
 */
@Composable
fun RadarBasemap(
    latitude: Double,
    longitude: Double,
    maxRangeKm: Float,
    headingUp: Boolean,
    bearing: () -> Float,
    mode: Int,
    opacity: Float,
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
        val g = radarGeometry(w, h, density)

        val metersPerPixel = maxRangeKm * 1000.0 / g.r
        val zoom = (ln(156543.03392 * cos(Math.toRadians(latitude)) / metersPerPixel) / ln(2.0))
            .coerceIn(3.0, 19.0)

        AndroidView(
            factory = { mapView },
            update = { mv ->
                mv.setTileSource(if (mode == 2) STREETS else SATELLITE)
                mv.controller.setZoom(zoom)
                mv.setExpectedCenter(GeoPoint(latitude, longitude))
                mv.invalidate()
            },
            // Fill the whole screen (centred on the observer) so there are no black
            // borders; the range rings are drawn as circles on top.
            modifier = Modifier.fillMaxSize().alpha(opacity),
        )
    }
}
