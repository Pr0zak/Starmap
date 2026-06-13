package com.starmap.app

import com.starmap.app.sky.FuzzySearch
import com.starmap.app.sky.SearchEntry
import com.starmap.app.sky.SearchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the dependency-free fuzzy matcher. */
class FuzzySearchTest {

    private fun entry(key: String, display: String = key) =
        SearchEntry(SearchTarget.SpecialT(display), display, "Test", key)

    @Test
    fun normalizeRomanizesGreek() {
        assertEquals("alpha orionis", FuzzySearch.normalize("α Orionis"))
        assertEquals("beta cygni", FuzzySearch.normalize("β Cygni"))
    }

    @Test
    fun normalizeStripsAccentsAndPunctuation() {
        assertEquals("betelgeuse", FuzzySearch.normalize("Bételgeuse!"))
        assertEquals("m 31", FuzzySearch.normalize("M-31"))
        assertEquals("47 tucanae", FuzzySearch.normalize("  47   Tucanae  "))
    }

    @Test
    fun exactMatchRanksAbovePrefixAndSubstring() {
        val entries = listOf(entry("polmars"), entry("marsate"), entry("mars"))
        val results = FuzzySearch.search("mars", entries)
        assertEquals("mars", results.first().display)
        // All three contain "mars" so all match.
        assertEquals(3, results.size)
    }

    @Test
    fun prefixRanksAboveMidWordSubstring() {
        val results = FuzzySearch.search("mar", listOf(entry("polmar"), entry("market")))
        assertEquals("market", results.first().display) // prefix beats interior substring
    }

    @Test
    fun subsequenceMatches() {
        val results = FuzzySearch.search("cnsmjr", listOf(entry("canis major", "Canis Major")))
        assertEquals(1, results.size)
        assertEquals("Canis Major", results.first().display)
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(FuzzySearch.search("xyzzy", listOf(entry("mars"), entry("venus"))).isEmpty())
        assertTrue(FuzzySearch.search("", listOf(entry("mars"))).isEmpty())
    }

    @Test
    fun limitIsRespected() {
        val entries = (0 until 50).map { entry("star $it", "Star $it") }
        assertEquals(5, FuzzySearch.search("star", entries, limit = 5).size)
    }
}
