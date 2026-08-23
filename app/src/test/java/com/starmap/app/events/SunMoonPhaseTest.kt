package com.starmap.app.events

import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.SunMoon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

/**
 * Pins the phase solver.
 *
 * The earlier version searched on the great-circle Sun-Moon separation, treating it as
 * a phase angle. It isn't one: separation never reaches 0 at new moon or 180 at full
 * moon, because the Moon rides up to 5.2 degrees off the ecliptic and the separation
 * bottoms out at that latitude instead. The error therefore tracked the Moon's ecliptic
 * latitude, vanishing at the eclipses and reaching several hours at the extremes —
 * enough to announce a full moon on the wrong evening.
 *
 * [phaseInstantDoesNotDependOnMoonEclipticLatitude] is the direct guard on that.
 */
class SunMoonPhaseTest {

    private fun jd(iso: String) = AstroMath.julianDay(Instant.parse(iso).toEpochMilli())
    private fun hoursBetween(aMillis: Long, bMillis: Long) = abs(aMillis - bMillis) / 3_600_000.0

    private fun nextOfType(fromIso: String, name: String): SunMoon.PhaseEvent =
        SunMoon.nextMoonPhases(jd(fromIso)).first { it.name == name }

    /**
     * At the instant a phase is reported, the ecliptic longitude difference must be
     * sitting on its target. This holds for every lunation regardless of how far the
     * Moon is from the ecliptic — which is precisely what the old solver could not do.
     */
    @Test
    fun phaseInstantDoesNotDependOnMoonEclipticLatitude() {
        var cursor = jd("2026-01-01T00:00:00Z")
        var checked = 0
        var worstBeta = 0.0
        repeat(13) {
            for (e in SunMoon.nextMoonPhases(cursor)) {
                val at = AstroMath.julianDay(e.timeMillis)
                val target = when (e.name) {
                    "New moon" -> 0.0
                    "First quarter" -> 90.0
                    "Full moon" -> 180.0
                    else -> 270.0
                }
                val off = (SunMoon.moonPhase(at).phaseAngleDeg - target + 180.0).mod(360.0) - 180.0
                val m = SunMoon.moonGeocentric(at)
                worstBeta = maxOf(worstBeta, abs(AstroMath.eclipticLatitude(m.raDeg, m.decDeg, at)))
                assertTrue(
                    "${e.name} at ${Instant.ofEpochMilli(e.timeMillis)} is $off deg off its target",
                    abs(off) < 0.05,
                )
                checked++
            }
            cursor = AstroMath.julianDay(SunMoon.nextMoonPhases(cursor).maxOf { it.timeMillis }) + 0.01
        }
        assertTrue("should have checked a full year of phases", checked >= 48)
        // If this never saw a high-latitude lunation the test would be toothless.
        assertTrue("should have covered a lunation well off the ecliptic", worstBeta > 3.0)
    }

    /**
     * A total lunar eclipse can only happen at full moon, and greatest eclipse falls
     * within an hour or so of the syzygy. 2025-03-14 06:58 UT is a published circumstance.
     */
    @Test
    fun fullMoonSitsAtTheMarch2025LunarEclipse() {
        val full = nextOfType("2025-03-08T00:00:00Z", "Full moon")
        val greatest = Instant.parse("2025-03-14T06:58:00Z").toEpochMilli()
        assertTrue(
            "full moon at ${Instant.ofEpochMilli(full.timeMillis)} should be near greatest eclipse",
            hoursBetween(full.timeMillis, greatest) < 3.0,
        )
    }

    /** Likewise a total solar eclipse can only happen at new moon. 2024-04-08 18:17 UT. */
    @Test
    fun newMoonSitsAtTheApril2024SolarEclipse() {
        val new = nextOfType("2024-04-02T00:00:00Z", "New moon")
        val greatest = Instant.parse("2024-04-08T18:17:00Z").toEpochMilli()
        assertTrue(
            "new moon at ${Instant.ofEpochMilli(new.timeMillis)} should be near greatest eclipse",
            hoursBetween(new.timeMillis, greatest) < 3.0,
        )
    }

