package com.starmap.app.astro

import com.starmap.app.astro.AstroMath.DEG2RAD
import com.starmap.app.astro.AstroMath.RAD2DEG
import com.starmap.app.astro.AstroMath.norm360
import org.json.JSONArray
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Geocentric positions of bright comets from osculating orbital elements
 * (bundled in assets/comets.json, sourced from Stellarium's data). Comets are
 * given by perihelion distance q + time of perihelion Tp, and their orbits are
 * often near-parabolic (e ≈ 1) or hyperbolic, so positions are propagated with a
 * universal-variable two-body solver (Stumpff functions) that handles every
 * conic type uniformly — accurate to a few arc-minutes near the apparition,
 * which is plenty for pointing.
 */
object Comets {

    /** Gaussian gravitational constant √(GM_sun) in AU^1.5 / day. */
    private const val K = 0.01720209895

    class Element(
        val name: String,
        val q: Double,   // perihelion distance (AU)
        val e: Double,   // eccentricity
        val i: Double,   // inclination (deg)
        val om: Double,  // longitude of ascending node (deg)
        val w: Double,   // argument of perihelion (deg)
        val tp: Double,  // time of perihelion passage (Julian Day)
        val m1: Double,  // total absolute magnitude
        val k: Double,   // activity slope parameter (Stellarium's "slope_parameter")
    )

    data class Comet(
        val name: String,
        val raDeg: Double,
        val decDeg: Double,
        val distanceAu: Double,
        val magnitude: Double,
        val sizeDp: Float,
    )

    fun parse(input: InputStream): List<Element> {
        val arr = JSONArray(input.bufferedReader().use { it.readText() })
        val out = ArrayList<Element>(arr.length())
        for (j in 0 until arr.length()) {
            val o = arr.getJSONObject(j)
            out.add(
                Element(
                    o.getString("name"), o.getDouble("q"), o.getDouble("e"), o.getDouble("i"),
                    o.getDouble("om"), o.getDouble("w"), o.getDouble("tp"),
                    o.optDouble("M1", 12.0), o.optDouble("k", 4.0),
                ),
            )
        }
        return out
    }

    fun positions(elements: List<Element>, jd: Double): List<Comet> {
        val d = AstroMath.schlyterDay(jd)
        val oblecl = (23.4393 - 3.563e-7 * d) * DEG2RAD
        val sun = sunRectEcliptic(d)
        return elements.map { el ->
            val helio = heliocentric(el, jd)
            val xg = helio[0] + sun[0]
            val yg = helio[1] + sun[1]
            val zg = helio[2]
            val xe = xg
            val ye = yg * cos(oblecl) - zg * sin(oblecl)
            val ze = yg * sin(oblecl) + zg * cos(oblecl)
            val ra = norm360(atan2(ye, xe) * RAD2DEG)
            val dec = atan2(ze, hypot(xe, ye)) * RAD2DEG
            val r = sqrt(helio[0] * helio[0] + helio[1] * helio[1] + helio[2] * helio[2])
            val delta = sqrt(xg * xg + yg * yg + zg * zg)
            // Standard comet photometry: m = M1 + 5·log10(Δ) + 2.5·k·log10(r).
            val mag = el.m1 + 5.0 * log10(delta.coerceAtLeast(1e-6)) +
                2.5 * el.k * log10(r.coerceAtLeast(1e-6))
            val size = (4.0 + (8.0 - mag) * 0.45).coerceIn(2.5, 7.0).toFloat()
            Comet(el.name, ra, dec, delta, mag, size)
        }
    }

    /** RA/Dec only (degrees) for one comet at [jd] — used to plot its path. */
    fun raDec(el: Element, jd: Double): DoubleArray {
        val d = AstroMath.schlyterDay(jd)
        val oblecl = (23.4393 - 3.563e-7 * d) * DEG2RAD
        val sun = sunRectEcliptic(d)
        val helio = heliocentric(el, jd)
        val xg = helio[0] + sun[0]
        val yg = helio[1] + sun[1]
        val zg = helio[2]
        val xe = xg
        val ye = yg * cos(oblecl) - zg * sin(oblecl)
        val ze = yg * sin(oblecl) + zg * cos(oblecl)
        return doubleArrayOf(norm360(atan2(ye, xe) * RAD2DEG), atan2(ze, hypot(xe, ye)) * RAD2DEG)
    }

    /** Heliocentric ecliptic rectangular position (AU) at [jd]. */
    private fun heliocentric(el: Element, jd: Double): DoubleArray {
        val dt = jd - el.tp
        // Universal-variable propagation from the perihelion state (r·v = 0 there).
        val alpha = (1.0 - el.e) / el.q          // = 1/a (>0 ellipse, 0 parabola, <0 hyperbola)
        val vp = K * sqrt((1.0 + el.e) / el.q)   // speed at perihelion (AU/day)
        val chi = solveUniversal(dt, el.q, alpha)
        val z = alpha * chi * chi
        val (c, s) = stumpff(z)
        val f = 1.0 - (chi * chi / el.q) * c
        val g = dt - (chi * chi * chi / K) * s
        // Perifocal coordinates: x toward perihelion, y 90° ahead in the orbit plane.
        val xp = f * el.q
        val yp = g * vp

        val nr = el.om * DEG2RAD
        val ir = el.i * DEG2RAD
        val wr = el.w * DEG2RAD
        val cosO = cos(nr); val sinO = sin(nr)
        val cosI = cos(ir); val sinI = sin(ir)
        val cosW = cos(wr); val sinW = sin(wr)
        // Perifocal → heliocentric ecliptic via the P (perihelion) and Q axes.
        val px = cosO * cosW - sinO * sinW * cosI
        val py = sinO * cosW + cosO * sinW * cosI
        val pz = sinW * sinI
        val qx = -cosO * sinW - sinO * cosW * cosI
        val qy = -sinO * sinW + cosO * cosW * cosI
        val qz = cosW * sinI
        return doubleArrayOf(xp * px + yp * qx, xp * py + yp * qy, xp * pz + yp * qz)
    }

    /**
     * Solves the universal Kepler equation for χ given time-from-perihelion [dt].
     * G(χ) = χ³·S(z) + q·χ·(1 − z·S(z)) − K·dt is monotonic in χ, so a
     * safeguarded Newton iteration (with a guaranteed bracket) always converges.
     */
    private fun solveUniversal(dt: Double, q: Double, alpha: Double): Double {
        if (dt == 0.0) return 0.0
        val target = K * dt
        fun gAndR(chi: Double): DoubleArray {
            val z = alpha * chi * chi
            val (c, s) = stumpff(z)
            val g = chi * chi * chi * s + q * chi * (1.0 - z * s) - target
            val r = chi * chi * c + q * (1.0 - z * c) // = G'(χ), the heliocentric radius
            return doubleArrayOf(g, r)
        }
        // Bracket the root: χ shares the sign of dt; G(0) = −target.
        var lo = 0.0
        var hi = if (dt > 0) 1.0 else -1.0
        var gHi = gAndR(hi)[0]
        var guard = 0
        // Widen while same sign as G(0). Stop if the Stumpff terms overflow to a
        // non-finite value (strongly hyperbolic orbits far from perihelion) so we
        // don't fall through with hi = ±Inf and produce NaN RA/Dec.
        while (gHi.isFinite() && gHi * (-target) > 0.0 && guard < 200) {
            hi *= 2.0
            gHi = gAndR(hi)[0]
            guard++
        }
        if (!hi.isFinite()) hi = if (dt > 0) 1e6 else -1e6
        if (hi < lo) { val t = lo; lo = hi; hi = t }
        // Safeguarded Newton ("rtsafe").
        var chi = 0.5 * (lo + hi)
        repeat(100) {
            val gr = gAndR(chi)
            val gVal = gr[0]; val deriv = gr[1]
            if (gVal > 0.0) hi = chi else lo = chi
            val step = if (deriv != 0.0) gVal / deriv else 0.0
            var next = chi - step
            if (next <= lo || next >= hi || step == 0.0) next = 0.5 * (lo + hi)
            if (abs(next - chi) < 1e-11) return next
            chi = next
        }
        return chi
    }

    /** Stumpff functions C(z), S(z) with a series expansion near z = 0. */
    private fun stumpff(z: Double): Pair<Double, Double> = when {
        z > 1e-6 -> {
            val sz = sqrt(z)
            Pair((1.0 - cos(sz)) / z, (sz - sin(sz)) / (sz * sz * sz))
        }
        z < -1e-6 -> {
            val sz = sqrt(-z)
            Pair((cosh(sz) - 1.0) / (-z), (sinh(sz) - sz) / (sz * sz * sz))
        }
        else -> {
            // C = 1/2 − z/24 + z²/720 − … ; S = 1/6 − z/120 + z²/5040 − …
            val c = 0.5 - z / 24.0 + z * z / 720.0
            val s = 1.0 / 6.0 - z / 120.0 + z * z / 5040.0
            Pair(c, s)
        }
    }

    private fun sunRectEcliptic(d: Double): DoubleArray {
        val w = 282.9404 + 4.70935e-5 * d
        val e = 0.016709 - 1.151e-9 * d
        val m = norm360(356.0470 + 0.9856002585 * d)
        val ea = m + RAD2DEG * e * sin(m * DEG2RAD) * (1.0 + e * cos(m * DEG2RAD))
        val er = ea * DEG2RAD
        val xv = cos(er) - e
        val yv = sqrt(1.0 - e * e) * sin(er)
        val v = atan2(yv, xv) * RAD2DEG
        val r = hypot(xv, yv)
        val lon = (v + w) * DEG2RAD
        return doubleArrayOf(r * cos(lon), r * sin(lon))
    }
}
