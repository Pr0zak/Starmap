package com.starmap.app.update

import com.starmap.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the project's GitHub Releases for a newer version. Uses the public API
 * (no token needed) and a plain [HttpURLConnection] to avoid extra dependencies.
 */
object UpdateChecker {

    data class Release(
        val tag: String,
        val versionName: String,
        val notes: String,
        val htmlUrl: String,
        val apkUrl: String?,
    )

    sealed interface Result {
        data class UpToDate(val current: String) : Result
        data class Available(val release: Release) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun check(currentVersionName: String): Result = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/" +
                    "${BuildConfig.GITHUB_REPO}/releases/latest",
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Starmap-Android")
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            conn.inputStream.use { stream ->
                val json = JSONObject(stream.bufferedReader().readText())
                val tag = json.getString("tag_name")
                val versionName = tag.removePrefix("v")
                val notes = json.optString("body", "")
                val htmlUrl = json.optString("html_url", "")
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                val release = Release(tag, versionName, notes, htmlUrl, apkUrl)
                if (isNewer(versionName, currentVersionName)) {
                    Result.Available(release)
                } else {
                    Result.UpToDate(currentVersionName)
                }
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Network error")
        }
    }

    /** Compares dotted numeric versions; returns true if [candidate] > [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.removePrefix("v").trim().split('.', '-').mapNotNull {
            it.takeWhile { c -> c.isDigit() }.toIntOrNull()
        }
}
