package com.starmap.app.info

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Encyclopedic detail for a sky object, from Wikipedia's free REST API (a short
 * description plus a representative image). It searches first — so a query like
 * "Vesta asteroid" or "Halley comet" lands on the right article — then fetches
 * that page's summary. Runs on the phone, which has open network access.
 */
class WikiManager {

    data class Info(
        val title: String,
        val extract: String,
        val imageUrl: String?,
        val pageUrl: String?,
    )

    sealed interface Result {
        data class Ok(val info: Info) : Result
        object None : Result
        data class Error(val message: String) : Result
    }

    suspend fun fetch(query: String): Result = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext Result.None
        try {
            val title = searchTopTitle(q) ?: return@withContext Result.None
            summaryFor(title)
        } catch (e: Exception) {
            Result.Error(e.message ?: "network error")
        }
    }

    /** Best-matching article title for a free-text query, or null if none. */
    private fun searchTopTitle(query: String): String? {
        val url = "https://en.wikipedia.org/w/rest.php/v1/search/page?q=${enc(query)}&limit=1"
        val o = getJson(url) ?: return null
        val pages = o.optJSONArray("pages") ?: return null
        if (pages.length() == 0) return null
        return pages.getJSONObject(0).optString("title").takeIf { it.isNotBlank() }
    }

    private fun summaryFor(title: String): Result {
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title)}"
        val o = getJson(url) ?: return Result.None
        if (o.optString("type") == "disambiguation") return Result.None
        val extract = o.optString("extract")
        if (extract.isBlank()) return Result.None
        val img = (o.optJSONObject("thumbnail") ?: o.optJSONObject("originalimage"))
            ?.optString("source")?.takeIf { it.isNotBlank() }
        val page = o.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")
            ?.takeIf { it.isNotBlank() }
        return Result.Ok(Info(o.optString("title", title), extract, img, page))
    }

    private fun getJson(url: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
        }
        if (conn.responseCode !in 200..299) return null
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        /** Wikimedia blocks generic clients, so every request (incl. images) needs this. */
        const val USER_AGENT = "Starmap/1.0 (Android; +https://github.com/pr0zak/starmap)"
    }
}
