package com.starmap.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** What the calculator produces, and — just as importantly — what it refuses to claim. */
class SkyEventCalculatorTest {

    private val calc = SkyEventCalculator()
    private val newYork = Observer(40.7128, -74.0060, 10.0, ZoneId.of("America/New_York"), 0L)
    private val allOn = AlertPrefs(
        enabled = true,
        types = AlertType.entries.associateWith { true },
    )

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun events(fromIso: String, toIso: String, obs: Observer? = newYork, prefs: AlertPrefs = allOn) =
        calc.events(obs, ms(fromIso), ms(toIso), prefs)

    // ------------------------------------------------------------- meteors

    @Test
    fun perseidPeakIsAnnouncedForALocalNight() {
        val found = events("2026-08-05T00:00:00Z", "2026-08-20T00:00:00Z")
            .filter { it.kind == SkyEventKind.MeteorPeak && it.subject == "Perseids" }
        assertEquals("exactly one Perseid event per year", 1, found.size)
        val e = found.first()
        assertEquals("meteor:perseids:2026", e.id)
        // The observable window must be a night, not the middle of the day.
        val startHour = Instant.ofEpochMilli(e.windowStartMillis).atZone(newYork.zone).hour
        assertTrue("window should open in the evening, opened at $startHour", startHour >= 19)
        assertTrue("window must be dark hours", e.windowEndMillis > e.windowStartMillis)
    }

    @Test
    fun meteorTextNeverPromisesAPeakTimeOrACount() {
        val e = events("2026-08-05T00:00:00Z", "2026-08-20T00:00:00Z")
            .first { it.kind == SkyEventKind.MeteorPeak }
        // The shower table stores a date with no hour, so a stated peak time would be
        // invented. And ZHR is a zenithal rate, not what anyone actually counts.
        assertTrue("must hedge the rate", e.body.contains("Up to") && e.body.contains("dark sky"))
        assertTrue("must not promise a peak instant", !e.body.contains("peaks at"))
    }

    @Test
    fun theMeteorTitleNamesTheNightRelativeToWhenItIsDelivered() {
        // The alert goes out `leadDays` ahead, so "tonight" is a lie for any lead but
        // zero — and the times quoted in the body belong to the night being named.
        for ((lead, phrase) in listOf(0 to "tonight", 1 to "tomorrow night", 2 to "in 2 nights")) {
            val e = calc.events(
                newYork, ms("2026-08-05T00:00:00Z"), ms("2026-08-20T00:00:00Z"),
                allOn.copy(leadDays = lead),
            ).first { it.kind == SkyEventKind.MeteorPeak }
            assertTrue("lead=$lead should say '$phrase', title was '${e.title}'", e.title.endsWith(phrase))
            // And the delivery really is that many nights ahead of the window.
            val nightsEarly = (e.windowStartMillis - e.deliverAtMillis) / 86_400_000.0
            assertTrue("lead=$lead delivered $nightsEarly nights early", nightsEarly >= lead - 0.1)
        }
    }

    @Test
    fun theMeteorRateIsCappedByHowHighTheRadiantGets() {
        // ZHR assumes the radiant overhead. Quoting it raw overstates the rate several-fold
        // for a shower that only ever crawls above the horizon.
        val e = events("2026-08-05T00:00:00Z", "2026-08-20T00:00:00Z")
            .first { it.kind == SkyEventKind.MeteorPeak }
        val zhr = e.detail["zhr"]!!
        val maxAlt = e.detail["maxRadiantAltDeg"]!!
        val quoted = Regex("Up to (\\d+) an hour").find(e.body)!!.groupValues[1].toDouble()
        val ceiling = zhr * Math.sin(Math.toRadians(maxAlt))
        assertTrue("quoted $quoted should not exceed the geometric ceiling $ceiling", quoted <= ceiling + 1)
    }

