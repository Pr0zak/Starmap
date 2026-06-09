package com.starmap.app.astro

import org.json.JSONArray
import java.io.InputStream

/**
 * The 110 Messier deep-sky objects (galaxies, nebulae and clusters), parsed from
 * the bundled assets/messier.json. Positions are fixed equatorial unit vectors,
 * precomputed once.
 */
object Messier {

    class Dso(
        val name: String,      // e.g. "M31"
        val common: String,    // e.g. "Andromeda" (may be empty)
        val type: String,      // e.g. "Spiral galaxy"
        val category: String,  // galaxy | cluster | nebula | other
        val mag: Float,
        val eqVec: FloatArray,
    )

    fun parse(input: InputStream): List<Dso> {
        val arr = JSONArray(input.bufferedReader().use { it.readText() })
        val out = ArrayList<Dso>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val v = AstroMath.equatorialToVec(o.getDouble("ra"), o.getDouble("dec"))
            out.add(
                Dso(
                    o.getString("n"),
                    o.optString("c", ""),
                    o.optString("t", "Deep-sky object"),
                    o.optString("cat", "other"),
                    o.optDouble("m", 99.0).toFloat(),
                    floatArrayOf(v[0].toFloat(), v[1].toFloat(), v[2].toFloat()),
                ),
            )
        }
        return out
    }
}
