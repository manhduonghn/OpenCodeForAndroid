package com.opencode.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonSemaphoreFdTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun installsIntoEveryPythonVersionAndIsIdempotent() {
        val prefix = temporary.newFolder("files", "usr")
        val sites = listOf(
            File(prefix, "lib/python3.14/site-packages"),
            File(prefix, "lib/python3.15/site-packages"),
        )
        sites.forEach { it.mkdirs() }
        File(prefix, "lib/notpython/site-packages").mkdirs()

        assertTrue(PythonSemaphoreFd.install(prefix))
        for (site in sites) {
            val module = File(site, PythonSemaphoreFd.MODULE).readText()
            assertEquals("import oc_semaphore_fd\n", File(site, PythonSemaphoreFd.PTH).readText())
            assertTrue(module.contains("OC_SEMAPHORE_DIR"))
            assertTrue(module.contains("os.dup2(opened, _SEM_FD)"))
            assertTrue(module.contains("_mp_util.spawnv_passfds"))
            assertTrue(module.contains("fds.add(_SEM_FD)"))
        }
        assertFalse(File(prefix, "lib/notpython/site-packages/${PythonSemaphoreFd.MODULE}").exists())

        assertFalse(PythonSemaphoreFd.install(prefix))

        val first = File(sites[0], PythonSemaphoreFd.MODULE)
        first.writeText("broken by a user or an interrupted update\n")
        assertTrue(PythonSemaphoreFd.install(prefix))
        assertTrue(first.readText().contains("_mp_util.spawnv_passfds"))
    }

    @Test
    fun doesNothingWithoutAPythonInstallation() {
        val prefix = temporary.newFolder("files", "usr")
        File(prefix, "lib").mkdirs()
        assertFalse(PythonSemaphoreFd.install(prefix))
    }
}
