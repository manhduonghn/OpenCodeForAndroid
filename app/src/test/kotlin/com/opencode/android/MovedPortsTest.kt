package com.opencode.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MovedPortsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun directoryLivesUnderThePrefix() {
        assertEquals(
            File("/data/data/com.opencode.android/files/usr/run/moved-ports"),
            MovedPorts.directory("/data/data/com.opencode.android/files/usr"),
        )
    }

    @Test
    fun missingDirectoryHasNoPendingPorts() {
        assertTrue(MovedPorts.read(File(temporary.root, "missing")).isEmpty())
    }

    @Test
    fun readParsesValidMarkersAndIgnoresTheRest() {
        val directory = temporary.newFolder("run", "moved-ports")
        File(directory, "80").writeText("8080\n")
        File(directory, "22").writeText("8022")
        // Not a privileged port, not numeric, a directory, and unusable content.
        File(directory, "8080").writeText("16080\n")
        File(directory, "notes").writeText("8080\n")
        File(directory, "443").mkdir()
        File(directory, "3000").writeText("not a port\n")
        File(directory, "8000").writeText("8000\n")
        File(directory, "80.tmp").writeText("8081\n")

        val moved = MovedPorts.read(directory)

        assertEquals(
            listOf(MovedPorts.MovedPort(22, 8022), MovedPorts.MovedPort(80, 8080)),
            moved,
        )
    }

    @Test
    fun discardRemovesOnlyTheReportedMarker() {
        val directory = temporary.newFolder("run", "moved-ports")
        val marker = File(directory, "80").apply { writeText("8080\n") }
        File(directory, "443").writeText("8443\n")
        val port = MovedPorts.MovedPort(80, 8080)

        assertTrue(MovedPorts.discard(directory, port))
        assertFalse(marker.exists())
        assertTrue(File(directory, "443").exists())

        // A second discard is harmless.
        assertTrue(MovedPorts.discard(directory, port))
    }

    @Test
    fun discardKeepsAMarkerThatWasUpdatedMeanwhile() {
        val directory = temporary.newFolder("run", "moved-ports")
        val marker = File(directory, "80").apply { writeText("8081\n") }

        assertFalse(MovedPorts.discard(directory, MovedPorts.MovedPort(80, 8080)))
        assertTrue(marker.exists())
        assertEquals(listOf(MovedPorts.MovedPort(80, 8081)), MovedPorts.read(directory))
    }
}
