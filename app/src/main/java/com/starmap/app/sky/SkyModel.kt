package com.starmap.app.sky

import android.hardware.GeomagneticField
import com.starmap.app.aircraft.AircraftTrack
import com.starmap.app.astro.Asteroids
import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.Constellation
import com.starmap.app.astro.MeteorShowers
import com.starmap.app.astro.Planets
import com.starmap.app.astro.ReferenceLines
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

/** A meteor shower radiant (the point meteors stream from) for the current night. */
class RadiantEnu(val name: String, val enu: FloatArray, val sublabel: String)

/** A rendered aircraft: its direction, a fading trail, and details for the info card. */
class AircraftRender(
    val enu: FloatArray,
    val callsign: String,
    val isHelicopter: Boolean,
    val typeCode: String,
    val altitudeMeters: Double,
    val groundSpeedKts: Double,
    val trackDeg: Double,
    val rangeKm: Double,
    /** Flattened ENU trail polyline x,y,z,… (oldest→newest). */
    val trail: FloatArray,
)

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
    val eclipticLine: FloatArray,
    val equatorLine: FloatArray,
    val gridLines: List<FloatArray>,
    val planets: List<PlanetEnu>,
    val radiants: List<RadiantEnu>,
    val asteroids: List<PlanetEnu>,
    /** Each entry is a flattened ENU polyline of an asteroid's track over time. */
    val asteroidPaths: List<FloatArray>,
    val sun: BodyEnu?,
    val moon: MoonEnu?,
    /** count*3 ENU vectors for visible satellites. */
    val satEnu: FloatArray,
    val satNames: List<String>,
    val satIsIss: BooleanArray,
    val aircraft: List<AircraftRender>,
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
        includeEcliptic: Boolean,
        includeEquator: Boolean,
        includeGrid: Boolean,
        includeMeteors: Boolean,
        includePlanets: Boolean,
        includeAsteroids: Boolean,
        asteroidElements: List<Asteroids.Element>,
        includeAsteroidPaths: Boolean,
        satellites: List<NamedSat>,
        aircraft: List<AircraftTrack>,
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

        val eclipticLine = if (includeEcliptic) eqLineToEnu(ReferenceLines.ecliptic, basis) else FloatArray(0)
        val equatorLine = if (includeEquator) eqLineToEnu(ReferenceLines.equator, basis) else FloatArray(0)
        val gridLines = if (includeGrid) ReferenceLines.grid.map { eqLineToEnu(it, basis) } else emptyList()

        val planets = if (includePlanets) {
            Planets.positions(jd).map { p ->
                val v = AstroMath.equatorialToVec(p.raDeg, p.decDeg)
                PlanetEnu(p.name, toEnu(v, basis), p.colorArgb, p.sizeDp)
            }
        } else {
            emptyList()
        }

        val radiants = if (includeMeteors) {
            MeteorShowers.active(timeMillis).map { sh ->
                val v = AstroMath.equatorialToVec(sh.raDeg, sh.decDeg)
                val sub = when {
                    sh.daysToPeak == 0L -> "peak tonight · ZHR ${sh.zhr}"
                    sh.daysToPeak in 1L..30L -> "peak in ${sh.daysToPeak}d · ZHR ${sh.zhr}"
                    else -> "active · ZHR ${sh.zhr}"
                }
                RadiantEnu(sh.name, toEnu(v, basis), sub)
            }
        } else {
            emptyList()
        }

        val asteroids = if (includeAsteroids) {
            Asteroids.positions(asteroidElements, jd).map { a ->
                val v = AstroMath.equatorialToVec(a.raDeg, a.decDeg)
                PlanetEnu(a.name, toEnu(v, basis), 0xFFC8C0A0, a.sizeDp)
            }
        } else {
            emptyList()
        }
        val asteroidPaths = if (includeAsteroids && includeAsteroidPaths) {
            asteroidElements.map { el ->
                val pts = FloatArray((PATH_SAMPLES * 2 + 1) * 3)
                var idx = 0
                var k = -PATH_SAMPLES
                while (k <= PATH_SAMPLES) {
                    val rd = Asteroids.raDec(el, jd + k * PATH_STEP_DAYS)
                    val v = AstroMath.equatorialToVec(rd[0], rd[1])
                    pts[idx] = AstroMath.dot(v, basis.east).toFloat()
                    pts[idx + 1] = AstroMath.dot(v, basis.north).toFloat()
                    pts[idx + 2] = AstroMath.dot(v, basis.up).toFloat()
                    idx += 3
                    k++
                }
                pts
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

        // Aircraft: convert each ADS-B position (and its trail) to local ENU.
        val aircraftRenders = ArrayList<AircraftRender>()
        if (aircraft.isNotEmpty()) {
            val obsEcef = Satellites.observerEcef(fix.latitude, fix.longitude, fix.altitude)
            for (ac in aircraft) {
                val acEcef = Satellites.observerEcef(ac.latitude, ac.longitude, ac.altitudeMeters)
                val enu = Satellites.enuFromEcef(acEcef, obsEcef, fix.latitude, fix.longitude)
                val range = sqrt(enu[0] * enu[0] + enu[1] * enu[1] + enu[2] * enu[2])
                if (range <= 0.0) continue
                if (!showBelowHorizon && enu[2] < 0.0) continue
                val unit = floatArrayOf(
                    (enu[0] / range).toFloat(), (enu[1] / range).toFloat(), (enu[2] / range).toFloat(),
                )
                val trail = FloatArray(ac.trail.size * 3)
                var ti = 0
                for (p in ac.trail) {
                    val pe = Satellites.observerEcef(p[0], p[1], p[2])
                    val penu = Satellites.enuFromEcef(pe, obsEcef, fix.latitude, fix.longitude)
                    val pr = sqrt(penu[0] * penu[0] + penu[1] * penu[1] + penu[2] * penu[2])
                    if (pr > 0.0) {
                        trail[ti] = (penu[0] / pr).toFloat()
                        trail[ti + 1] = (penu[1] / pr).toFloat()
                        trail[ti + 2] = (penu[2] / pr).toFloat()
                    }
                    ti += 3
                }
                aircraftRenders.add(
                    AircraftRender(
                        unit, ac.callsign, ac.isHelicopter, ac.typeCode,
                        ac.altitudeMeters, ac.groundSpeedKts, ac.trackDeg, range, trail,
                    ),
                )
            }
        }

        val declination = GeomagneticField(
            fix.latitude.toFloat(),
            fix.longitude.toFloat(),
            fix.altitude.toFloat(),
            timeMillis,
        ).declination

        return SkyModel(
            n, starEnu, catalog.mag, catalog.ci, catalog.labels,
            cons, eclipticLine, equatorLine, gridLines, planets, radiants, asteroids, asteroidPaths,
            sun, moon, satEnu, satNames, satIsIss,
            aircraftRenders, declination, fix, timeMillis,
        )
    }

    private const val PATH_SAMPLES = 15      // each side of "now"
    private const val PATH_STEP_DAYS = 4.0   // ⇒ ±60 days of track

    private fun toEnu(vecEq: DoubleArray, basis: AstroMath.EnuBasis): FloatArray = floatArrayOf(
        AstroMath.dot(vecEq, basis.east).toFloat(),
        AstroMath.dot(vecEq, basis.north).toFloat(),
        AstroMath.dot(vecEq, basis.up).toFloat(),
    )

    /** Rotate a flattened equatorial-vector polyline into the ENU frame. */
    private fun eqLineToEnu(eq: FloatArray, basis: AstroMath.EnuBasis): FloatArray {
        val out = FloatArray(eq.size)
        val tmp = DoubleArray(3)
        var i = 0
        while (i < eq.size) {
            tmp[0] = eq[i].toDouble(); tmp[1] = eq[i + 1].toDouble(); tmp[2] = eq[i + 2].toDouble()
            out[i] = AstroMath.dot(tmp, basis.east).toFloat()
            out[i + 1] = AstroMath.dot(tmp, basis.north).toFloat()
            out[i + 2] = AstroMath.dot(tmp, basis.up).toFloat()
            i += 3
        }
        return out
    }
}
