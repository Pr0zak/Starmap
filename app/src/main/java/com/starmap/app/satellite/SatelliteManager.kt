package com.starmap.app.satellite

import android.content.Context
import com.starmap.app.astro.Sgp4
import com.starmap.app.astro.TleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A named satellite ready to propagate. */
class NamedSat(val name: String, val sgp4: Sgp4, val isIss: Boolean)

/**
 * Downloads and caches NORAD two-line elements from Celestrak so satellites can
 * be shown offline between refreshes. TLEs drift after a few days, so the UI lets
 * the user re-download.
 */
class SatelliteManager(private val context: Context) {

    private val dir = File(context.filesDir, "tle").apply { mkdirs() }
    private val issFile = File(dir, "iss.tle")
    private val starlinkFile = File(dir, "starlink.tle")

    private val issUrl = "https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=tle"
    private val starlinkUrl = "https://celestrak.org/NORAD/elements/gp.php?GROUP=starlink&FORMAT=tle"

    val isIssDownloaded: Boolean get() = issFile.exists() && issFile.length() > 0
    val isStarlinkDownloaded: Boolean get() = starlinkFile.exists() && starlinkFile.length() > 0
    val issAgeMillis: Long get() = if (issFile.exists()) System.currentTimeMillis() - issFile.lastModified() else -1
    val starlinkAgeMillis: Long get() = if (starlinkFile.exists()) System.currentTimeMillis() - starlinkFile.lastModified() else -1
    val starlinkSizeBytes: Long get() = if (starlinkFile.exists()) starlinkFile.length() else 0

    sealed interface Result {
        data class Ok(val count: Int) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun downloadIss(): Result = download(issUrl, issFile, isIss = true) {}
    suspend fun downloadStarlink(onProgress: (Float) -> Unit): Result =
        download(starlinkUrl, starlinkFile, isIss = false, onProgress)

    suspend fun loadIss(): List<NamedSat> = load(issFile, isIss = true)
    suspend fun loadStarlink(): List<NamedSat> = load(starlinkFile, isIss = false)

    fun deleteIss() { issFile.delete() }
    fun deleteStarlink() { starlinkFile.delete() }

    private suspend fun load(file: File, isIss: Boolean): List<NamedSat> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext emptyList()
        val text = file.readText()
        TleParser.parse(text).mapNotNull { tle ->
            runCatching { NamedSat(tle.name, Sgp4(tle), isIss) }
                .getOrNull()
                ?.takeIf { !it.sgp4.deepSpace }
        }
    }

    private suspend fun download(
        url: String,
        file: File,
        isIss: Boolean,
        onProgress: (Float) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Starmap-Android")
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            val code = conn.responseCode
            if (code !in 200..299) return@withContext Result.Failed("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val parsed = TleParser.parse(tmp.readText())
            if (parsed.isEmpty()) {
                tmp.delete()
                return@withContext Result.Failed("No satellites in response")
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
            Result.Ok(parsed.size)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Download failed")
        }
    }
}
