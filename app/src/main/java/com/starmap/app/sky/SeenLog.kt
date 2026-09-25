package com.starmap.app.sky

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every aircraft seen today while Starmap was fetching traffic, kept on the phone only
 * (one small JSON file per day under filesDir/seen, the last 7 days kept). Feeds the
 * radar's "Seen today" sheet: counts, traffic per hour, and the rarest types.
 */
class SeenLog(private val dir: File, private val zone: ZoneId = ZoneId.systemDefault()) {

    class Entry(
        val hex: String,
        var callsign: String,
        var type: String,
        var registration: String,
        var kind: Int,
        val firstSeen: Long,
        var lastSeen: Long,
        var closestKm: Double,
    )

    class Day(val date: LocalDate, val entries: Map<String, Entry>, val perHour: IntArray) {
        /** Types seen only once today, most interesting first (an ordinary jet type isn't rare). */
        val rareTypes: List<String> get() = entries.values.groupBy { it.type }
            .filter { (t, list) -> t.isNotBlank() && list.size == 1 }.keys.sorted()
        val busiestHour: Int? get() = perHour.indices.maxByOrNull { perHour[it] }?.takeIf { perHour[it] > 0 }
    }

    private var date: LocalDate? = null
    private val entries = LinkedHashMap<String, Entry>()
    private val hourSeen = Array(24) { HashSet<String>() }
    private var lastSaved = 0L

    /** Record one fetch. [nowMillis] is when it happened. */
    @Synchronized
    fun record(nowMillis: Long, aircraft: List<Sighting>) {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone)
        if (date != today.toLocalDate()) load(today.toLocalDate())
        val hour = today.hour
        for (s in aircraft) {
            val e = entries.getOrPut(s.hex) {
                Entry(s.hex, s.callsign, s.type, s.registration, s.kind, nowMillis, nowMillis, s.distanceKm)
            }
            if (s.callsign.isNotBlank()) e.callsign = s.callsign
            if (s.type.isNotBlank()) e.type = s.type
            if (s.registration.isNotBlank()) e.registration = s.registration
            e.kind = s.kind
            e.lastSeen = nowMillis
            e.closestKm = minOf(e.closestKm, s.distanceKm)
            hourSeen[hour].add(s.hex)
        }
        if (nowMillis - lastSaved > 60_000) save()
    }

    class Sighting(
        val hex: String,
        val callsign: String,
        val type: String,
        val registration: String,
        val kind: Int,
        val distanceKm: Double,
    )

    @Synchronized
    fun today(nowMillis: Long = System.currentTimeMillis()): Day {
        val d = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        if (date != d) load(d)
        return Day(d, entries.mapValues { it.value }, IntArray(24) { hourSeen[it].size })
    }

    private fun file(d: LocalDate) = File(dir, "$d.json")

    private fun load(d: LocalDate) {
        if (date != null) save()
        date = d
        entries.clear()
        hourSeen.forEach { it.clear() }
        val f = file(d)
        if (f.exists()) runCatching {
            val root = JSONObject(f.readText())
            val arr = root.getJSONArray("aircraft")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(
                    o.getString("hex"), o.optString("cs"), o.optString("type"), o.optString("reg"),
                    o.optInt("kind"), o.getLong("first"), o.getLong("last"), o.optDouble("closest", 999.0),
                )
                entries[e.hex] = e
            }
            val hours = root.getJSONArray("hours")
            for (h in 0 until minOf(24, hours.length())) {
                val ids = hours.getJSONArray(h)
                for (k in 0 until ids.length()) hourSeen[h].add(ids.getString(k))
            }
        }
        // Keep a week of history.
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name }?.dropLast(7)?.forEach { it.delete() }
    }

    @Synchronized
    fun save() {
        val d = date ?: return
        dir.mkdirs()
        val arr = JSONArray()
        for (e in entries.values) {
            arr.put(
                JSONObject()
                    .put("hex", e.hex).put("cs", e.callsign).put("type", e.type).put("reg", e.registration)
                    .put("kind", e.kind).put("first", e.firstSeen).put("last", e.lastSeen)
                    .put("closest", e.closestKm),
            )
        }
        val hours = JSONArray()
        for (h in 0 until 24) hours.put(JSONArray(hourSeen[h].toList()))
        runCatching {
            val tmp = File(dir, "$d.json.tmp")
            tmp.writeText(JSONObject().put("aircraft", arr).put("hours", hours).toString())
            tmp.renameTo(file(d))
        }
        lastSaved = System.currentTimeMillis()
    }
}
