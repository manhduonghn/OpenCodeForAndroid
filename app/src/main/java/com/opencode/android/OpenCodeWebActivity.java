package com.opencode.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal WebView shell for the OpenCode server. The actual OpenCode server runs as a
 * foreground service on 127.0.0.1:49374 and this activity renders it.
 *
 * <p>The first start installs the Linux toolchain and OpenCode, which takes several minutes on a
 * phone. The activity shows the progress of {@link OpenCodeBootstrap} instead of an empty screen
 * and only loads the web UI once the server really accepts connections, so the WebView never
 * shows a network error again.</p>
 *
 * <p>Opening {@code /} alone is not enough: the OpenCode web app is a client that asks for a
 * server address and password. The app mints a short-lived code with {@code POST /api/pair},
 * redeems it for a session token and loads {@code /?auth_token=<base64(user:token)>}, exactly
 * the link format {@code opencode pair} prints. The token is also stored as the session cookie,
 * so reloads stay authenticated.</p>
 */
public final class OpenCodeWebActivity extends Activity implements OpenCodeBootstrap.InstallListener {

    private static final String TAG = "OpenCodeWebActivity";
    private static final int PORT = 49374;
    /** Endpoint that redeems a pairing code for a session token. */
    private static final String PAIR_PATH = "/auth/connect/";

