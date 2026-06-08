package com.starmap.app.update

import android.content.Context

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
