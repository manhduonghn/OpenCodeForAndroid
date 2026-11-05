package com.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorProtocolTest {

    @Test
    fun acceptsOnlyIntendedLinkSchemes() {
        assertTrue(EditorProtocol.isSafeLink("https://opencode.ai/docs"))
        assertTrue(EditorProtocol.isSafeLink("http://example.com/path?q=1"))
        assertTrue(EditorProtocol.isSafeLink("HTTPS://EXAMPLE.COM"))
        assertTrue(EditorProtocol.isSafeLink("mailto:someone@example.com"))

        assertFalse(EditorProtocol.isSafeLink("javascript:alert(1)"))
        assertFalse(EditorProtocol.isSafeLink("file:///data/local/tmp/x"))
        assertFalse(EditorProtocol.isSafeLink("content://com.example/x"))
        assertFalse(EditorProtocol.isSafeLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(EditorProtocol.isSafeLink("//example.com"))
        assertFalse(EditorProtocol.isSafeLink(""))
        assertFalse(EditorProtocol.isSafeLink("https://example.com/" + "a".repeat(EditorProtocol.MAX_URL_CHARS)))
    }

    @Test
    fun messageLimitsLeaveRoomForTheJsonEnvelope() {
        assertTrue(EditorProtocol.withinTextLimit(0))
        assertTrue(EditorProtocol.withinTextLimit(EditorProtocol.MAX_TEXT_CHARS))
        assertFalse(EditorProtocol.withinTextLimit(EditorProtocol.MAX_TEXT_CHARS + 1))
        assertFalse(EditorProtocol.withinTextLimit(-1))

        assertTrue(EditorProtocol.withinMessageLimit(EditorProtocol.MAX_MESSAGE_CHARS))
        assertFalse(EditorProtocol.withinMessageLimit(EditorProtocol.MAX_MESSAGE_CHARS + 1))
        assertTrue(EditorProtocol.MAX_MESSAGE_CHARS > EditorProtocol.MAX_TEXT_CHARS)
    }

    @Test
    fun protocolVersionIsTheSingleSourceOfTruth() {
        assertEquals(EditorProtocol.VERSION, EditorTheme.PROTOCOL)
        assertTrue(EditorProtocol.supports(EditorProtocol.VERSION))
        assertFalse(EditorProtocol.supports(EditorProtocol.VERSION + 1))
    }
}
