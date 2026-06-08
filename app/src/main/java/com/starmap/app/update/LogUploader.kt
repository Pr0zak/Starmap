package com.starmap.app.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads a diagnostic/crash report and returns a shareable URL.
 *
 * If the user has saved a GitHub token (see [DiagPrefs]) it creates a *secret
 * Gist*; otherwise it falls back to a tokenless paste service. Anonymous Gists
 * are no longer supported and baking a token into a public app is unsafe, hence
 * the bring-your-own-token design.
 */
object LogUploader {

    /** Routes to a Gist when a token is saved, else to the paste service. */
    suspend fun upload(context: Context, text: String): Result<String> {
        val token = DiagPrefs.getToken(context)
        return if (token.isNotBlank()) uploadGist(token, text) else uploadPaste(text)
    }

    suspend fun uploadGist(token: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://api.github.com/gists").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Starmap-Android")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val files = JSONObject().put(
                "starmap-report.txt",
                JSONObject().put("content", text),
            )
            val body = JSONObject()
                .put("description", "Starmap diagnostic report")
                .put("public", false)
                .put("files", files)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val url = JSONObject(resp).optString("html_url")
                Result.success(url.ifBlank { "Gist created" })
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Result.failure(IOException("Gist upload failed (HTTP $code). ${err.take(160)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadPaste(text: String): Result<String> = withContext(Dispatchers.IO) {
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
                Result.success(conn.inputStream.bufferedReader().use { it.readText() }.trim())
            } else {
                Result.failure(IOException("Upload failed: HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
