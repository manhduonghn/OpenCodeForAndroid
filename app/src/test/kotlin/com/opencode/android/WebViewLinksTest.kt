package com.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewLinksTest {

    private val baseUrl = "http://127.0.0.1:49374"

    @Test
    fun keepsTheServerInTheWebView() {
        assertEquals(
            WebViewLinks.Target.WEB_VIEW,
            WebViewLinks.classify("http://127.0.0.1:49374/session/abc", baseUrl),
        )
        assertEquals(WebViewLinks.Target.WEB_VIEW, WebViewLinks.classify("$baseUrl/", baseUrl))
        // The loopback interface may also be named by host name.
        assertEquals(
            WebViewLinks.Target.WEB_VIEW,
            WebViewLinks.classify("http://localhost:49374/session/abc", baseUrl),
        )
        assertEquals(
            WebViewLinks.Target.WEB_VIEW,
            WebViewLinks.classify("http://127.0.0.1:49374/?q=1#top", baseUrl),
        )
    }

    @Test
    fun handsOtherSitesToTheBrowser() {
        assertEquals(
            WebViewLinks.Target.EXTERNAL,
            WebViewLinks.classify("https://opencode.ai/docs", baseUrl),
        )
        assertEquals(
            WebViewLinks.Target.EXTERNAL,
            WebViewLinks.classify("http://127.0.0.1:8080/", baseUrl),
        )
        assertEquals(
            WebViewLinks.Target.EXTERNAL,
            WebViewLinks.classify("mailto:someone@example.com", baseUrl),
        )
        assertEquals(
            WebViewLinks.Target.EXTERNAL,
            WebViewLinks.classify("https://example.com/docs", baseUrl),
        )
        // Userinfo cannot fake the server origin.
        assertEquals(
            WebViewLinks.Target.EXTERNAL,
            WebViewLinks.classify("http://127.0.0.1:49374@evil.example/", baseUrl),
        )
    }

    @Test
    fun handsFilePathsToTheApp() {
        assertEquals(
            WebViewLinks.Target.EXTERNAL,
            WebViewLinks.classify("file:///data/data/com.opencode.android/files/home/a.txt", baseUrl),
        )
        assertEquals(
            WebViewLinks.Target.EXTERNAL,
            WebViewLinks.classify("/data/data/com.opencode.android/files/home/a.txt", baseUrl),
        )
        assertEquals(
            "/data/data/com.opencode.android/files/home/a b.txt",
            WebViewLinks.localFile("/data/data/com.opencode.android/files/home/a b.txt")?.path,
        )
        assertEquals(
            "/data/data/com.opencode.android/files/home/a b.txt",
            WebViewLinks.localFile("file:///data/data/com.opencode.android/files/home/a%20b.txt")?.path,
        )
        // The chat appends a line selection to a file link.
        assertEquals(
            "/data/data/com.opencode.android/files/home/a.txt",
            WebViewLinks.localFile("/data/data/com.opencode.android/files/home/a.txt#L10-L12")?.path,
        )
        assertNull(WebViewLinks.localFile("https://opencode.ai/docs"))
    }

    @Test
    fun ignoresSchemesThatAreNotLinks() {
        assertEquals(WebViewLinks.Target.IGNORE, WebViewLinks.classify("javascript:alert(1)", baseUrl))
        assertEquals(WebViewLinks.Target.IGNORE, WebViewLinks.classify("content://com.example/x", baseUrl))
        assertEquals(WebViewLinks.Target.IGNORE, WebViewLinks.classify("intent://scan/#Intent", baseUrl))
        assertEquals(WebViewLinks.Target.IGNORE, WebViewLinks.classify("//example.com", baseUrl))
        assertEquals(WebViewLinks.Target.IGNORE, WebViewLinks.classify("relative/page", baseUrl))
        assertEquals(WebViewLinks.Target.IGNORE, WebViewLinks.classify("", baseUrl))
        assertEquals(WebViewLinks.Target.IGNORE, WebViewLinks.classify(null, baseUrl))
        assertNull(WebViewLinks.localFile("//example.com"))
    }

    @Test
    fun acceptsTheEncodedFormTheWebViewSends() {
        val encoded = "https://opencode.ai/docs?q=a%20b"
        assertEquals(WebViewLinks.Target.EXTERNAL, WebViewLinks.classify(encoded, baseUrl))
        assertTrue(WebViewLinks.localFile("file:///data/a%20b")?.name == "a b")
    }

    @Test
    fun recognisesInlineContentOfThePage() {
        assertTrue(WebViewLinks.isInlineContent("about:blank"))
        assertTrue(WebViewLinks.isInlineContent("about:srcdoc"))
        assertTrue(WebViewLinks.isInlineContent("blob:http://127.0.0.1:49374/1234"))
        assertTrue(WebViewLinks.isInlineContent("data:text/html,x"))

        assertFalse(WebViewLinks.isInlineContent("https://opencode.ai/docs"))
        assertFalse(WebViewLinks.isInlineContent("file:///data/a.txt"))
        assertFalse(WebViewLinks.isInlineContent(null))
    }
}
