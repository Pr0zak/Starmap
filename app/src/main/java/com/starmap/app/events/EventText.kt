package com.starmap.app.events

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Wording helpers. Kept apart from the calculator so the phrasing can be read and
 * changed on its own, and so every string is built from an explicit zone and locale —
 * a background worker must never pick up the device defaults implicitly.
 */
internal object EventText {

    private val COMPASS = arrayOf(
        "north", "north-north-east", "north-east", "east-north-east",
        "east", "east-south-east", "south-east", "south-south-east",
        "south", "south-south-west", "south-west", "west-south-west",
        "west", "west-north-west", "north-west", "north-north-west",
    )

    /** Compass direction of an azimuth, spelled out the way a person would say it. */
    fun compass(azimuthDeg: Double): String {
        val idx = (((azimuthDeg % 360.0 + 360.0) % 360.0) / 22.5).let { Math.round(it).toInt() % 16 }
        return COMPASS[idx]
    }

    /** Local clock time, e.g. "21:43". */
    fun clock(atMillis: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
            .format(Instant.ofEpochMilli(atMillis).atZone(zone))

    /** A duration as "6h40m", or "45m" when under an hour. */
    fun duration(millis: Long): String {
        val totalMinutes = millis / 60_000L
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h <= 0L) "${m}m" else "${h}h${m.toString().padStart(2, '0')}m"
    }

    fun degrees(value: Double, decimals: Int = 0): String =
        String.format(Locale.UK, "%.${decimals}f°", value)

    fun percent(fraction: Double): String =
        String.format(Locale.UK, "%.0f%%", fraction * 100.0)

    /**
     * How the Moon will interfere, said plainly — or null when it simply won't.
     * [upDuringWindow] is what decides it: a bright Moon that has already set is not a
     * problem, and saying it is would be wrong.
     */
    fun moonNote(illum: Double, upDuringWindow: Boolean, setsAtMillis: Long?, zone: ZoneId): String? {
        // Already down before it got dark: nothing to warn about.
        if (!upDuringWindow) return null
        val leaves = if (setsAtMillis != null && illum > 0.3) {
            " It sets at ${clock(setsAtMillis, zone)}."
        } else {
            ""
        }
        return when {
            illum < 0.15 -> "A ${percent(illum)} Moon won't get in the way."
            illum < 0.45 -> "A ${percent(illum)} Moon takes the edge off the faintest ones."
            illum < 0.8 -> "A ${percent(illum)} Moon will wash out the fainter ones."
            else -> "A ${percent(illum)} Moon is up — expect to see far fewer than that."
        } + leaves
    }

    /** "in the north-east, 61° up" — omitted entirely when the fix is too old to claim it. */
    fun placeInSky(azimuthDeg: Double, altitudeDeg: Double): String =
        "${degrees(altitudeDeg)} up in the ${compass(azimuthDeg)}"
}
