package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Foreground service that hosts the OpenCode server natively on Android.
 *
 * <p>The official OpenCode CLI is a glibc binary, so it is executed through the glibc loader that
 * the {@link OpenCodeBootstrap} installed into the app-private toolchain prefix. The server binds
 * to {@code 127.0.0.1:<port>} and the {@link OpenCodeWebActivity} renders it inside a WebView.</p>
 */
public final class OpenCodeService extends Service {

    private static final String TAG = "OpenCodeService";

    public static final String NOTIFICATION_CHANNEL_ID = "opencode_service_channel";
    public static final int NOTIFICATION_ID = 1340;

    public static final String ACTION_START = "com.opencode.android.opencode.START";
    public static final String ACTION_STOP = "com.opencode.android.opencode.STOP";

    private static volatile OpenCodeService sInstance;

    private volatile java.lang.Process process;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int port = 49374;
    /** True once the bootstrap reports that the toolchain and the binary are installed. */
    private volatile boolean bootstrapReady = false;
    /** True while the server is being stopped on purpose, its exit is not an error then. */
    private volatile boolean stopping;
    /** Path of the binary the running server was started from. */
    private volatile String runningBinaryPath;

    /** The parameters of the last start request, they are passed through the intent. */
    public static final class Params {
        public int port = 49374;
    }