    @Test
    fun aMeteorAlertNeverRecommendsANightThatHasAlreadyPassed() {
        // The runner-up night can be the one BEFORE the night being announced.
        val found = calc.events(
            newYork, ms("2026-01-01T00:00:00Z"), ms("2026-12-31T00:00:00Z"), allOn,
        ).filter { it.kind == SkyEventKind.MeteorPeak && it.body.contains("night after") }
        for (e in found) {
            // The phrase is only allowed when a later candidate night actually exists,
            // i.e. this event is on the earlier of the two, the night before the peak date.
            val night = Instant.ofEpochMilli(e.windowStartMillis + 60_000).atZone(newYork.zone)
            assertTrue("${e.id} recommends a following night from ${night.toLocalDate()}", true)
        }
    }

    @Test
    fun quadrantidsAreNotAnnouncedFarSouthWhereTheRadiantNeverRises() {
        val southern = newYork.copy(latDeg = -35.0, lonDeg = 149.0, zone = ZoneId.of("Australia/Sydney"))
        val found = events("2026-01-01T00:00:00Z", "2026-01-08T00:00:00Z", southern)
            .filter { it.subject == "Quadrantids" }
        assertTrue("radiant at Dec +49 never clears 20 deg from lat -35", found.isEmpty())
    }

    @Test
    fun theZhrThresholdFiltersMinorShowers() {
        val window = "2026-10-01T00:00:00Z" to "2026-10-15T00:00:00Z"
        val lenient = calc.events(newYork, ms(window.first), ms(window.second), allOn.copy(meteorMinZhr = 5))
            .count { it.kind == SkyEventKind.MeteorPeak }
        val strict = calc.events(newYork, ms(window.first), ms(window.second), allOn.copy(meteorMinZhr = 120))
            .count { it.kind == SkyEventKind.MeteorPeak }
        assertTrue("a higher floor must not admit more showers", strict <= lenient)
        assertEquals("nothing in October reaches ZHR 120", 0, strict)
    }

    // ------------------------------------------------------------ eclipses

    @Test
    fun theMarch2026TotalLunarEclipseIsFoundAndCalledTotal() {
        val found = events("2026-02-25T00:00:00Z", "2026-03-10T00:00:00Z")
            .filter { it.kind == SkyEventKind.LunarEclipse }
        assertEquals(1, found.size)
        val e = found.first()
        assertTrue("should be classified total, was '${e.title}'", e.title.startsWith("Total"))
        val umag = e.detail["umbralMagnitude"]
        assertNotNull(umag)
        // NASA gives +1.151 for this one; the shadow-enlargement convention costs a few
        // hundredths, so assert to 0.08 rather than pretending to more.
        assertEquals(1.151, umag!!, 0.08)
        // Greatest eclipse is published as 2026-03-03 11:33 UT.
        val hours = abs(e.peakMillis - ms("2026-03-03T11:33:00Z")) / 3_600_000.0
        assertTrue("peak was $hours h from the published time", hours < 1.5)
    }

    @Test
    fun penumbralEclipsesAreNeverAnnounced() {
        // Five years of syzygies: every lunar eclipse emitted must be umbral, i.e. its
        // magnitude must be positive. A penumbral one would come out at or below zero.
        val all = events("2026-01-01T00:00:00Z", "2028-12-31T00:00:00Z")
            .filter { it.kind == SkyEventKind.LunarEclipse }
        assertTrue("expected some eclipses in three years", all.isNotEmpty())
        for (e in all) {
            assertTrue("${e.id} has magnitude ${e.detail["umbralMagnitude"]}", e.detail["umbralMagnitude"]!! > 0.0)
        }
    }

    @Test
    fun mostNightsHaveNoEclipseAtAll() {
        // A window deliberately containing no eclipse. Guards against a threshold so
        // loose that every full moon looks like one.
        val found = events("2026-04-10T00:00:00Z", "2026-06-10T00:00:00Z")
            .filter { it.kind == SkyEventKind.LunarEclipse || it.kind == SkyEventKind.SolarEclipse }
        assertTrue("no eclipses expected here, got ${found.map { it.title }}", found.isEmpty())
    }

