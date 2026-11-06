package com.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorThemeBuilderTest {

    @Test
    fun lightFallbackContainsEveryRequiredGroup() {
        val theme = EditorThemeBuilder.fallback(dark = false)
        assertFalse(theme.dark)

        for (key in REQUIRED_COLORS) assertTrue("missing color $key", theme.colors.containsKey(key))
        for (key in REQUIRED_SYNTAX) assertTrue("missing syntax $key", theme.syntax.containsKey(key))
        for (key in REQUIRED_MARKDOWN) assertTrue("missing markdown $key", theme.markdown.containsKey(key))
        assertTrue(theme.colors.values.none { it.isBlank() })
    }

    @Test
    fun darkFallbackChangesBackgroundAndKeepsAllKeys() {
        val light = EditorThemeBuilder.fallback(dark = false)
        val dark = EditorThemeBuilder.fallback(dark = true)
        assertTrue(dark.dark)
        assertNotEquals(light.colors["background"], dark.colors["background"])
        assertEquals(light.colors.keys, dark.colors.keys)
        assertEquals(light.syntax.keys, dark.syntax.keys)
        assertEquals(light.markdown.keys, dark.markdown.keys)
    }

    private companion object {
        val REQUIRED_COLORS = listOf(
            "background",
            "foreground",
            "surface",
            "gutter",
            "gutterText",
            "caret",
            "selection",
            "activeLine",
            "searchMatch",
            "searchMatchSelected",
            "bracketMatch",
            "indentGuide",
            "invalid",
            "foldPlaceholder",
        )
        val REQUIRED_SYNTAX = listOf(
            "comment",
            "keyword",
            "string",
            "primitive",
            "property",
            "type",
            "function",
            "constant",
            "operator",
            "punctuation",
            "variable",
            "invalid",
        )
        val REQUIRED_MARKDOWN = listOf(
            "heading",
            "text",
            "link",
            "code",
            "quote",
            "emph",
            "strong",
            "rule",
            "listItem",
            "listEnumeration",
            "image",
        )
    }
}
