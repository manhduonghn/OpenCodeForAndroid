package com.opencode.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.net.Socket;
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
 */
public final class OpenCodeWebActivity extends Activity implements OpenCodeBootstrap.InstallListener {

    private static final int PORT = 49374;
    private static final int REQUEST_STORAGE = 4201;

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
    /** HTTP Basic password of the local OpenCode server, see {@link OpenCodeBootstrap#getServerPassword}. */
    private String serverPassword = "";

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (destroyed || probeInFlight || probeExecutor.isShutdown()) return;
            probeInFlight = true;
            probeExecutor.execute(() -> {
                final boolean reachable = serverReachable();
                handler.post(() -> {
                    probeInFlight = false;
                    if (destroyed) return;
                    if (reachable) {
                        loadUi();
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
        requestStoragePermissionIfNeeded();

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
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    /** The server accepts connections, replace the progress screen with the web UI. */
    private void loadUi() {
        if (pageLoaded) return;
        pageLoaded = true;
        waitingForInstall = false;
        statusPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    private boolean serverReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestStoragePermissionIfNeeded() {
        // Only the legacy Download folder mirror of the crash reports needs this permission, the
        // app itself writes everything into its private directory.
        if (Build.VERSION.SDK_INT > 28) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) return;
        try {
            requestPermissions(new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQUEST_STORAGE);
        } catch (Exception ignored) {
            // The app works without the permission, only the Download copy is skipped.
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
