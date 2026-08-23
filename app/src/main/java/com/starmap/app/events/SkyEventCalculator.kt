package com.starmap.app.events

import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.MeteorShowers
import com.starmap.app.astro.Planets
import com.starmap.app.astro.SunMoon
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Works out what is worth stepping outside for, from the app's own ephemeris and
 * nothing else — no network, no backend, no service.
 *
 * Pure and total: the same inputs always produce the same list, with no clock, no
 * randomness and no iteration-order leakage, which is what makes the whole thing
 * testable on the JVM and what makes event ids stable between runs.
 *
 * ## What it will not claim
 *
 * The underlying models are low-precision (Schlyter for the Sun, Moon and planets), and
 * several statements that look natural are simply not supportable by them. None of
 * these appear in any string this class produces:
 *
 *  - **A meteor shower peak time.** The shower table stores a date with no hour, and a
 *    real maximum drifts half a day either way. Peaks are nights here, never times.
 *  - **Eclipse contact times or a precise magnitude.** A few arc-minutes of lunar error
 *    is ten minutes of contact time. Durations are rounded and hedged; obscuration is a
 *    bucket, not a number.
 *  - **Totality without margin.** Near the edge of a path the honest answer is to send
 *    the reader to a dedicated eclipse map.
 *  - **A meteor count.** ZHR is a zenithal rate under a magnitude-6.5 sky; from a suburb
 *    you see several times fewer, so the wording is always "up to N an hour under a
 *    dark sky".
 */
class SkyEventCalculator(private val cfg: EventConfig = EventConfig()) {

