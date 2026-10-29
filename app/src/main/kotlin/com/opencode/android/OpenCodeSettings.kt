package com.opencode.android

import android.content.Context

/**
 * Preferences of the settings screen.
 *
 * The values live in the same `opencode_cfg` file that the older Java implementation used, so an
 * update keeps the choice of the wake lock and of the last project directory.
 */
object OpenCodeSettings {

    private const val PREFS_NAME = "opencode_cfg"
    private const val KEY_WAKE_LOCK = "wake_lock"
    private const val KEY_AUTO_UPDATE = "auto_update_check"
    private const val KEY_LAST_PROJECT = "storage_last_project_dir"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the foreground service keeps a PARTIAL_WAKE_LOCK while it runs. */
    fun wakeLockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WAKE_LOCK, true)

    fun setWakeLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_LOCK, enabled).apply()
    }

    fun autoUpdateCheck(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdateCheck(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }

    fun lastProjectDir(context: Context): String? =
        prefs(context).getString(KEY_LAST_PROJECT, null)?.takeIf { it.isNotEmpty() }

    fun setLastProjectDir(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LAST_PROJECT, path).apply()
    }
}
