package com.opencode.android

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * A snapshot of the state of the OpenCode installation.
 *
 * The state is written to the disk after every change, so that the app shows the real progress
 * again after it was closed, after the activity was destroyed or after Android killed the
 * process. The screen of the app is never blank while something is being downloaded.
 *
 * The installation is split into stages. Every stage has a weight, the sum of all weights is
 * 100, so that the overall percentage can be computed from the percentage of the current stage.
 */
class OpenCodeInstallState {

    /** The stage the installation is currently in. */
    var stage: String = STAGE_IDLE
    /** The percentage of the current stage, between 0 and 100. */
    var stagePercent: Int = 0
    /** The percentage of the whole installation, between 0 and 100. */
    var overallPercent: Int = 0
    /** The main status line, eg. "Downloading OpenCode 1.2.3…". */
    var message: String = ""
    /** The secondary line, eg. "12.3 MB / 98.7 MB - 1.4 MB/s - 1m 20s left". */
    var detail: String = ""
    /** The error message when the installation failed. */
    var error: String = ""
    /** Number of bytes that were downloaded so far. */
    var bytesDone: Long = 0
    /** Number of bytes that have to be downloaded in total, 0 when unknown. */
    var bytesTotal: Long = 0
    /** True while the installation is running. */
    var running: Boolean = false
    /** True when the installation finished successfully. */
    var done: Boolean = false
    /** True when the installation failed. */
    var failed: Boolean = false
    /** Timestamp of the last update. */
    var updatedAt: Long = 0

    /** The last steps of the installation, the oldest ones are dropped. */
    val steps: MutableList<String> = ArrayList()

    /** The percentage of the whole installation, between 0 and 100. */
    fun overallProgress(): Int = clampPercent(overallPercent)

    /** The percentage of the current stage, between 0 and 100. */
    fun stageProgress(): Int = clampPercent(stagePercent)

    /**
     * Recompute [overallPercent] from the current stage and its percentage. Must be called
     * whenever the stage or the percentage of the stage changes.
     */
    fun recomputeOverallPercent() {
        if (STAGE_DONE == stage) {
            overallPercent = 100
            return
        }

        var total = 0
        var found = false
        for ((key, weight) in STAGE_WEIGHTS) {
            if (key == stage) {
                total += weight * clampPercent(stagePercent) / 100
                found = true
                break
            }
            total += weight
        }

        // An unknown stage (idle, failed, …) keeps the last known percentage.
        overallPercent = clampPercent(if (found) total else overallPercent)
    }

    /** Add a step to the list of the steps, the oldest ones are dropped. */
    fun addStep(step: String?) {
        if (step == null) return
        synchronized(steps) {
            steps.add(step)
            while (steps.size > MAX_STEPS) steps.removeAt(0)
        }
    }

    /** The last steps of the installation, the newest ones last. */
    fun getSteps(): List<String> = synchronized(steps) { ArrayList(steps) }

    /** The state as a JSON object. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("stage", stage)
        put("stagePercent", stagePercent)
        put("overallPercent", overallPercent)
        put("message", message)
        put("detail", detail)
        put("error", error)
        put("bytesDone", bytesDone)
        put("bytesTotal", bytesTotal)
        put("running", running)
        put("done", done)
        put("failed", failed)
        put("updatedAt", updatedAt)
        put("steps", JSONArray(getSteps()))
    }

    companion object {
        private const val TAG = "OpenCodeInstallState"

        /** Nothing is being installed. */
        const val STAGE_IDLE = "idle"
        /** Preparing the environment (second stage of the Termux bootstrap, resolver, …). */
        const val STAGE_PREPARE = "prepare"
        /** Refreshing the package lists of apt. */
        const val STAGE_APT_UPDATE = "apt_update"
        /** Installing the Linux toolchain packages (bash, glibc, patchelf, …). */
        const val STAGE_APT_INSTALL = "apt_install"
        /** Downloading the OpenCode package. */
        const val STAGE_DOWNLOAD = "download"
        /** Extracting the OpenCode package. */
        const val STAGE_EXTRACT = "extract"
        /** Preparing the ELF binary for the glibc loader. */
        const val STAGE_PATCH = "patch"
        /** Verifying the patched binary. */
        const val STAGE_VERIFY = "verify"
        /** The installation finished successfully. */
        const val STAGE_DONE = "done"
        /** The installation failed. */
        const val STAGE_FAILED = "failed"

