package com.opencode.android

import android.util.Base64
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Small HTTP helpers that the loopback client, the activity and the services share.
 *
 * Keeping the credentials and the port probe in one place matters: the server accepts a request
 * only when the user name and the password are encoded exactly the same way everywhere.
 */
object OpenCodeHttp {

    /** Value of the `Authorization` header for the local OpenCode server. */
    fun basicAuth(secret: String): String = basicAuth(OpenCodeBootstrap.SERVER_USER, secret)

    /** Value of the `Authorization` header for an arbitrary user, eg. a GitHub token. */
    fun basicAuth(user: String, secret: String): String =
        "Basic " + Base64.encodeToString(
            "$user:$secret".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )

    /** True when something accepts a TCP connection on the loopback [port]. */
    fun isPortOpen(port: Int, timeoutMs: Int = 500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }
}
