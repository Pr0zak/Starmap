package com.starmap.app

import com.starmap.app.aircraft.RadarKind
import com.starmap.app.sky.SeenLog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneOffset

class SeenLogTest {
    private val t0 = 1_790_330_400_000L // 2026-09-25 10:00 UTC

    private fun s(hex: String, type: String, km: Double) =
        SeenLog.Sighting(hex, "CS$hex", type, "", RadarKind.AIRLINER, km)

    @Test
    fun countsUniqueAircraftPerHourAndKeepsClosestDistance() {
        val dir = Files.createTempDirectory("seen").toFile()
        val log = SeenLog(dir, ZoneOffset.UTC)
        log.record(t0, listOf(s("a", "B738", 30.0), s("b", "A321", 20.0)))
        log.record(t0 + 60_000, listOf(s("a", "B738", 12.0)))
        log.record(t0 + 3_600_000, listOf(s("a", "B738", 40.0), s("c", "C172", 5.0)))
        val day = log.today(t0 + 3_600_000)
        assertEquals(3, day.entries.size)
        assertEquals(2, day.perHour[10])
        assertEquals(2, day.perHour[11])
        assertEquals(12.0, day.entries.getValue("a").closestKm, 1e-9)
        // Each type flew once (a single aircraft each), so all three count as rare.
        assertEquals(listOf("A321", "B738", "C172"), day.rareTypes)
    }

    @Test
    fun aTypeSeenOnTwoAircraftIsNotRare() {
        val log = SeenLog(Files.createTempDirectory("seen").toFile(), ZoneOffset.UTC)
        log.record(t0, listOf(s("a", "B738", 30.0), s("b", "B738", 20.0), s("c", "PC12", 9.0)))
        assertEquals(listOf("PC12"), log.today(t0).rareTypes)
    }

    @Test
    fun survivesARestart() {
        val dir = Files.createTempDirectory("seen").toFile()
        SeenLog(dir, ZoneOffset.UTC).apply { record(t0, listOf(s("a", "B738", 30.0))); save() }
        val again = SeenLog(dir, ZoneOffset.UTC).today(t0 + 5_000)
        assertEquals(1, again.entries.size)
        assertEquals(1, again.perHour[10])
    }
}
