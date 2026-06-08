package com.starmap.app.sky

import android.hardware.GeomagneticField
import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.Constellation
import com.starmap.app.astro.Planets
import com.starmap.app.astro.Satellites
import com.starmap.app.astro.StarCatalog
import com.starmap.app.astro.SunMoon
import com.starmap.app.satellite.NamedSat
import com.starmap.app.sensors.LocationProvider
import kotlin.math.sqrt

/** A celestial object reduced to a true-north ENU unit vector ready for projection. */
class BodyEnu(val enu: FloatArray, val label: String)

class MoonEnu(val enu: FloatArray, val illuminatedFraction: Float, val waxing: Boolean, val label: String)

class PlanetEnu(val name: String, val enu: FloatArray, val colorArgb: Long, val sizeDp: Float)

class ConstellationEnu(
    val name: String,
    val labelEnu: FloatArray,
    val segments: List<FloatArray>, // each: x0,y0,z0,x1,y1,z1,... in ENU
)

/**
 * A snapshot of the sky in the observer's local ENU frame for a specific instant
 * and location. Recomputed every couple of seconds; the per-frame render loop only
 * applies the device orientation on top of this.
 */
class SkyModel(
    val count: Int,
    val starEnu: FloatArray,
    val starMag: FloatArray,
    val starCi: FloatArray,
    val labels: Map<Int, String>,
    val constellations: List<ConstellationEnu>,
    val planets: List<PlanetEnu>,
    val sun: BodyEnu?,
    val moon: MoonEnu?,
    /** count*3 ENU vectors for visible satellites. */
    val satEnu: FloatArray,
    val satNames: List<String>,
    val satIsIss: BooleanArray,
    val declinationDeg: Float,
    val location: LocationProvider.Fix,
    val timeMillis: Long,
) {
    val satCount: Int get() = satNames.size
}

object SkyBuilder {

    fun build(
        catalog: StarCatalog,
        constellations: List<Constellation>,
        fix: LocationProvider.Fix,
        timeMillis: Long,
        includeConstellations: Boolean,
        includePlanets: Boolean,
        satellites: List<NamedSat>,
        showBelowHorizon: Boolean,
    ): SkyModel {
        val jd = AstroMath.julianDay(timeMillis)
        val lst = AstroMath.lstDegrees(jd, fix.longitude)
        val basis = AstroMath.enuBasis(lst, fix.latitude)

        val n = catalog.count
        val starEnu = FloatArray(n * 3)
        val tmp = DoubleArray(3)
        for (i in 0 until n) {
            tmp[0] = catalog.eqVec[i * 3].toDouble()
            tmp[1] = catalog.eqVec[i * 3 + 1].toDouble()
            tmp[2] = catalog.eqVec[i * 3 + 2].toDouble()
            starEnu[i * 3] = AstroMath.dot(tmp, basis.east).toFloat()
            starEnu[i * 3 + 1] = AstroMath.dot(tmp, basis.north).toFloat()
            starEnu[i * 3 + 2] = AstroMath.dot(tmp, basis.up).toFloat()
        }

        val sunEq = SunMoon.sun(jd)
        val sunVec = AstroMath.equatorialToVec(sunEq.raDeg, sunEq.decDeg)
        val sun = BodyEnu(toEnu(sunVec, basis), "Sun")

        val moonEq = SunMoon.moon(jd, fix.latitude, lst)
        val moonVec = AstroMath.equatorialToVec(moonEq.raDeg, moonEq.decDeg)
        val phase = SunMoon.moonPhase(jd)
        val moon = MoonEnu(
            toEnu(moonVec, basis),
            phase.illuminatedFraction.toFloat(),
            phase.waxing,
            "Moon",
        )

        val planets = if (includePlanets) {
            Planets.positions(jd).map { p ->
                val v = AstroMath.equatorialToVec(p.raDeg, p.decDeg)
                PlanetEnu(p.name, toEnu(v, basis), p.colorArgb, p.sizeDp)
            }
        } else {
            emptyList()
        }

        val cons = if (includeConstellations) {
            constellations.map { c ->
                ConstellationEnu(
                    name = c.name,
                    labelEnu = toEnu(
                        doubleArrayOf(
                            c.labelVec[0].toDouble(),
                            c.labelVec[1].toDouble(),
                            c.labelVec[2].toDouble(),
                        ),
                        basis,
                    ),
                    segments = c.segments.map { seg ->
                        val out = FloatArray(seg.size)
                        var p = 0
                        while (p < seg.size) {
                            tmp[0] = seg[p].toDouble()
                            tmp[1] = seg[p + 1].toDouble()
                            tmp[2] = seg[p + 2].toDouble()
                            out[p] = AstroMath.dot(tmp, basis.east).toFloat()
                            out[p + 1] = AstroMath.dot(tmp, basis.north).toFloat()
                            out[p + 2] = AstroMath.dot(tmp, basis.up).toFloat()
                            p += 3
                        }
                        out
                    },
                )
            }
        } else {
            emptyList()
        }

        // Satellites: propagate each TLE and convert to a local ENU direction.
        val satCoords = ArrayList<Float>()
        val satNames = ArrayList<String>()
        val satIss = ArrayList<Boolean>()
        if (satellites.isNotEmpty()) {
            val gmstRad = Math.toRadians(AstroMath.gmstDegrees(jd))
            val obsEcef = Satellites.observerEcef(fix.latitude, fix.longitude, fix.altitude)
            for (sat in satellites) {
                val tsince = (jd - sat.sgp4.tle.jdEpoch) * 1440.0
                val teme = sat.sgp4.positionTeme(tsince) ?: continue
                val enu = Satellites.lookEnu(teme, obsEcef, gmstRad, fix.latitude, fix.longitude)
                val e = enu[0]; val north = enu[1]; val up = enu[2]
                if (!showBelowHorizon && up < 0.0) continue
                val range = sqrt(e * e + north * north + up * up)
                if (range <= 0.0) continue
                satCoords.add((e / range).toFloat())
                satCoords.add((north / range).toFloat())
                satCoords.add((up / range).toFloat())
                satNames.add(sat.name)
                satIss.add(sat.isIss)
            }
        }
        val satEnu = FloatArray(satCoords.size) { satCoords[it] }
        val satIsIss = BooleanArray(satIss.size) { satIss[it] }

        val declination = GeomagneticField(
            fix.latitude.toFloat(),
            fix.longitude.toFloat(),
            fix.altitude.toFloat(),
            timeMillis,
        ).declination

        return SkyModel(
            n, starEnu, catalog.mag, catalog.ci, catalog.labels,
            cons, planets, sun, moon, satEnu, satNames, satIsIss,
            declination, fix, timeMillis,
        )
    }

    private fun toEnu(vecEq: DoubleArray, basis: AstroMath.EnuBasis): FloatArray = floatArrayOf(
        AstroMath.dot(vecEq, basis.east).toFloat(),
        AstroMath.dot(vecEq, basis.north).toFloat(),
        AstroMath.dot(vecEq, basis.up).toFloat(),
    )
}
