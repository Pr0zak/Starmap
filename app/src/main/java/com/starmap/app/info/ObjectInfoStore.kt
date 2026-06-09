package com.starmap.app.info

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Wikipedia object info with an on-disk cache, so a description (and, via Coil's
 * image cache, its photo) keeps working offline once fetched or synced. Capacity
 * used and clearing cover both the text cache here and Coil's image disk cache.
 */
class ObjectInfoStore(private val context: Context) {

    private val wiki = WikiManager()
    private val dir = File(context.cacheDir, "objinfo").apply { mkdirs() }

    /** Cached info first, then the network (which is cached for next time). */
    suspend fun get(query: String): WikiManager.Result {
        readCache(query)?.let { return WikiManager.Result.Ok(it) }
        val r = wiki.fetch(query)
        if (r is WikiManager.Result.Ok) writeCache(query, r.info)
        return r
    }

    /** Download an image into Coil's disk cache so it shows offline later. */
    suspend fun prewarmImage(url: String) {
        runCatching {
            context.imageLoader.execute(ImageRequest.Builder(context).data(url).build())
        }
    }

    /** Bytes used by the cached text plus Coil's image disk cache. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        val text = dir.listFiles()?.sumOf { it.length() } ?: 0L
        text + (context.imageLoader.diskCache?.size ?: 0L)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        context.imageLoader.diskCache?.clear()
    }

    private fun fileFor(query: String) = File(dir, md5(query) + ".json")

    private fun readCache(query: String): WikiManager.Info? {
        val f = fileFor(query)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            WikiManager.Info(
                o.optString("title"),
                o.optString("extract"),
                o.optString("imageUrl").takeIf { it.isNotBlank() },
                o.optString("pageUrl").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(query: String, info: WikiManager.Info) {
        runCatching {
            val o = JSONObject()
                .put("title", info.title)
                .put("extract", info.extract)
                .put("imageUrl", info.imageUrl.orEmpty())
                .put("pageUrl", info.pageUrl.orEmpty())
            fileFor(query).writeText(o.toString())
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