    /**
     * Every event between [fromMillis] and [toMillis] that [prefs] has switched on.
     *
     * A null [observer] means the location is unknown: only events that are the same
     * everywhere on Earth come back, and they say nothing about altitude or direction.
     */
    fun events(
        observer: Observer?,
        fromMillis: Long,
        toMillis: Long,
        prefs: AlertPrefs,
        issPasses: List<IssPass> = emptyList(),
    ): List<SkyEvent> {
        if (toMillis <= fromMillis) return emptyList()
        val obs = observer?.takeIf { it.isUsable }
        val zone = obs?.zone ?: ZoneId.of("UTC")
        val out = ArrayList<SkyEvent>()

        if (prefs[AlertType.MeteorPeak]) out += meteorPeaks(obs, zone, fromMillis, toMillis, prefs)
        if (prefs[AlertType.Eclipse]) out += eclipses(obs, zone, fromMillis, toMillis)
        if (prefs[AlertType.Conjunction]) out += conjunctions(obs, zone, fromMillis, toMillis, prefs)
        if (prefs[AlertType.PlanetEvent]) out += planetEvents(obs, zone, fromMillis, toMillis, prefs)
        if (prefs[AlertType.SeasonMarker]) out += seasonMarkers(zone, fromMillis, toMillis)
        if (prefs[AlertType.MoonPhase]) out += moonPhases(obs, zone, fromMillis, toMillis)
        if (prefs[AlertType.DarkSkyNight]) out += darkNights(obs, zone, fromMillis, toMillis, prefs)
        if (prefs[AlertType.IssPass]) out += issEvents(obs, zone, fromMillis, toMillis, prefs, issPasses)

        return dedupe(out, zone)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Local altitude and azimuth. Note this takes the true Julian Day: sidereal time is
     * correctly J2000-referenced and must not be handed Schlyter's day number.
     */
    private fun altAz(raDeg: Double, decDeg: Double, jd: Double, lat: Double, lon: Double) =
        AstroMath.toHorizontal(
            AstroMath.equatorialToVec(raDeg, decDeg),
            AstroMath.enuBasis(AstroMath.lstDegrees(jd, lon), lat),
        )

    private fun jd(atMillis: Long) = AstroMath.julianDay(atMillis)
    private fun millis(jdValue: Double) = ((jdValue - 2_440_587.5) * 86_400_000.0).toLong()

    /** Golden-section minimisation of a smooth unimodal function of Julian Day. */
    private fun goldenMin(lo: Double, hi: Double, tolDays: Double, f: (Double) -> Double): Double {
        val phi = 0.6180339887498949
        var a = lo
        var b = hi
        var c = b - phi * (b - a)
        var d = a + phi * (b - a)
        var fc = f(c)
        var fd = f(d)
        var guard = 0
        while (b - a > tolDays && guard < 200) {
            if (fc < fd) {
                b = d; d = c; fd = fc; c = b - phi * (b - a); fc = f(c)
            } else {
                a = c; c = d; fc = fd; d = a + phi * (b - a); fd = f(d)
            }
            guard++
        }
        return 0.5 * (a + b)
    }

    private fun goldenMax(lo: Double, hi: Double, tolDays: Double, f: (Double) -> Double): Double =
        goldenMin(lo, hi, tolDays) { -f(it) }

    /** Nights covered by the range, keyed by the civil date of each evening. */
    private fun nightsIn(zone: ZoneId, fromMillis: Long, toMillis: Long): List<LocalDate> {
        val first = nightKeyOf(fromMillis, zone)
        val last = nightKeyOf(toMillis, zone)
        val out = ArrayList<LocalDate>()
        var d = first
        while (!d.isAfter(last)) { out.add(d); d = d.plusDays(1) }
        return out
    }

    /**
     * When to say something about a night: shortly after it gets dark, pulled forward by
     * the user's lead time so there is still a chance to drive somewhere darker.
     */
    private fun deliveryFor(night: NightWindow?, nightKey: LocalDate, zone: ZoneId, leadDays: Int): Long {
        val base = night?.duskMillis?.minus(30 * 60_000L)
            ?: localNoonMillis(nightKey, zone) + 7 * 3_600_000L // 19:00 local if we have no dusk
        return base - leadDays.coerceIn(0, 7) * 24L * 3_600_000L
    }

    private fun confidenceFor(obs: Observer?, base: Confidence = Confidence.Firm): Confidence =
        if (obs == null || obs.isStale) Confidence.Approximate else base

    // ---------------------------------------------------------------- meteors

    private fun meteorPeaks(
        obs: Observer?,
        zone: ZoneId,
        fromMillis: Long,
        toMillis: Long,
        prefs: AlertPrefs,
    ): List<SkyEvent> {
        if (obs == null) return emptyList() // a radiant you can't place is not worth sending
        val out = ArrayList<SkyEvent>()
        val years = (utcDateOf(fromMillis).year..utcDateOf(toMillis).year).toList()

        for (shower in MeteorShowers.all) {
            if (shower.zhr < prefs.meteorMinZhr) continue
            for (year in years) {
                val peakDate = try {
                    LocalDate.of(year, shower.peak / 100, shower.peak % 100)
                } catch (e: Exception) {
                    continue // e.g. a peak stored as Feb 29 in a non-leap year
                }

                // "Peak on the 12th" is genuinely ambiguous between the night of the 11th
                // and the night of the 12th, and at far-western longitudes the UTC peak
                // date may not overlap the night you would name at all. So score both and
                // let the sky decide, rather than special-casing time zones.
                var best: Pair<NightWindow, Double>? = null
                var runnerUp: Pair<NightWindow, Double>? = null
                for (offset in -1L..0L) {
                    val key = peakDate.plusDays(offset)
                    val night = nightWindow(obs, key, cfg) ?: continue
                    if (night.darknessMillis < cfg.minDarknessMinutes * 60_000L) continue
                    val score = showerScore(shower, night, obs, peakDate)
                    if (score <= 0.0) continue
                    if (best == null || score > best!!.second) {
                        runnerUp = best
                        best = night to score
                    } else if (runnerUp == null || score > runnerUp!!.second) {
                        runnerUp = night to score
                    }
                }
                val (night, score) = best ?: continue
                val dusk = night.duskMillis ?: continue
                val dawn = night.dawnMillis ?: continue
                if (dusk > toMillis || dawn < fromMillis) continue

                // Where the radiant gets to, and when — the detail that makes it useful.
                var bestAlt = -90.0
                var bestAltAt = dusk
                var t = dusk
                while (t <= dawn) {
                    val h = altAz(shower.raDeg, shower.decDeg, jd(t), obs.latDeg, obs.lonDeg)
                    if (h.altitudeDeg > bestAlt) { bestAlt = h.altitudeDeg; bestAltAt = t }
                    t += 15 * 60_000L
                }
                if (bestAlt < cfg.minRadiantAltDeg) continue

                val moonUp = night.moonlessMillis < night.darknessMillis
                val moonNote = EventText.moonNote(
                    night.moonIllumAtMid, moonUp, night.moonSetInWindow, zone,
                )
                // Skip only when the Moon actually spoils most of the dark window — one
                // that sets an hour in leaves a perfectly good night behind it.
                if (prefs.moonlight == AlertPrefs.MOONLIGHT_SKIP &&
                    night.moonlessMillis * 2 < night.darknessMillis && night.moonIllumAtMid > 0.5
                ) {
                    continue
                }

                val az = altAz(shower.raDeg, shower.decDeg, jd(bestAltAt), obs.latDeg, obs.lonDeg)
                val stale = obs.isStale
                val where = if (stale) "" else " in the ${EventText.compass(az.azimuthDeg)}"
                val sharp = shower.name in SHARP_PEAKED

                // The alert is delivered `leadDays` ahead, so "tonight" is only true when
                // that is zero. The wording has to follow the lead or it names the wrong
                // night along with times that belong to a different one.
                val lead = prefs.leadDays.coerceIn(0, 7)
                val whenNight = when (lead) {
                    0 -> "tonight"
                    1 -> "tomorrow night"
                    else -> "in $lead nights"
                }
                // ZHR is the rate with the radiant at the zenith. The sky can only deliver
                // that scaled by sin(radiant altitude) — the same factor the scoring uses —
                // so quoting the raw figure would overstate it several-fold at low altitude.
                val ceiling = (shower.zhr * sin(Math.toRadians(bestAlt))).roundToInt().coerceAtLeast(1)

                val body = buildString {
                    append("Up to $ceiling an hour under a dark sky. ")
                    append("Dark ${if (lead == 0) "" else "$whenNight "}")
                    append("from ${EventText.clock(dusk, zone)} to ${EventText.clock(dawn, zone)}")
                    if (!stale) {
                        append("; the radiant is highest (${EventText.degrees(bestAlt)}$where) ")
                        append("around ${EventText.clock(bestAltAt, zone)}")
                    }
                    append(". ")
                    if (prefs.moonlight != AlertPrefs.MOONLIGHT_IGNORE && moonNote != null) {
                        append(moonNote).append(' ')
                    }
                    if (sharp) append("This one peaks briefly, so rates can vary a lot. ")
                    // The runner-up is whichever candidate night lost, which is often the
                    // night BEFORE this one — no use to anyone reading the alert.
                    val runnerUpNight = runnerUp?.first?.nightKey
                    if (runnerUpNight != null && runnerUpNight.isAfter(night.nightKey) &&
                        runnerUp!!.second > 0.85 * score
                    ) {
                        append("The night after should be nearly as good. ")
                    }
                    append("No equipment needed — just look up.")
                }.trim()

                out.add(
                    SkyEvent(
                        id = EventIds.meteor(shower.name, year),
                        kind = SkyEventKind.MeteorPeak,
                        subject = shower.name,
                        peakMillis = bestAltAt,
                        windowStartMillis = dusk,
                        windowEndMillis = dawn,
                        deliverAtMillis = deliveryFor(night, night.nightKey, zone, prefs.leadDays),
                        validUntilMillis = dawn,
                        priority = if (shower.zhr >= 50) 70 else 40,
                        title = "${shower.name} peak $whenNight",
                        body = body,
                        confidence = if (sharp) Confidence.Approximate else confidenceFor(obs),
                        requiresLocation = true,
                        detail = mapOf(
                            "zhr" to shower.zhr.toDouble(),
                            "maxRadiantAltDeg" to bestAlt,
                            "moonIllum" to night.moonIllumAtMid,
                            "score" to score,
                        ),
                    ),
                )
            }
        }
        return out
    }

    /**
     * How good a night actually is for a shower: the zenithal rate cut down by how low
     * the radiant sits and how much moonlight is in the way, integrated across the dark
     * hours, then weighted by how much of that night falls on the peak date.
     */
    private fun showerScore(
        shower: MeteorShowers.Shower,
        night: NightWindow,
        obs: Observer,
        peakDateUtc: LocalDate,
    ): Double {
        val dusk = night.duskMillis ?: return 0.0
        val dawn = night.dawnMillis ?: return 0.0
        val step = 15 * 60_000L
        var total = 0.0
        var onPeakDate = 0L
        var samples = 0L
        var t = dusk
        while (t <= dawn) {
            val j = jd(t)
            val radiantAlt = altAz(shower.raDeg, shower.decDeg, j, obs.latDeg, obs.lonDeg).altitudeDeg
            if (radiantAlt > 0) {
                val moon = SunMoon.moon(j, obs.latDeg, AstroMath.lstDegrees(j, obs.lonDeg))
                val moonAlt = altAz(moon.raDeg, moon.decDeg, j, obs.latDeg, obs.lonDeg).altitudeDeg
                val illum = SunMoon.moonPhase(j).illuminatedFraction
                val interference = if (moonAlt <= 0) 0.0 else illum * sin(Math.toRadians(moonAlt))
                val rate = shower.zhr * max(0.0, sin(Math.toRadians(radiantAlt))) *
                    (1.0 - cfg.moonlightCoefficient * interference)
                total += max(0.0, rate)
            }
            if (utcDateOf(t) == peakDateUtc) onPeakDate++
            samples++
            t += step
        }
        if (samples == 0L) return 0.0
        val overlap = onPeakDate.toDouble() / samples
        return total * (0.5 + 0.5 * overlap)
    }

    // ---------------------------------------------------------------- eclipses

    private fun eclipses(obs: Observer?, zone: ZoneId, fromMillis: Long, toMillis: Long): List<SkyEvent> {
        val out = ArrayList<SkyEvent>()
        for (syzygy in syzygies(fromMillis, toMillis)) {
            if (syzygy.full) {
                lunarEclipse(syzygy.jd, obs, zone, fromMillis, toMillis)?.let { out.add(it) }
            } else {
                solarEclipse(syzygy.jd, obs, zone, fromMillis, toMillis)?.let { out.add(it) }
            }
        }
        return out
    }

    private data class Syzygy(val jd: Double, val full: Boolean)

    /** Every new and full moon in the range, from the phase solver. */
    private fun syzygies(fromMillis: Long, toMillis: Long): List<Syzygy> {
        val out = ArrayList<Syzygy>()
        // Start a day early so a syzygy already under way when the scan fires is still
        // found; the range filter below discards anything genuinely before the window.
        var cursor = fromMillis - 86_400_000L
        // One iteration covers roughly a lunation, so the cap has to scale with the window
        // or a long scan stops early and silently returns nothing past about three years.
        val maxIterations = ((toMillis - fromMillis) / 29.53 / 86_400_000L).toInt() + 4
        var guard = 0
        while (cursor < toMillis && guard < maxIterations) {
            val phases = SunMoon.nextMoonPhases(jd(cursor))
            if (phases.isEmpty()) break
            for (p in phases) {
                if (p.timeMillis in fromMillis..toMillis) {
                    when (p.name) {
                        "Full moon" -> out.add(Syzygy(jd(p.timeMillis), true))
                        "New moon" -> out.add(Syzygy(jd(p.timeMillis), false))
                    }
                }
            }
            cursor = phases.maxOf { it.timeMillis } + 60_000L
            guard++
        }
        return out.distinctBy { EventIds.lunation(it.jd).toString() + it.full }.sortedBy { it.jd }
    }

    /**
     * Umbral and total lunar eclipses. Penumbral ones are detected so the boundary is
     * drawn correctly, but never announced: they are invisible to the naked eye, and the
     * model cannot classify them reliably either.
     */
    private fun lunarEclipse(
        fullJd: Double,
        obs: Observer?,
        zone: ZoneId,
        fromMillis: Long,
        toMillis: Long,
    ): SkyEvent? {
        fun sepFromAntisolar(j: Double): Double {
            val s = SunMoon.sun(j)
            val m = SunMoon.moonGeocentric(j)
            return AstroMath.angularSepDeg(
                AstroMath.norm360(s.raDeg + 180.0), -s.decDeg, m.raDeg, m.decDeg,
            )
        }
        val tMin = goldenMin(fullJd - 0.5, fullJd + 0.5, 1e-8, ::sepFromAntisolar)
        val beta = sepFromAntisolar(tMin)

        val sunAt = SunMoon.sun(tMin)
        val moonAt = SunMoon.moonGeocentric(tMin)
        val rm = moonAt.distance                      // Earth radii
        val rs = sunAt.distance                       // AU
        val parMoon = Math.toDegrees(asin((1.0 / rm).coerceIn(-1.0, 1.0)))
        val parSun = 0.002442 / rs
        val semiSun = 0.266563 / rs
        val semiMoon = Math.toDegrees(asin((0.272481 / rm).coerceIn(-1.0, 1.0)))
        // 1.02 is the conventional atmospheric enlargement of Earth's shadow — a stated
        // convention (sources give 1.005 to 1.02), not something measured here.
        val umbra = 1.02 * (parMoon + parSun - semiSun)
        val umbralMagnitude = (umbra + semiMoon - beta) / (2 * semiMoon)

        val type = when {
            beta < umbra - semiMoon -> "Total"
            beta < umbra + semiMoon -> "Partial"
            else -> return null // penumbral or nothing: never announced
        }

        val peak = millis(tMin)
        if (peak !in fromMillis..toMillis) return null

        var visible = true
        var placeText = ""
        if (obs != null && !obs.isStale) {
            val topo = SunMoon.moon(tMin, obs.latDeg, AstroMath.lstDegrees(tMin, obs.lonDeg))
            val h = altAz(topo.raDeg, topo.decDeg, tMin, obs.latDeg, obs.lonDeg)
            visible = h.altitudeDeg > 0
            placeText = if (visible) {
                ", with the Moon ${EventText.placeInSky(h.azimuthDeg, h.altitudeDeg)}"
            } else {
                ", when the Moon is ${EventText.degrees(-h.altitudeDeg)} below your horizon"
            }
        }

        // How long the Moon stays inside the shadow, bisected on the same separation curve
        // the magnitude came from and then bucketed. The model supports a band, never a
        // contact time, so the wording stays a band.
        val innerRadius = if (type == "Total") umbra - semiMoon else umbra + semiMoon
        fun contact(direction: Int): Double {
            var lo = tMin
            var hi = tMin + direction * 0.25
            if (sepFromAntisolar(hi) < innerRadius) return hi
            repeat(60) {
                val mid = 0.5 * (lo + hi)
                if (sepFromAntisolar(mid) < innerRadius) lo = mid else hi = mid
            }
            return 0.5 * (lo + hi)
        }
        val shadowMinutes = (contact(1) - contact(-1)) * 1440.0
        val span = when {
            shadowMinutes <= 45.0 -> "under an hour"
            shadowMinutes <= 75.0 -> "about an hour"
            shadowMinutes <= 105.0 -> "an hour and a half or so"
            shadowMinutes <= 165.0 -> "a couple of hours"
            else -> "over three hours"
        }

        val title = if (visible) "$type lunar eclipse tonight" else "$type lunar eclipse — not visible from here"
        val body = buildString {
            append("Deepest around ${EventText.clock(peak, zone)}$placeText. ")
            if (visible) {
                if (type == "Total") {
                    append("The Moon sits fully inside Earth's shadow for $span and turns a deep red. ")
                } else {
                    append("Earth's shadow takes a visible bite out of the Moon for $span. ")
                }
                append("Safe to look at with no equipment at all.")
            } else {
                append("It is happening, but below your horizon the whole time.")
            }
        }

        return SkyEvent(
            id = EventIds.lunarEclipse(tMin),
            kind = SkyEventKind.LunarEclipse,
            subject = "Moon",
            peakMillis = peak,
            windowStartMillis = peak - 2 * 3_600_000L,
            windowEndMillis = peak + 2 * 3_600_000L,
            deliverAtMillis = peak - 5 * 3_600_000L,
            validUntilMillis = peak + 3_600_000L,
            priority = if (type == "Total") 95 else 75,
            title = title,
            body = body,
            confidence = Confidence.Firm,
            requiresLocation = false,
            detail = mapOf("umbralMagnitude" to umbralMagnitude, "betaDeg" to beta),
        )
    }

    /**
     * Solar eclipses. Detection is global; local circumstances are first-order only, and
     * every string carries an eye-safety sentence with no exceptions.
     */
    private fun solarEclipse(
        newJd: Double,
        obs: Observer?,
        zone: ZoneId,
        fromMillis: Long,
        toMillis: Long,
    ): SkyEvent? {
        fun sep(j: Double): Double {
            val s = SunMoon.sun(j)
            val m = SunMoon.moonGeocentric(j)
            return AstroMath.angularSepDeg(s.raDeg, s.decDeg, m.raDeg, m.decDeg)
        }
        val tMin = goldenMin(newJd - 0.6, newJd + 0.6, 1e-8, ::sep)
        val gamma = sep(tMin)

        val sunAt = SunMoon.sun(tMin)
        val moonAt = SunMoon.moonGeocentric(tMin)
        val semiSun = 0.266563 / sunAt.distance
        val semiMoon = Math.toDegrees(asin((0.272481 / moonAt.distance).coerceIn(-1.0, 1.0)))
        val parMoon = Math.toDegrees(asin((1.0 / moonAt.distance).coerceIn(-1.0, 1.0)))
        val parSun = 0.002442 / sunAt.distance
        if (gamma >= semiSun + semiMoon + parMoon - parSun) return null // no eclipse anywhere

        // Seen from the sub-lunar point the Moon is one Earth radius closer, which is what
        // decides total versus annular.
        val semiMoonTopo = Math.toDegrees(
            asin((0.272481 / (moonAt.distance - 1.0)).coerceIn(-1.0, 1.0)),
        )
        val ratio = semiMoonTopo / semiSun
        // The size ratio decides total versus annular only if the shadow axis reaches
        // Earth at all. This is the axis's miss distance from Earth's centre in Earth
        // radii; past the conventional limit the eclipse is partial everywhere, however
        // large the Moon happens to look from the ground.
        val axisMissRe = moonAt.distance * sin(Math.toRadians(gamma))
        val central = axisMissRe < 0.997
        val globalType = when {
            !central -> "Partial"
            abs(ratio - 1.0) < 0.01 -> "Total or annular"
            ratio > 1.0 -> "Total"
            else -> "Annular"
        }

        val peakGlobal = millis(tMin)
        if (peakGlobal !in fromMillis..toMillis) return null

        var localPeak = peakGlobal
        var localText: String
        var priority = 60
        var title = "$globalType solar eclipse today — not from here"

        if (obs != null && !obs.isStale) {
            fun topoSep(j: Double): Double {
                val s = SunMoon.sun(j)
                val m = SunMoon.moon(j, obs.latDeg, AstroMath.lstDegrees(j, obs.lonDeg))
                return AstroMath.angularSepDeg(s.raDeg, s.decDeg, m.raDeg, m.decDeg)
            }
            val tLocal = goldenMin(tMin - 0.12, tMin + 0.12, 1e-7, ::topoSep)
            val localSep = topoSep(tLocal)
            localPeak = millis(tLocal)
            val sunHere = SunMoon.sun(tLocal)
            val h = altAz(sunHere.raDeg, sunHere.decDeg, tLocal, obs.latDeg, obs.lonDeg)

            if (localSep < semiSun + semiMoon && h.altitudeDeg > 0) {
                // Fraction of the Sun's diameter covered, bucketed — the model does not
                // support quoting a number.
                val covered = ((semiSun + semiMoon - localSep) / (2 * semiSun)).coerceIn(0.0, 1.0)
                val bucket = when {
                    covered > 0.98 -> "almost all"
                    covered > 0.75 -> "most"
                    covered > 0.55 -> "about half"
                    covered > 0.3 -> "about a quarter"
                    else -> "a sliver"
                }
                // Never claim totality without room to spare. The umbra is only
                // (semiMoonTopo - semiSun) wide in this metric — a couple of arcminutes,
                // roughly a hundred km on the ground — so the margin has to scale to it,
                // and "near the edge" has to be a band around the path rather than
                // everywhere outside it.
                val umbraWidth = semiMoonTopo - semiSun
                val comfortablyTotal = central && ratio > 1.0 && localSep < 0.75 * umbraWidth
                val nearPathEdge = central && ratio > 1.0 && !comfortablyTotal &&
                    localSep < 1.25 * umbraWidth
                title = if (comfortablyTotal) {
                    "Total solar eclipse today"
                } else {
                    "Partial solar eclipse today"
                }
                priority = if (comfortablyTotal) 95 else 80
                localText = buildString {
                    append("From where you are, the Moon covers $bucket of the Sun around ")
                    append("${EventText.clock(localPeak, zone)}, with the Sun ")
                    append("${EventText.placeInSky(h.azimuthDeg, h.altitudeDeg)}. ")
                    if (nearPathEdge) {
                        append("You may be near the edge of the path of totality — check a dedicated eclipse map. ")
                    }
                }
            } else {
                localText = "It is not visible from where you are — this one runs across another part of the world. "
            }
        } else {
            localText = "Visible from parts of the world; Starmap needs your location to say whether that includes you. "
        }

        val body = localText +
            "Never look at the Sun without certified eclipse glasses — sunglasses, " +
            "smoked glass and a phone camera are not safe."

        return SkyEvent(
            id = EventIds.solarEclipse(tMin),
            kind = SkyEventKind.SolarEclipse,
            subject = "Sun",
            peakMillis = localPeak,
            windowStartMillis = localPeak - 90 * 60_000L,
            windowEndMillis = localPeak + 90 * 60_000L,
            deliverAtMillis = localPeak - 12 * 3_600_000L,
            validUntilMillis = localPeak,
            priority = priority,
            title = title,
            body = body,
            confidence = Confidence.Approximate,
            requiresLocation = false,
            detail = mapOf("gammaDeg" to gamma, "sizeRatio" to ratio, "axisMissRe" to axisMissRe),
        )
    }

    // ------------------------------------------------------------ conjunctions

    private val naked = listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn")

    private fun conjunctions(
        obs: Observer?,
        zone: ZoneId,
        fromMillis: Long,
        toMillis: Long,
        prefs: AlertPrefs,
    ): List<SkyEvent> {
        val out = ArrayList<SkyEvent>()
        val threshold = prefs.conjunctionMaxSepDeg.toDouble()

        fun planetPos(name: String, j: Double) = Planets.positions(j).first { it.name == name }

        // Planet-planet: a genuine local minimum, not merely a sample under the
        // threshold, so the separation quoted is the real closest approach and the id
        // lands on the same passage every time.
        for (i in naked.indices) {
            for (k in i + 1 until naked.size) {
                val a = naked[i]
                val b = naked[k]
                fun sep(j: Double) = Planets.positions(j).let { ps ->
                    val pa = ps.first { it.name == a }
                    val pb = ps.first { it.name == b }
                    AstroMath.angularSepDeg(pa.raDeg, pa.decDeg, pb.raDeg, pb.decDeg)
                }
                scanMinima(fromMillis, toMillis, 1.0, ::sep) { tMin, sepMin ->
                    if (sepMin <= threshold) {
                        conjunctionEvent(a, b, tMin, sepMin, obs, zone, prefs, false)?.let { out.add(it) }
                    }
                }
            }
        }

        // Moon-planet: the Moon moves ~13°/day, so it needs a finer step to bracket.
        for (p in naked) {
            fun sep(j: Double): Double {
                val m = SunMoon.moonGeocentric(j)
                val pp = planetPos(p, j)
                return AstroMath.angularSepDeg(m.raDeg, m.decDeg, pp.raDeg, pp.decDeg)
            }
            scanMinima(fromMillis, toMillis, 0.25, ::sep) { tMin, sepMin ->
                if (sepMin <= threshold) {
                    conjunctionEvent("Moon", p, tMin, sepMin, obs, zone, prefs, true)?.let { out.add(it) }
                }
            }
        }
        return out
    }

    /** Walk the range, bracket every local minimum, refine it, and hand it over. */
    private fun scanMinima(
        fromMillis: Long,
        toMillis: Long,
        stepDays: Double,
        f: (Double) -> Double,
        onMinimum: (tMin: Double, value: Double) -> Unit,
    ) {
        val start = jd(fromMillis)
        val end = jd(toMillis)
        // Bracketing needs a sample either side of the extremum, so the walk overhangs the
        // range by one step at each end and anything refining to outside is dropped again.
        // Without the overhang a minimum in the first or last half-step is never bracketed
        // and vanishes silently.
        var t0 = start - stepDays
        var f0 = f(t0)
        var t1 = start
        var f1 = f(t1)
        var t2 = t1 + stepDays
        while (t1 <= end + stepDays) {
            val f2 = f(t2)
            if (f1 < f0 && f1 <= f2) {
                val tMin = goldenMin(t0, t2, 1e-5, f)
                if (tMin in start..end) onMinimum(tMin, f(tMin))
            }
            t0 = t1; f0 = f1
            t1 = t2; f1 = f2
            t2 += stepDays
        }
    }

    private fun conjunctionEvent(
        a: String,
        b: String,
        tMin: Double,
        sepGeocentric: Double,
        obs: Observer?,
        zone: ZoneId,
        prefs: AlertPrefs,
        withMoon: Boolean,
    ): SkyEvent? {
        // A pairing too close to the Sun cannot be seen however tight it is.
        val sun = SunMoon.sun(tMin)
        val positions = Planets.positions(tMin)
        fun bodyRaDec(name: String): Pair<Double, Double> = if (name == "Moon") {
            SunMoon.moonGeocentric(tMin).let { it.raDeg to it.decDeg }
        } else {
            positions.first { it.name == name }.let { it.raDeg to it.decDeg }
        }
        val (raA, decA) = bodyRaDec(a)
        val (raB, decB) = bodyRaDec(b)
        val elongA = AstroMath.angularSepDeg(sun.raDeg, sun.decDeg, raA, decA)
        val elongB = AstroMath.angularSepDeg(sun.raDeg, sun.decDeg, raB, decB)
        if (elongA < cfg.conjunctionMinElongationDeg || elongB < cfg.conjunctionMinElongationDeg) return null

        val peak = millis(tMin)
        var viewText = ""
        var quotedSep = sepGeocentric
        var occultationNote = ""

        if (obs != null && !obs.isStale) {
            // The Moon's parallax is up to a degree, so a tight geocentric approach can be
            // much wider from where you actually stand. Find the minimum geocentrically —
            // that keeps the id stable — but quote what the observer will see.
            if (withMoon) {
                val moonTopo = SunMoon.moon(tMin, obs.latDeg, AstroMath.lstDegrees(tMin, obs.lonDeg))
                val other = if (a == "Moon") b else a
                val (ra2, dec2) = bodyRaDec(other)
                quotedSep = AstroMath.angularSepDeg(moonTopo.raDeg, moonTopo.decDeg, ra2, dec2)
                val semiMoon = Math.toDegrees(asin((0.272481 / moonTopo.distance).coerceIn(-1.0, 1.0)))
                if (quotedSep < semiMoon + 0.2) {
                    occultationNote = " The Moon may briefly cover $other as seen from some places — " +
                        "check a dedicated occultation prediction for your exact spot."
                }
            }
            // Best time to look: highest the pair gets while the sky is dark.
            val night = nightWindow(obs, nightKeyOf(peak, zone), cfg)
            val from = night?.sunsetMillis ?: (peak - 6 * 3_600_000L)
            val to = night?.dawnMillis ?: (peak + 6 * 3_600_000L)
            var bestAlt = -90.0
            var bestAt = from
            var t = from
            while (t <= to) {
                val h = altAz(raA, decA, jd(t), obs.latDeg, obs.lonDeg)
                if (h.altitudeDeg > bestAlt) { bestAlt = h.altitudeDeg; bestAt = t }
                t += 15 * 60_000L
            }
            if (bestAlt < prefs.minAltitudeDeg) return null
            val hz = altAz(raA, decA, jd(bestAt), obs.latDeg, obs.lonDeg)
            viewText = " Look ${EventText.placeInSky(hz.azimuthDeg, hz.altitudeDeg)} " +
                "around ${EventText.clock(bestAt, zone)}."
        }

        val rounded = Math.round(quotedSep * 10.0) / 10.0
        val tight = rounded < 0.5
        val nameA = if (a == "Moon") "The Moon" else a
        val title = if (withMoon) "$nameA meets $b" else "$a and $b, close together"
        val body = buildString {
            append("About ${EventText.degrees(rounded, 1)} apart")
            if (tight) append(" — close enough that a fingertip at arm's length covers both")
            append(".")
            append(viewText)
            append(occultationNote)
        }

        return SkyEvent(
            id = EventIds.conjunction(a, b, tMin),
            kind = if (withMoon) SkyEventKind.MoonConjunction else SkyEventKind.PlanetConjunction,
            subject = listOf(a, b).sorted().joinToString("|"),
            peakMillis = peak,
            windowStartMillis = peak - 12 * 3_600_000L,
            windowEndMillis = peak + 12 * 3_600_000L,
            deliverAtMillis = peak - 8 * 3_600_000L,
            validUntilMillis = peak + 12 * 3_600_000L,
            priority = if (tight) 65 else 40,
            title = title,
            body = body,
            // Mercury carries the largest error of the naked-eye planets.
            confidence = if (a == "Mercury" || b == "Mercury") {
                Confidence.Approximate
            } else {
                confidenceFor(obs)
            },
            requiresLocation = false,
            detail = mapOf("separationDeg" to quotedSep, "geocentricSepDeg" to sepGeocentric),
        )
    }

    // ---------------------------------------------------------- planet events

    private fun planetEvents(
        obs: Observer?,
        zone: ZoneId,
        fromMillis: Long,
        toMillis: Long,
        prefs: AlertPrefs,
    ): List<SkyEvent> {
        val out = ArrayList<SkyEvent>()

        // Opposition: an outer planet opposite the Sun, so it is up all night and at its
        // closest and brightest for this apparition.
        for (planet in listOf("Mars", "Jupiter", "Saturn")) {
            fun elong(j: Double): Double {
                val s = SunMoon.sun(j)
                val p = Planets.positions(j).first { it.name == planet }
                return AstroMath.angularSepDeg(s.raDeg, s.decDeg, p.raDeg, p.decDeg)
            }
            scanMinima(fromMillis, toMillis, 1.0, { j -> -elong(j) }) { tMax, negValue ->
                val elongation = -negValue
                // Elongation at opposition falls short of 180 by the planet's geocentric
                // ecliptic latitude — up to ~6 deg for Mars and ~3 deg for Saturn — so a
                // 178 gate admitted Jupiter and almost nothing else. There is exactly one
                // elongation maximum per synodic period, so a looser gate cannot admit
                // anything that is not an opposition.
                if (elongation > 172.0) {
                    val peak = millis(tMax)
                    val p = Planets.positions(tMax).first { it.name == planet }
                    var viewText = ""
                    if (obs != null && !obs.isStale) {
                        val night = nightWindow(obs, nightKeyOf(peak, zone), cfg)
                        val mid = night?.midDarkMillis ?: peak
                        val h = altAz(p.raDeg, p.decDeg, jd(mid), obs.latDeg, obs.lonDeg)
                        if (h.altitudeDeg > 0) {
                            viewText = " Around midnight it sits ${EventText.placeInSky(h.azimuthDeg, h.altitudeDeg)}."
                        }
                    }
                    out.add(
                        SkyEvent(
                            id = EventIds.opposition(planet, tMax),
                            kind = SkyEventKind.Opposition,
                            subject = planet,
                            peakMillis = peak,
                            windowStartMillis = peak - 3 * 24 * 3_600_000L,
                            windowEndMillis = peak + 3 * 24 * 3_600_000L,
                            deliverAtMillis = peak - 12 * 3_600_000L,
                            validUntilMillis = peak + 3 * 24 * 3_600_000L,
                            priority = 60,
                            title = "$planet at opposition",
                            body = "Opposite the Sun, so it rises at sunset, sets at sunrise and is up " +
                                "all night — the best few weeks of this apparition. " +
                                String.format(Locale.UK, "%.2f", p.distanceAu) +
                                " AU away, its closest for now." + viewText,
                            confidence = confidenceFor(obs),
                            requiresLocation = false,
                            detail = mapOf("elongationDeg" to elongation, "distanceAu" to p.distanceAu),
                        ),
                    )
                }
            }
        }

        // Greatest elongation: the fortnight Mercury or Venus stands furthest from the
        // Sun. Whether that is actually a good sight depends on how steeply the ecliptic
        // meets the horizon where you are, so the altitude is computed, never assumed.
        for (planet in listOf("Mercury", "Venus")) {
            fun elong(j: Double): Double {
                val s = SunMoon.sun(j)
                val p = Planets.positions(j).first { it.name == planet }
                return AstroMath.angularSepDeg(s.raDeg, s.decDeg, p.raDeg, p.decDeg)
            }
            scanMinima(fromMillis, toMillis, 1.0, { j -> -elong(j) }) { tMax, negValue ->
                val elongation = -negValue
                val floorDeg = if (planet == "Mercury") 17.0 else 44.0
                if (elongation > floorDeg) {
                    val peak = millis(tMax)
                    val s = SunMoon.sun(tMax)
                    val p = Planets.positions(tMax).first { it.name == planet }
                    val east = AstroMath.norm360(
                        AstroMath.eclipticLongitude(p.raDeg, p.decDeg, tMax) -
                            AstroMath.eclipticLongitude(s.raDeg, s.decDeg, tMax),
                    ) < 180.0
                    var viewText = ""
                    if (obs != null && !obs.isStale) {
                        val noon = localNoonMillis(nightKeyOf(peak, zone), zone)
                        val edge = if (east) {
                            SunMoon.sunEvent(noon, obs.latDeg, obs.lonDeg, -0.833).setMillis
                        } else {
                            SunMoon.sunEvent(noon, obs.latDeg, obs.lonDeg, -0.833).riseMillis
                        } ?: return@scanMinima
                        val at = if (east) edge + 30 * 60_000L else edge - 30 * 60_000L
                        val pAt = Planets.positions(jd(at)).first { it.name == planet }
                        val h = altAz(pAt.raDeg, pAt.decDeg, jd(at), obs.latDeg, obs.lonDeg)
                        if (h.altitudeDeg < 5.0) return@scanMinima // too low to be worth mentioning
                        viewText = " Half an hour after ${if (east) "sunset" else "sunrise"} it is " +
                            "${EventText.placeInSky(h.azimuthDeg, h.altitudeDeg)}."
                    }
                    val whenText = if (east) "evening" else "morning"
                    out.add(
                        SkyEvent(
                            id = EventIds.elongation(planet, east, tMax),
                            kind = SkyEventKind.GreatestElongation,
                            subject = planet,
                            peakMillis = peak,
                            windowStartMillis = peak - 5 * 24 * 3_600_000L,
                            windowEndMillis = peak + 5 * 24 * 3_600_000L,
                            deliverAtMillis = peak - 12 * 3_600_000L,
                            validUntilMillis = peak + 5 * 24 * 3_600_000L,
                            priority = 30,
                            title = "$planet at its best in the $whenText sky",
                            body = "${EventText.degrees(elongation)} from the Sun — as far as it gets this " +
                                "time round, and the easiest it will be to pick out.$viewText" +
                                if (planet == "Mercury") " Mercury is never easy: you need a clear, flat horizon." else "",
                            confidence = if (planet == "Mercury") Confidence.Approximate else confidenceFor(obs),
                            requiresLocation = false,
                            detail = mapOf("elongationDeg" to elongation),
                        ),
                    )
                }
            }
        }
        return out
    }

    // -------------------------------------------------------- season markers

    private fun seasonMarkers(zone: ZoneId, fromMillis: Long, toMillis: Long): List<SkyEvent> {
        val out = ArrayList<SkyEvent>()
        val markers = listOf(
            0.0 to "March equinox", 90.0 to "June solstice",
            180.0 to "September equinox", 270.0 to "December solstice",
        )
        for ((target, name) in markers) {
            fun f(j: Double): Double {
                val s = SunMoon.sun(j)
                return (AstroMath.eclipticLongitude(s.raDeg, s.decDeg, j) - target + 180.0)
                    .mod(360.0) - 180.0
            }
            var t = jd(fromMillis)
            val end = jd(toMillis)
            var fPrev = f(t)
            var next = t + 1.0
            while (next <= end) {
                val fNext = f(next)
                if (fPrev < 0 && fNext >= 0 && fNext - fPrev < 180.0) {
                    var lo = t
                    var hi = next
                    repeat(40) {
                        val mid = 0.5 * (lo + hi)
                        if (f(mid) < 0) lo = mid else hi = mid
                    }
                    val peak = millis(0.5 * (lo + hi))
                    val year = utcDateOf(peak).year
                    val solstice = name.contains("solstice")
                    out.add(
                        SkyEvent(
                            id = EventIds.season(name, year),
                            kind = if (solstice) SkyEventKind.Solstice else SkyEventKind.Equinox,
                            subject = name,
                            peakMillis = peak,
                            windowStartMillis = peak,
                            windowEndMillis = peak + 24 * 3_600_000L,
                            deliverAtMillis = peak - 6 * 3_600_000L,
                            validUntilMillis = peak + 24 * 3_600_000L,
                            priority = 25,
                            title = name,
                            body = if (solstice) {
                                "The Sun reaches its furthest from the equator today — the longest night " +
                                    "of the year in one hemisphere and the shortest in the other."
                            } else {
                                "The Sun crosses the equator today: day and night are close to equal " +
                                    "everywhere on Earth."
                            },
                            confidence = Confidence.Firm,
                            requiresLocation = false,
                        ),
                    )
                    break
                }
                fPrev = fNext
                t = next
                next += 1.0
            }
        }
        return out
    }

    // ----------------------------------------------------------- moon phases

    private fun moonPhases(obs: Observer?, zone: ZoneId, fromMillis: Long, toMillis: Long): List<SkyEvent> {
        val out = ArrayList<SkyEvent>()
        for (s in syzygies(fromMillis, toMillis)) {
            val peak = millis(s.jd)
            val nightKey = nightKeyOf(peak, zone)
            val night = obs?.let { nightWindow(it, nightKey, cfg) }
            if (s.full) {
                val dist = SunMoon.moonGeocentric(s.jd).distance
                val superMoon = dist < cfg.supermoonMaxDistanceRe
                var riseText = ""
                if (obs != null && !obs.isStale) {
                    night?.moonRiseInWindow?.let { riseText = " It rises around ${EventText.clock(it, zone)}." }
                }
                out.add(
                    SkyEvent(
                        id = EventIds.fullMoon(s.jd),
                        kind = SkyEventKind.FullMoon,
                        subject = "Moon",
                        peakMillis = peak,
                        windowStartMillis = night?.duskMillis ?: (peak - 6 * 3_600_000L),
                        windowEndMillis = night?.dawnMillis ?: (peak + 6 * 3_600_000L),
                        deliverAtMillis = deliveryFor(night, nightKey, zone, 0),
                        validUntilMillis = night?.dawnMillis ?: (peak + 12 * 3_600_000L),
                        priority = 20,
                        title = if (superMoon) "Supermoon tonight" else "Full moon tonight",
                        body = (if (superMoon) {
                            "A full moon at one of its closest points to Earth, so a touch bigger and " +
                                "brighter than usual — though the difference is hard to spot by eye. "
                        } else {
                            "Full and up all night. "
                        }) + "Bright enough to wash out anything faint, so it's a night for the Moon " +
                            "itself rather than for galaxies.$riseText",
                        confidence = Confidence.Firm,
                        requiresLocation = false,
                        detail = mapOf("distanceEarthRadii" to dist),
                    ),
                )
            } else {
                out.add(
                    SkyEvent(
                        id = EventIds.newMoon(s.jd),
                        kind = SkyEventKind.NewMoon,
                        subject = "Moon",
                        peakMillis = peak,
                        windowStartMillis = night?.duskMillis ?: (peak - 6 * 3_600_000L),
                        windowEndMillis = night?.dawnMillis ?: (peak + 6 * 3_600_000L),
                        deliverAtMillis = deliveryFor(night, nightKey, zone, 0),
                        validUntilMillis = night?.dawnMillis ?: (peak + 24 * 3_600_000L),
                        priority = 20,
                        title = "New moon",
                        body = "No Moon in the sky tonight — the darkest skies of the month, and the " +
                            "best chance at the Milky Way and anything faint.",
                        confidence = Confidence.Firm,
                        requiresLocation = false,
                    ),
                )
            }
        }
        return out
    }

    // ----------------------------------------------------------- dark nights

    private fun darkNights(
        obs: Observer?,
        zone: ZoneId,
        fromMillis: Long,
        toMillis: Long,
        prefs: AlertPrefs,
    ): List<SkyEvent> {
        if (obs == null) return emptyList()
        val out = ArrayList<SkyEvent>()
        val byLunation = HashMap<Int, Pair<NightWindow, Long>>()
        for (key in nightsIn(zone, fromMillis, toMillis)) {
            val night = nightWindow(obs, key, cfg) ?: continue
            if (night.moonIllumAtMid > prefs.darkSkyMaxMoon) continue
            if (night.moonlessMillis < 3 * 3_600_000L) continue
            val mid = night.midDarkMillis ?: continue
            val lun = EventIds.lunation(jd(mid))
            val existing = byLunation[lun]
            if (existing == null || night.moonlessMillis > existing.first.moonlessMillis) {
                byLunation[lun] = night to mid
            }
        }
        // Sorted so the output never depends on hash iteration order.
        for (lun in byLunation.keys.sorted()) {
            val (night, mid) = byLunation.getValue(lun)
            val start = night.moonlessStartMillis ?: continue
            val end = night.moonlessEndMillis ?: continue
            out.add(
                SkyEvent(
                    id = EventIds.darkNight(jd(mid)),
                    kind = SkyEventKind.DarkNight,
                    subject = "Dark sky",
                    peakMillis = mid,
                    windowStartMillis = start,
                    windowEndMillis = end,
                    deliverAtMillis = deliveryFor(night, night.nightKey, zone, 0),
                    validUntilMillis = end,
                    priority = 35,
                    title = "Darkest night this month",
                    body = "No Moon at all between ${EventText.clock(start, zone)} and " +
                        "${EventText.clock(end, zone)} — ${EventText.duration(night.moonlessMillis)} " +
                        "of genuine darkness. The best night this month for faint things.",
                    confidence = confidenceFor(obs),
                    requiresLocation = true,
                    detail = mapOf("moonlessHours" to night.moonlessMillis / 3_600_000.0),
                ),
            )
        }
        return out
    }

    // ------------------------------------------------------------ ISS passes

    private fun issEvents(
        obs: Observer?,
        zone: ZoneId,
        fromMillis: Long,
        toMillis: Long,
        prefs: AlertPrefs,
        passes: List<IssPass>,
    ): List<SkyEvent> {
        if (obs == null) return emptyList()
        // One per night, the best of them. Several pass notifications in one evening is
        // the fastest way to get the whole feature muted.
        val best = HashMap<String, IssPass>()
        for (p in passes) {
            if (p.peakAltitudeDeg < prefs.issMinPeakAltDeg) continue
            if (p.peakMillis !in fromMillis..toMillis) continue
            val key = nightKeyOf(p.peakMillis, zone).toString()
            val existing = best[key]
            if (existing == null || p.peakAltitudeDeg > existing.peakAltitudeDeg) best[key] = p
        }
        return best.keys.sorted().map { key ->
            val p = best.getValue(key)
            val quality = when {
                p.peakAltitudeDeg >= 70 -> "straight overhead"
                p.peakAltitudeDeg >= 45 -> "high across the sky"
                else -> "fairly low"
            }
            SkyEvent(
                id = EventIds.issPass(key),
                kind = SkyEventKind.IssPass,
                subject = "ISS",
                peakMillis = p.peakMillis,
                windowStartMillis = p.startMillis,
                windowEndMillis = p.endMillis,
                deliverAtMillis = p.startMillis - 45 * 60_000L,
                validUntilMillis = p.endMillis,
                priority = if (p.peakAltitudeDeg >= 60) 55 else 45,
                title = "Space Station passes over tonight",
                body = "Comes up in the ${EventText.compass(p.startAzimuthDeg)} at " +
                    "${EventText.clock(p.startMillis, zone)}, climbs to " +
                    "${EventText.degrees(p.peakAltitudeDeg)} ($quality) and drops away in the " +
                    "${EventText.compass(p.endAzimuthDeg)} by ${EventText.clock(p.endMillis, zone)}. " +
                    "It looks like a bright star moving steadily, with no flashing lights.",
                confidence = if (p.approximate) Confidence.Approximate else Confidence.Firm,
                requiresLocation = true,
                detail = mapOf(
                    "peakAltitudeDeg" to p.peakAltitudeDeg,
                    "durationMinutes" to (p.endMillis - p.startMillis) / 60_000.0,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- dedupe

    /**
     * Drop the things that would only repeat what a bigger event on the same night
     * already says — an eclipse notification already tells you it is a full moon.
     */
    private fun dedupe(events: List<SkyEvent>, zone: ZoneId): List<SkyEvent> {
        val eclipseNightsLunar = events.filter { it.kind == SkyEventKind.LunarEclipse }
            .map { nightKeyOf(it.peakMillis, zone) }.toSet()
        val eclipseNightsSolar = events.filter { it.kind == SkyEventKind.SolarEclipse }
            .map { nightKeyOf(it.peakMillis, zone) }.toSet()
        val bigNights = events.filter {
            it.kind == SkyEventKind.MeteorPeak || it.kind == SkyEventKind.LunarEclipse
        }.map { nightKeyOf(it.peakMillis, zone) }.toSet()

        val kept = events.filter { e ->
            val night = nightKeyOf(e.peakMillis, zone)
            when (e.kind) {
                SkyEventKind.FullMoon -> night !in eclipseNightsLunar
                SkyEventKind.NewMoon -> night !in eclipseNightsSolar
                SkyEventKind.DarkNight -> night !in bigNights
                else -> true
            }
        }.distinctBy { it.id }

        return kept
            // An alert that arrives after the thing is already up has lost most of its
            // point, so delivery is never later than the moment the window opens. Doing
            // it here means no individual event builder can get it wrong.
            .map {
                if (it.deliverAtMillis > it.windowStartMillis) {
                    it.copy(deliverAtMillis = it.windowStartMillis)
                } else {
                    it
                }
            }
            .sortedWith(
                compareBy<SkyEvent> { it.peakMillis }
                    .thenByDescending { it.priority }
                    .thenBy { it.id },
            )
    }

    private companion object {
        /**
         * Showers whose maximum is brief enough that a date-only table can miss it by a
         * night. These get hedged wording.
         */
        val SHARP_PEAKED = setOf("Quadrantids", "Draconids", "Ursids")
    }
}
