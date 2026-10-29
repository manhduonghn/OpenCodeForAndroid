package com.opencode.android

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Small client for the loopback HTTP API of the OpenCode server.
 *
 * The 2.x server protects `/api/...` with HTTP Basic auth (`opencode:<server password>`). Every
 * method here is blocking and must be called from a background thread.
 */
object OpenCodeApi {

    private const val TAG = "OpenCodeApi"
    private const val UPDATE_URL = "https://opencode.ai/update/api/latest/cli/npm"

    data class Session(
        val id: String,
        val title: String,
        val directory: String,
        val agent: String,
        val model: String,
        val updated: Long,
    )

    fun sessionList(port: Int, secret: String, timeoutMs: Int = 5000): List<Session>? {
        val body = get("http://127.0.0.1:$port/api/session", secret, timeoutMs) ?: return null
        return try {
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: JSONArray()
            val sessions = ArrayList<Session>(data.length())
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                sessions.add(parseSession(item))
            }
            sessions.sortedByDescending { it.updated }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse the session list", e)
            null
        }
    }

    fun session(port: Int, secret: String, id: String, timeoutMs: Int = 5000): Session? {
        if (id.isEmpty()) return null
        val body = get("http://127.0.0.1:$port/api/session/$id", secret, timeoutMs) ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            parseSession(data)
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse the session $id", e)
            null
        }
    }

    /**
     * Create a session in [directory]. The server derives the project list from the sessions, so
     * opening a folder this way makes it appear in the web UI.
     *
     * @return the HTTP status code of the request.
     */
    fun createSession(
        port: Int,
        secret: String,
        directory: String,
        cookie: String? = null,
    ): Int {
        val payload = JSONObject()
            .put("location", JSONObject().put("directory", directory))
            .toString()
        return request(
            url = "http://127.0.0.1:$port/api/session",
            secret = secret,
            method = "POST",
            body = payload,
            cookie = cookie,
            timeoutMs = 15000,
        )
    }

    fun serverVersion(port: Int, secret: String, timeoutMs: Int = 4000): String? {
        val body = get("http://127.0.0.1:$port/api/info", secret, timeoutMs) ?: return null
        return try {
            JSONObject(body).optString("version", "").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** The latest OpenCode version published by the update endpoint, or null when offline. */
    fun latestVersion(): String? {
        val body = try {
            val connection = URL(UPDATE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "OpenCodeForAndroid")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch the latest OpenCode version", e)
            return null
        }
        return try {
            JSONObject(body).optString("version", "").ifEmpty { null }
        } catch (e: Exception) {
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
        }
    }

    /** Human readable age of a session, used by the notification and the session list. */
    fun relativeAge(updated: Long): String {
        if (updated <= 0L) return ""
        val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - updated)
        return when {
            minutes < 1 -> "vừa xong"
            minutes < 60 -> "$minutes phút trước"
            minutes < 1440 -> "${TimeUnit.MINUTES.toHours(minutes)} giờ trước"
            else -> "${TimeUnit.MINUTES.toDays(minutes)} ngày trước"
        }
    }

    private fun parseSession(item: JSONObject): Session {
        val location = item.optJSONObject("location")
        val model = item.optJSONObject("model")
        val time = item.optJSONObject("time")
        val modelName = model?.optString("id", "").orEmpty()
        val provider = model?.optString("providerID", "").orEmpty()
        return Session(
            id = item.optString("id", ""),
            title = item.optString("title", "").ifEmpty { "Phiên chưa đặt tên" },
            directory = location?.optString("directory", "").orEmpty(),
            agent = item.optString("agent", ""),
            model = if (provider.isEmpty()) modelName else "$provider/$modelName",
            updated = time?.optLong("updated", 0L) ?: 0L,
        )
    }

    private fun get(url: String, secret: String, timeoutMs: Int): String? =
        requestBody(url, secret, timeoutMs)

    private fun requestBody(url: String, secret: String, timeoutMs: Int): String? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", basic(secret))
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                null
            } else {
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: $url", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        url: String,
        secret: String,
        method: String,
        body: String?,
        cookie: String?,
        timeoutMs: Int,
    ): Int {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return -1
        }
        return try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", basic(secret))
            if (!cookie.isNullOrEmpty()) connection.setRequestProperty("Cookie", cookie)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            // Drain the answer so the connection can be reused.
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            try {
                stream?.use { it.readBytes() }
            } catch (e: Exception) {
                // Not important.
            }
            status
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: $method $url", e)
            -1
        } finally {
            connection.disconnect()
        }
    }

    private fun basic(secret: String): String {
        val credentials = "${OpenCodeBootstrap.SERVER_USER}:$secret"
        return "Basic " + Base64.encodeToString(
            credentials.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
    }
}
