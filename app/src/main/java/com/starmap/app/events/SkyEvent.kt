package com.starmap.app.events

import kotlin.math.abs
import kotlin.math.floor

/** What actually happened in the sky. Several kinds map onto one user-facing [AlertType]. */
enum class SkyEventKind(val alertType: AlertType) {
    MeteorPeak(AlertType.MeteorPeak),
    LunarEclipse(AlertType.Eclipse),
    SolarEclipse(AlertType.Eclipse),
    PlanetConjunction(AlertType.Conjunction),
    MoonConjunction(AlertType.Conjunction),
    Opposition(AlertType.PlanetEvent),
    GreatestElongation(AlertType.PlanetEvent),
    Equinox(AlertType.SeasonMarker),
    Solstice(AlertType.SeasonMarker),
    IssPass(AlertType.IssPass),
    FullMoon(AlertType.MoonPhase),
    NewMoon(AlertType.MoonPhase),
    DarkNight(AlertType.DarkSkyNight),
}

/**
 * How much weight the wording may carry. [Approximate] events must not state altitudes,
 * compass directions or times as if they were certain — either because the underlying
 * model is weak for that body, or because the observer's location is stale.
 */
enum class Confidence { Firm, Approximate }

/**
 * One thing worth telling someone about.
 *
 * [peakMillis] is when it happens; [windowStartMillis]..[windowEndMillis] is when it can
 * actually be seen; [deliverAtMillis] is when to say so, always at or before the window
 * opens; after [validUntilMillis] the notification would be a lie and must be dropped
 * rather than posted late.
 */
data class SkyEvent(
    val id: String,
    val kind: SkyEventKind,
    /** "Perseids", "Mars", "jupiter|venus" (pair always sorted). */
    val subject: String,
    val peakMillis: Long,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val deliverAtMillis: Long,
    val validUntilMillis: Long,
    val priority: Int,
    val title: String,
    val body: String,
    val confidence: Confidence,
    val requiresLocation: Boolean,
    /** Set only in the in-app preview, to explain why something would not be sent. */
    val suppressedBy: String? = null,
    /** Numbers behind the copy, for the preview and for tests. */
    val detail: Map<String, Double> = emptyMap(),
)

/**
 * Stable event identity.
 *
 * The whole feature's credibility rests on never announcing the same thing twice, and
 * the id is what prevents it. So every id is a function of slowly-varying **integers** —
 * a lunation index, a calendar year, a synodic-passage number — and never of a raw
 * timestamp. A recomputation that moves an eclipse by four minutes, or a future
 * refinement to the ephemeris that moves it by an hour, must still produce a
 * byte-identical id.
 */
object EventIds {

    /**
     * Which lunation an instant falls in.
     *
     * Meeus numbers lunations from the new moon at JD 2451550.09766, so new moons sit on
     * integer boundaries and full moons exactly half way between. Rounding — or flooring
     * — the raw quotient therefore puts one of the two right on a bin edge, where a
     * shift of a few hours flips the index and mints a second id for the same event.
     * The quarter-lunation offset moves both roughly a week clear of any boundary, so
     * new and full moon in the same lunation share an index and neither can flip.
     */
    fun lunation(jd: Double): Int =
        floor((jd - 2_451_550.09766) / 29.530588861 + 0.25).toInt()

    fun fullMoon(jd: Double) = "moon:full:${lunation(jd)}"
    fun newMoon(jd: Double) = "moon:new:${lunation(jd)}"
    fun lunarEclipse(jd: Double) = "ecl:lunar:${lunation(jd)}"
    fun solarEclipse(jd: Double) = "ecl:solar:${lunation(jd)}"
    fun darkNight(jd: Double) = "dark:${lunation(jd)}"
    fun meteor(name: String, year: Int) = "meteor:${slug(name)}:$year"
    fun season(marker: String, year: Int) = "season:${slug(marker)}:$year"
    fun issPass(nightKey: String) = "iss:$nightKey"

    fun opposition(planet: String, jd: Double) =
        "opp:${slug(planet)}:${passage(planet, jd)}"

    fun elongation(planet: String, east: Boolean, jd: Double) =
        "gelong:${slug(planet)}:${if (east) "e" else "w"}:${passage(planet, jd)}"

    /**
     * The pair is sorted before it goes into the id, so two scans that happen to visit
     * Venus and Jupiter in a different order still mint one id, not two.
     */
    fun conjunction(a: String, b: String, jd: Double): String {
        val pair = listOf(slug(a), slug(b)).sorted()
        val index = if (pair[0] == "moon" || pair[1] == "moon") {
            moonLap(if (pair[0] == "moon") pair[1] else pair[0], jd)
        } else {
            // Two planets meet at their mutual synodic period, 47 days at the very
            // shortest, so a 30-day bucket can never hold two of them.
            floor((jd - 2_451_545.0) / 30.0).toInt()
        }
        return "conj:${pair[0]}|${pair[1]}:$index"
    }

    /**
     * Which lap of the Moon past this planet a meeting belongs to.
     *
     * The Moon laps a planet every 26 to 33 days — shorter than a 30-day bucket whenever
     * the planet is retrograde — so a fixed bucket silently swallows one of two
     * consecutive meetings and it is never announced. Counting the laps cannot. Mean
     * longitudes are enough here, and the index steps while the pair is opposite, a
     * fortnight from any meeting, so no plausible refinement carries an event across a
     * boundary.
     */
    private fun moonLap(planet: String, jd: Double): Int {
        val d = jd - 2_451_545.0
        val moonLon = 218.3165 + 13.17639648 * d
        val planetLon = when (planet) {
            // Mercury and Venus never stray far from the Sun, so the Sun's own mean
            // longitude tracks them closely enough to separate consecutive meetings.
            "mercury", "venus" -> 280.4665 + 0.98564736 * d
            "mars" -> 355.433 + 0.52403304 * d
            "jupiter" -> 34.351 + 0.08308530 * d
            "saturn" -> 50.077 + 0.03344414 * d
            else -> 280.4665 + 0.98564736 * d
        }
        // Half a lap offset so the index steps at opposition rather than at conjunction.
        return floor((moonLon - planetLon) / 360.0 + 0.5).toInt()
    }

    /**
     * ASCII-only, so an id is safe to put in the `starmap://event/...` URI a notification
     * carries. Restricted to a-z0-9 rather than [Char.isLetterOrDigit], which happily
     * passes through the Greek letters in names like "Southern delta Aquariids".
     */
    fun slug(s: String): String =
        s.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')

    /** Which apparition of this planet — a genuine integer counter, not a date. */
    private fun passage(planet: String, jd: Double): Int =
        floor((jd - 2_451_545.0) / synodicPeriod(planet)).toInt()

    private fun synodicPeriod(planet: String): Double = when (planet.lowercase()) {
        "mercury" -> 115.88
        "venus" -> 583.92
        "mars" -> 779.94
        "jupiter" -> 398.88
        "saturn" -> 378.09
        "uranus" -> 369.66
        "neptune" -> 367.49
        else -> 365.25
    }

    /**
     * A notification id derived from the event id, so re-posting the same event replaces
     * the existing notification instead of stacking a duplicate. Collisions are
     * acceptable and benign — one replaces an older, almost certainly stale notification
     * — whereas a counter would lose that idempotence, which matters far more.
     */
    fun notificationId(eventId: String): Int = 2_000_000 + abs(eventId.hashCode()) % 100_000
}
