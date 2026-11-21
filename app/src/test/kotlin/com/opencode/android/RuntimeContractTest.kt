package com.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        assertTrue(script.contains("unset LD_PRELOAD"))
        assertTrue(script.contains("pkg() {"))
        assertTrue(script.contains("npm_config_cache="))
        assertFalse(script.contains("for _oc_sdk"))
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
