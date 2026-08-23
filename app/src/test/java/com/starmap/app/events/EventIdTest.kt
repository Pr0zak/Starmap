package com.starmap.app.events

import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.SunMoon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Event identity is the thing that stops the same eclipse being announced three times,
 * so it gets its own suite. The rule under test throughout: an id is a function of
 * slowly-varying integers, never of a raw timestamp.
 */
class EventIdTest {

    private fun jd(iso: String) = AstroMath.julianDay(Instant.parse(iso).toEpochMilli())

    @Test
    fun theLunationIndexSurvivesATimingWobble() {
        // The whole point: a later refinement to the ephemeris that moves a full moon by
        // several hours must not mint a second id for the same full moon.
        var cursor = jd("2026-01-01T00:00:00Z")
        repeat(12) {
            val full = SunMoon.nextMoonPhases(cursor).first { it.name == "Full moon" }
            val at = AstroMath.julianDay(full.timeMillis)
            val reference = EventIds.lunation(at)
            for (wobbleDays in listOf(-0.25, -0.1, 0.1, 0.25)) {
                assertEquals(
                    "lunation moved when the instant shifted by $wobbleDays d",
                    reference, EventIds.lunation(at + wobbleDays),
                )
            }
            cursor = at + 1.0
        }
    }

    @Test
    fun consecutiveLunationsGetConsecutiveNumbers() {
        var cursor = jd("2026-01-01T00:00:00Z")
        var previous: Int? = null
        repeat(10) {
            val full = SunMoon.nextMoonPhases(cursor).first { it.name == "Full moon" }
            val n = EventIds.lunation(AstroMath.julianDay(full.timeMillis))
            previous?.let { assertEquals("lunations should step by one", it + 1, n) }
            previous = n
            cursor = AstroMath.julianDay(full.timeMillis) + 1.0
        }
    }

    @Test
    fun aConjunctionPairIsSortedSoOrderCannotMatter() {
        val j = jd("2026-05-01T00:00:00Z")
        assertEquals(EventIds.conjunction("Venus", "Jupiter", j), EventIds.conjunction("Jupiter", "Venus", j))
        assertTrue(EventIds.conjunction("Venus", "Jupiter", j).contains("jupiter|venus"))
    }

    @Test
    fun differentKindsOfEventNeverCollide() {
        val j = jd("2026-03-03T11:33:00Z")
        val ids = setOf(
            EventIds.fullMoon(j), EventIds.newMoon(j), EventIds.lunarEclipse(j),
            EventIds.solarEclipse(j), EventIds.darkNight(j),
        )
        assertEquals("each should be distinct", 5, ids.size)
    }

    @Test
    fun meteorIdsAreScopedToTheYear() {
        assertNotEquals(EventIds.meteor("Perseids", 2026), EventIds.meteor("Perseids", 2027))
        assertEquals("meteor:perseids:2026", EventIds.meteor("Perseids", 2026))
    }

    @Test
    fun showerNamesWithPunctuationStillProduceCleanIds() {
        val id = EventIds.meteor("Southern δ Aquariids", 2026)
        assertTrue("id should be url-safe: $id", id.matches(Regex("^[a-z]+:[a-z0-9-]+:[0-9]+$")))
    }

    @Test
    fun anApparitionCounterAdvancesOncePerSynodicPeriod() {
        val first = EventIds.opposition("Mars", jd("2027-02-19T00:00:00Z"))
        val nextApparition = EventIds.opposition("Mars", jd("2029-03-25T00:00:00Z"))
        assertNotEquals("successive Mars oppositions need different ids", first, nextApparition)
        // But two samples a few days apart within one opposition must agree.
        assertEquals(first, EventIds.opposition("Mars", jd("2027-02-25T00:00:00Z")))
    }

    @Test
    fun notificationIdsAreDeterministicAndPositive() {
        for (id in listOf("meteor:perseids:2026", "ecl:lunar:321", "conj:jupiter|venus:9")) {
            val a = EventIds.notificationId(id)
            assertEquals(a, EventIds.notificationId(id))
            assertTrue("must be a usable notification id", a > 0)
        }
    }
}
