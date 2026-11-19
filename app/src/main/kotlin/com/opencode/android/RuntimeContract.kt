package com.opencode.android

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Everything the runtime needs, resolved once from the Android/file-system world.
 *
 * This is deliberately a plain data class: the renderers in [RuntimeEnv] and [RuntimeContract] are
 * pure JVM code and can be unit tested without an emulator. Resolving Android-specific inputs
 * (Context, SharedPreferences, file system) happens in `OpenCodeService.buildContract()`, the only
 * adapter that constructs these values.
 */
data class RuntimeInputs(
    val abi: String,
    val prefix: String,
    val home: String,
    val ocHome: String,
    val port: Int,
    val binaryPath: String,
    val binarySha256: String = "",
    val binaryVersion: String = "",
    val loader: String,
    val preload: String?,
    val glibcLibDir: String,
    val ptyBin: String?,
    val ptySocketDir: String,
    val ripgrepPath: String = "",
    val pathmapPath: String = "",
    val javaHome: String?,
    val javaToolOptions: String,
    val gradleUserHome: String,
    val androidUserHome: String,
    val androidSdkHome: File?,
    val bashPath: String?,
    val envScriptPath: String,
    val caBundlePath: String?,
    val opensslConfPath: String?,
    val opensslModulesPath: String?,
    val opensslEnginesPath: String?,
    val gitExecPath: String?,
    val gitTemplateDir: String?,
    val gitSystemConfigPath: String?,
    val apkStamp: String = "",
    val packagesStamp: String = "",
    val nativeStamp: String = "",
)

/**
 * The single runtime contract shared by the service shell, the terminal and the WebView.
 *
 * It has three renderers, all derived from the same [env] map:
 *  - [spawnEnvironment] — the `ProcessBuilder.environment()` of the server;
 *  - [shellExports] — the `export KEY=value` block of the package environment script;
 *  - [toJson] — the persistent `$OC_HOME/runtime.json` (never contains a secret).
 *
 * [contractHash] is the identity of the environment. The service writes it to
 * `$OC_HOME/.server-contract` when it spawns and compares it before adopting a server that survived
 * a process death. This replaces the hand-maintained `serverEnvironmentCurrent()` list and the
 * `serverEnvironmentStamp()` string.
 */
