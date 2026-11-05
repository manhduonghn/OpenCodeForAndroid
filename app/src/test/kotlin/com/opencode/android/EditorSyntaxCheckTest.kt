package com.opencode.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSyntaxCheckTest {

    @Test
    fun supportsOnlyJsonAndXml() {
        assertTrue(EditorSyntaxCheck.supports("json"))
        assertTrue(EditorSyntaxCheck.supports("xml"))
        assertFalse(EditorSyntaxCheck.supports("kotlin"))
        assertFalse(EditorSyntaxCheck.supports("markdown"))
    }

    @Test
    fun acceptsWellFormedXml() {
        val result = EditorSyntaxCheck.check("xml", "<root><item name=\"x\">value</item></root>")
        assertTrue(result.ok)
        assertTrue(result.message.isEmpty())
    }

    @Test
    fun reportsTheLineOfAMalformedXmlDocument() {
        val result = EditorSyntaxCheck.check(
            "xml",
            """
            <root>
              <item>
            </root>
            """.trimIndent(),
        )
        assertFalse(result.ok)
        assertTrue(result.message.isNotEmpty())
    }

    @Test
    fun rejectsEmptyDocuments() {
        assertFalse(EditorSyntaxCheck.check("xml", "").ok)
        assertFalse(EditorSyntaxCheck.check("json", "  \n ").ok)
    }
}
