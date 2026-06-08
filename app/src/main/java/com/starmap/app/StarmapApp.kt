package com.starmap.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Shared crash-report formatting + the file the report is written to. */
object CrashLog {
    const val FILE_NAME = "last_crash.txt"
    const val TAG = "Starmap"

    fun format(thread: Thread, t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return buildString {
            append("Starmap ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Time: ${Date()}\n")
            append("Thread: ${thread.name}\n\n")
            append(sw.toString())
        }
    }

    fun write(context: Context, report: String) {
        runCatching { File(context.filesDir, FILE_NAME).writeText(report) }
    }
}

/**
 * Installs a global crash handler so a fatal exception is recorded (Logcat +
 * a file the UI shows on the next launch) instead of vanishing into a silent
 * "app keeps stopping".
 */
class StarmapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = CrashLog.format(thread, throwable)
                Log.e("StarmapCrash", report)
                CrashLog.write(this, report)
            } catch (_: Throwable) {
                // Never let the crash handler itself crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(CrashLog.TAG, "Application.onCreate — v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
