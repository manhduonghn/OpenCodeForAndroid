package com.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeContractTest {

    private fun inputs(javaToolOptions: String = "-Duser.home=/home") = RuntimeInputs(
        abi = "arm64-v8a",
        prefix = "/data/data/com.opencode.android/files/usr",
        home = "/data/data/com.opencode.android/files/home",
        ocHome = "/data/data/com.opencode.android/files/home/.opencode",
        port = 49374,
        binaryPath = "/data/data/com.opencode.android/files/home/.opencode/installed/opencode-v1",
        loader = "/data/data/com.opencode.android/files/usr/glibc/lib/ld-linux-aarch64.so.1",
        preload = "/data/data/com.opencode.android/files/usr/lib/liboc-pathmap.so",
        glibcLibDir = "/data/data/com.opencode.android/files/usr/glibc/lib",
        nativeLibraryDir = "/data/app/com.opencode.android/lib/arm64",
        execShim = "/data/app/com.opencode.android/lib/arm64/liboc_exec.so",
        execWrapperBin = "/data/app/com.opencode.android/lib/arm64/liboc_ocrun.so",
        ptyBin = "/data/data/com.opencode.android/files/home/.opencode/bin/opencode-pty",
        ptySocketDir = "/data/data/com.opencode.android/files/usr/run",
        javaHome = null,
        javaToolOptions = javaToolOptions,
        gradleUserHome = "/data/data/com.opencode.android/files/home/.gradle",
        androidUserHome = "/data/data/com.opencode.android/files/home/.android",
        androidSdkHome = null,
        bashPath = "/data/data/com.opencode.android/files/usr/bin/bash",
        envScriptPath = "/data/data/com.opencode.android/files/usr/etc/opencode-env.sh",
        caBundlePath = "/data/data/com.opencode.android/files/usr/etc/tls/cert.pem",
        opensslConfPath = null,
        opensslModulesPath = null,
        opensslEnginesPath = null,
        gitExecPath = null,
        gitTemplateDir = null,
        gitSystemConfigPath = null,
    )

    @Test
    fun renderingIsDeterministic() {
        val first = RuntimeEnv.build(inputs())
        val second = RuntimeEnv.build(inputs())
        assertEquals(first.contractHash(), second.contractHash())
        assertEquals(first.toJson(), second.toJson())
        assertEquals(first.shellExports(), second.shellExports())
    }

    @Test
    fun oneEnvironmentChangeChangesTheHash() {
        val before = RuntimeEnv.build(inputs("-Duser.home=/home"))
        val after = RuntimeEnv.build(inputs("-Duser.home=/other"))
        assertNotEquals(before.contractHash(), after.contractHash())
    }

    @Test
    fun secretsAreNotPersistedButAreInjectedAtSpawn() {
        val contract = RuntimeEnv.build(inputs())
        val json = contract.toJson()
        assertTrue(json.contains("passwordRef"))
        assertFalse(json.contains("super-secret"))

        val shell = contract.shellExports()
        assertFalse(shell.contains("super-secret"))
        assertTrue(shell.contains("server-password"))

        val env = contract.spawnEnvironment("super-secret", "ghp_token")
        assertEquals("super-secret", env[RuntimeContract.OPENCODE_SERVER_PASSWORD])
        assertEquals("ghp_token", env["GH_TOKEN"])
        assertEquals("49374", env[RuntimeContract.OPENCODE_PORT])
        assertFalse(contract.env.containsKey(RuntimeContract.OPENCODE_SERVER_PASSWORD))
    }

    @Test
    fun generatedShellScriptHasOneExportPerEnvironmentVariable() {
        val contract = RuntimeEnv.build(inputs())
        val script = RuntimeShellScript.render(contract)
        for (key in contract.env.keys) {
            if (key == RuntimeContract.BASH_ENV) continue
            assertEquals(
                "the script must not redefine $key outside the contract",
                1,
                script.lineSequence().count { it.startsWith("export $key=") },
            )
        }
        assertTrue(script.contains("export ${RuntimeContract.BASH_ENV}="))
        assertFalse(script.contains("export ${RuntimeContract.OPENCODE_SERVER_PASSWORD}="))
    }

    @Test
    fun generatedShellScriptKeepsOnlyShellBehaviourOutsideTheContract() {
        val script = RuntimeShellScript.render(RuntimeEnv.build(inputs()))
        assertTrue(script.contains("Generated from RuntimeContract"))
        assertTrue(script.contains("OC_PREFIX="))
        assertTrue(script.contains("LD_PRELOAD='/data/app/com.opencode.android/lib/arm64/liboc_exec.so'; export LD_PRELOAD"))
        assertFalse(script.contains("unset LD_PRELOAD"))
        assertTrue(script.contains("LD_LIBRARY_PATH= command pkg"))
        assertTrue(script.contains("npm_config_cache="))
        assertFalse(script.contains("for _oc_sdk"))

        val withoutShim = RuntimeShellScript.render(
            RuntimeEnv.build(inputs().copy(execShim = null)),
        )
        assertTrue(withoutShim.contains("unset LD_PRELOAD"))
    }

    @Test
    fun execShimAndGlibcLoaderAreExportedToChildren() {
        val contract = RuntimeEnv.build(inputs())
        assertEquals(contract.execShim, contract.env["OC_EXEC_SHIM"])
        assertEquals(contract.loader, contract.env["OC_GLIBC_LOADER"])
        assertEquals(contract.glibcLibDir, contract.env["OC_GLIBC_LIB_DIR"])
        assertTrue(contract.toJson().contains("liboc_exec.so"))
        assertFalse(contract.env.containsKey("LD_PRELOAD"))
    }

    @Test
    fun execWrapperIsFirstInPathAndMaterialisedForEveryPrefixBinary() {
        val contract = RuntimeEnv.build(inputs())
        val wrapperDir = "${contract.prefix}/libexec/oc-bin"

        assertEquals(contract.execWrapperBin, contract.env["OC_EXEC_WRAPPER_BIN"])
        assertEquals(wrapperDir, contract.env["OC_EXEC_WRAPPER_DIR"])
        assertEquals(wrapperDir, contract.env["PATH"]!!.substringBefore(':'))
        assertEquals(wrapperDir, contract.shellEnvironment()["PATH"]!!.substringBefore(':'))
        assertTrue(contract.toJson().contains("liboc_ocrun.so"))
        assertTrue(contract.toJson().contains(wrapperDir))

        val script = RuntimeShellScript.render(contract)
        assertTrue(script.contains("/system/bin/ln -sf \"\$OC_EXEC_WRAPPER_BIN\""))
        assertTrue(script.contains("[ \"\$OC_PREFIX/bin\" -nt \"\$OC_EXEC_WRAPPER_DIR/.stamp\" ]"))
        assertTrue(script.contains("LD_LIBRARY_PATH= command pkg"))

        val other = RuntimeEnv.build(inputs().copy(execWrapperBin = "/other/liboc_ocrun.so"))
        assertNotEquals(contract.contractHash(), other.contractHash())
    }

    @Test
    fun terminalAndToolShellsUseTheContractEnvironment() {
        val contract = RuntimeEnv.build(inputs())
        val shell = contract.shellEnvironment(terminal = true)

        // Everything the terminal needs before its rc file runs comes from the contract.
        assertEquals(contract.env["PATH"], shell["PATH"])
        assertEquals(contract.env["HOME"], shell["HOME"])
        assertEquals(contract.env["TMPDIR"], shell["TMPDIR"])
        assertEquals(contract.env["BASH_ENV"], shell["BASH_ENV"])
        assertEquals(contract.env["OC_SEMAPHORE_DIR"], shell["OC_SEMAPHORE_DIR"])
        assertEquals(contract.prefix, shell["OC_PREFIX"])
        assertEquals(contract.home, shell["OC_USER_HOME"])
        assertEquals(contract.execShim, shell["LD_PRELOAD"])
        assertEquals("1", shell["OPENCODE_TERMINAL"])
        assertFalse(shell.containsKey(RuntimeContract.OPENCODE_SERVER_PASSWORD))
        assertFalse(shell.containsKey("GH_TOKEN"))

        // The script and the initial process carry the same shim, from one renderer.
        assertTrue(
            RuntimeShellScript.render(contract).contains(
                "LD_PRELOAD='${contract.execShim}'; export LD_PRELOAD",
            ),
        )
    }

    @Test
    fun prefixLibIsInTheInitialShellEnvironment() {
        val prefix = Files.createTempDirectory("oc-prefix").toFile()
        try {
            File(prefix, "lib").mkdirs()
            File(prefix, "share/terminfo").mkdirs()
            val contract = RuntimeEnv.build(inputs().copy(prefix = prefix.absolutePath))
            val libraryPath = "${prefix.absolutePath}/lib"

            // The linker needs this before bash starts, not after a profile sources the script.
            assertEquals(libraryPath, contract.env["LD_LIBRARY_PATH"])
            assertEquals(libraryPath, contract.shellEnvironment()["LD_LIBRARY_PATH"])
            assertEquals(libraryPath, contract.shellEnvironment(terminal = true)["LD_LIBRARY_PATH"])
            assertEquals(
                "${prefix.absolutePath}/share/terminfo",
                contract.shellEnvironment(terminal = true)["TERMINFO"],
            )
        } finally {
            prefix.deleteRecursively()
        }
    }

    @Test
    fun helperEnvironmentSharesPathsWithoutLoaderVariables() {
        val contract = RuntimeEnv.build(inputs())
        val env = contract.bootstrapEnvironment(mapOf("TMPDIR" to "/custom/tmp"))

        assertEquals(contract.home, env["HOME"])
        assertEquals(contract.prefix, env["PREFIX"])
        assertEquals(contract.env["PATH"], env["PATH"])
        assertEquals("/custom/tmp", env["TMPDIR"])
        assertEquals(contract.execShim, env["OC_EXEC_SHIM"])
        assertEquals(contract.execWrapperBin, env["OC_EXEC_WRAPPER_BIN"])
        assertEquals(contract.execWrapperDir, env["OC_EXEC_WRAPPER_DIR"])
        assertEquals(contract.loader, env["OC_GLIBC_LOADER"])
        assertFalse(env.containsKey("LD_LIBRARY_PATH"))
        assertFalse(env.containsKey("LD_PRELOAD"))
    }

    @Test
    fun serverLauncherEnvironmentAddsTheShimAndDropsThePathmapHomeHint() {
        val contract = RuntimeEnv.build(inputs())
        val launcher = contract.serverProcessEnvironment("super-secret", "ghp_token")

        assertEquals(contract.execShim, launcher["LD_PRELOAD"])
        assertEquals("super-secret", launcher[RuntimeContract.OPENCODE_SERVER_PASSWORD])
        assertEquals("ghp_token", launcher["GH_TOKEN"])
        assertEquals(contract.env["LD_LIBRARY_PATH"], launcher["LD_LIBRARY_PATH"])
        // getcwd() must not be rewritten to the Termux home for the CLI: it uses that path as its
        // workspace root and then fails every location lookup because the directory does not exist.
        assertFalse(launcher.containsKey("OC_USER_HOME"))
        assertEquals(contract.home, launcher["HOME"])
        // Shells still get the hint, so legacy programs and child processes keep path translation.
        assertEquals(contract.home, contract.shellEnvironment()["OC_USER_HOME"])
        // The contract itself stays secret-free and unmodified.
        assertFalse(contract.env.containsKey("LD_PRELOAD"))
        assertFalse(contract.env.containsKey(RuntimeContract.OPENCODE_SERVER_PASSWORD))
    }

    @Test
    fun shellExportsIncludeOptionalResolvedPaths() {
        val contract = RuntimeEnv.build(
            inputs(
                javaToolOptions = "-Duser.home=/home -Djava.io.tmpdir=/tmp",
            ).copy(
                javaHome = "/opt/jdk",
                androidSdkHome = File("/opt/sdk"),
                opensslConfPath = "/etc/openssl.cnf",
                gitExecPath = "/git-core",
            ),
        )
        val shell = contract.shellExports()
        assertTrue(shell.contains("export JAVA_HOME='/opt/jdk'"))
        assertTrue(shell.contains("export ANDROID_HOME='/opt/sdk'"))
        assertTrue(shell.contains("export OPENSSL_CONF='/etc/openssl.cnf'"))
        assertTrue(shell.contains("export GIT_EXEC_PATH='/git-core'"))
    }

    @Test
    fun semaphoreDirectoryFollowsTheInstallPathInEveryEnvironment() {
        val contract = RuntimeEnv.build(inputs())
        assertEquals("/data/data/com.opencode.android/tmp", contract.env["OC_SEMAPHORE_DIR"])
        assertTrue(contract.shellExports().contains("export OC_SEMAPHORE_DIR='/data/data/com.opencode.android/tmp'"))
        assertEquals(
            "/data/data/com.opencode.android/tmp",
            contract.spawnEnvironment("secret", null)["OC_SEMAPHORE_DIR"],
        )

        val workProfile = RuntimeEnv.build(
            inputs().copy(prefix = "/data/user/10/com.example.with.a.long.package/files/usr"),
        )
        assertEquals(
            "/data/user/10/com.example.with.a.long.package/tmp",
            workProfile.env["OC_SEMAPHORE_DIR"],
        )
        assertTrue(workProfile.shellExports().contains("export OC_SEMAPHORE_DIR="))
    }

    @Test
    fun shellQuoteKeepsSingleQuotedValuesValid() {
        assertEquals("a'\\''b", RuntimeContract.shellQuote("a'b"))
    }

    @Test
    fun launchCommandUsesOneContract() {
        val contract = RuntimeEnv.build(inputs())
        val command = contract.launchCommand("/pid")
        assertTrue(command.contains("exec '"))
        assertTrue(command.contains("--preload '"))
        assertTrue(command.contains("--library-path '"))
        assertTrue(command.contains("serve --hostname 127.0.0.1 --port 49374"))
    }
}