    private WebView webView;
    private View statusPanel;
    private TextView statusTitle;
    private TextView statusDetail;
    private ProgressBar statusProgress;
    private Button statusRetry;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * The reachability check opens a socket to 127.0.0.1. Doing that on the main thread throws
     * {@link android.os.NetworkOnMainThreadException}, so the probe runs here and the result is
     * posted back to the UI thread.
     */
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "opencode-web-probe");
        thread.setDaemon(true);
        return thread;
    });
    private boolean probeInFlight;
    private boolean pageLoaded;
    private boolean destroyed;
    private boolean waitingForInstall;
    private long lastServiceStart;
    /** How often the pairing code was requested for the current server start. */
    private int pairAttempts;
    /** HTTP Basic password of the local OpenCode server, see {@link OpenCodeBootstrap#getServerPassword}. */
    private String serverPassword = "";

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (destroyed || probeInFlight || probeExecutor.isShutdown()) return;
            probeInFlight = true;
            probeExecutor.execute(() -> {
                final boolean reachable = serverReachable();
                final String sessionToken = reachable ? requestSessionToken() : null;
                handler.post(() -> {
                    probeInFlight = false;
                    if (destroyed) return;
                    if (reachable) {
                        if (sessionToken == null && pairAttempts < 6) {
                            // The server accepted the connection but not the pairing request yet,
                            // retry briefly before falling back to the bare web app.
                            pairAttempts++;
                            handler.postDelayed(poll, 500);
                            return;
                        }
                        loadUi(sessionToken);
                        return;
                    }
                    updateProgress();
                    if (OpenCodeBootstrap.getInstallState().failed) return;
                    handler.postDelayed(poll, 700);
                });
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Install the process wide crash handler that writes crash reports into the storage.
        OpenCodeCrashLog.install(this);

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        statusPanel = findViewById(R.id.status_panel);
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        statusProgress = findViewById(R.id.status_progress);
        statusRetry = findViewById(R.id.status_retry);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                  String host, String realm) {
                // The OpenCode 2.x server protects its API with HTTP Basic auth.
                handler.proceed(OpenCodeBootstrap.SERVER_USER, serverPassword);
            }
        });
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        // The pairing redirect stores the session cookie of the server here.
        CookieManager.getInstance().setAcceptCookie(true);

        // Pre-populate the credentials of the realm the server announces
        // (WWW-Authenticate: Basic realm="Secure Area"), so the WebView answers the very first
        // challenge of the page and of its fetch/XHR calls without a system dialog.
        serverPassword = OpenCodeBootstrap.getServerPassword(this);
        try {
            WebViewDatabase.getInstance(this).setHttpAuthUsernamePassword(
                "127.0.0.1", "Secure Area", OpenCodeBootstrap.SERVER_USER, serverPassword);
        } catch (Exception e) {
            // The callback above is the primary mechanism, this is only a head start.
        }

        statusRetry.setOnClickListener(v -> retryInstall());

        // Restore the progress of an installation that was interrupted by a process death.
        OpenCodeBootstrap.restoreState();
        OpenCodeBootstrap.addInstallListener(this);

        // Make sure the OpenCode server is running natively before the WebView talks to it.
        startServerService();

        handler.post(poll);

        // 'oc-install' (and the 'opencode upgrade' command) opens this activity with this extra to
        // force a fresh download of the runtime.
        if (getIntent() != null
            && getIntent().getBooleanExtra(OpenCodeBootstrap.ACTION_FORCE_UPDATE, false)) {
            waitingForInstall = true;
            statusTitle.setText("Đang cập nhật OpenCode…");
            statusDetail.setText("Đang chuẩn bị tải lại OpenCode.");
            statusProgress.setIndeterminate(true);
            OpenCodeBootstrap.requestUpdate(this);
        }
    }

    @Override
    public void onOpenCodeInstallStateChanged(OpenCodeInstallState state) {
        if (destroyed || state == null) return;
        applyState(state);
    }

    private void applyState(OpenCodeInstallState state) {
        if (pageLoaded) return;

        if (state.failed) {
            waitingForInstall = false;
            statusPanel.setVisibility(View.VISIBLE);
            statusProgress.setIndeterminate(false);
            statusProgress.setProgress(state.getOverallPercent());
            statusTitle.setText("Cài đặt thất bại");
            String message = state.error == null || state.error.isEmpty() ? state.message : state.error;
            statusDetail.setText(message == null ? "" : message);
            statusRetry.setVisibility(View.VISIBLE);
            return;
        }

        if (state.done) {
            statusTitle.setText("Đang khởi động OpenCode…");
            statusDetail.setText("Quá trình cài đặt đã xong, server đang khởi động.");
            statusProgress.setIndeterminate(true);
            statusRetry.setVisibility(View.GONE);
            return;
        }

        if (state.running) {
            waitingForInstall = true;
            statusPanel.setVisibility(View.VISIBLE);
            statusProgress.setIndeterminate(false);
            statusProgress.setProgress(state.getOverallPercent());
            statusTitle.setText("Đang cài đặt OpenCode…");
            String detail = state.message == null ? "" : state.message;
            if (state.detail != null && !state.detail.isEmpty()) detail = detail + " " + state.detail;
            statusDetail.setText(detail);
            statusRetry.setVisibility(View.GONE);
        }
    }

    /** Show the last known progress, it is useful before the first listener callback arrives. */
    private void updateProgress() {
        if (pageLoaded || waitingForInstall) return;
        OpenCodeInstallState state = OpenCodeBootstrap.getInstallState();
        applyState(state);
        if (state.failed) return;

        if (OpenCodeBootstrap.isRuntimeComplete()) {
            // Installed, but the server is not answering. Ask the service to start it again (it
            // may have been killed while the app was in the background).
            long now = SystemClock.uptimeMillis();
            if (now - lastServiceStart > 3000) {
                lastServiceStart = now;
                startServerService();
            }
            return;
        }

        if (!OpenCodeBootstrap.isInstallRunning()) {
            // No installer is running and the runtime is not complete (eg. the process was killed
            // right after the install started). Start it again instead of waiting forever.
            waitingForInstall = true;
            OpenCodeBootstrap.setupIfNeeded(this, null);
        }
    }

    /** Start the foreground service that hosts the server, ignoring background restrictions. */
    private void startServerService() {
        try {
            Intent intent = new Intent(this, OpenCodeService.class);
            intent.putExtra("port", PORT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            // The system may refuse to start a foreground service from the background, the poll
            // below retries.
        }
    }

    private void retryInstall() {
        statusRetry.setVisibility(View.GONE);
        statusTitle.setText("Đang chuẩn bị OpenCode…");
        statusDetail.setText("Đang kiểm tra lại các thành phần còn thiếu.");
        statusProgress.setIndeterminate(true);
        waitingForInstall = true;
        OpenCodeBootstrap.setupIfNeeded(this, null);
        pairAttempts = 0;
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    /** The server accepts connections, replace the progress screen with the paired web UI. */
    private void loadUi(String sessionToken) {
        if (pageLoaded) return;
        pageLoaded = true;
        waitingForInstall = false;
        statusPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);

        String target = "http://127.0.0.1:" + PORT + "/";
        // Prefer the session token of the pairing code. The real password is an acceptable
        // fallback, it is the same credential 'opencode pair' puts into its links.
        boolean paired = sessionToken != null && !sessionToken.isEmpty();
        String secret = paired ? sessionToken : serverPassword;
        if (secret != null && !secret.isEmpty()) {
            // 'opencode pair' prints links of the form /?auth_token=<base64(user:password)>,
            // the web app reads it, strips it from the address bar and authenticates its API
            // calls with it.
            String credentials = Base64.encodeToString(
                (OpenCodeBootstrap.SERVER_USER + ":" + secret)
                    .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            target = target + "?auth_token=" + credentials;

            // The session token is also the value of the session cookie, so a reload of the
            // WebView keeps working after the app removed the token from the URL.
            if (paired) {
                try {
                    CookieManager cookies = CookieManager.getInstance();
                    cookies.setAcceptCookie(true);
                    cookies.setCookie("http://127.0.0.1:" + PORT,
                        "opencode_session_" + PORT + "=" + secret
                            + "; Path=/; Max-Age=2592000");
                    cookies.flush();
                } catch (Exception e) {
                    Log.w(TAG, "Could not store the session cookie", e);
                }
            }
        }
        Log.i(TAG, "Loading the paired OpenCode web UI: " + target);
        webView.loadUrl(target);
    }

    /**
     * Mint a single-use pairing code and redeem it for a session token. The web app cannot use
     * the Basic auth of the API directly, it needs the token in its {@code auth_token} link.
     *
     * @return the session token, or null when the server does not answer as expected.
     */
    private String requestSessionToken() {
        String code = requestPairCode();
        if (code == null) return null;

        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://127.0.0.1:" + PORT + PAIR_PATH + code);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(3000);
            connection.setUseCaches(false);
            // Without a browser Accept header the server answers with JSON instead of setting a
            // cookie, which is what we need to build the link ourselves.
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "The server refused the pairing code: " + connection.getResponseCode());
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            String token = new JSONObject(body.toString()).optString("token", "");
            return token.isEmpty() ? null : token;
        } catch (Exception e) {
            Log.w(TAG, "Could not redeem the pairing code", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** @return a short-lived pairing code, or null when the server does not answer. */
    private String requestPairCode() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://127.0.0.1:" + PORT + "/api/pair");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(3000);
            connection.setUseCaches(false);
            String credentials = OpenCodeBootstrap.SERVER_USER + ":" + serverPassword;
            connection.setRequestProperty("Authorization", "Basic "
                + Base64.encodeToString(credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "The server refused the pairing request: " + connection.getResponseCode());
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            String code = new JSONObject(body.toString()).optString("code", "");
            return code.isEmpty() ? null : code;
        } catch (Exception e) {
            Log.w(TAG, "Could not create a pairing code", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean serverReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        probeExecutor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        OpenCodeBootstrap.removeInstallListener(this);
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
