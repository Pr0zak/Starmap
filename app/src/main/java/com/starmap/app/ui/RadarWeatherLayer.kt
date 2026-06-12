package com.starmap.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/** Where the shared frame bitmap sits relative to the scope, plus the draw transform. */
private data class WeatherGrid(
    val grid: WeatherTiles.Grid,
    val cx: Float, val cy: Float,
    val observerX: Float, val observerY: Float, // observer's pixel inside the bitmap
    val scaleFactor: Float,
)

/**
 * Animated weather layer. Every frame is pre-rendered to a bitmap up front (keyed by
 * frame path) so playback is smooth and tear-free, then the current frame is drawn on a
 * Canvas — scaled/rotated/positioned to fill the screen aligned with the range rings.
 * [mode] is 1 = rain, 2 = clouds. [onBuffered] reports prefetch progress (loaded, total).
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
    frames: List<WeatherTiles.Frame>,
    frameIndex: Int,
    onBuffered: (loaded: Int, total: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var wg by remember { mutableStateOf<WeatherGrid?>(null) }
    val bitmaps = remember { mutableStateMapOf<String, Bitmap>() }

    val w = sizePx.width.toFloat()
    val h = sizePx.height.toFloat()
    val framesKey = frames.lastOrNull()?.path ?: ""

    // Round the centre to ~1 km so GPS jitter doesn't keep restarting the prefetch.
    LaunchedEffect(sizePx, keyRound(latitude), keyRound(longitude), maxRangeKm, mode, host, framesKey) {
        bitmaps.clear()
        wg = null
        onBuffered(0, frames.size)
        if (w <= 0f || h <= 0f || host == null || frames.isEmpty()) return@LaunchedEffect

        val g = radarGeometry(w, h, density)
        val metersPerPixel = maxRangeKm * 1000.0 / g.r
        val desiredZoom = (ln(156543.03392 * cos(Math.toRadians(latitude)) / metersPerPixel) / ln(2.0))
            .coerceIn(2.0, 12.0)
        // RainViewer only has data to ~z7. Pick a tile zoom that keeps the grid small
        // and within range (instead of always z7, which blew up the grid — and went
        // blank — at large ranges), then scale to the exact scope zoom.
        val tileZoom = floor(desiredZoom).toInt().coerceIn(2, 7)
        val nativeScale = Math.pow(2.0, desiredZoom - tileZoom).toFloat()
        val ratio = WeatherTiles.TILE_OUT_PX / 256.0 // composited tiles are downsampled

        val n = 1 shl tileZoom
        val ogx = (longitude + 180.0) / 360.0 * n * 256.0
        val s = sin(Math.toRadians(latitude))
        val ogy = (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * n * 256.0
        val halfX = w / 2.0 * 1.15 / nativeScale
        val halfY = h / 2.0 * 1.15 / nativeScale
        val txMin = floor((ogx - halfX) / 256.0).toInt()
        val txMax = floor((ogx + halfX) / 256.0).toInt()
        val tyMin = floor((ogy - halfY) / 256.0).toInt()
        val tyMax = floor((ogy + halfY) / 256.0).toInt()
        val grid = WeatherTiles.Grid(tileZoom, txMin, txMax, tyMin, tyMax)
        wg = WeatherGrid(
            grid = grid, cx = g.cx, cy = g.cy,
            observerX = ((ogx - txMin * 256) * ratio).toFloat(),
            observerY = ((ogy - tyMin * 256) * ratio).toFloat(),
            scaleFactor = (nativeScale / ratio).toFloat(),
        )

        var done = 0
        for (f in frames) {
            val bmp = WeatherTiles.loadFrameBitmap(host, f.path, rain = mode == 1, grid = grid)
            if (bmp != null) bitmaps[f.path] = bmp
            done++
            onBuffered(done, frames.size) // count attempts so a failed tile can't stall buffering
        }
    }

    Box(modifier.fillMaxSize().onSizeChanged { sizePx = it }) {
        val grid = wg ?: return@Box
        Canvas(Modifier.fillMaxSize()) {
            val f = frames.getOrNull(frameIndex) ?: return@Canvas
            val bmp = bitmaps[f.path] ?: return@Canvas
            val m = Matrix().apply {
                postTranslate(-grid.observerX, -grid.observerY)
                postScale(grid.scaleFactor, grid.scaleFactor)
                if (headingUp) postRotate(-bearing())
                postTranslate(grid.cx, grid.cy)
            }
            val paint = Paint().apply {
                alpha = (opacity * 255).toInt().coerceIn(0, 255)
                isFilterBitmap = true
            }
            drawContext.canvas.nativeCanvas.drawBitmap(bmp, m, paint)
        }
    }
}

private fun keyRound(v: Double): Double = Math.round(v * 100.0) / 100.0