    private Params params = new Params();

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting the OpenCode server..."));
        // Make sure the Linux toolchain and OpenCode itself are installed first, then start the
        // server. The callback runs on the UI thread, the installation itself runs in the
        // background thread of the bootstrap.
        bootstrapReady = false;
        OpenCodeBootstrap.setupIfNeeded(this, () -> {
            bootstrapReady = OpenCodeBootstrap.isRuntimeComplete();
            if (!bootstrapReady) {
                Log.e(TAG, "The OpenCode runtime is still incomplete: "
                    + OpenCodeBootstrap.runtimeProblems());
                updateNotification("The installation did not finish, open the app for details.");
                return;
            }
            restartIfNewBinary();
            startServer();
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getStringExtra("action");
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        port = intent.getIntExtra("port", params.port);
        params.port = port;
        // Never start before the bootstrap finished, otherwise the missing glibc loader or the
        // missing binary would stop the service while the installation is still running. The
        // callback of setupIfNeeded starts the server as soon as the runtime is complete.
        if (bootstrapReady) {
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
        sInstance = null;
    }

    @Override
    public void onTaskRemoved(Intent intent) {
        super.onTaskRemoved(intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopSelf();
        }
    }

    /* ==========================================================================================
     * Server lifecycle
     * ========================================================================================== */

    /** @return true while the OpenCode server process is running. */
    public static boolean isRunning() {
        return sInstance != null;
    }

    /** Start the OpenCode server, it is a no-op when a server is already running. */
    public void startServer() {
        if (process != null) {
            Log.i(TAG, "The OpenCode server is already running.");
            return;
        }

        String loader = OpenCodeBootstrap.findGlibcLoader();
        File binary = resolveBinary();
        if (loader == null || binary == null) {
            Log.w(TAG, "The OpenCode server cannot be started: glibc runtime or binary is missing.");
            OpenCodeCrashLog.writeReport(this, "opencode-start",
                "loader=" + loader + "\nbinary=" + binary
                    + "\nproblems=" + OpenCodeBootstrap.runtimeProblems());
            stopSelf();
            return;
        }

        stopExisting();
        stopping = false;

        ProcessBuilder builder = new ProcessBuilder(
            loader,
            "--library-path",
            OpenCodeBootstrap.GLIBC_LIB_DIR,
            binary.getAbsolutePath(),
            "serve",
            "--hostname",
            "127.0.0.1",
            "--port",
            String.valueOf(port)
        );
        builder.directory(new File(OpenCodeBootstrap.HOME));
        builder.redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("HOME", OpenCodeBootstrap.HOME);
        builder.environment().put("PREFIX", OpenCodeBootstrap.PREFIX);
        builder.environment().put("PATH", OpenCodeBootstrap.PREFIX + "/bin:/system/bin");
        builder.environment().put("TMPDIR", OpenCodeBootstrap.PREFIX + "/tmp");
        builder.environment().put("TERM", "xterm-256color");
        builder.environment().put("LANG", "en_US.UTF-8");
        builder.environment().put("COLORTERM", "truecolor");
        builder.environment().put("DEBIAN_FRONTEND", "noninteractive");
        builder.environment().put("OPENCODE_DISABLE_AUTOUPDATE", "1");
        // The 2.x server protects its API (/api, /api/session, …) with HTTP Basic auth. A fixed
        // password from the app preferences is used instead of the random one the server would
        // print, so the WebView can answer the authentication challenge itself.
        builder.environment().put("OPENCODE_SERVER_PASSWORD", OpenCodeBootstrap.getServerPassword(this));
        // The CA bundle of the ca-certificates package, Bun/Node would otherwise have no trust
        // store on Android and every HTTPS request of OpenCode would fail.
        builder.environment().put("SSL_CERT_FILE",
            OpenCodeBootstrap.PREFIX + "/etc/tls/cert.pem");
        builder.environment().put("NODE_EXTRA_CA_CERTS",
            OpenCodeBootstrap.PREFIX + "/etc/tls/cert.pem");
        // The tools OpenCode spawns (bash, rg, …) are Termux binaries whose RUNPATH points at the
        // com.termux prefix. The glibc directory is deliberately not part of this variable: it
        // contains a 'libc.so' linker script that the bionic linker cannot load as an ELF file.
        builder.environment().put("LD_LIBRARY_PATH", OpenCodeBootstrap.PREFIX + "/lib");
        String ghToken = System.getenv("GH_TOKEN");
        if (ghToken != null && !ghToken.isEmpty()) {
            builder.environment().put("GH_TOKEN", ghToken);
        }
        builder.environment().put("OPENCODE_PORT", String.valueOf(port));

        try {
            process = builder.start();
        } catch (IOException e) {
            Log.e(TAG, "Could not start the OpenCode server process", e);
            stopSelf();
            return;
        }
        final Process started = process;
        runningBinaryPath = binary.getAbsolutePath();
        Log.i(TAG, "The OpenCode server process was started: " + port);

        Thread reader = new Thread(() -> {
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    Log.i(TAG, "opencode: " + line);
                    synchronized (tail) {
                        tail.addLast(line);
                        while (tail.size() > 300) tail.removeFirst();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "The OpenCode server reader stopped", e);
            }

            int exitCode = -1;
            try {
                exitCode = started.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            boolean current = process == started;
            if (current) process = null;
            Log.w(TAG, "The OpenCode server exited with " + exitCode);
            boolean intentional = stopping || !current;
            // A server that dies right away usually means a missing library or a broken patch, the
            // output is unreadable for the user in logcat, so keep it in the Download folder.
            if (exitCode != 0 && !intentional) {
                StringBuilder sb = new StringBuilder();
                sb.append("OpenCode server exited with ").append(exitCode).append('\n');
                sb.append("port: ").append(port).append('\n');
                sb.append("loader: ").append(loader).append('\n');
                sb.append("binary: ").append(binary.getAbsolutePath()).append('\n');
                sb.append("command: ").append(loader).append(" --library-path ")
                    .append(OpenCodeBootstrap.GLIBC_LIB_DIR).append(' ')
                    .append(binary.getAbsolutePath()).append(" serve --port ").append(port).append('\n');
                sb.append("---- output ----\n");
                synchronized (tail) {
                    for (String line : tail) sb.append(line).append('\n');
                }
                OpenCodeCrashLog.writeReport(OpenCodeService.this, "opencode-server", sb.toString());
                updateNotification("The OpenCode server stopped (exit " + exitCode
                    + "), the log is in Download/opencode-server-*.log");
            }
        }, "opencode-service-reader");
        reader.setDaemon(true);
        reader.start();

        updateNotification("Running on http://127.0.0.1:" + port);
    }

    /** Kill the OpenCode server process, the next start command starts it again. */
    public void stopServer() {
        if (process == null) return;
        stopping = true;
        try {
            process.destroy();
            process.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "Could not stop the OpenCode server", e);
        }
        process = null;
        stopSelf();
    }

    private void stopExisting() {
        if (process == null) return;
        stopping = true;
        try {
            process.destroy();
            process.waitFor();
        } catch (Exception ignored) {
        }
        process = null;
        stopping = false;
    }

    /**
     * Stop the running server when the installed binary is not the one it was started from, so a
     * freshly downloaded update is picked up without killing the app.
     */
    private void restartIfNewBinary() {
        if (process == null) return;
        File binary = resolveBinary();
        if (binary == null) return;
        if (binary.getAbsolutePath().equals(runningBinaryPath)) return;
        Log.i(TAG, "The OpenCode binary changed, restarting the server.");
        stopExisting();
    }

    private File resolveBinary() {
        File link = new File(OpenCodeBootstrap.LINK_PATH);
        if (!link.exists()) {
            // The installed binary lives in the install directory.
            File dir = new File(OpenCodeBootstrap.INSTALL_DIR);
            File[] binaries = dir.listFiles();
            if (binaries == null) return null;
            for (File binary : binaries) {
                if (binary.isFile() && binary.getName().startsWith("opencode-v")) {
                    return binary;
                }
            }
            return null;
        }
        return OpenCodeBootstrap.resolveLink(link);
    }

    /* ==========================================================================================
     * Notification
     * ========================================================================================== */

    /** @return true when the FOREGROUND_SERVICE_SPECIAL_USE permission was granted. */
    private boolean hasSpecialUsePermission() {
        return checkSelfPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE") == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "OpenCode server",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("The OpenCode server that runs natively on Android.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "OpenCode server",
            NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);

        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            new Intent(this, OpenCodeWebActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("OpenCode server")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasSpecialUsePermission()) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return notification;
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("OpenCode server")
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(
                this,
                0,
                new Intent(this, OpenCodeWebActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            ))
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasSpecialUsePermission()) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        manager.notify(NOTIFICATION_ID, notification);
    }
}