    @Test
    fun nextMoonPhasesReturnsFourDistinctPhasesInOrder() {
        val start = jd("2026-04-17T00:00:00Z")
        val phases = SunMoon.nextMoonPhases(start)
        assertEquals(4, phases.size)
        assertEquals(4, phases.map { it.name }.toSet().size)
        for (i in 1 until phases.size) {
            assertTrue("phases must be in order", phases[i].timeMillis > phases[i - 1].timeMillis)
        }
        val startMillis = ((start - 2_440_587.5) * 86_400_000.0).toLong()
        for (p in phases) {
            assertTrue(p.timeMillis > startMillis)
            assertTrue(p.timeMillis <= startMillis + 45L * 86_400_000L)
        }
    }

    @Test
    fun consecutiveFullMoonsAreOneSynodicMonthApart() {
        var cursor = jd("2026-01-01T00:00:00Z")
        var previous: Long? = null
        repeat(12) {
            val full = SunMoon.nextMoonPhases(cursor).first { it.name == "Full moon" }
            previous?.let {
                val days = (full.timeMillis - it) / 86_400_000.0
                assertEquals("synodic month", 29.53, days, 0.6)
            }
            previous = full.timeMillis
            cursor = AstroMath.julianDay(full.timeMillis) + 1.0
        }
    }

    @Test
    fun illuminationIsRightAtTheSyzygies() {
        val full = nextOfType("2026-06-01T00:00:00Z", "Full moon")
        val new = nextOfType("2026-06-01T00:00:00Z", "New moon")
        assertTrue(SunMoon.moonPhase(AstroMath.julianDay(full.timeMillis)).illuminatedFraction > 0.99)
        assertTrue(SunMoon.moonPhase(AstroMath.julianDay(new.timeMillis)).illuminatedFraction < 0.01)
    }

    @Test
    fun sunEventGeneralisesRiseAndSetExactly() {
        val t = Instant.parse("2026-08-12T12:00:00Z").toEpochMilli()
        val viaAlias = SunMoon.sunRiseSet(t, 40.71, -74.01)
        val viaGeneral = SunMoon.sunEvent(t, 40.71, -74.01, -0.833)
        assertEquals(viaAlias.riseMillis, viaGeneral.riseMillis)
        assertEquals(viaAlias.setMillis, viaGeneral.setMillis)
    }

    @Test
    fun astronomicalTwilightIsAfterSunsetAndBeforeSunrise() {
        val t = Instant.parse("2026-08-12T12:00:00Z").toEpochMilli()
        val civil = SunMoon.sunEvent(t, 40.71, -74.01, -0.833)
        val astro = SunMoon.sunEvent(t, 40.71, -74.01, -18.0)
        assertTrue("dusk comes after sunset", astro.setMillis!! > civil.setMillis!!)
        assertTrue("dawn comes before sunrise", astro.riseMillis!! < civil.riseMillis!!)
        // Roughly an hour and a half of twilight at this latitude in August.
        val gap = (astro.setMillis!! - civil.setMillis!!) / 60_000.0
        assertTrue("twilight length $gap min looks wrong", gap in 60.0..150.0)
    }

    @Test
    fun polarCasesReportRatherThanReturningNonsense() {
        // Tromso in June: the Sun never gets 18 degrees down.
        val june = Instant.parse("2026-06-21T12:00:00Z").toEpochMilli()
        val neverDark = SunMoon.sunEvent(june, 69.65, 18.96, -18.0)
        assertTrue(neverDark.riseMillis == null && neverDark.setMillis == null)
        assertTrue(neverDark.note != null)
        // And in December it does get properly dark.
        val december = Instant.parse("2026-12-21T12:00:00Z").toEpochMilli()
        val dark = SunMoon.sunEvent(december, 69.65, 18.96, -18.0)
        assertTrue(dark.setMillis != null && dark.riseMillis != null)
    }

    @Test
    fun nonsenseCoordinatesAreRejectedRatherThanReturningAnEpochTime() {
        val t = Instant.parse("2026-08-12T12:00:00Z").toEpochMilli()
        for (bad in listOf(Double.NaN, 95.0, -95.0)) {
            val r = SunMoon.sunEvent(t, bad, 0.0, -0.833)
            assertTrue("lat=$bad should not yield a time", r.riseMillis == null && r.setMillis == null)
        }
        assertTrue(SunMoon.sunEvent(t, 40.0, Double.NaN, -0.833).riseMillis == null)
    }
}
