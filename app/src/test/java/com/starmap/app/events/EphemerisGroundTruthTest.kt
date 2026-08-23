package com.starmap.app.events

import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.Planets
import com.starmap.app.astro.SunMoon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Anchors the ephemeris to facts established outside this codebase.
 *
 * These exist because the app previously fed Schlyter's mean-element polynomials a
 * J2000 day number instead of his own epoch, putting every Sun, Moon and planet position
 * 1.5 days behind — about twenty degrees for the Moon. The one test that touched solar
 * position at the time had been given goldens generated from the buggy code, so it
 * passed throughout. Every assertion here is against something independently known:
 * a published eclipse, a textbook elongation range, or a formula from a different source.
 */
class EphemerisGroundTruthTest {

    private fun jd(iso: String) = AstroMath.julianDay(Instant.parse(iso).toEpochMilli())

    /** Astronomical Almanac low-precision Sun, good to ~0.01 deg. A different algorithm. */
    private fun referenceSun(jdValue: Double): DoubleArray {
        val n = jdValue - 2_451_545.0
        val l = Math.toRadians((280.460 + 0.9856474 * n) % 360.0)
        val g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0)
        val lam = l + Math.toRadians(1.915) * Math.sin(g) + Math.toRadians(0.020) * Math.sin(2 * g)
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        var ra = Math.toDegrees(Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam)))
        if (ra < 0) ra += 360.0
        return doubleArrayOf(ra, Math.toDegrees(Math.asin(Math.sin(eps) * Math.sin(lam))))
    }

    @Test
    fun schlyterDayIsZeroAtSchlytersOwnEpoch() {
        // d = 0 at 2000 Jan 0.0 UT = 1999-12-31 00:00 UT = JD 2451543.5.
        assertEquals(0.0, AstroMath.schlyterDay(2_451_543.5), 1e-9)
        assertEquals(1.0, AstroMath.schlyterDay(jd("2000-01-01T00:00:00Z")), 1e-9)
    }

    @Test
    fun gmstStillUsesTheJ2000Epoch() {
        // Guards against "fixing" the wrong function: sidereal time is genuinely
        // J2000-referenced and must not be moved.
        assertEquals(280.4606, AstroMath.gmstDegrees(2_451_545.0), 0.001)
    }

    @Test
    fun sunMatchesAnIndependentFormula() {
        for (iso in listOf(
            "2000-01-01T12:00:00Z",
            "2025-03-14T06:59:00Z",
            "2026-01-01T00:00:00Z",
            "2026-06-08T12:00:00Z",
            "2026-08-12T18:00:00Z",
        )) {
            val j = jd(iso)
            val app = SunMoon.sun(j)
            val ref = referenceSun(j)
            assertEquals("RA at $iso", ref[0], app.raDeg, 0.05)
            assertEquals("Dec at $iso", ref[1], app.decDeg, 0.05)
        }
    }

    @Test
    fun geocentricMoonDoesNotDependOnAnObserver() {
        // The old phase code asked for moon(jd, 0, 0) and called it geocentric. It was
        // not: the right-ascension parallax term survives at latitude zero, up to a
        // degree of it, which is larger than the entire umbra of a lunar eclipse.
        val j = jd("2026-03-03T11:00:00Z")
        val g = SunMoon.moonGeocentric(j)
        val equatorObserver = SunMoon.moon(j, 0.0, 0.0)
        val sep = AstroMath.angularSepDeg(
            g.raDeg, g.decDeg, equatorObserver.raDeg, equatorObserver.decDeg,
        )
        assertTrue("moon(jd,0,0) is topocentric, so it must differ from geocentric", sep > 0.1)
        // And the geocentric one is genuinely observer-free.
        assertEquals(g.raDeg, SunMoon.moonGeocentric(j).raDeg, 0.0)
    }

    /**
     * A total lunar eclipse happens only when the Moon passes through the antisolar
     * point. On 2025-03-14 at 06:58 UT it did, so the separation must be a fraction of a
     * degree. On the pre-fix code this was 16.6 degrees.
     */
    @Test
    fun moonSitsAtTheAntisolarPointDuringATotalLunarEclipse() {
        val j = jd("2025-03-14T06:58:00Z")
        val s = SunMoon.sun(j)
        val m = SunMoon.moonGeocentric(j)
        val sep = AstroMath.angularSepDeg(
            AstroMath.norm360(s.raDeg + 180.0), -s.decDeg, m.raDeg, m.decDeg,
        )
        assertTrue("Moon should be within 0.55 deg of the antisolar point, was $sep", sep < 0.55)
    }

    /**
     * A total solar eclipse requires the Sun and Moon to be all but coincident.
     * 2024-04-08 18:17 UT was greatest eclipse. Pre-fix this read 21.2 degrees.
     */
    @Test
    fun sunAndMoonCoincideDuringATotalSolarEclipse() {
        val j = jd("2024-04-08T18:17:00Z")
        val s = SunMoon.sun(j)
        val m = SunMoon.moonGeocentric(j)
        val sep = AstroMath.angularSepDeg(s.raDeg, s.decDeg, m.raDeg, m.decDeg)
        assertTrue("Sun-Moon separation should be under 0.6 deg, was $sep", sep < 0.6)
    }

    /**
     * Mercury and Venus have well-known elongation limits set by their orbits. These
     * exercise the entire planetary pipeline, and they fail on the pre-fix epoch.
     */
    @Test
    fun innerPlanetElongationsStayInsideTheirKnownLimits() {
        fun elongation(planet: String, j: Double): Double {
            val s = SunMoon.sun(j)
            val p = Planets.positions(j).first { it.name == planet }
            return AstroMath.angularSepDeg(s.raDeg, s.decDeg, p.raDeg, p.decDeg)
        }
        var maxMercury = 0.0
        var maxVenus = 0.0
        var j = jd("2026-01-01T00:00:00Z")
        val end = j + 5 * 365.25
        while (j < end) {
            maxMercury = maxOf(maxMercury, elongation("Mercury", j))
            maxVenus = maxOf(maxVenus, elongation("Venus", j))
            j += 1.0
        }
        assertTrue("Mercury max elongation $maxMercury outside 17.9..28.5", maxMercury in 17.9..28.5)
        assertTrue("Venus max elongation $maxVenus outside 44.5..47.5", maxVenus in 44.5..47.5)
    }

    /**
     * The 2020 "great conjunction" of Jupiter and Saturn was on 21 December, about six
     * arc-minutes apart. Both the separation and the date have to come out right — the
     * pre-fix code got the separation right and the date 1.5 days late, which is exactly
     * the failure mode a separation-only assertion would have missed.
     */
    @Test
    fun greatConjunctionLandsOnTheRightDayAndSeparation() {
        var best = 999.0
        var bestJd = 0.0
        var t = jd("2020-12-18T00:00:00Z")
        val end = jd("2020-12-25T00:00:00Z")
        while (t < end) {
            val ps = Planets.positions(t)
            val ju = ps.first { it.name == "Jupiter" }
            val sa = ps.first { it.name == "Saturn" }
            val sep = AstroMath.angularSepDeg(ju.raDeg, ju.decDeg, sa.raDeg, sa.decDeg)
            if (sep < best) { best = sep; bestJd = t }
            t += 1.0 / 48.0
        }
        assertEquals("closest approach in arc-minutes", 6.1, best * 60.0, 1.0)
        // 2020-12-21 18:00 UT is JD 2459205.25. Half a day of slack.
        assertEquals("date of closest approach", 2_459_205.25, bestJd, 0.5)
    }

    @Test
    fun eclipticLongitudeRoundTripsThroughTheEquatorialFrame() {
        val j = jd("2026-05-05T00:00:00Z")
        // The Sun sits on the ecliptic by definition, so its ecliptic latitude is ~0.
        val s = SunMoon.sun(j)
        assertEquals(0.0, AstroMath.eclipticLatitude(s.raDeg, s.decDeg, j), 0.01)
    }
}
