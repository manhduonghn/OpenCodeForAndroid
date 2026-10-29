package com.opencode.android

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * GitHub support of the settings screen: GitHub CLI (`gh`) based device login, the repository
 * list and `git clone`.
 *
 * The OAuth device flow is implemented here instead of relying on the interactive prompt of
 * `gh`, because `gh` runs without a TTY inside an Android app and would not show a code. The
 * token is handed to `gh auth login --with-token`, so `gh` and `git` store it in their own
 * configuration below the app HOME.
 */
object OpenCodeGitHub {

    private const val TAG = "OpenCodeGitHub"

    private const val CLIENT_ID = "178c6fc778ccc68e1d6a"
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val VERIFY_URL = "https://github.com/login/device"
    private const val SCOPES = "repo read:org gist"

    /** scp-like (`git@github.com:owner/repo`) and `ssh://git@github.com/owner/repo` URLs. */
    private val SSH_GITHUB_URL = Regex("^(?:ssh://)?git@github\\.com[:/](.+)$")

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Long,
    )

    data class Repo(
        val name: String,
        val description: String,
        val privateRepo: Boolean,
        val url: String,
    )

    class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun gitAvailable(): Boolean = File(OpenCodeBootstrap.PREFIX, "bin/git").canExecute()

    fun ghAvailable(): Boolean = File(OpenCodeBootstrap.PREFIX, "bin/gh").canExecute()

    /**
     * Absolute path of git. Android's ProcessBuilder does not look the program up in the PATH of
     * the environment it is given, it searches the PATH of the app process (which only contains
     * /system/bin). A bare "git" therefore fails with ENOENT even after git was installed into
     * the prefix, while `gh` always worked because [runGh] uses its absolute path.
     */
    private fun gitBinary(): String {
        val git = File(OpenCodeBootstrap.PREFIX, "bin/git")
        return if (git.canExecute()) git.absolutePath else "git"
    }

    fun bothAvailable(): Boolean = gitAvailable() && ghAvailable()

    /** The token file the foreground service reads to export GH_TOKEN for OpenCode. */
    private fun tokenFile(): File = File(OpenCodeBootstrap.OC_HOME, "github-token")

    fun storedToken(): String? = try {
        tokenFile().takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun storeToken(token: String) {
        try {
            val file = tokenFile()
            file.parentFile?.mkdirs()
            file.writeText(token)
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        } catch (e: Exception) {
            Log.w(TAG, "Could not store the GitHub token", e)
        }
    }

    private fun clearToken() {
        try {
            tokenFile().delete()
        } catch (e: Exception) {
            // Not important.
        }
    }

    /** Install git and gh into the private prefix, blocking the calling thread. */
    fun install(context: Context, onStatus: (String) -> Unit): Result {
        return try {
            OpenCodePackages.install(
                File(OpenCodeBootstrap.PREFIX),
                listOf("git", "gh"),
                object : OpenCodePackages.Progress {
                    override fun onStatus(message: String) = onStatus(message)

                    override fun onPercent(percent: Int, detail: String) {
                        onStatus(detail.ifEmpty { "$percent%" })
                    }
                },
            )
            // The extraction already repairs the package, run the same repair for a prefix that
            // was installed by an older build, then refresh the shell environment so git and gh
            // find their helpers and the shared libraries of the prefix.
            try {
                OpenCodePackages.repair(File(OpenCodeBootstrap.PREFIX))
            } catch (e: Exception) {
                Log.w(TAG, "Could not repair the package environment", e)
            }
            try {
                OpenCodeBootstrap.ensurePackageEnvironment()
            } catch (e: Exception) {
                Log.w(TAG, "Could not refresh the shell environment", e)
            }
            Result(if (bothAvailable()) 0 else 1, "")
        } catch (e: Exception) {
            Log.w(TAG, "Could not install git/gh", e)
            Result(-1, e.message ?: e.toString())
        }
    }

    fun isLoggedIn(): Boolean {
        if (!ghAvailable()) return false
        val result = runGh(arrayOf("auth", "status", "-h", "github.com"))
        if (!result.ok) return false
        val lower = result.output.lowercase()
        if (lower.contains("not logged")) return false
        return lower.contains("logged in to github.com")
    }

    fun currentUser(): String? {
        if (!isLoggedIn()) return null
        val result = runGh(arrayOf("api", "user", "-q", ".login"))
        if (!result.ok) return null
        val name = result.output.trim()
        if (name.isEmpty() || name.contains('\n') || name.contains(' ')) return null
        return name
    }

    fun logout() {
        if (ghAvailable()) runGh(arrayOf("auth", "logout", "-h", "github.com"))
        clearToken()
    }

    /** Ask GitHub for a device code. */
    fun requestDeviceCode(): DeviceCode {
        val body = postForm(
            DEVICE_CODE_URL,
            mapOf("client_id" to CLIENT_ID, "scope" to SCOPES),
        )
        val json = JSONObject(body)
        val deviceCode = json.optString("device_code", "")
        val userCode = json.optString("user_code", "")
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            val error = json.optString("error_description", json.optString("error", body))
            throw IllegalStateException("GitHub không trả về mã đăng nhập: $error")
        }
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = json.optString("verification_uri", VERIFY_URL),
            interval = maxOf(5, json.optInt("interval", 5)),
            expiresIn = maxOf(60L, json.optLong("expires_in", 900L)),
        )
    }

    /** Poll GitHub until the user confirms the code, then store the token in gh. */
    fun completeLogin(device: DeviceCode) {
        val deadline = SystemClock.uptimeMillis() + device.expiresIn * 1000L
        var interval = maxOf(5, device.interval)
        while (SystemClock.uptimeMillis() < deadline) {
            val body = postForm(
                ACCESS_TOKEN_URL,
                mapOf(
                    "client_id" to CLIENT_ID,
                    "device_code" to device.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            )
            val json = JSONObject(body)
            val token = json.optString("access_token", "")
            if (token.isNotEmpty()) {
                storeToken(token)
                val auth = runGh(
                    arrayOf("auth", "login", "--with-token", "-h", "github.com"),
                    input = token,
                )
                if (!auth.ok) {
                    clearToken()
                    val detail = auth.output.trim().take(200)
                    throw IllegalStateException(
                        "Không lưu được token vào gh" + if (detail.isEmpty()) "." else ": $detail",
                    )
                }
                // Plain `git clone` can use the gh credential helper, even for private repos.
                runGh(arrayOf("auth", "setup-git"))
                if (!isLoggedIn()) {
                    throw IllegalStateException("gh chưa ghi nhận đăng nhập. Hãy thử lại.")
                }
                return
            }
            when (json.optString("error", "")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "expired_token" ->
                    throw IllegalStateException("Mã đăng nhập đã hết hạn. Bấm Đăng nhập lại để lấy mã mới.")
                "access_denied" ->
                    throw IllegalStateException("Bạn đã từ chối cấp quyền cho ứng dụng.")
                else -> {
                    val description = json.optString("error_description", json.optString("error", ""))
                    throw IllegalStateException(
                        description.ifEmpty { "Không lấy được access token." },
                    )
                }
            }
            try {
                Thread.sleep(interval * 1000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Đã huỷ đăng nhập.")
            }
        }
        throw IllegalStateException("Hết thời gian chờ xác thực. Bấm Đăng nhập lại để lấy mã mới.")
    }

    fun listRepos(): Result {
        return runGh(
            arrayOf(
                "repo", "list", "--limit", "100",
                "--json", "nameWithOwner,description,isPrivate,url",
            ),
        )
    }

    fun parseRepos(output: String): List<Repo> {
        val text = output.trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = JSONArray(text.substring(start, end + 1))
        val repos = ArrayList<Repo>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("nameWithOwner", "")
            if (name.isEmpty()) continue
            val description = item.optString("description", "").let {
                if (it == "null") "" else it
            }
            repos.add(
                Repo(
                    name = name,
                    description = description,
                    privateRepo = item.optBoolean("isPrivate", false),
                    url = "https://github.com/$name.git",
                ),
            )
        }
        return repos
    }

    fun normalizeCloneUrl(repo: String): String {
        var url = repo.trim()
        if (url.isEmpty()) return url
        while (url.endsWith("/")) url = url.dropLast(1)
        // The prefix has no ssh binary and the GitHub login is an HTTPS token, so the ssh:// and
        // scp-like URLs of GitHub would always fail. Rewrite them to HTTPS, which can use the
        // stored token.
        SSH_GITHUB_URL.find(url)?.let { match ->
            url = "https://github.com/" + match.groupValues[1]
        }
        if (url.startsWith("github.com/") || url.startsWith("www.github.com/")) {
            url = "https://$url"
        } else if (!url.contains("://") && !url.startsWith("git@") && url.indexOf('/') > 0) {
            url = "https://github.com/$url"
        }
        if (url.startsWith("https://github.com/") && !url.endsWith(".git")) url += ".git"
        return url
    }

    /**
     * Clone [repoUrl] into the app HOME. Every output line is handed to [onLine] while git is
     * still running, so the settings card can show live progress.
     */
    fun clone(repoUrl: String, onLine: ((String) -> Unit)? = null): Result {
        val url = normalizeCloneUrl(repoUrl)
        if (url.isEmpty()) return Result(-1, "empty URL")
        val extraEnv = HashMap<String, String>()
        extraEnv["GIT_TERMINAL_PROMPT"] = "0"
        // The git package of Termux was built with SHELL_PATH pointing at the Termux app. Git runs
        // a `!` credential helper through that shell, so the helper of `gh auth setup-git` cannot
        // start inside this sandbox and private clones would fail. Send the token to libcurl
        // instead, the request is authenticated before git asks a helper. An empty helper also
        // keeps the unusable one out of the way when the token was revoked.
        storedToken()?.let { token ->
            val basic = Base64.encodeToString(
                "x-access-token:$token".toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
            extraEnv["GIT_CONFIG_COUNT"] = "2"
            extraEnv["GIT_CONFIG_KEY_0"] = "http.https://github.com/.extraheader"
            extraEnv["GIT_CONFIG_VALUE_0"] = "Authorization: Basic $basic"
            extraEnv["GIT_CONFIG_KEY_1"] = "credential.helper"
            extraEnv["GIT_CONFIG_VALUE_1"] = ""
        }
        return run(
            arrayOf(gitBinary(), "clone", "--progress", "--", url),
            directory = File(OpenCodeBootstrap.HOME),
            input = null,
            timeoutMs = 900000L,
            extraEnv = extraEnv,
            onLine = onLine,
        )
    }

    /** Run gh with the environment of the app prefix. gh must be able to persist a token. */
    fun runGh(args: Array<String>, input: String? = null, timeoutMs: Long = 180000L): Result {
        val gh = File(OpenCodeBootstrap.PREFIX, "bin/gh")
        val command = if (gh.canExecute()) {
            arrayOf(gh.absolutePath) + args
        } else {
            arrayOf("gh") + args
        }
        return run(command, File(OpenCodeBootstrap.HOME), input, timeoutMs, null, null, persistToken = true)
    }

    private fun run(
        command: Array<String>,
        directory: File,
        input: String?,
        timeoutMs: Long,
        extraEnv: Map<String, String>? = null,
        onLine: ((String) -> Unit)? = null,
        persistToken: Boolean = false,
    ): Result {
        return try {
            val builder = ProcessBuilder(*command)
            builder.directory(directory)
            builder.redirectErrorStream(true)
            val env = builder.environment()
            env.clear()
            env["HOME"] = OpenCodeBootstrap.HOME
            env["PREFIX"] = OpenCodeBootstrap.PREFIX
            env["PATH"] = "${OpenCodeBootstrap.PREFIX}/bin:/system/bin"
            env["TMPDIR"] = "${OpenCodeBootstrap.PREFIX}/tmp"
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"
            env["LD_LIBRARY_PATH"] = "${OpenCodeBootstrap.PREFIX}/lib"
            env["GIT_EXEC_PATH"] = "${OpenCodeBootstrap.PREFIX}/libexec/git-core"
            File(OpenCodeBootstrap.PREFIX, "share/git-core/templates")
                .takeIf { it.isDirectory }
                ?.let { env["GIT_TEMPLATE_DIR"] = it.absolutePath }
            val systemConfig = File(OpenCodeBootstrap.PREFIX, "etc/gitconfig")
            if (systemConfig.isFile) env["GIT_CONFIG_SYSTEM"] = systemConfig.absolutePath
            else env["GIT_CONFIG_NOSYSTEM"] = "1"
            env["GIT_ATTR_NOSYSTEM"] = "1"
            env["SSL_CERT_FILE"] = "${OpenCodeBootstrap.PREFIX}/etc/tls/cert.pem"
            env["CURL_CA_BUNDLE"] = "${OpenCodeBootstrap.PREFIX}/etc/tls/cert.pem"
            extraEnv?.forEach { (key, value) -> env[key] = value }
            if (persistToken) {
                // gh refuses to store a token while GH_TOKEN is set.
                env.remove("GH_TOKEN")
                env.remove("GITHUB_TOKEN")
            } else {
                storedToken()?.let { env["GH_TOKEN"] = it }
            }

            val process = builder.start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { stream ->
                        while (true) {
                            val line = stream.readLine() ?: break
                            synchronized(output) { output.append(line).append('\n') }
                            onLine?.let {
                                try {
                                    it(line)
                                } catch (e: Exception) {
                                    // The listener must not break the process.
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // The process ended.
                }
            }
            reader.isDaemon = true
            reader.start()

            if (input != null) {
                try {
                    BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)).use { writer ->
                        writer.write(input)
                        if (!input.endsWith("\n")) writer.write("\n")
                        writer.flush()
                    }
                } catch (e: Exception) {
                    // The process may not read stdin.
                }
            } else {
                try {
                    process.outputStream.close()
                } catch (e: Exception) {
                    // Not important.
                }
            }

            var exitCode = -1
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (true) {
                try {
                    exitCode = process.exitValue()
                    break
                } catch (e: IllegalThreadStateException) {
                    if (SystemClock.uptimeMillis() > deadline) {
                        process.destroy()
                        break
                    }
                    try {
                        Thread.sleep(50L)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        process.destroy()
                        break
                    }
                }
            }
            reader.join(1500L)
            Result(exitCode, synchronized(output) { output.toString() })
        } catch (e: Exception) {
            Log.w(TAG, "Could not run ${command.firstOrNull()}", e)
            Result(-1, e.message ?: e.toString())
        }
    }

    private fun postForm(url: String, params: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "OpenCodeForAndroid")
            val body = params.entries.joinToString("&") { (key, value) ->
                URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            if (stream == null) throw IllegalStateException("GitHub trả về HTTP $status")
            stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Timeout helper used by older callers. */
    fun waitFor(process: Process, timeoutMs: Long): Boolean =
        process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
}
