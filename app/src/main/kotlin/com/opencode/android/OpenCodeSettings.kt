package com.opencode.android

import android.content.Context
import org.json.JSONObject

/**
 * Preferences of the settings screen.
 *
 * The values live in the same `opencode_cfg` file that the older Java implementation used, so an
 * update keeps the choice of the wake lock and the last Files directory.
 */
object OpenCodeSettings {

    private const val PREFS_NAME = "opencode_cfg"
    private const val KEY_WAKE_LOCK = "wake_lock"
    private const val KEY_BOOT_START = "boot_start"
    private const val KEY_WATCHDOG = "watchdog"
    private const val KEY_USER_STOPPED = "user_stopped"
    private const val KEY_AUTO_UPDATE = "auto_update_check"
    private const val KEY_LAST_FILES = "files_last_dir"
    private const val KEY_GIT_AUTO_COMMIT = "git_auto_commit"
    private const val KEY_LAST_SESSION = "last_session_id"
    private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
    private const val KEY_REPO_PATHS = "repo_local_paths"
    private const val KEY_LAST_CLONE_DIR = "clone_last_dir"

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

    /** The folder the Files screen reopens on, unless it was removed or became unreadable. */
    fun lastFilesDir(context: Context): String? =
        prefs(context).getString(KEY_LAST_FILES, null)?.takeIf { it.isNotEmpty() }

    fun setLastFilesDir(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LAST_FILES, path).apply()
    }

    /** Commit manual edits locally after returning to Files; pushing stays a separate action. */
    fun gitAutoCommit(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GIT_AUTO_COMMIT, false)

    fun setGitAutoCommit(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GIT_AUTO_COMMIT, enabled).apply()
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

    /**
     * Local checkout of a GitHub repository, keyed by `owner/repo`.
     *
     * The mapping is stored as JSON so cloning the same repository again is detected, and the
     * repository detail can offer "Open folder" and "Git changes" without scanning the device.
     */
    fun repoPath(context: Context, repo: String): String? {
        val raw = prefs(context).getString(KEY_REPO_PATHS, null) ?: return null
        return try {
            val json = JSONObject(raw)
            json.optString(repo, "").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    fun setRepoPath(context: Context, repo: String, path: String) {
        if (repo.isEmpty() || path.isEmpty()) return
        val json = readRepoPaths(context)
        try {
            json.put(repo, path)
            prefs(context).edit().putString(KEY_REPO_PATHS, json.toString()).apply()
        } catch (e: Exception) {
            // A broken preference must not break the screen.
        }
    }

    fun removeRepoPath(context: Context, repo: String) {
        val json = readRepoPaths(context)
        if (!json.has(repo)) return
        json.remove(repo)
        prefs(context).edit().putString(KEY_REPO_PATHS, json.toString()).apply()
    }

    private fun readRepoPaths(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_REPO_PATHS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /** The folder the previous clone dialog used, falling back to the app HOME. */
    fun lastCloneDir(context: Context): String? =
        prefs(context).getString(KEY_LAST_CLONE_DIR, null)?.takeIf { it.isNotEmpty() }

    fun setLastCloneDir(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LAST_CLONE_DIR, path).apply()
    }
}
