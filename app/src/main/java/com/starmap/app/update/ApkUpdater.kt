package com.starmap.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.starmap.app.net.Http
import java.io.File

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
    ): State {
        val out = File(updatesDir(context), "starmap-update.apk")
        val error = Http.downloadToFile(
            url, out, accept = "application/octet-stream", timeoutMs = 60_000, onProgress = onProgress,
        )
        return if (error == null) State.ReadyToInstall(out) else State.Failed(error)
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