data class RuntimeContract(
    val schema: Int = SCHEMA,
    val abi: String,
    val prefix: String,
    val home: String,
    val ocHome: String,
    val port: Int,
    val binaryPath: String,
    val binarySha256: String,
    val binaryVersion: String,
    val loader: String,
    val preload: String?,
    val glibcLibDir: String,
    val libraryPath: List<String>,
    val ptyBin: String?,
    val ptySocketDir: String,
    val ripgrepPath: String,
    val pathmapPath: String,
    val healthPath: String,
    val authUser: String,
    val pairPath: String,
    val connectPath: String,
    val passwordRef: String,
    /** Non-secret process environment. Secrets are merged in by [spawnEnvironment]. */
    val env: Map<String, String>,
    val apkStamp: String,
    val packagesStamp: String,
    val nativeStamp: String,
) {

    /**
     * The exact environment of the server process. Secrets stay out of the contract file and are
     * injected here, at spawn time, so `runtime.json` never has to be secured beyond mode 0600.
     */
    fun spawnEnvironment(password: String, gitHubToken: String?): Map<String, String> {
        val result = LinkedHashMap(env)
        if (password.isNotEmpty()) result[OPENCODE_SERVER_PASSWORD] = password
        if (!gitHubToken.isNullOrEmpty()) result["GH_TOKEN"] = gitHubToken
        result[OPENCODE_PORT] = port.toString()
        return result
    }

    /** The shell command that executes the downloaded glibc binary through this prefix's loader. */
    fun launchCommand(pidFile: String): String {
        val preloadArg = preload?.takeIf { it.isNotEmpty() }?.let { " --preload '${shellQuote(it)}'" }.orEmpty()
        val exec = "exec '$loader'$preloadArg --library-path '${shellQuote(glibcLibDir)}'" +
            " '${shellQuote(binaryPath)}' serve --hostname 127.0.0.1 --port $port"
        return "echo \$\$ > '${shellQuote(pidFile)}'; $exec"
    }

    /**
     * Reusable `export` block for the generated shell script. The shell script keeps its
     * shell-only helpers (wrappers, npm caches, locale fallbacks), but every variable that also
     * exists in the server process comes from this single map.
     */
    fun shellExports(): String = buildString {
        append("# Generated from RuntimeContract (schema $schema). Do not edit by hand.\n")
        for ((key, value) in env.toSortedMap()) {
            if (key == BASH_ENV) continue // the script sets BASH_ENV to its own path below
            append("export ").append(key).append("='").append(shellQuote(value)).append("'\n")
        }
        append("export ").append(BASH_ENV).append("='").append(shellQuote(envScriptPathValue())).append("'\n")
        // Never write the password into the script. The generated environment reads the 0600
        // file at source time, so the secret is resolved by the shell, not baked into the APK.
        append("if [ -r \"$passwordFilePath\" ]; then\n")
        append("  OPENCODE_SERVER_PASSWORD=\"$(cat \"$passwordFilePath\")\"\n")
        append("  export OPENCODE_SERVER_PASSWORD\n")
        append("fi\n")
    }

    /** Stable identity of the environment. Secrets and generation are excluded on purpose. */
    fun contractHash(): String {
        val canonical = buildString {
            append("schema=").append(schema).append('\n')
            append("abi=").append(abi).append('\n')
            append("prefix=").append(prefix).append('\n')
            append("home=").append(home).append('\n')
            append("ocHome=").append(ocHome).append('\n')
            append("port=").append(port).append('\n')
            append("binary=").append(binaryPath).append('\n')
            append("sha=").append(binarySha256).append('\n')
            append("loader=").append(loader).append('\n')
            append("preload=").append(preload.orEmpty()).append('\n')
            append("libraryPath=").append(libraryPath.joinToString(":")).append('\n')
            append("pty=").append(ptyBin.orEmpty()).append('\n')
            append("socket=").append(ptySocketDir).append('\n')
            append("pathmap=").append(pathmapPath).append('\n')
            append("apk=").append(apkStamp).append('\n')
            append("packages=").append(packagesStamp).append('\n')
            append("native=").append(nativeStamp).append('\n')
            for ((key, value) in env.toSortedMap()) {
                if (key == OPENCODE_SERVER_PASSWORD || key == "GH_TOKEN" || key == OPENCODE_PORT) continue
                append(key).append('=').append(value).append('\n')
            }
        }
        return sha256(canonical)
    }

    /** Persistent manifest for the CLI/watchdog. It contains a reference to the secret, never the value. */
    fun toJson(): String {
        val json = JSONObject()
        json.put("schema", schema)
        json.put("abi", abi)
        json.put("port", port)
        json.put("health", healthPath)
        val paths = JSONObject()
        paths.put("prefix", prefix)
        paths.put("home", home)
        paths.put("ocHome", ocHome)
        json.put("paths", paths)
        val binary = JSONObject()
        binary.put("path", binaryPath)
        binary.put("sha256", binarySha256)
        binary.put("version", binaryVersion)
        json.put("binary", binary)
        val launch = JSONObject()
        launch.put("loader", loader)
        launch.put("preload", preload ?: JSONObject.NULL)
        launch.put("libraryPath", org.json.JSONArray(libraryPath))
        json.put("launch", launch)
        val helpers = JSONObject()
        helpers.put("pty", ptyBin ?: JSONObject.NULL)
        helpers.put("ptySocketDir", ptySocketDir)
        helpers.put("ripgrep", ripgrepPath)
        helpers.put("pathmap", pathmapPath)
        json.put("helpers", helpers)
        val auth = JSONObject()
        auth.put("user", authUser)
        auth.put("passwordRef", passwordRef)
        auth.put("pair", pairPath)
        auth.put("connect", connectPath)
        json.put("auth", auth)
        val stamps = JSONObject()
        stamps.put("apk", apkStamp)
        stamps.put("packages", packagesStamp)
        stamps.put("native", nativeStamp)
        json.put("stamps", stamps)
        json.put("contractHash", contractHash())
        return json.toString(2)
    }

    private fun envScriptPathValue(): String = env[BASH_ENV] ?: ""

    private val passwordFilePath: String get() = "$ocHome/server-password"

    companion object {
        const val SCHEMA = 1
        const val OPENCODE_SERVER_PASSWORD = "OPENCODE_SERVER_PASSWORD"
        const val OPENCODE_PORT = "OPENCODE_PORT"
        const val BASH_ENV = "BASH_ENV"

        /** Read the hash a spawned server stamped into `$OC_HOME/.server-contract`. */
        fun readHash(file: File): String? = try {
            if (!file.isFile) null else file.readText().trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }

        fun writeHash(file: File, hash: String) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(hash + "\n")
            } catch (e: Exception) {
                // The stamp is an optimisation: without it adoption falls back to a restart.
            }
        }

        fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return buildString(bytes.size * 2) {
                for (byte in bytes) {
                    val unsigned = byte.toInt() and 0xFF
                    append(Character.forDigit(unsigned ushr 4, 16))
                    append(Character.forDigit(unsigned and 0x0F, 16))
                }
            }
        }

        /** Shell-safe single-quoted content. POSIX has no escape inside single quotes except ending them. */
        fun shellQuote(value: String): String = value.replace("'", "'\\''")

        /** Stable hex of a byte array, used for binary stamps. */
        fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (byte in bytes) append(String.format(Locale.US, "%02x", byte.toInt() and 0xFF))
        }
    }
}

