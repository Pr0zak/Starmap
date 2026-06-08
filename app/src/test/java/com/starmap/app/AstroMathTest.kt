package com.starmap.app

import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.SunMoon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Validates the coordinate engine against independently known references.
 * These are pure-JVM (no Android), so they run as fast unit tests in CI.
 */
class AstroMathTest {

    private fun jd(iso: String) = AstroMath.julianDay(Instant.parse(iso).toEpochMilli())

    @Test
    fun gmstAtJ2000() {
        assertEquals(280.4606, AstroMath.gmstDegrees(2_451_545.0), 0.001)
    }

    @Test
    fun sunNearSummerSolstice() {
        val s = SunMoon.sun(jd("2026-06-08T12:00:00Z"))
        // Sun approaching the June solstice: dec ~ +22.7, RA ~ 75 deg.
        assertEquals(22.73, s.decDeg, 0.15)
        assertEquals(75.11, s.raDeg, 0.3)
    }

    @Test
    fun polarisAltitudeEqualsLatitude() {
        // Polaris (Dec 89.26) sits within ~0.74 deg of the pole, so its altitude
        // tracks the observer's latitude.
        val j = jd("2026-01-15T02:00:00Z")
        val basis = AstroMath.enuBasis(AstroMath.lstDegrees(j, -74.01), 40.71)
        val h = AstroMath.toHorizontal(AstroMath.equatorialToVec(37.95, 89.26), basis)
        assertEquals(40.71, h.altitudeDeg, 1.0)
        assertTrue("Polaris should be roughly north", h.azimuthDeg < 2.0 || h.azimuthDeg > 358.0)
    }

    @Test
    fun solarNoonAltitude() {
        // When the Sun is on the meridian, altitude = 90 - lat + dec exactly.
        val s = SunMoon.sun(jd("2026-06-08T12:00:00Z"))
        val lat = 37.0
        val basis = AstroMath.enuBasis(s.raDeg, lat) // put the Sun's RA on the meridian
        val h = AstroMath.toHorizontal(AstroMath.equatorialToVec(s.raDeg, s.decDeg), basis)
        assertEquals(90.0 - lat + s.decDeg, h.altitudeDeg, 0.01)
        assertEquals(180.0, h.azimuthDeg, 0.5)
    }

    @Test
    fun moonDistanceAndPhaseInRange() {
        val m = SunMoon.moon(jd("2026-06-08T00:00:00Z"), 0.0, 0.0)
        assertTrue("Moon distance 56-64 Earth radii", m.distance in 55.0..65.0)
        val p = SunMoon.moonPhase(jd("2026-06-08T00:00:00Z"))
        assertTrue(p.illuminatedFraction in 0.0..1.0)
    }
}
