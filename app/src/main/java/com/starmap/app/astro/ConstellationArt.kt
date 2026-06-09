package com.starmap.app.astro

import org.json.JSONArray
import java.io.InputStream

/**
 * Constellation artwork (Stellarium's painted figures) ready to be warped onto the
 * sky. Each figure has an image file and three anchors: a fractional position in the
 * image (0..1, top-left origin) and the equatorial unit vector of the star it pins to.
 * The renderer maps the three image points to the three projected star positions with
 * an affine transform.
 */
object ConstellationArt {

    class Art(
        val file: String,
        /** 3 image points as fractions: fx0,fy0, fx1,fy1, fx2,fy2. */
        val imgFrac: FloatArray,
        /** 3 equatorial unit vectors: x0,y0,z0, x1,y1,z1, x2,y2,z2. */
        val eqVec: FloatArray,
    )

    fun parse(input: InputStream): List<Art> {
        val arr = JSONArray(input.bufferedReader().use { it.readText() })
        val out = ArrayList<Art>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val a = o.getJSONArray("a") // 3 anchors, each [fx, fy, ra, dec]
            val frac = FloatArray(6)
            val eq = FloatArray(9)
            for (k in 0 until 3) {
                val an = a.getJSONArray(k)
                frac[k * 2] = an.getDouble(0).toFloat()
                frac[k * 2 + 1] = an.getDouble(1).toFloat()
                val v = AstroMath.equatorialToVec(an.getDouble(2), an.getDouble(3))
                eq[k * 3] = v[0].toFloat()
                eq[k * 3 + 1] = v[1].toFloat()
                eq[k * 3 + 2] = v[2].toFloat()
            }
            out.add(Art(o.getString("f"), frac, eq))
        }
        return out
    }
}
