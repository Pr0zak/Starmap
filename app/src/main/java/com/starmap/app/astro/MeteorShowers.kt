package com.starmap.app.astro

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The major annual meteor showers, with their radiant (the point they appear to
 * stream from). Dates are stored as MMDD so the "active tonight" test is a simple
 * range check; radiant positions are the representative peak-night coordinates.
 */
object MeteorShowers {

    class Shower(
        val name: String,
        val raDeg: Double,
        val decDeg: Double,
        val start: Int,  // MMDD
        val end: Int,    // MMDD
        val peak: Int,   // MMDD
        val zhr: Int,    // peak rate (meteors/hour under ideal skies)
    )

    class ActiveShower(
        val name: String,
        val raDeg: Double,
        val decDeg: Double,
        val zhr: Int,
        val daysToPeak: Long,
    )

    private val showers = listOf(
        Shower("Quadrantids", 230.0, 49.0, 101, 105, 103, 110),
        Shower("Lyrids", 271.0, 34.0, 416, 425, 422, 18),
        Shower("Eta Aquariids", 338.0, -1.0, 419, 528, 506, 50),
        Shower("Southern δ Aquariids", 340.0, -16.0, 712, 823, 730, 25),
        Shower("Perseids", 48.0, 58.0, 717, 824, 812, 100),
        Shower("Draconids", 262.0, 54.0, 1006, 1010, 1008, 10),
        Shower("Orionids", 95.0, 16.0, 1002, 1107, 1021, 20),
        Shower("Southern Taurids", 52.0, 13.0, 910, 1120, 1010, 5),
        Shower("Northern Taurids", 58.0, 22.0, 1020, 1210, 1112, 5),
        Shower("Leonids", 152.0, 22.0, 1106, 1130, 1117, 15),
        Shower("Geminids", 112.0, 33.0, 1204, 1217, 1214, 150),
        Shower("Ursids", 217.0, 76.0, 1217, 1226, 1222, 10),
    )

    /**
     * The whole table. Peak dates are MMDD with no year and no hour: the true maximum
     * is set by solar longitude and drifts by half a day either way, so treat a peak as
     * a night, never a time.
     */
    val all: List<Shower> get() = showers

    /** Showers active on the given instant, with how many days until each peaks. */
    fun active(timeMillis: Long): List<ActiveShower> {
        val date = Instant.ofEpochMilli(timeMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val today = date.monthValue * 100 + date.dayOfMonth
        return showers.filter { today in it.start..it.end }.map { s ->
            val peakDate = try {
                date.withMonth(s.peak / 100).withDayOfMonth(s.peak % 100)
            } catch (e: Exception) {
                date
            }
            ActiveShower(s.name, s.raDeg, s.decDeg, s.zhr, ChronoUnit.DAYS.between(date, peakDate))
        }
    }
}
