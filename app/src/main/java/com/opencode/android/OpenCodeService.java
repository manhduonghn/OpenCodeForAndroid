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
import android.app.ServiceInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
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

    private java.lang.Process process;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int port = 49374;

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
        startServer();
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
        startServer();
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
            stopSelf();
            return;
        }

        stopExisting();

        ProcessBuilder builder = new ProcessBuilder(
            loader,
            binary.getAbsolutePath(),
            "serve",
            "--port",
            String.valueOf(port)
        );
        builder.directory(new File(OpenCodeBootstrap.HOME));
        builder.redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("HOME", OpenCodeBootstrap.HOME);
        builder.environment().put("PREFIX", OpenCodeBootstrap.PREFIX);
        builder.environment().put("PATH", OpenCodeBootstrap.PREFIX + "/bin");
        builder.environment().put("TMPDIR", OpenCodeBootstrap.PREFIX + "/tmp");
        builder.environment().put("TERM", "xterm-256color");
        builder.environment().put("LANG", "en_US.UTF-8");
        builder.environment().put("COLORTERM", "truecolor");
        builder.environment().put("DEBIAN_FRONTEND", "noninteractive");
        builder.environment().put("OPENCODE_DISABLE_AUTOUPDATE", "1");
        String ghToken = System.getenv("GH_TOKEN");
        if (ghToken != null && !ghToken.isEmpty()) {
            builder.environment().put("GH_TOKEN", ghToken);
        }
        builder.environment().put("OPENCODE_PORT", String.valueOf(port));

        process = builder.start();
        Log.i(TAG, "The OpenCode server process was started: " + port);

        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    Log.i(TAG, "opencode: " + line);
                    handler.post(() -> updateNotification("Running on http://127.0.0.1:" + port));
                }
            } catch (Exception e) {
                Log.w(TAG, "The OpenCode server reader stopped", e);
            }
        }, "opencode-service-reader");
        reader.setDaemon(true);
        reader.start();

        updateNotification("Running on http://127.0.0.1:" + port);
    }

    /** Kill the OpenCode server process, the next start command starts it again. */
    public void stopServer() {
        if (process == null) return;
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
        try {
            process.destroy();
            process.waitFor();
        } catch (Exception ignored) {
        }
        process = null;
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        manager.notify(NOTIFICATION_ID, notification);
    }
}
