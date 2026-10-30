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
    private const val KEY_BOOT_START = "boot_start"
    private const val KEY_WATCHDOG = "watchdog"
    private const val KEY_USER_STOPPED = "user_stopped"
    private const val KEY_AUTO_UPDATE = "auto_update_check"
    private const val KEY_LAST_PROJECT = "storage_last_project_dir"
    private const val KEY_LAST_SESSION = "last_session_id"
    private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the foreground service keeps a PARTIAL_WAKE_LOCK while it runs. */
    fun wakeLockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WAKE_LOCK, true)

    fun setWakeLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_LOCK, enabled).apply()
    }

    /**
     * Whether the server is started right after the device boots (or after an app update).
     *
     * Off by default: an app that starts a foreground service on every boot is surprising for
     * users who only open OpenCode now and then.
     */
    fun bootStartEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOT_START, false)

    fun setBootStartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOT_START, enabled).apply()
    }

    /**
     * Whether the periodic [android.app.job.JobScheduler] watchdog keeps the server alive.
     * It runs every 15 minutes and only starts the service again when the port is closed.
     */
    fun watchdogEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WATCHDOG, true)

    fun setWatchdogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATCHDOG, enabled).apply()
    }

    /**
     * True after the user pressed **Stop** in the notification. The watchdog must not override an
     * explicit stop; opening the app (or pressing Restart) clears the flag again.
     */
    fun stoppedByUser(context: Context): Boolean = prefs(context).getBoolean(KEY_USER_STOPPED, false)

    fun setStoppedByUser(context: Context, stopped: Boolean) {
        prefs(context).edit().putBoolean(KEY_USER_STOPPED, stopped).apply()
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

    /**
     * The chat session that was open when the app was last used. It is kept across a process
     * death so opening the app again continues that chat, like the TUI does; the start page of
     * the web UI does not clear it.
     */
    fun lastSessionId(context: Context): String? =
        prefs(context).getString(KEY_LAST_SESSION, null)
            ?.trim()
            ?.takeIf { it.startsWith("ses_") }

    fun setLastSessionId(context: Context, id: String) {
        val normalized = id.trim()
        if (!normalized.startsWith("ses_")) return
        prefs(context).edit().putString(KEY_LAST_SESSION, normalized).apply()
    }

    /**
     * False until the app offered the battery optimization exemption once. Asking on every launch
     * is hostile; the settings screen keeps the button for later.
     */
    fun batteryPromptShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)

    fun setBatteryPromptShown(context: Context, shown: Boolean) {
        prefs(context).edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, shown).apply()
    }
}
