package com.starmap.app.catalog

import android.content.Context
import com.starmap.app.BuildConfig
import com.starmap.app.astro.Constellation
import com.starmap.app.astro.ConstellationCatalog
import com.starmap.app.astro.StarCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the on-device catalogs. The naked-eye catalog and constellation figures
 * ship inside the APK (so the app is fully usable offline immediately). An
 * extended catalog can be downloaded once and cached in internal storage.
 */
class CatalogManager(private val context: Context) {

    private val extendedFile = File(context.filesDir, "catalog/stars_ext.json")

    /** Where the extended catalog is fetched from (raw file in the GitHub repo). */
    private val extendedUrl =
        "https://raw.githubusercontent.com/${BuildConfig.GITHUB_OWNER}/" +
            "${BuildConfig.GITHUB_REPO}/main/catalog/stars_ext.json"

    val isExtendedDownloaded: Boolean get() = extendedFile.exists() && extendedFile.length() > 0

    val extendedSizeBytes: Long get() = if (extendedFile.exists()) extendedFile.length() else 0L

    suspend fun loadStars(useExtended: Boolean): StarCatalog = withContext(Dispatchers.IO) {
        if (useExtended && isExtendedDownloaded) {
            extendedFile.inputStream().use { StarCatalog.parse(it) }
        } else {
            context.assets.open("stars.json").use { StarCatalog.parse(it) }
        }
    }

    suspend fun loadConstellations(): List<Constellation> = withContext(Dispatchers.IO) {
        context.assets.open("constellations.json").use { ConstellationCatalog.parse(it) }
    }

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class InProgress(val fraction: Float) : DownloadState
        data class Done(val sizeBytes: Long) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    /**
     * Downloads the extended catalog, reporting progress through [onProgress].
     * Writes atomically (temp file + rename) so a partial download is never used.
     */
    suspend fun downloadExtended(onProgress: (Float) -> Unit): DownloadState =
        withContext(Dispatchers.IO) {
            try {
                extendedFile.parentFile?.mkdirs()
                val tmp = File(extendedFile.parentFile, "stars_ext.tmp")
                val conn = (URL(extendedUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Starmap-Android")
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (extendedFile.exists()) extendedFile.delete()
                if (!tmp.renameTo(extendedFile)) {
                    tmp.copyTo(extendedFile, overwrite = true)
                    tmp.delete()
                }
                DownloadState.Done(extendedFile.length())
            } catch (e: Exception) {
                DownloadState.Failed(e.message ?: "Download failed")
            }
        }

    fun deleteExtended(): Boolean = if (extendedFile.exists()) extendedFile.delete() else false
}
