package com.opencode.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Minimal WebView shell for the OpenCode server. The actual OpenCode server runs as a foreground
 * service on 127.0.0.1:49374 and this activity renders it.
 *
 * The first start installs the Linux toolchain and OpenCode, which takes several minutes on a
 * phone. The activity shows the progress of [OpenCodeBootstrap] instead of an empty screen and
 * only loads the web UI once the server really accepts connections.
 *
 * Opening `/` alone is not enough: the OpenCode web app is a client that asks for a server
 * address and password. The app mints a short-lived code with `POST /api/pair`, redeems it for a
 * session token and loads `/?auth_token=<base64(user:token)>`, exactly the link format
 * `opencode pair` prints. The token is also stored as the session cookie, so reloads stay
 * authenticated.
 */
class OpenCodeWebActivity : Activity(), OpenCodeBootstrap.InstallListener {

    private lateinit var webView: WebView
    private lateinit var statusPanel: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusProgress: ProgressBar
    private lateinit var statusRetry: Button

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The reachability check opens a socket to 127.0.0.1. Doing that on the main thread throws
     * [android.os.NetworkOnMainThreadException], so the probe runs here and the result is posted
     * back to the UI thread.
     */
    private val probeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-web-probe").apply { isDaemon = true }
    }

    private var probeInFlight = false
    private var pageLoaded = false
    private var destroyed = false
    private var waitingForInstall = false
    private var lastServiceStart = 0L

    /** How often the pairing code was requested for the current server start. */
    private var pairAttempts = 0

    /** HTTP Basic password of the local OpenCode server, see [OpenCodeBootstrap.getServerPassword]. */
    private var serverPassword = ""

    /** Number of failed loads of the web UI, the automatic retry stops after a few of them. */
    private var loadFailures = 0

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || probeInFlight || probeExecutor.isShutdown) return
            probeInFlight = true
            probeExecutor.execute {
                val reachable = serverReachable()
                val sessionToken = if (reachable) requestSessionToken() else null
                handler.post {
                    probeInFlight = false
                    if (destroyed) return@post
                    if (reachable) {
                        if (sessionToken == null && pairAttempts < 6) {
                            // The server accepted the connection but not the pairing request yet,
                            // retry briefly before falling back to the bare web app.
                            pairAttempts++
                            handler.postDelayed(poll, 500)
                            return@post
                        }
                        loadUi(sessionToken)
                        return@post
                    }
                    updateProgress()
                    if (OpenCodeBootstrap.getInstallState().failed) return@post
                    handler.postDelayed(poll, 700)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        statusPanel = findViewById(R.id.status_panel)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        statusProgress = findViewById(R.id.status_progress)
        statusRetry = findViewById(R.id.status_retry)

        configureWebView()

        // Pre-populate the credentials of the realm the server announces
        // (WWW-Authenticate: Basic realm="Secure Area"), so the WebView answers the very first
        // challenge of the page and of its fetch/XHR calls without a system dialog.
        serverPassword = OpenCodeBootstrap.getServerPassword(this)
        try {
            @Suppress("DEPRECATION")
            WebViewDatabase.getInstance(this).setHttpAuthUsernamePassword(
                "127.0.0.1", "Secure Area", OpenCodeBootstrap.SERVER_USER, serverPassword,
            )
        } catch (e: Exception) {
            // The callback below is the primary mechanism, this is only a head start.
        }

        statusRetry.setOnClickListener { retryInstall() }

        // Restore the progress of an installation that was interrupted by a process death.
        OpenCodeBootstrap.restoreState()
        OpenCodeBootstrap.addInstallListener(this)

        // Make sure the OpenCode server is running before the WebView talks to it.
        startServerService()
        handler.post(poll)

        // 'oc-install' (and the 'opencode upgrade' command) opens this activity with this extra to
        // force a fresh download of the runtime.
        if (intent?.getBooleanExtra(OpenCodeBootstrap.ACTION_FORCE_UPDATE, false) == true) {
            waitingForInstall = true
            statusTitle.text = getString(R.string.opencode_updating)
            statusDetail.text = getString(R.string.opencode_update_preparing)
            statusProgress.isIndeterminate = true
            OpenCodeBootstrap.requestUpdate(this)
        }
    }

    private fun configureWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?,
            ) {
                // The OpenCode 2.x server protects its API with HTTP Basic auth.
                handler?.proceed(OpenCodeBootstrap.SERVER_USER, serverPassword)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // The page rendered, so the counter of failed loads starts again.
                if (pageLoaded) loadFailures = 0
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                // The page itself could not be loaded. The server may have died or the port is
                // not answerable any more: drop back to the progress screen and let the poll
                // restart the service and pair again.
                Log.w(TAG, "The OpenCode web UI could not be loaded: ${error?.errorCode} ${error?.description}")
                failUi(
                    getString(R.string.opencode_ui_unavailable),
                    getString(
                        R.string.opencode_ui_unavailable_detail,
                        error?.errorCode ?: 0,
                        error?.description ?: "",
                    ),
                )
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Local files and content URIs are not needed, the UI is served over loopback.
            allowFileAccess = false
            allowContentAccess = false
        }
        // The Pairing redirect stores the session cookie of the server here.
        CookieManager.getInstance().setAcceptCookie(true)
    }

    // ------------------------------------------------------------------ install state

    override fun onOpenCodeInstallStateChanged(state: OpenCodeInstallState?) {
        if (destroyed || state == null) return
        applyState(state)
    }

    private fun applyState(state: OpenCodeInstallState) {
        if (pageLoaded) return

        if (state.failed) {
            waitingForInstall = false
            statusPanel.visibility = View.VISIBLE
            statusProgress.isIndeterminate = false
            statusProgress.progress = state.overallProgress()
            statusTitle.text = getString(R.string.opencode_install_failed)
            val message = state.error.ifEmpty { state.message }
            statusDetail.text = message
            statusRetry.visibility = View.VISIBLE
            return
        }

        if (state.done) {
            statusTitle.text = getString(R.string.opencode_starting)
            statusDetail.text = getString(R.string.opencode_install_done_detail)
            statusProgress.isIndeterminate = true
            statusRetry.visibility = View.GONE
            return
        }

        if (state.running) {
            waitingForInstall = true
            statusPanel.visibility = View.VISIBLE
            statusProgress.isIndeterminate = false
            statusProgress.progress = state.overallProgress()
            statusTitle.text = getString(R.string.opencode_installing)
            statusDetail.text =
                listOf(state.message, state.detail).filter { it.isNotEmpty() }.joinToString(" ")
            statusRetry.visibility = View.GONE
        }
    }

    /** Show the last known progress, it is useful before the first listener callback arrives. */
    private fun updateProgress() {
        if (pageLoaded || waitingForInstall) return
        val state = OpenCodeBootstrap.getInstallState()
        applyState(state)
        if (state.failed) return

        if (OpenCodeBootstrap.isRuntimeComplete()) {
            // Installed, but the server is not answering. Ask the service to start it again (it
            // may have been killed while the app was in the background).
            val now = SystemClock.uptimeMillis()
            if (now - lastServiceStart > 3000) {
                lastServiceStart = now
                startServerService()
            }
            return
        }

        if (!OpenCodeBootstrap.isInstallRunning()) {
            // No installer is running and the runtime is not complete (eg. the process was killed
            // right after the install started). Start it again instead of waiting forever.
            waitingForInstall = true
            OpenCodeBootstrap.setupIfNeeded(this, null)
        }
    }

    /**
     * The UI could not be loaded: show the reason and let [poll] try again. The service is
     * restarted by that loop when it is no longer reachable, and the page is paired again.
     */
    private fun failUi(title: String, detail: String) {
        if (destroyed) return
        pageLoaded = false
        waitingForInstall = false
        pairAttempts = 0
        webView.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        statusProgress.isIndeterminate = true
        statusTitle.text = title
        statusDetail.text = detail
        statusRetry.visibility = View.GONE
        handler.removeCallbacks(poll)
        loadFailures++
        if (loadFailures > 3) {
            // Stop the automatic loop: the user can retry by hand, otherwise a broken server
            // would reload the page every two seconds forever.
            statusDetail.text = "$detail\n" + getString(R.string.opencode_retry_hint)
            statusRetry.visibility = View.VISIBLE
            return
        }
        handler.postDelayed(poll, 1500)
    }

    /** Start the foreground service that hosts the server, ignoring background restrictions. */
    private fun startServerService() {
        try {
            val intent = Intent(this, OpenCodeService::class.java)
            intent.putExtra(EXTRA_PORT, PORT)
            startForegroundService(intent)
        } catch (e: Exception) {
            // The system may refuse to start a foreground service from the background, the poll
            // retries.
        }
    }

    private fun retryInstall() {
        statusRetry.visibility = View.GONE
        statusTitle.text = getString(R.string.opencode_preparing)
        statusDetail.text = getString(R.string.opencode_preparing_detail)
        statusProgress.isIndeterminate = true
        waitingForInstall = true
        OpenCodeBootstrap.setupIfNeeded(this, null)
        pairAttempts = 0
        loadFailures = 0
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    /** The server accepts connections, replace the progress screen with the paired web UI. */
    private fun loadUi(sessionToken: String?) {
        if (pageLoaded) return
        pageLoaded = true
        waitingForInstall = false
        statusPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE

        var target = "http://127.0.0.1:$PORT/"
        // Prefer the session token of the pairing code. The real password is an acceptable
        // fallback, it is the same credential 'opencode pair' puts into its links.
        val paired = !sessionToken.isNullOrEmpty()
        val secret = if (paired) sessionToken else serverPassword
        if (!secret.isNullOrEmpty()) {
            // 'opencode pair' prints links of the form /?auth_token=<base64(user:password)>,
            // the web app reads it, strips it from the address bar and authenticates its API
            // calls with it.
            //
            // The web app decodes the parameter with a base64url decoder (it replaces '-' and
            // '_' back to '+' and '/'), so the URL-safe alphabet is the format it expects. A
            // standard '+' would additionally be turned into a space by URLSearchParams.
            val credentials = Base64.encodeToString(
                "${OpenCodeBootstrap.SERVER_USER}:$secret".toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            target += "?auth_token=$credentials"

            // The session token is also the value of the session cookie, so a reload of the
            // WebView keeps working after the app removed the token from the URL.
            try {
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setCookie(
                        "http://127.0.0.1:$PORT",
                        if (paired) {
                            "opencode_session_$PORT=$secret; Path=/; Max-Age=2592000"
                        } else {
                            // A token of an earlier server must not survive: the API calls of the
                            // UI would carry a cookie that the current server rejects with 401.
                            "opencode_session_$PORT=; Path=/; Max-Age=0"
                        },
                    )
                    flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not store the session cookie", e)
            }
        }
        Log.i(TAG, "Loading the paired OpenCode web UI")
        webView.loadUrl(target)
    }

    /**
     * Mint a single-use pairing code and redeem it for a session token. The web app cannot use
     * the Basic auth of the API directly, it needs the token in its `auth_token` link.
     */
    private fun requestSessionToken(): String? {
        val code = requestPairCode() ?: return null
        val body = try {
            request("http://127.0.0.1:$PORT$PAIR_PATH$code", "application/json")
        } catch (e: Exception) {
            Log.w(TAG, "Could not redeem the pairing code", e)
            return null
        } ?: return null

        val token = JSONObject(body).optString("token", "")
        if (token.isEmpty()) return null
        // Make sure the token is actually accepted before the UI starts with it. A token of a
        // server that has been replaced in the meantime only produces 401s and the UI then shows
        // its "server unreachable" panel.
        if (!serverAccepts(token)) {
            Log.w(TAG, "The server rejected the freshly minted pairing token, using the password.")
            return null
        }
        return token
    }

    /** A short-lived pairing code, or null when the server does not answer. */
    private fun requestPairCode(): String? {
        val body = try {
            request("http://127.0.0.1:$PORT/api/pair", accept = null, post = true)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create a pairing code", e)
            return null
        } ?: return null

        val code = JSONObject(body).optString("code", "")
        return code.ifEmpty { null }
    }

    /**
     * A small GET/POST helper for the loopback API. Returns the body of a 200 answer and null
     * otherwise, the callers only need the JSON document.
     */
    private fun request(
        url: String,
        accept: String?,
        post: Boolean = false,
        secret: String = serverPassword,
    ): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2000
            connection.readTimeout = 3000
            connection.useCaches = false
            if (post) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                val credentials = "${OpenCodeBootstrap.SERVER_USER}:$secret"
                connection.setRequestProperty(
                    "Authorization",
                    "Basic " + Base64.encodeToString(credentials.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP),
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

    private fun serverReachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 1000) }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Check that the server answers `/api/info` with the given secret (a session token or the
     * server password) as HTTP Basic credentials, exactly like the web UI does.
     */
    private fun serverAccepts(secret: String): Boolean {
        if (secret.isEmpty()) return false
        val connection = try {
            URL("http://127.0.0.1:$PORT/api/info").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return false
        }
        try {
            connection.connectTimeout = 2000
            connection.readTimeout = 3000
            connection.useCaches = false
            val credentials = "${OpenCodeBootstrap.SERVER_USER}:$secret"
            connection.setRequestProperty(
                "Authorization",
                "Basic " + Base64.encodeToString(credentials.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP),
            )
            return connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.w(TAG, "Could not verify the server credentials", e)
            return false
        } finally {
            connection.disconnect()
        }
    }

    override fun onDestroy() {
        destroyed = true
        probeExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        OpenCodeBootstrap.removeInstallListener(this)
        super.onDestroy()
        webView.destroy()
    }

    companion object {
        private const val TAG = "OpenCodeWebActivity"

        const val PORT = 49374

        /** Extra accepted by [OpenCodeService] to know the port it should serve. */
        const val EXTRA_PORT = "port"

        /** Endpoint that redeems a pairing code for a session token. */
        private const val PAIR_PATH = "/auth/connect/"
    }
}