/** Pure renderer: turns resolved inputs into the one contract all layers consume. */
object RuntimeEnv {

    fun build(inputs: RuntimeInputs): RuntimeContract {
        val env = LinkedHashMap<String, String>()
        val pathEntries = ArrayList<String>()
        pathEntries.add("${inputs.prefix}/bin")
        inputs.javaHome?.let { pathEntries.add("$it/bin") }
        inputs.androidSdkHome?.let { sdk ->
            for (relative in listOf(
                "cmdline-tools/latest/bin", "cmdline-tools/bin", "tools/bin", "platform-tools",
            )) {
                File(sdk, relative).takeIf { it.isDirectory }?.let { pathEntries.add(it.absolutePath) }
            }
        }
        pathEntries.add("/system/bin")

        env["HOME"] = inputs.home
        env["PREFIX"] = inputs.prefix
        env["PATH"] = pathEntries.joinToString(":")
        inputs.javaHome?.let { env["JAVA_HOME"] = it }
        env["JAVA_TOOL_OPTIONS"] = inputs.javaToolOptions
        env["GRADLE_USER_HOME"] = inputs.gradleUserHome
        env["ANDROID_USER_HOME"] = inputs.androidUserHome
        inputs.androidSdkHome?.let {
            env["ANDROID_HOME"] = it.absolutePath
            env["ANDROID_SDK_ROOT"] = it.absolutePath
        }
        env["TMPDIR"] = "${inputs.prefix}/tmp"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        if (File(inputs.prefix, "share/zoneinfo").isDirectory) {
            env["TZDIR"] = "${inputs.prefix}/share/zoneinfo"
        }
        env["COLORTERM"] = "truecolor"
        inputs.bashPath?.let { env["SHELL"] = it }
        env[RuntimeContract.BASH_ENV] = inputs.envScriptPath
        env["DEBIAN_FRONTEND"] = "noninteractive"
        env["OPENCODE_DISABLE_AUTOUPDATE"] = "1"
        env["OC_REAL_EXE"] = inputs.binaryPath
        inputs.ptyBin?.let { env["OPENCODE_PTY_BIN"] = it }
        env["OPENCODE_PTY_SOCKET_DIR"] = inputs.ptySocketDir

        inputs.caBundlePath?.let { cert ->
            env["SSL_CERT_FILE"] = cert
            env["CURL_CA_BUNDLE"] = cert
            env["NODE_EXTRA_CA_CERTS"] = cert
            env["REQUESTS_CA_BUNDLE"] = cert
            env["PIP_CERT"] = cert
            env["GIT_SSL_CAINFO"] = cert
        }

        val libraryPath = ArrayList<String>()
        inputs.javaHome?.let { java ->
            File(java, "lib/server").takeIf { it.isDirectory }?.let { libraryPath.add(it.absolutePath) }
            File(java, "lib").takeIf { it.isDirectory }?.let { libraryPath.add(it.absolutePath) }
        }
        File(inputs.prefix, "lib").takeIf { it.isDirectory }?.let { libraryPath.add(it.absolutePath) }
        if (libraryPath.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = libraryPath.joinToString(":")
        }

        inputs.gitExecPath?.let { env["GIT_EXEC_PATH"] = it }
        inputs.gitTemplateDir?.let { env["GIT_TEMPLATE_DIR"] = it }
        if (inputs.gitSystemConfigPath != null) {
            env["GIT_CONFIG_SYSTEM"] = inputs.gitSystemConfigPath
        } else {
            env["GIT_CONFIG_NOSYSTEM"] = "1"
        }
        env["GIT_ATTR_NOSYSTEM"] = "1"
        inputs.opensslConfPath?.let { env["OPENSSL_CONF"] = it }
        inputs.opensslModulesPath?.let { env["OPENSSL_MODULES"] = it }
        inputs.opensslEnginesPath?.let { env["OPENSSL_ENGINES"] = it }

        return RuntimeContract(
            abi = inputs.abi,
            prefix = inputs.prefix,
            home = inputs.home,
            ocHome = inputs.ocHome,
            port = inputs.port,
            binaryPath = inputs.binaryPath,
            binarySha256 = inputs.binarySha256,
            binaryVersion = inputs.binaryVersion,
            loader = inputs.loader,
            preload = inputs.preload,
            glibcLibDir = inputs.glibcLibDir,
            libraryPath = libraryPath,
            ptyBin = inputs.ptyBin,
            ptySocketDir = inputs.ptySocketDir,
            ripgrepPath = inputs.ripgrepPath,
            pathmapPath = inputs.pathmapPath,
            healthPath = "/api/info",
            authUser = "opencode",
            pairPath = "/api/pair",
            connectPath = "/auth/connect/",
            passwordRef = "sharedPreferences:server_password",
            env = env,
            apkStamp = inputs.apkStamp,
            packagesStamp = inputs.packagesStamp,
            nativeStamp = inputs.nativeStamp,
        )
    }
}
