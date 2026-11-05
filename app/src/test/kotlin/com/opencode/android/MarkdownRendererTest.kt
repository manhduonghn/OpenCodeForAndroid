package com.opencode.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    private val theme = EditorThemeBuilder.fallback(dark = false)

    @Test
    fun escapesHtmlInsteadOfEmbeddingIt() {
        val html = MarkdownRenderer.render("<script>alert('x')</script>")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun rendersHeadingsListsAndCode() {
        val html = MarkdownRenderer.render(
            """
            # Tiêu đề

            - một
            - hai

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertTrue(html.contains("<h1>Tiêu đề</h1>"))
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li>một</li>"))
        assertTrue(html.contains("language-kotlin"))
        assertTrue(html.contains("val x = 1"))
    }

    @Test
    fun keepsUnsafeLinksAsPlainText() {
        val html = MarkdownRenderer.render("[bấm](javascript:alert(1))")
        assertFalse(html.contains("href"))
        assertTrue(html.contains("bấm"))
    }

    @Test
    fun rendersSafeLinksAndImageAltText() {
        val html = MarkdownRenderer.render(
            "[tài liệu](https://opencode.ai/docs) và ![ảnh](file:///secret.png)",
        )
        assertTrue(html.contains("href=\"https://opencode.ai/docs\""))
        assertTrue(html.contains("<em>ảnh</em>"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("secret.png"))
    }

    @Test
    fun fullDocumentDisablesScriptsAndNetwork() {
        val page = MarkdownRenderer.toHtml("# x", theme)
        assertTrue(page.contains("Content-Security-Policy"))
        assertTrue(page.contains("default-src 'none'"))
        assertTrue(page.contains("color-scheme:light"))
    }
}
