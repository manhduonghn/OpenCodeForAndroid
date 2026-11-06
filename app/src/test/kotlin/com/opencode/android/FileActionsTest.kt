package com.opencode.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FileActionsTest {

    @Test
    fun opensQtProjectFilesInTheTextEditor() {
        // Qt/qmake project files are plain text but the platform MIME table does not always
        // report them as text/*, so the explicit allowlist must catch them.
        assertTrue(FileActions.isTextFile(File("MyProject.pro")))
        assertTrue(FileActions.isTextFile(File("MYPROJECT.PRO")))
    }
}
