package com.starmap.app.sky

import java.text.Normalizer

/** Something the user can search for and be guided to. */
sealed interface SearchTarget {
    val label: String

    data class StarT(val index: Int, override val label: String) : SearchTarget
    data class PlanetT(override val label: String) : SearchTarget
    data class AsteroidT(override val label: String) : SearchTarget
    data class MessierT(override val label: String) : SearchTarget
    data class ConstellationT(override val label: String) : SearchTarget
    data class SpecialT(override val label: String) : SearchTarget // Sun, Moon, ISS
}

/** An indexed, searchable entry (built once from the loaded catalogs). */
class SearchEntry(val target: SearchTarget, val display: String, val kind: String, val key: String)

/** A ranked search hit shown in the UI. */
data class SearchResult(val target: SearchTarget, val display: String, val kind: String)

/**
 * Tiny dependency-free fuzzy matcher: exact > prefix > word-boundary substring >
 * substring > in-order subsequence. Good enough to find "betelgeuse" from "bet"
 * or "canis major" from "α CMa".
 */
object FuzzySearch {

    private val greek = mapOf(
        'α' to "alpha", 'β' to "beta", 'γ' to "gamma", 'δ' to "delta", 'ε' to "epsilon",
        'ζ' to "zeta", 'η' to "eta", 'θ' to "theta", 'ι' to "iota", 'κ' to "kappa",
        'λ' to "lambda", 'μ' to "mu", 'ν' to "nu", 'ξ' to "xi", 'ο' to "omicron",
        'π' to "pi", 'ρ' to "rho", 'σ' to "sigma", 'τ' to "tau", 'υ' to "upsilon",
        'φ' to "phi", 'χ' to "chi", 'ψ' to "psi", 'ω' to "omega",
    )

    /** Lowercase, romanize Greek letters, strip accents and punctuation. */
    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            val g = greek[c]
            if (g != null) sb.append(g).append(' ') else sb.append(c)
        }
        val nfd = Normalizer.normalize(sb, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun score(q: String, key: String): Int {
        if (q.isEmpty()) return 0
        if (key == q) return 1000
        val idx = key.indexOf(q)
        if (idx == 0) return 850 - (key.length - q.length).coerceAtMost(100)
        if (idx > 0) {
            val boundary = key[idx - 1] == ' '
            return (if (boundary) 720 else 600) - idx - (key.length - q.length).coerceAtMost(50)
        }
        // In-order subsequence.
        var ki = 0; var qi = 0; var gaps = 0; var first = -1
        while (ki < key.length && qi < q.length) {
            if (key[ki] == q[qi]) {
                if (first < 0) first = ki
                qi++
            } else if (qi > 0) {
                gaps++
            }
            ki++
        }
        return if (qi == q.length) (400 - first - gaps).coerceAtLeast(1) else -1
    }

    fun search(query: String, entries: List<SearchEntry>, limit: Int = 40): List<SearchResult> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return entries.asSequence()
            .mapNotNull { e -> score(q, e.key).takeIf { it > 0 }?.let { it to e } }
            .sortedWith(compareByDescending<Pair<Int, SearchEntry>> { it.first }
                .thenBy { it.second.display.length })
            .take(limit)
            .map { SearchResult(it.second.target, it.second.display, it.second.kind) }
            .toList()
    }
}

/** Common nicknames / asterisms so "big dipper" finds Ursa Major, etc. (keyed by IAU abbr). */
val constellationAliases: Map<String, String> = mapOf(
    "uma" to "big dipper plough plow saucepan the great bear wagon",
    "umi" to "little dipper little bear",
    "ori" to "the hunter orions belt",
    "cyg" to "northern cross the swan",
    "sgr" to "the teapot the archer",
    "sco" to "the scorpion fishhook",
    "cru" to "southern cross",
    "leo" to "the lion the sickle",
    "tau" to "the bull pleiades seven sisters hyades",
    "gem" to "the twins",
    "cas" to "the w the queen",
    "lyr" to "the harp",
    "aql" to "the eagle",
    "cma" to "great dog",
    "cmi" to "little dog",
    "boo" to "the herdsman the kite",
    "peg" to "great square the winged horse",
    "and" to "the chained princess",
    "aur" to "the charioteer",
    "del" to "the dolphin jobs coffin",
    "crv" to "the crow",
    "her" to "the keystone",
)

/** Resolves the current local ENU direction of a search target from the sky model. */
fun resolveTargetEnu(model: SkyModel, target: SearchTarget): FloatArray? = when (target) {
    is SearchTarget.StarT -> if (target.index in 0 until model.count) {
        val b = target.index * 3
        floatArrayOf(model.starEnu[b], model.starEnu[b + 1], model.starEnu[b + 2])
    } else {
        null
    }
    is SearchTarget.PlanetT -> model.planets.firstOrNull { it.name == target.label }?.enu
    is SearchTarget.AsteroidT -> model.asteroids.firstOrNull { it.name == target.label }?.enu
    is SearchTarget.MessierT -> model.messier.firstOrNull { it.name == target.label }?.enu
    is SearchTarget.ConstellationT -> model.constellations.firstOrNull { it.name == target.label }?.labelEnu
    is SearchTarget.SpecialT -> when (target.label) {
        "Sun" -> model.sun?.enu
        "Moon" -> model.moon?.enu
        else -> {
            var r: FloatArray? = null
            for (i in 0 until model.satCount) {
                if (model.satNames[i].contains(target.label, ignoreCase = true)) {
                    val b = i * 3
                    r = floatArrayOf(model.satEnu[b], model.satEnu[b + 1], model.satEnu[b + 2])
                    break
                }
            }
            r
        }
    }
}
