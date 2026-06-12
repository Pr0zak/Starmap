package com.starmap.app.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlin.math.cos
import kotlin.math.ln

/**
 * Animated weather layer (rain radar or cloud satellite) drawn over the basemap and
 * under the scope. The RainViewer frame is the map's *base* tile source — it returns
 * transparent tiles where there's no precipitation, so with a transparent loading
 * background only the weather shows, compositing over the basemap below.
 *
 * RainViewer data tops out at ~z7, so the map renders at z7 in a smaller viewport and
 * is scaled up to fill the scope (low-res, but correctly aligned with the range rings).
 * [mode] is 1 = rain, 2 = clouds.
 */
@Composable
fun RadarWeatherLayer(
    latitude: Double,
    longitude: Double,
    maxRangeKm: Float,
    headingUp: Boolean,
    bearing: () -> Float,
    mode: Int,
    opacity: Float,
    host: String?,
    framePath: String?,
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
            setBackgroundColor(AndroidColor.TRANSPARENT)
            overlayManager.tilesOverlay.loadingBackgroundColor = AndroidColor.TRANSPARENT
            overlayManager.tilesOverlay.loadingLineColor = AndroidColor.TRANSPARENT
            setOnTouchListener { _, _ -> true }
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    // Each frame gets its own per-timestamp source so frames cache independently.
    LaunchedEffect(host, framePath, mode) {
        val h = host
        val p = framePath
        if (h != null && p != null) {
            mapView.setTileSource(WeatherTiles.tileSource(h, p, rain = mode == 1))
            mapView.invalidate()
        }
    }

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
        val desiredZoom = (ln(156543.03392 * cos(Math.toRadians(latitude)) / metersPerPixel) / ln(2.0))
            .coerceIn(3.0, 12.0)
        // Render at <= z7 (where RainViewer has data) in a smaller viewport, then scale
        // it up so the real tiles fill the scope at the correct geographic scale.
        val weatherZoom = minOf(desiredZoom, 7.0)
        val scaleFactor = Math.pow(2.0, desiredZoom - weatherZoom).toFloat()

        // Fill the whole screen: a square viewport (the longer screen dimension)
        // rendered at z7 and scaled up so the real tiles cover the screen with no
        // black borders, centred on the observer.
        val side = maxOf(w, h)
        Box(
            Modifier.fillMaxSize().alpha(opacity),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { mapView },
                update = { mv ->
                    mv.controller.setZoom(weatherZoom)
                    mv.setExpectedCenter(GeoPoint(latitude, longitude))
                    mv.invalidate()
                },
                modifier = Modifier
                    .size((side / scaleFactor / density).dp)
                    .graphicsLayer(scaleX = scaleFactor, scaleY = scaleFactor),
            )
        }
    }
}
