package com.starmap.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK from GitHub and hands it to the system package
 * installer so the app can update itself in place — no reinstall, data
 * preserved. In-place update only works because every release is signed with
 * the same certificate (see keystore.properties / app/build.gradle.kts).
 */
object ApkUpdater {

    sealed interface State {
        data object Idle : State
        data class Downloading(val fraction: Float) : State
        data class ReadyToInstall(val file: File) : State
        data class Failed(val message: String) : State
    }

    private fun updatesDir(context: Context): File =
        File(context.filesDir, "updates").apply { mkdirs() }

    /** Download the APK at [url], reporting progress via [onProgress]. */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit,
    ): State = withContext(Dispatchers.IO) {
        try {
            val dir = updatesDir(context)
            val out = File(dir, "starmap-update.apk")
            val tmp = File(dir, "starmap-update.tmp")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Starmap-Android")
                setRequestProperty("Accept", "application/octet-stream")
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext State.Failed("Server returned HTTP $code")
            }
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
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            State.ReadyToInstall(out)
        } catch (e: Exception) {
            State.Failed(e.message ?: "Download failed")
        }
    }

    /** Whether the user has allowed this app to install packages. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Intent that opens the "install unknown apps" screen for this app. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /** Launch the system installer for a downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
