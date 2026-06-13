package com.starmap.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny shared HTTP transport so the feature managers don't each hand-roll
 * connection setup, timeouts, the User-Agent, and the 200..299 check. Parsing and
 * result types stay in the callers. All suspend functions run on [Dispatchers.IO]
 * and rethrow [CancellationException] so structured-concurrency cancellation works.
 */
object Http {
    const val USER_AGENT = "Starmap/1.0 (Android; +https://github.com/pr0zak/starmap)"

    /** GETs the response body as text, or null on any non-2xx / failure. */
    suspend fun getText(url: String, accept: String? = null, timeoutMs: Int = 12_000): String? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = open(url, accept, timeoutMs)
                if (conn.responseCode !in 200..299) return@withContext null
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    /** GETs and parses a JSON object, or null on failure / malformed body. */
    suspend fun getJson(url: String, accept: String? = "application/json", timeoutMs: Int = 12_000): JSONObject? =
        getText(url, accept, timeoutMs)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * Downloads [url] to [dest] atomically (temp file + rename), reporting 0..1
     * progress when the content length is known. Returns null on success or a short
     * error message on failure.
     */
    suspend fun downloadToFile(
        url: String,
        dest: File,
        accept: String? = null,
        timeoutMs: Int = 15_000,
        onProgress: (Float) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = open(url, accept, timeoutMs)
            val code = conn.responseCode
            if (code !in 200..299) return@withContext "HTTP $code"
            val total = conn.contentLengthLong
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "download failed"
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(url: String, accept: String?, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            if (accept != null) setRequestProperty("Accept", accept)
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
}
