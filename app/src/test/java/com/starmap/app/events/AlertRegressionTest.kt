package com.starmap.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Regressions for defects an adversarial review found after the feature was first written.
 * Each of these fails on the code as it stood before its fix.
 */
class AlertRegressionTest {

    private val calc = SkyEventCalculator()
    private val newYork = Observer(40.7128, -74.0060, 10.0, ZoneId.of("America/New_York"), 0L)
    private val allOn = AlertPrefs(enabled = true, types = AlertType.entries.associateWith { true })
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /**
     * Elongation at opposition falls short of 180 by the planet's ecliptic latitude — Mars
     * only reaches 175.5 and Saturn 177.3 — so a 178 gate admitted Jupiter and almost
     * nothing else, silently dropping most oppositions.
     */
    @Test
    fun everyOuterPlanetOppositionIsFound() {
        val found = calc.events(
            newYork, ms("2026-01-01T00:00:00Z"), ms("2028-12-31T00:00:00Z"), allOn,
        ).filter { it.kind == SkyEventKind.Opposition }
        val subjects = found.map { it.subject }.toSet()
        assertTrue("Jupiter opposes yearly and must be found", "Jupiter" in subjects)
        assertTrue("Saturn opposes yearly and must be found", "Saturn" in subjects)
        assertTrue("Mars opposes every ~26 months and must be found", "Mars" in subjects)
        // Jupiter and Saturn each oppose about once a year, Mars once in this span.
        assertTrue("expected at least 6 oppositions in 3 years, got ${found.size}", found.size >= 6)
    }

    /**
     * The phase solver skipped anything in its first half day, so a scan that ran on the
     * day of an eclipse reported nothing at all.
     */
    @Test
    fun anEclipseIsStillFoundWhenTheScanRunsOnTheDay() {
        // 2026-03-03, greatest eclipse 11:33 UT.
        for (startIso in listOf(
            "2026-03-01T00:00:00Z", "2026-03-03T00:00:00Z",
            "2026-03-03T06:00:00Z", "2026-03-03T10:00:00Z",
        )) {
            val found = calc.events(newYork, ms(startIso), ms("2026-03-20T00:00:00Z"), allOn)
                .filter { it.kind == SkyEventKind.LunarEclipse }
            assertEquals("scanning from $startIso should still find the eclipse", 1, found.size)
            assertEquals("ecl:lunar:323", found.first().id)
        }
    }

    /**
     * The apparent-size ratio only decides total versus annular if the shadow axis reaches
     * Earth. Without that gate, eclipses that are partial everywhere were announced as
     * "Total" or "Annular".
     */
    @Test
    fun anEclipseThatIsPartialEverywhereIsNotCalledTotalOrAnnular() {
        val solar = calc.events(
            newYork, ms("2026-01-01T00:00:00Z"), ms("2030-12-31T00:00:00Z"), allOn,
        ).filter { it.kind == SkyEventKind.SolarEclipse }
        assertTrue("expected several solar eclipses in five years", solar.size >= 5)
        for (e in solar) {
            val miss = e.detail["axisMissRe"]!!
            val central = miss < 0.997
            val claimsCentral = e.title.startsWith("Total") || e.title.startsWith("Annular")
            assertTrue(
                "${e.id} claims '${e.title}' but the axis misses Earth by $miss Earth radii",
                central || !claimsCentral,
            )
        }
    }

    /** The path-of-totality hedge was reaching every observer who saw any partial phase. */
    @Test
    fun theEdgeOfTotalityHedgeIsNotShownToDistantObservers() {
        val solar = calc.events(
            newYork, ms("2026-01-01T00:00:00Z"), ms("2030-12-31T00:00:00Z"), allOn,
        ).filter { it.kind == SkyEventKind.SolarEclipse }
        val hedged = solar.count { it.body.contains("edge of the path") }
        assertTrue(
            "New York should not be told it is near the path edge for most eclipses ($hedged of ${solar.size})",
            hedged <= 1,
        )
    }

    /** A minimum in the first or last half-step of the window was never bracketed. */
    @Test
    fun aConjunctionAtTheVeryStartOfTheWindowIsStillFound() {
        // Find a conjunction, then rescan a window that begins right on top of it.
        val wide = calc.events(newYork, ms("2026-01-01T00:00:00Z"), ms("2026-06-30T00:00:00Z"), allOn)
            .filter { it.kind == SkyEventKind.MoonConjunction || it.kind == SkyEventKind.PlanetConjunction }
        assertTrue("need a conjunction to test with", wide.isNotEmpty())
        val target = wide.first()
        val tight = calc.events(
            newYork, target.peakMillis - 2 * 3_600_000L, target.peakMillis + 20 * 86_400_000L, allOn,
        ).map { it.id }
        assertTrue("${target.id} vanished when the window started next to it", target.id in tight)
    }

    /** The Moon laps a planet in as little as 26 days, inside the old 30-day id bucket. */
    @Test
    fun consecutiveMoonPlanetMeetingsGetDifferentIds() {
        // An outer planet barely moves against the Moon, so meetings recur at the sidereal
        // month; Venus and Mercury travel with the Sun, so theirs recur at the synodic one.
        for ((planet, lapDays) in listOf(
            "Mars" to 27.5, "Jupiter" to 27.4, "Saturn" to 27.4, "Venus" to 29.6, "Mercury" to 29.6,
        )) {
            val ids = ArrayList<String>()
            var jd = 2_461_042.0 // 2026-01-01
            repeat(14) {
                ids.add(EventIds.conjunction("Moon", planet, jd))
                jd += lapDays
            }
            assertEquals("$planet: consecutive meetings collided", ids.size, ids.toSet().size)
        }
    }

    /** A bright Moon that sets early leaves a perfectly usable night behind it. */
    @Test
    fun aMoonThatSetsEarlyDoesNotSuppressTheWholeNight() {
        val skipMoonlit = allOn.copy(moonlight = AlertPrefs.MOONLIGHT_SKIP)
        val kept = calc.events(newYork, ms("2026-01-01T00:00:00Z"), ms("2026-12-31T00:00:00Z"), skipMoonlit)
            .count { it.kind == SkyEventKind.MeteorPeak }
        val all = calc.events(newYork, ms("2026-01-01T00:00:00Z"), ms("2026-12-31T00:00:00Z"), allOn)
            .count { it.kind == SkyEventKind.MeteorPeak }
        assertTrue("skipping moonlit nights should not remove every shower ($kept of $all)", kept > 0)
        assertTrue(kept <= all)
    }

    /** Eclipse duration was a hardcoded sentence rather than anything computed. */
    @Test
    fun eclipseDurationVariesBetweenEclipses() {
        val spans = listOf(
            "under an hour", "about an hour", "an hour and a half",
            "a couple of hours", "over three hours",
        )
        val phrases = calc.events(
            newYork, ms("2025-01-01T00:00:00Z"), ms("2032-12-31T00:00:00Z"), allOn,
        ).filter { it.kind == SkyEventKind.LunarEclipse }
            .mapNotNull { e -> spans.firstOrNull { it in e.body } }
        // Eclipses below the horizon carry no duration, so not every one contributes.
        assertTrue("expected several lunar eclipses to quote a duration, got ${phrases.size}",
            phrases.size >= 4)
        assertTrue("expected the duration to be computed, not a constant: $phrases",
            phrases.toSet().size > 1)

    }
}
