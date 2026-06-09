package com.starmap.app.astro

import com.starmap.app.astro.AstroMath.DEG2RAD
import org.json.JSONArray
import java.io.InputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Milky Way as a faint dot cloud sampled from brightness isophotes
 * (assets/milkyway.json, built from d3-celestial's contours). Each dot carries an
 * equatorial unit vector and a brightness level 1 (faint outer band) … 5 (bright
 * galactic-centre core), so the canvas can draw the galactic glow by reusing the
 * same cheap per-point projection it uses for stars.
 */
class MilkyWay(
    val count: Int,
    val eqVec: FloatArray, // count*3 equatorial unit vectors
    val level: ByteArray,  // count brightness levels 1..5
) {
    companion object {
        fun parse(input: InputStream): MilkyWay {
            val arr = JSONArray(input.bufferedReader().use { it.readText() })
            val n = arr.length() / 3
            val eq = FloatArray(n * 3)
            val lv = ByteArray(n)
            for (i in 0 until n) {
                val ra = arr.getDouble(i * 3) * DEG2RAD
                val dec = arr.getDouble(i * 3 + 1) * DEG2RAD
                val cd = cos(dec)
                eq[i * 3] = (cd * cos(ra)).toFloat()
                eq[i * 3 + 1] = (cd * sin(ra)).toFloat()
                eq[i * 3 + 2] = sin(dec).toFloat()
                lv[i] = arr.getInt(i * 3 + 2).toByte()
            }
            return MilkyWay(n, eq, lv)
        }
    }
}