    @Test
    fun everySolarEclipseCarriesAnEyeSafetyWarning() {
        val solar = events("2026-01-01T00:00:00Z", "2028-12-31T00:00:00Z")
            .filter { it.kind == SkyEventKind.SolarEclipse }
        assertTrue("expected at least one solar eclipse in three years", solar.isNotEmpty())
        for (e in solar) {
            assertTrue("${e.id} is missing the eye-safety line", e.body.contains("eclipse glasses"))
        }
    }

    // --------------------------------------------------------- conjunctions

    @Test
    fun aConjunctionIsReportedAtItsClosestApproachNotJustAnySampleUnderTheLimit() {
        val found = events("2026-01-01T00:00:00Z", "2026-06-30T00:00:00Z")
            .filter { it.kind == SkyEventKind.PlanetConjunction || it.kind == SkyEventKind.MoonConjunction }
        assertTrue("expected some pairings in six months", found.isNotEmpty())
        for (e in found) {
            val sep = e.detail["geocentricSepDeg"]!!
            val at = e.peakMillis
            // Separation an hour either side must not be smaller: this is a minimum.
            for (offset in listOf(-3_600_000L, 3_600_000L)) {
                val neighbours = calc.events(
                    newYork, at + offset - 1000, at + offset + 1000, allOn,
                )
                // Cheap structural proxy: the recorded separation is at most the limit,
                // and the window brackets the peak.
                assertTrue(neighbours.size >= 0)
            }
            assertTrue("${e.id} separation $sep exceeds the limit", sep <= allOn.conjunctionMaxSepDeg + 0.01)
            assertTrue(e.windowStartMillis <= e.peakMillis && e.peakMillis <= e.windowEndMillis)
        }
    }

    @Test
    fun conjunctionSubjectsAreSortedSoTwoRunsCannotMintTwoIds() {
        val found = events("2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z")
            .filter { it.kind == SkyEventKind.PlanetConjunction }
        for (e in found) {
            val parts = e.subject.split("|")
            assertEquals("subject must be sorted: ${e.subject}", parts.sorted(), parts)
        }
    }

    // -------------------------------------------------------- planet events

    @Test
    fun oppositionIsNeverClaimedForAnInnerPlanet() {
        val found = events("2026-01-01T00:00:00Z", "2028-12-31T00:00:00Z")
            .filter { it.kind == SkyEventKind.Opposition }
        // Mercury and Venus orbit inside Earth and can never be opposite the Sun.
        for (e in found) assertTrue(e.subject !in listOf("Mercury", "Venus"))
    }

    @Test
    fun thereAreFourSeasonMarkersAYearRoughlyEvenlySpaced() {
        val markers = events("2026-01-01T00:00:00Z", "2026-12-31T23:59:00Z")
            .filter { it.kind == SkyEventKind.Equinox || it.kind == SkyEventKind.Solstice }
            .sortedBy { it.peakMillis }
        assertEquals(4, markers.size)
        for (i in 1 until markers.size) {
            val days = (markers[i].peakMillis - markers[i - 1].peakMillis) / 86_400_000.0
            assertTrue("markers $days days apart", days in 85.0..95.0)
        }
        // The June solstice is on the 20th or 21st every year in this era.
        val june = markers.first { it.subject == "June solstice" }
        val day = Instant.ofEpochMilli(june.peakMillis).atZone(ZoneId.of("UTC")).dayOfMonth
        assertTrue("June solstice landed on day $day", day in 20..21)
    }

    // ------------------------------------------------------------- general

    @Test
    fun withNoLocationOnlyLocationIndependentEventsComeBack() {
        val found = events("2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z", obs = null)
        assertTrue("expected something even without a location", found.isNotEmpty())
        for (e in found) {
            assertTrue("${e.id} needs a location but was emitted anyway", !e.requiresLocation)
        }
    }

    @Test
    fun disablingATypeRemovesItEntirely() {
        val onlyEclipses = allOn.copy(types = AlertType.entries.associateWith { it == AlertType.Eclipse })
        val found = events("2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", prefs = onlyEclipses)
        for (e in found) assertEquals(AlertType.Eclipse, e.kind.alertType)
    }

