package com.opencode.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Minimal WebView shell for the OpenCode server. The actual OpenCode server runs as a
 * foreground service on 127.0.0.1:49374 and this activity renders it.
 */
public final class OpenCodeWebActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Install the process wide crash handler that writes crash reports into the storage.
        OpenCodeCrashLog.install(this);

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        webView.setWebViewClient(new WebViewClient());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowFileAccess(true);

        // Make sure the OpenCode server is running natively before the WebView talks to it.
        try {
            Intent serviceIntent = new Intent(this, OpenCodeService.class);
            serviceIntent.putExtra("port", 49374);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            // Starting the foreground service can be refused by the system (eg. while the app is
            // in the background on newer Android versions). The OpenCode server can still be
            // started later through the oc-install launcher.
        }

        waitForServerAndLoad();
    }

    /**
     * The first start installs the Linux toolchain and OpenCode (tens of megabytes), so the server
     * is not reachable right away. Wait until it accepts connections on the loopback port and only
     * then let the WebView load the UI, otherwise the WebView shows a connection error.
     */
    private void waitForServerAndLoad() {
        Thread thread = new Thread(() -> {
            for (int attempt = 0; attempt < 1200; attempt++) {
                if (isServerReachable()) break;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            runOnUiThread(() -> {
                if (webView != null) webView.loadUrl("http://127.0.0.1:49374");
            });
        }, "opencode-web-wait");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean isServerReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 49374), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }
}
