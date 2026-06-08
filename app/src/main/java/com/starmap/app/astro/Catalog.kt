package com.starmap.app.astro

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * A loaded star catalog stored in flat arrays for cache-friendly rendering.
 * Equatorial unit vectors are precomputed once at load time; the render loop only
 * needs cheap dot products against the current view basis.
 */
class StarCatalog(
    val count: Int,
    val raDeg: FloatArray,
    val decDeg: FloatArray,
    val mag: FloatArray,
    val ci: FloatArray,
    /** count*3 equatorial unit vectors (x,y,z interleaved). */
    val eqVec: FloatArray,
    val labels: Map<Int, String>,
) {
    companion object {
        /** Parse a columnar catalog JSON (see tools/build_catalog.py). */
        fun parse(input: InputStream): StarCatalog {
            val text = input.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val ra = root.getJSONArray("ra")
            val dec = root.getJSONArray("dec")
            val mag = root.getJSONArray("mag")
            val ci = root.optJSONArray("ci")
            val n = ra.length()

            val raA = FloatArray(n)
            val decA = FloatArray(n)
            val magA = FloatArray(n)
            val ciA = FloatArray(n)
            val vec = FloatArray(n * 3)
            for (i in 0 until n) {
                val r = ra.getDouble(i)
                val d = dec.getDouble(i)
                raA[i] = r.toFloat()
                decA[i] = d.toFloat()
                magA[i] = mag.getDouble(i).toFloat()
                ciA[i] = ci?.optDouble(i, 0.0)?.toFloat() ?: 0f
                val v = AstroMath.equatorialToVec(r, d)
                vec[i * 3] = v[0].toFloat()
                vec[i * 3 + 1] = v[1].toFloat()
                vec[i * 3 + 2] = v[2].toFloat()
            }

            val labels = HashMap<Int, String>()
            root.optJSONObject("labels")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    labels[k.toInt()] = obj.getString(k)
                }
            }
            return StarCatalog(n, raA, decA, magA, ciA, vec, labels)
        }
    }
}

/** A constellation stick figure with line segments precomputed as equatorial vectors. */
class Constellation(
    val abbr: String,
    val name: String,
    /** Equatorial unit vector for the label position. */
    val labelVec: FloatArray,
    /** Each entry is a flattened polyline of equatorial vectors: x0,y0,z0,x1,y1,z1,... */
    val segments: List<FloatArray>,
)

object ConstellationCatalog {
    fun parse(input: InputStream): List<Constellation> {
        val text = input.bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        val out = ArrayList<Constellation>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val abbr = o.getString("con")
            val name = o.getString("name")
            val label = o.getJSONArray("label")
            val lv = AstroMath.equatorialToVec(label.getDouble(0), label.getDouble(1))
            val linesJson = o.getJSONArray("lines")
            val segments = ArrayList<FloatArray>(linesJson.length())
            for (s in 0 until linesJson.length()) {
                val seg = linesJson.getJSONArray(s)
                val pts = FloatArray(seg.length() * 3)
                for (p in 0 until seg.length()) {
                    val pt = seg.getJSONArray(p)
                    val v = AstroMath.equatorialToVec(pt.getDouble(0), pt.getDouble(1))
                    pts[p * 3] = v[0].toFloat()
                    pts[p * 3 + 1] = v[1].toFloat()
                    pts[p * 3 + 2] = v[2].toFloat()
                }
                segments.add(pts)
            }
            out.add(
                Constellation(
                    abbr, name,
                    floatArrayOf(lv[0].toFloat(), lv[1].toFloat(), lv[2].toFloat()),
                    segments,
                ),
            )
        }
        return out
    }
}
