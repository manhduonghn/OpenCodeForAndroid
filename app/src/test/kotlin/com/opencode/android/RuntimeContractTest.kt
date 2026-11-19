package com.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
