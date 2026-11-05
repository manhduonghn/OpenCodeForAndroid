package com.opencode.android

import android.content.Context
import org.json.JSONArray
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
    private const val KEY_EDITOR_RENDERER = "editor_renderer"
    private const val KEY_EDITOR_SIMPLE = "editor_simple_mode"
    private const val KEY_EDITOR_FONT_SIZE = "editor_font_size"
    private const val KEY_EDITOR_WORD_WRAP = "editor_word_wrap"
    private const val KEY_EDITOR_TAB_SIZE = "editor_tab_size"
    private const val KEY_EDITOR_SOFT_TAB = "editor_soft_tab"
    private const val KEY_EDITOR_RECENT = "editor_recent_files"
    private const val KEY_EDITOR_LANGUAGES = "editor_language_overrides"

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

    /* ------------------------------------------------------------------ editor */

    /**
     * Which renderer the editor prefers: `auto` (CodeMirror when the WebView can run it),
     * `native` (the simple EditText) or `web` (force CodeMirror for debugging on a device).
     */
    fun editorRenderer(context: Context): String =
        prefs(context).getString(KEY_EDITOR_RENDERER, "auto")?.takeIf {
            it == "auto" || it == "native" || it == "web"
        } ?: "auto"

    fun setEditorRenderer(context: Context, value: String) {
        val normalized = if (value == "native" || value == "web") value else "auto"
        prefs(context).edit().putString(KEY_EDITOR_RENDERER, normalized).apply()
    }

    /** Accessibility/simple mode always uses the native EditText, even if `web` is selected. */
    fun editorSimpleMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EDITOR_SIMPLE, false)

    fun setEditorSimpleMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EDITOR_SIMPLE, enabled).apply()
    }

    fun editorFontSize(context: Context): Int =
        prefs(context).getInt(KEY_EDITOR_FONT_SIZE, 14).coerceIn(8, 32)

    fun setEditorFontSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_EDITOR_FONT_SIZE, size.coerceIn(8, 32)).apply()
    }

    fun editorWordWrap(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EDITOR_WORD_WRAP, false)

    fun setEditorWordWrap(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EDITOR_WORD_WRAP, enabled).apply()
    }

    fun editorTabSize(context: Context): Int =
        prefs(context).getInt(KEY_EDITOR_TAB_SIZE, 4).coerceIn(1, 8)

    fun setEditorTabSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_EDITOR_TAB_SIZE, size.coerceIn(1, 8)).apply()
    }

    fun editorSoftTab(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EDITOR_SOFT_TAB, true)

    fun setEditorSoftTab(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EDITOR_SOFT_TAB, enabled).apply()
    }

    /** Paths of the files opened most recently, newest first, at most 20 entries. */
    fun editorRecentFiles(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_EDITOR_RECENT, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val path = array.optString(index, "")
                    if (path.isNotEmpty()) add(path)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addEditorRecentFile(context: Context, path: String) {
        if (path.isEmpty()) return
        val recent = editorRecentFiles(context).toMutableList()
        recent.remove(path)
        recent.add(0, path)
        while (recent.size > 20) recent.removeAt(recent.size - 1)
        val array = JSONArray()
        for (item in recent) array.put(item)
        prefs(context).edit().putString(KEY_EDITOR_RECENT, array.toString()).apply()
    }

    fun clearEditorRecentFiles(context: Context) {
        prefs(context).edit().remove(KEY_EDITOR_RECENT).apply()
    }

    fun editorLanguageOverride(context: Context, path: String): String? {
        val raw = prefs(context).getString(KEY_EDITOR_LANGUAGES, null) ?: return null
        return try {
            JSONObject(raw).optString(path, "").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    fun setEditorLanguageOverride(context: Context, path: String, languageId: String?) {
        if (path.isEmpty()) return
        val json = try {
            prefs(context).getString(KEY_EDITOR_LANGUAGES, null)?.let { JSONObject(it) } ?: JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        try {
            if (languageId.isNullOrEmpty()) json.remove(path) else json.put(path, languageId)
            prefs(context).edit().putString(KEY_EDITOR_LANGUAGES, json.toString()).apply()
        } catch (e: Exception) {
            // A broken preference must not break the editor.
        }
    }
}
