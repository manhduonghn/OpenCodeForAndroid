package com.opencode.android

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebViewDatabase
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * The only component that is allowed to mint credentials, write the session cookie and decide
 * which chat belongs to which server generation.
 *
 * The old Activity had four copies of "take the id from the URL or the preferences", four places
 * that changed cookies and two different ways of deciding whether a token was still valid. This
 * class removes those copies: [SessionRef] is the single identity, [attach] is the single pairing
 * path, and [applyCookie] is the single cookie writer. A generation change keeps the session id
 * and throws the credential away, so a restarted server can never leak a stale 401 into the
 * WebView.
 */
class SessionGateway(
    private val context: Context,
    initialPassword: String,
) {
    data class Credential(
        val generation: Int,
        val token: String,
        val password: String,
        val verified: Boolean,
    ) {
        /** The secret that is both the Basic password and the session cookie value. */
        val secret: String get() = if (token.isNotEmpty()) token else password
    }

    private val port: Int = OpenCodeService.DEFAULT_PORT
    private val baseUrl: String = "http://127.0.0.1:$port"
    private val cookieUrl: String = "http://127.0.0.1:$port"

    @Volatile
    var password: String = initialPassword
        private set

    @Volatile
    private var session: SessionRef = SessionRef(
        generation = RuntimeStateHolder.state.generation,
        id = currentId(),
    )

    /** The session identity shared by the WebView, the notification and the reconnect path. */
    fun current(): SessionRef = session

    /** Record a route reported by the page or opened from a notification. */
    fun remember(id: String?) {
        val normalized = id?.trim()?.takeIf { it.startsWith("ses_") }
        if (normalized == null && session.id == null) return
        session = session.copy(id = normalized)
        OpenCodeSessionTracker.attach(context)
        OpenCodeSessionTracker.setActiveSession(normalized)
        if (normalized != null) OpenCodeSettings.setLastSessionId(context, normalized)
    }

    /** Rebind the current chat to a new server, dropping the old credential. */
    fun onGenerationChanged(generation: Int): SessionRef {
        session = session.onGenerationChanged(generation)
        return session
    }

    /** Refresh the stored server password (it can change when the app is reinstalled). */
    fun refreshPassword(value: String) {
        if (value.isNotEmpty()) password = value
    }

    /**
     * Health check of the contract: a socket is not enough, the server must answer `/api/info`
     * with the credential we are about to give the page.
     */
    fun healthy(secret: String = password): Boolean {
        if (secret.isEmpty()) return false
        return OpenCodeApi.serverVersion(port, secret, HEALTH_TIMEOUT_MS) != null
    }

    /**
     * Mint a short-lived pairing code and redeem it for a session token. The token is verified
     * against `/api/info` before it is handed to the WebView; a stale token therefore never
     * reaches the page and cannot trigger the SolidJS error boundary.
     */
    fun attach(generation: Int): Credential? {
        val code = requestPairCode() ?: return null
        val body = request("$baseUrl/auth/connect/$code", accept = "application/json")
            ?: return null
        val token = runCatching { JSONObject(body).optString("token", "") }.getOrNull().orEmpty()
        if (token.isNotEmpty() && healthy(token)) {
            return Credential(generation, token, password, verified = true)
        }
        // A password of the current install is the credential `opencode pair` puts in its links.
        // Use it only after the same verification, never blindly.
        if (healthy(password)) {
            return Credential(generation, "", password, verified = true)
        }
        return null
    }

    /** Verify an arbitrary candidate token, used by the native fallback for old WebViews. */
    fun verify(secret: String): Boolean = healthy(secret)

    /** The `?auth_token=` parameter of the `opencode pair` link format. */
    fun authorizedUrl(secret: String, suffix: String = "/"): String {
        if (secret.isEmpty()) return "$baseUrl$suffix"
        val credentials = Base64.encodeToString(
            "${OpenCodeBootstrap.SERVER_USER}:$secret".toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$baseUrl$suffix?auth_token=$credentials"
    }

    /** The single cookie writer. `sessionToken` selects the long-lived value of a paired token. */
    fun applyCookie(secret: String, sessionToken: Boolean) {
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setCookie(
                    cookieUrl,
                    if (sessionToken && secret.isNotEmpty()) {
                        "opencode_session_$port=$secret; Path=/; Max-Age=2592000"
                    } else {
                        // A token of an earlier server must not survive: the API calls of the UI
                        // would carry a cookie that the current server rejects with 401.
                        "opencode_session_$port=; Path=/; Max-Age=0"
                    },
                )
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not store the session cookie", e)
        }
    }

    /** Drop everything that belongs to the previous server generation. */
    fun clearCookies() {
        try {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not drop the cookies of the previous server", e)
        }
    }

    fun clearHttpAuth() {
        try {
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear HTTP auth credentials", e)
        }
    }

    private fun currentId(): String? =
        OpenCodeSessionTracker.activeSessionId ?: OpenCodeSettings.lastSessionId(context)

    private fun requestPairCode(): String? {
        val body = try {
            request("$baseUrl/api/pair", accept = null, post = true)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create a pairing code", e)
            return null
        } ?: return null
        return runCatching { JSONObject(body).optString("code", "") }
            .getOrNull()
            ?.ifEmpty { null }
    }

    private fun request(
        url: String,
        accept: String?,
        post: Boolean = false,
        secret: String = password,
    ): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            if (post) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.setRequestProperty(
                    "Authorization",
                    OpenCodeHttp.basicAuth(secret),
                )
            }
            if (accept != null) connection.setRequestProperty("Accept", accept)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "The server answered ${connection.responseCode} for $url")
                return null
            }
            return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "OpenCodeGateway"
        private const val CONNECT_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 3000
        private const val HEALTH_TIMEOUT_MS = 1500
    }
}
