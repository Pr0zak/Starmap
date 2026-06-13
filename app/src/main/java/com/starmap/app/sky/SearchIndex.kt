package com.starmap.app.sky

import com.starmap.app.astro.Asteroids
import com.starmap.app.astro.Comets
import com.starmap.app.astro.Constellation
import com.starmap.app.astro.Messier
import com.starmap.app.astro.StarCatalog

/**
 * The searchable index of everything the user can look up — stars (with their Bayer
 * + constellation keys), planets, asteroids, comets, Messier objects, the Sun/Moon/
 * ISS specials, and constellations (with abbreviations and common-name aliases).
 *
 * [build] is called by [SkyViewModel] whenever the star catalogue (re)loads, taking
 * the loaded catalogue data as parameters so the index stays free of that state.
 * [search] is a pure fuzzy query over the built entries.
 */
class SearchIndex {
    private var entries: List<SearchEntry> = emptyList()

    fun build(
        catalog: StarCatalog,
        constellations: List<Constellation>,
        asteroids: List<Asteroids.Element>,
        comets: List<Comets.Element>,
        messier: List<Messier.Dso>,
    ) {
        val abbrToName = constellations.associate { it.abbr.lowercase() to it.name }
        val out = ArrayList<SearchEntry>(catalog.labels.size + constellations.size + 16)
        for ((idx, label) in catalog.labels) {
            val tokens = label.split(' ')
            val last = tokens.lastOrNull()?.lowercase()
            // Bayer labels ("α CMa") get the full constellation name added to the key.
            val extra = if (tokens.size >= 2 && last != null && abbrToName.containsKey(last)) {
                " " + abbrToName.getValue(last)
            } else {
                ""
            }
            out.add(SearchEntry(SearchTarget.StarT(idx, label), label, "Star", FuzzySearch.normalize(label + extra)))
        }
        for (p in listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")) {
            out.add(SearchEntry(SearchTarget.PlanetT(p), p, "Planet", FuzzySearch.normalize(p)))
        }
        for (a in asteroids) {
            out.add(SearchEntry(SearchTarget.AsteroidT(a.name), a.name, "Asteroid", FuzzySearch.normalize(a.name)))
        }
        for (c in comets) {
            out.add(SearchEntry(SearchTarget.CometT(c.name), c.name, "Comet", FuzzySearch.normalize(c.name)))
        }
        for (d in messier) {
            val display = if (d.common.isBlank()) d.name else "${d.name} · ${d.common}"
            out.add(
                SearchEntry(
                    SearchTarget.MessierT(d.name), display, d.type,
                    FuzzySearch.normalize("${d.name} ${d.common} ${d.type}"),
                ),
            )
        }
        out.add(SearchEntry(SearchTarget.SpecialT("Sun"), "Sun", "Solar System", "sun"))
        out.add(SearchEntry(SearchTarget.SpecialT("Moon"), "Moon", "Solar System", "moon"))
        out.add(SearchEntry(SearchTarget.SpecialT("ISS"), "ISS (Space Station)", "Satellite", "iss space station"))
        for (c in constellations) {
            val alias = constellationAliases[c.abbr.lowercase()] ?: ""
            out.add(
                SearchEntry(
                    SearchTarget.ConstellationT(c.name), c.name, "Constellation",
                    FuzzySearch.normalize("${c.name} ${c.abbr} $alias"),
                ),
            )
        }
        entries = out
    }

    fun search(query: String): List<SearchResult> = FuzzySearch.search(query, entries)
}
