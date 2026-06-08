package com.starmap.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads a diagnostic/crash report to a tokenless paste service and returns a
 * shareable URL. Anonymous GitHub Gists are no longer supported, and baking a
 * token into a public app is unsafe, so this uses paste.rs which accepts an
 * unauthenticated POST and replies with the URL.
 */
object LogUploader {

    suspend fun upload(text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://paste.rs/").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setRequestProperty("User-Agent", "Starmap-Android")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            conn.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                val url = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                Result.success(url)
            } else {
                Result.failure(IOException("Upload failed: HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
