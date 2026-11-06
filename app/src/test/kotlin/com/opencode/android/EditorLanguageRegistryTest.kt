package com.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EditorLanguageRegistryTest {

    @Test
    fun resolvesExactNamesBeforeExtensions() {
        assertEquals("kotlin", EditorLanguageRegistry.resolve("build.gradle.kts").id)
        assertEquals("kotlin", EditorLanguageRegistry.resolve("settings.gradle.kts").id)
        assertEquals("groovy", EditorLanguageRegistry.resolve("build.gradle").id)
        assertEquals("properties", EditorLanguageRegistry.resolve("gradle.properties").id)
        assertEquals("properties", EditorLanguageRegistry.resolve(".env.local").id)
        assertEquals("plaintext", EditorLanguageRegistry.resolve(".gitignore").id)
    }

    @Test
    fun resolvesTheCommonEditorLanguages() {
        val expected = mapOf(
            "Main.kt" to "kotlin",
            "Main.java" to "java",
            "layout.xml" to "xml",
            "data.json" to "json",
            "config.yaml" to "yaml",
            "README.md" to "markdown",
            "app.js" to "javascript",
            "app.ts" to "typescript",
            "app.tsx" to "tsx",
            "index.html" to "html",
            "site.css" to "css",
            "site.scss" to "scss",
            "run.sh" to "shell",
            "main.py" to "python",
            "build.gradle" to "groovy",
            "Cargo.toml" to "toml",
            "Cargo.lock" to "toml",
            "Pipfile" to "toml",
            "Dockerfile" to "dockerfile",
            "Containerfile" to "dockerfile",
            "Makefile" to "shell",
            "CMakeLists.txt" to "shell",
            "SConstruct" to "python",
            "change.diff" to "diff",
            "main.c" to "c",
            "main.cpp" to "cpp",
            "main.go" to "go",
            "main.rs" to "rust",
            "query.sql" to "sql",
            "index.php" to "php",
        )
        for ((name, id) in expected) {
            assertEquals(name, id, EditorLanguageRegistry.resolve(name).id)
        }
    }

    @Test
    fun longestExtensionWins() {
        assertEquals("typescript", EditorLanguageRegistry.resolve("types.d.ts").id)
        assertEquals("php", EditorLanguageRegistry.resolve("view.blade.php").id)
    }

    @Test
    fun shebangIsUsedWhenTheNameHasNoLanguage() {
        assertEquals("python", EditorLanguageRegistry.resolve("tool", "#!/usr/bin/env python3").id)
        assertEquals("shell", EditorLanguageRegistry.resolve("tool", "#!/bin/bash").id)
        assertEquals("javascript", EditorLanguageRegistry.resolve("tool", "#!/usr/bin/env node").id)
        assertEquals("plaintext", EditorLanguageRegistry.resolve("tool", "#!/usr/bin/perl").id)
    }

    @Test
    fun extensionWinsOverAShebang() {
        assertEquals("python", EditorLanguageRegistry.resolve("tool.py", "#!/bin/bash").id)
        assertEquals("shell", EditorLanguageRegistry.resolve("tool.sh", "#!/usr/bin/env python3").id)
    }

    @Test
    fun idsAndAliasesAreCaseInsensitive() {
        assertEquals("kotlin", EditorLanguageRegistry.byId("KOTLIN").id)
        assertEquals("javascript", EditorLanguageRegistry.byId("node").id)
        assertEquals("plaintext", EditorLanguageRegistry.byId("not-a-language").id)
        assertNotEquals(
            EditorLanguageRegistry.byId("kotlin"),
            EditorLanguageRegistry.byId("java"),
        )
    }

    @Test
    fun everyRegisteredLanguageHasAUniqueId() {
        val languages = EditorLanguageRegistry.all()
        val ids = languages.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (language in languages) {
            assertEquals(language, EditorLanguageRegistry.byId(language.id))
        }
    }
}
