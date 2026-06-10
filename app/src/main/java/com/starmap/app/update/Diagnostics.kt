package com.starmap.app.update

import android.content.Context
import android.os.Build
import com.starmap.app.BuildConfig
import com.starmap.app.CrashLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small synchronous store for the optional GitHub token used to upload diagnostic
 * reports to a secret Gist. Kept in app-private SharedPreferences so the crash
 * screen (which runs before the normal UI) can read it without a coroutine.
 */
object DiagPrefs {
    private const val FILE = "starmap_diag"
    private const val KEY_TOKEN = "github_token"

    fun getToken(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TOKEN, "").orEmpty()

    fun setToken(context: Context, token: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token.trim()).apply()
    }
}

/**
 * A tiny rolling diagnostics log. Features (e.g. the landmark fetch) append a
 * timestamped line; the About screen uploads the whole thing so problems that
 * never crash — like "no landmarks found" — can still be inspected. The app
 * context is captured once at startup so callers don't need to thread it through.
 */
object DiagLog {
    private const val FILE_NAME = "diag_log.txt"
    private const val MAX_BYTES = 64 * 1024
    private val lock = Any()
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Appends one timestamped line, trimming the oldest content past the cap. */
    fun log(message: String) {
        val ctx = appContext ?: return
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        runCatching {
            synchronized(lock) {
                val f = File(ctx.filesDir, FILE_NAME)
                val existing = if (f.exists()) f.readText() else ""
                var combined = existing + "$stamp  $message\n"
                if (combined.length > MAX_BYTES) combined = combined.takeLast(MAX_BYTES)
                f.writeText(combined)
            }
        }
    }

    private fun read(context: Context): String =
        runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /** Assembles the full upload: device header, the rolling log, and any crash. */
    fun report(context: Context): String = buildString {
        append("Starmap ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Time: ${Date()}\n\n")
        val log = read(context)
        append("=== Diagnostics log ===\n")
        append(
            if (log.isBlank()) {
                "(empty — exercise a feature, e.g. enable Landmarks, then upload)\n"
            } else {
                log
            },
        )
        val crash = File(context.filesDir, CrashLog.FILE_NAME)
        if (crash.exists()) {
            append("\n=== Last crash ===\n")
            append(runCatching { crash.readText() }.getOrDefault("(unreadable)"))
        }
    }
}