        /** The weight of every stage, in percent. The sum of all of them is 100. */
        private val STAGE_WEIGHTS = linkedMapOf(
            STAGE_PREPARE to 3,
            STAGE_APT_UPDATE to 7,
            STAGE_APT_INSTALL to 25,
            STAGE_DOWNLOAD to 45,
            STAGE_EXTRACT to 8,
            STAGE_PATCH to 7,
            STAGE_VERIFY to 5,
        )

        /** Number of steps kept in the state, the oldest ones are dropped. */
        private const val MAX_STEPS = 60

        /** File the state is written to, it survives a restart of the app. */
        const val STATE_FILE_PATH = OpenCodeBootstrap.OC_HOME + "/install-state.json"

        /** The state read from a JSON object, never null. */
        fun fromJson(json: JSONObject?): OpenCodeInstallState {
            val state = OpenCodeInstallState()
            if (json == null) return state

            state.stage = json.optString("stage", STAGE_IDLE)
            state.stagePercent = json.optInt("stagePercent", 0)
            state.overallPercent = json.optInt("overallPercent", 0)
            state.message = json.optString("message", "")
            state.detail = json.optString("detail", "")
            state.error = json.optString("error", "")
            state.bytesDone = json.optLong("bytesDone", 0)
            state.bytesTotal = json.optLong("bytesTotal", 0)
            state.running = json.optBoolean("running", false)
            state.done = json.optBoolean("done", false)
            state.failed = json.optBoolean("failed", false)
            state.updatedAt = json.optLong("updatedAt", 0)

            val array = json.optJSONArray("steps")
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optString(i, null)?.let { state.steps.add(it) }
                }
            }
            return state
        }

        /**
         * Write the state to the disk. The file is written to a temporary file first and then
         * renamed, so that a crash never leaves a half written file behind.
         */
        fun save(state: OpenCodeInstallState?) {
            if (state == null) return

            val file = File(STATE_FILE_PATH)
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) return

            val temp = File(file.absolutePath + ".tmp")
            try {
                temp.outputStream().use { it.write(state.toJson().toString().toByteArray(Charsets.UTF_8)) }
            } catch (e: IOException) {
                Log.e(TAG, "Could not write the install state", e)
                return
            }

            if (!temp.renameTo(file)) {
                // The rename can fail when the target exists on some file systems.
                if (!file.delete() || !temp.renameTo(file)) {
                    Log.w(TAG, "Could not replace $file")
                }
            }
        }

        /** The state that was saved on the disk, never null. */
        fun load(): OpenCodeInstallState {
            val file = File(STATE_FILE_PATH)
            if (!file.isFile) return OpenCodeInstallState()
            return try {
                val text = file.readText(Charsets.UTF_8)
                fromJson(JSONObject(text))
            } catch (e: Exception) {
                Log.e(TAG, "Could not read the install state", e)
                OpenCodeInstallState()
            }
        }

        /** Remove the saved state, used when the runtime is complete. */
        fun clear() {
            try {
                File(STATE_FILE_PATH).delete()
            } catch (e: Exception) {
                Log.e(TAG, "Could not delete the install state", e)
            }
        }

        private fun clampPercent(value: Int): Int = value.coerceIn(0, 100)

        /** True if the given message is empty or only contains spaces. */
        fun isEmpty(text: String?): Boolean = text.isNullOrBlank()
    }
}