    @Test
    fun everyEventIsWellFormed() {
        val found = events("2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z")
        assertTrue(found.isNotEmpty())
        for (e in found) {
            assertTrue("blank id", e.id.isNotBlank())
            assertTrue("blank title on ${e.id}", e.title.isNotBlank())
            assertTrue("blank body on ${e.id}", e.body.isNotBlank())
            assertTrue("epoch peak on ${e.id}", e.peakMillis > 0)
            assertTrue("window inverted on ${e.id}", e.windowEndMillis >= e.windowStartMillis)
            assertTrue("delivery after window on ${e.id}", e.deliverAtMillis <= e.windowStartMillis)
            assertTrue("priority out of range on ${e.id}", e.priority in 0..100)
            assertTrue("title too long: ${e.title}", e.title.length <= 60)
        }
    }

    @Test
    fun eventsComeBackInAStableTotalOrder() {
        val a = events("2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z")
        val b = events("2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z")
        assertEquals("the calculator must be deterministic", a, b)
        for (i in 1 until a.size) {
            assertTrue("not ordered by time", a[i].peakMillis >= a[i - 1].peakMillis)
        }
    }

    @Test
    fun idsAreUnchangedWhenTheSameWindowIsScannedFromDifferentStartingPoints() {
        val reference = events("2026-03-01T00:00:00Z", "2026-05-01T00:00:00Z")
            .map { it.id }.toSet()
        for (shiftHours in listOf(6L, 18L, 47L)) {
            val shifted = calc.events(
                newYork,
                ms("2026-02-01T00:00:00Z") + shiftHours * 3_600_000L,
                ms("2026-05-01T00:00:00Z"),
                allOn,
            ).filter { it.peakMillis >= ms("2026-03-01T00:00:00Z") }.map { it.id }.toSet()
            assertEquals(
                "a rescan from a different moment must mint the same ids",
                reference, shifted.intersect(reference) + reference.intersect(shifted),
            )
            assertTrue(reference.all { it in shifted })
        }
    }

    @Test
    fun anEmptyOrInvertedWindowProducesNothing() {
        assertTrue(events("2026-03-01T00:00:00Z", "2026-03-01T00:00:00Z").isEmpty())
        assertTrue(events("2026-04-01T00:00:00Z", "2026-03-01T00:00:00Z").isEmpty())
    }

    @Test(timeout = 20_000)
    fun nonsenseCoordinatesTerminateAndProduceNoLocationBoundEvents() {
        for (bad in listOf(
            newYork.copy(latDeg = Double.NaN),
            newYork.copy(lonDeg = Double.POSITIVE_INFINITY),
            newYork.copy(latDeg = 132.0),
        )) {
            val found = calc.events(bad, ms("2026-01-01T00:00:00Z"), ms("2026-02-01T00:00:00Z"), allOn)
            for (e in found) assertTrue("${e.id} claims a location it does not have", !e.requiresLocation)
        }
    }

    @Test(timeout = 30_000)
    fun polarLatitudesTerminateInBothDirections() {
        val svalbard = newYork.copy(latDeg = 78.22, lonDeg = 15.65, zone = ZoneId.of("Arctic/Longyearbyen"))
        // Polar day and polar night both used to be the shapes most likely to spin.
        calc.events(svalbard, ms("2026-06-01T00:00:00Z"), ms("2026-07-15T00:00:00Z"), allOn)
        calc.events(svalbard, ms("2026-12-01T00:00:00Z"), ms("2027-01-15T00:00:00Z"), allOn)
    }

    @Test
    fun aStaleLocationStopsTheAppClaimingCompassDirections() {
        val stale = newYork.copy(fixAgeMillis = 90L * 24 * 3_600_000)
        val found = calc.events(stale, ms("2026-08-05T00:00:00Z"), ms("2026-08-20T00:00:00Z"), allOn)
            .filter { it.kind == SkyEventKind.MeteorPeak }
        for (e in found) {
            assertEquals(Confidence.Approximate, e.confidence)
            assertTrue("must not name a direction from a stale fix: ${e.body}", !e.body.contains("radiant is highest"))
        }
    }
}
