package com.starmap.app.events

import com.starmap.app.astro.Sgp4
import com.starmap.app.astro.TleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class IssPassesTest {

    // A real ISS element set, epoch 2024-01-01 12:00 UT.
    private val issTle = """
        ISS (ZARYA)
        1 25544U 98067A   24001.50000000  .00016717  00000-0  30777-3 0  9008
        2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.49814614 20000
    """.trimIndent()

    private val sgp4 = Sgp4(TleParser.parse(issTle).first())
    private val newYork = Observer(40.7128, -74.0060, 10.0, ZoneId.of("America/New_York"), 0L)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun passes(minAlt: Double = 30.0, ageMillis: Long = 0L) = IssPasses.find(
        sgp4 = sgp4,
        tleAgeMillis = ageMillis,
        observer = newYork,
        fromMillis = ms("2024-01-01T12:00:00Z"),
        toMillis = ms("2024-01-04T12:00:00Z"),
        minPeakAltitudeDeg = minAlt,
    )

    @Test
    fun findsVisibleEveningPassesOverThreeDays() {
        val found = passes(minAlt = 20.0)
        assertTrue("expected at least one visible pass in three days, got ${found.size}", found.isNotEmpty())
    }

    @Test
    fun everyPassIsInternallyConsistent() {
        for (p in passes(minAlt = 20.0)) {
            assertTrue("rise before peak", p.startMillis <= p.peakMillis)
            assertTrue("peak before set", p.peakMillis <= p.endMillis)
            assertTrue("altitude in range", p.peakAltitudeDeg in 20.0..90.0)
            assertTrue("azimuths in range", p.startAzimuthDeg in 0.0..360.0 && p.endAzimuthDeg in 0.0..360.0)
        }
    }

    @Test
    fun noPassLastsLongerThanOrbitalGeometryAllows() {
        // From 400 km, horizon to horizon is about ten minutes at the very best.
        for (p in passes(minAlt = 20.0)) {
            val minutes = (p.endMillis - p.startMillis) / 60_000.0
            assertTrue("a $minutes-minute ISS pass is not physically possible", minutes < 12.0)
        }
    }

    @Test
    fun raisingTheAltitudeFloorNarrowsTheResults() {
        val low = passes(minAlt = 15.0).size
        val high = passes(minAlt = 70.0).size
        assertTrue("a higher floor must not admit more passes", high <= low)
    }

    @Test
    fun staleElementsProduceNothingRatherThanSomethingWrong() {
        // Predicting from week-old elements is worse than saying nothing at all.
        assertEquals(0, passes(minAlt = 20.0, ageMillis = 8L * 24 * 3_600_000).size)
    }

    @Test
    fun elementsAFewDaysOldAreFlaggedAsApproximate() {
        val found = passes(minAlt = 20.0, ageMillis = 5L * 24 * 3_600_000)
        for (p in found) assertTrue("a 5-day-old prediction should be hedged", p.approximate)
        for (p in passes(minAlt = 20.0, ageMillis = 3_600_000)) {
            assertTrue("a fresh prediction should not be hedged", !p.approximate)
        }
    }

    @Test
    fun anUnusableObserverProducesNothing() {
        val broken = newYork.copy(latDeg = Double.NaN)
        assertTrue(
            IssPasses.find(sgp4, 0L, broken, ms("2024-01-01T12:00:00Z"), ms("2024-01-03T12:00:00Z"))
                .isEmpty(),
        )
    }

    @Test
    fun passesAreAlwaysDuringDarkness() {
        // A satellite is only worth mentioning when the observer's sky is dark. Anything
        // reported in daylight means the twilight gate is broken.
        for (p in passes(minAlt = 20.0)) {
            val hour = Instant.ofEpochMilli(p.peakMillis).atZone(newYork.zone).hour
            assertTrue("a pass peaking at $hour local cannot be visible", hour >= 16 || hour <= 8)
        }
    }
}
