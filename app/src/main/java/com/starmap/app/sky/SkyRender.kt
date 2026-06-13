package com.starmap.app.sky

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure, allocation-light helpers shared by the sky renderer.
 */
object SkyRender {

    // Anchor points mapping B-V colour index to an approximate sRGB star colour.
    private val anchors = arrayOf(
        floatArrayOf(-0.40f, 155f, 176f, 255f),
        floatArrayOf(0.00f, 200f, 212f, 255f),
        floatArrayOf(0.30f, 248f, 247f, 255f),
        floatArrayOf(0.58f, 255f, 244f, 234f),
        floatArrayOf(0.81f, 255f, 229f, 207f),
        floatArrayOf(1.40f, 255f, 206f, 178f),
        floatArrayOf(2.00f, 255f, 180f, 150f),
    )

    /** Approximate star colour from its B-V colour index. */
    fun starColor(bv: Float): Color {
        if (bv <= anchors.first()[0]) return rgb(anchors.first())
        if (bv >= anchors.last()[0]) return rgb(anchors.last())
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            if (bv in a[0]..b[0]) {
                val t = (bv - a[0]) / (b[0] - a[0])
                return Color(
                    red = lerp(a[1], b[1], t) / 255f,
                    green = lerp(a[2], b[2], t) / 255f,
                    blue = lerp(a[3], b[3], t) / 255f,
                )
            }
        }
        return Color.White
    }

    private fun rgb(a: FloatArray) = Color(a[1] / 255f, a[2] / 255f, a[3] / 255f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Star dot radius (px) from magnitude; brighter stars are larger. */
    fun magnitudeRadiusPx(mag: Float, magLimit: Float, density: Float): Float {
        val sizeDp = (1.5f + (magLimit - mag) * 0.55f).coerceIn(0.8f, 8.5f)
        return sizeDp * density
    }

    /**
     * Build the [look, right, up] true-north ENU basis for a virtual camera aimed
     * at the given azimuth/altitude (used by manual drag-to-look mode).
     */
    fun lookBasis(azDeg: Float, altDeg: Float): Array<FloatArray> {
        val az = Math.toRadians(azDeg.toDouble())
        val alt = Math.toRadians(altDeg.toDouble())
        val ca = cos(alt); val sa = sin(alt); val sz = sin(az); val cz = cos(az)
        val look = floatArrayOf((ca * sz).toFloat(), (ca * cz).toFloat(), sa.toFloat())
        val up = floatArrayOf((-sa * sz).toFloat(), (-sa * cz).toFloat(), ca.toFloat())
        val right = floatArrayOf(
            look[1] * up[2] - look[2] * up[1],
            look[2] * up[0] - look[0] * up[2],
            look[0] * up[1] - look[1] * up[0],
        )
        return arrayOf(look, right, up)
    }

    /**
     * Rotate a magnetic-frame ENU vector about the Up(Z) axis by [declinationDeg]
     * so its azimuth becomes true-north referenced. Returns a new array.
     */
    fun toTrueNorth(v: FloatArray, declinationDeg: Float): FloatArray {
        val out = FloatArray(3)
        val d = Math.toRadians(declinationDeg.toDouble())
        toTrueNorthInto(v, cos(d).toFloat(), sin(d).toFloat(), out)
        return out
    }

    /** Allocation-free [toTrueNorth] writing into [out], with precomputed cos/sin. */
    fun toTrueNorthInto(v: FloatArray, cosDecl: Float, sinDecl: Float, out: FloatArray) {
        out[0] = v[0] * cosDecl + v[1] * sinDecl
        out[1] = -v[0] * sinDecl + v[1] * cosDecl
        out[2] = v[2]
    }
}
