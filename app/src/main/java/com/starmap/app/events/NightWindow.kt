package com.starmap.app.events

import com.starmap.app.astro.AstroMath
import com.starmap.app.astro.SunMoon
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One night, as an observer experiences it: when it gets properly dark, when the Moon
 * is in the way, and how much genuinely dark sky is left over.
 *
 * A night is named by the civil date of its *evening*, and runs from dusk on that date
 * to dawn the next morning.
 */
data class NightWindow(
    val nightKey: LocalDate,
    /** Sun passing below the twilight depth, in the evening. */
    val duskMillis: Long?,
    /** Sun climbing back above it, the NEXT morning. */
    val dawnMillis: Long?,
    val sunsetMillis: Long?,
    val sunriseMillis: Long?,
    val moonSetInWindow: Long?,
    val moonRiseInWindow: Long?,
    val moonlessStartMillis: Long?,
    val moonlessEndMillis: Long?,
    val moonIllumAtMid: Double,
    /** True when we had to settle for nautical twilight because it never gets properly dark. */
    val approximate: Boolean,
) {
    val darknessMillis: Long
        get() = if (duskMillis != null && dawnMillis != null) {
            (dawnMillis - duskMillis).coerceAtLeast(0L)
        } else {
            0L
        }

    val moonlessMillis: Long
        get() = if (moonlessStartMillis != null && moonlessEndMillis != null) {
            (moonlessEndMillis - moonlessStartMillis).coerceAtLeast(0L)
        } else {
            0L
        }

    val midDarkMillis: Long?
        get() = if (duskMillis != null && dawnMillis != null) (duskMillis + dawnMillis) / 2 else null
}

/**
 * Which night an instant belongs to.
 *
 * Shifted back twelve hours before taking the local date, so 02:00 counts as part of
 * the previous evening rather than starting a new night halfway through one. Every
 * night-scoped id, dedup rule and per-day cap keys off this, so it is defined once.
 */
fun nightKeyOf(atMillis: Long, zone: ZoneId): LocalDate =
    // atZone().toLocalDate() rather than LocalDate.ofInstant, which is API 34 and would
    // crash on everything below Android 14. minSdk here is 26.
    Instant.ofEpochMilli(atMillis - 12 * 3_600_000L).atZone(zone).toLocalDate()

/** Local noon of [date], the instant the solar-altitude routine expects to be given. */
fun localNoonMillis(date: LocalDate, zone: ZoneId): Long =
    date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

/**
 * Build the picture of one night, or null when it never gets dark enough to be worth
 * calling a night at all (high-latitude summer).
 */
fun nightWindow(observer: Observer, nightKey: LocalDate, cfg: EventConfig = EventConfig()): NightWindow? {
    if (!observer.isUsable) return null
    val lat = observer.latDeg
    val lon = observer.lonDeg
    val noonToday = localNoonMillis(nightKey, observer.zone)
    val noonTomorrow = localNoonMillis(nightKey.plusDays(1), observer.zone)

    // Dusk and dawn come from two SEPARATE calls: the routine resolves one UTC day, so
    // asking it for both ends of a night that straddles midnight would be wrong.
    fun twilight(depth: Double): Pair<Long?, Long?> =
        SunMoon.sunEvent(noonToday, lat, lon, depth).setMillis to
            SunMoon.sunEvent(noonTomorrow, lat, lon, depth).riseMillis

    var approximate = false
    var pair = twilight(cfg.twilightDepthDeg)
    if (pair.first == null || pair.second == null) {
        approximate = true
        pair = twilight(cfg.fallbackTwilightDepthDeg)
    }
    val dusk = pair.first ?: return null
    val dawn = pair.second ?: return null
    if (dawn <= dusk) return null

    val sunset = SunMoon.sunEvent(noonToday, lat, lon, -0.833).setMillis
    val sunrise = SunMoon.sunEvent(noonTomorrow, lat, lon, -0.833).riseMillis

    // Moon altitude across the window. Topocentric on purpose: near the horizon parallax
    // lowers the Moon by about a degree, which is a couple of minutes on a stated moonset.
    fun moonAlt(atMillis: Long): Double {
        val jd = AstroMath.julianDay(atMillis)
        val m = SunMoon.moon(jd, lat, AstroMath.lstDegrees(jd, lon))
        return AstroMath.toHorizontal(
            AstroMath.equatorialToVec(m.raDeg, m.decDeg),
            AstroMath.enuBasis(AstroMath.lstDegrees(jd, lon), lat),
        ).altitudeDeg
    }

    fun bisectAltZero(loMillis: Long, hiMillis: Long): Long {
        var lo = loMillis
        var hi = hiMillis
        val loUp = moonAlt(lo) > 0
        repeat(24) {
            val mid = lo + (hi - lo) / 2
            if ((moonAlt(mid) > 0) == loUp) lo = mid else hi = mid
            if (hi - lo < 30_000L) return lo + (hi - lo) / 2
        }
        return lo + (hi - lo) / 2
    }

    val step = 15 * 60_000L
    var moonSet: Long? = null
    var moonRise: Long? = null
    var t = dusk
    var prevUp = moonAlt(t) > 0
    val moonUpAtDusk = prevUp
    while (t < dawn) {
        val next = minOf(t + step, dawn)
        val up = moonAlt(next) > 0
        if (up != prevUp) {
            val cross = bisectAltZero(t, next)
            if (prevUp) {
                if (moonSet == null) moonSet = cross
            } else if (moonRise == null) {
                moonRise = cross
            }
        }
        prevUp = up
        t = next
    }

    // The longest stretch of the night with no Moon above the horizon.
    val rise = moonRise
    val set = moonSet
    val moonless: Pair<Long, Long>? = when {
        !moonUpAtDusk -> dusk to (rise ?: dawn)
        set != null && rise != null -> set to rise
        set != null -> set to dawn
        else -> null
    }

    val mid = (dusk + dawn) / 2
    val illum = SunMoon.moonPhase(AstroMath.julianDay(mid)).illuminatedFraction

    return NightWindow(
        nightKey = nightKey,
        duskMillis = dusk,
        dawnMillis = dawn,
        sunsetMillis = sunset,
        sunriseMillis = sunrise,
        moonSetInWindow = moonSet,
        moonRiseInWindow = moonRise,
        moonlessStartMillis = moonless?.first,
        moonlessEndMillis = moonless?.second,
        moonIllumAtMid = illum,
        approximate = approximate,
    )
}

/** UTC calendar date of an instant — used where a source table is defined in UTC. */
fun utcDateOf(atMillis: Long): LocalDate =
    Instant.ofEpochMilli(atMillis).atZone(ZoneOffset.UTC).toLocalDate()
