package com.opencode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that installs Termux packages into the app-private prefix.
 *
 * <p>There is no {@code apt}, {@code dpkg} or {@code pkg} in this prefix: the app installs the
 * Debian archives itself with {@link OpenCodePackages}. The {@code pkg} shell script of the
 * terminal has no way to call Java, so it starts this service with {@code am
 * start-foreground-service} and follows the result through a small status file. The service is
 * not exported, the request therefore comes from the same UID as the app.</p>
 *
 * <p>Every request carries a session name. The service writes
 * {@code $HOME/.opencode/<session>.status} (key=value lines) and, for a search,
 * {@code $HOME/.opencode/<session>.out}. Using a session per request keeps two terminals that run
 * {@code pkg} at the same time from overwriting each other's progress.</p>
 */
public final class OpenCodePkgService extends Service {

    private static final String TAG = "OpenCodePkgService";

    /** Extra accepted from the {@code pkg} script: install, search. */
    public static final String EXTRA_ACTION = "action";
    public static final String EXTRA_PACKAGES = "packages";
    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_SESSION = "session";

    public static final String ACTION_INSTALL = "com.opencode.android.pkg.INSTALL";
    public static final String ACTION_SEARCH = "com.opencode.android.pkg.SEARCH";

    public static final String CHANNEL_ID = "opencode_pkg_channel";
    public static final int NOTIFICATION_ID = 1342;

    /** Directory of the status files, inside the home of the runtime. */
    public static final String STATUS_DIR = OpenCodeBootstrap.OC_HOME;

    private static final ExecutorService WORKER =
        Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "opencode-pkg");
            thread.setDaemon(true);
            return thread;
        });

    /** Only one download/extraction runs at a time, they all write into the same prefix. */
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    /**
     * Packages that belong to the runtime itself. Reinstalling them with pkg would replace the
     * patched binaries (the interpreter of bash, the relocated glibc loader) and break the
     * terminal, so they are updated through the app instead.
     */
    private static final List<String> RUNTIME_PACKAGES = java.util.Arrays.asList(
        "bash", "glibc", "glibc-runner", "patchelf", "ca-certificates",
        "termux-tools", "termux-am", "termux-am-socket", "termux-core", "termux-exec"
    );

    /** @return true when a package operation is currently running in this process. */
    public static boolean isBusy() {
        return BUSY.get();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // A service started with 'am start-foreground-service' must call startForeground quickly.
        startForegroundCompat(buildNotification("Preparing the package installation..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getStringExtra(EXTRA_ACTION);
        if (action == null) action = intent.getAction();
        if (action == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Job job = new Job();
        job.action = action;
        job.packages = splitPackages(intent.getStringExtra(EXTRA_PACKAGES));
        job.query = intent.getStringExtra(EXTRA_QUERY);
        job.session = sanitizeSession(intent.getStringExtra(EXTRA_SESSION));
        job.statusPath = STATUS_DIR + "/" + job.session + ".status";
        job.outPath = STATUS_DIR + "/" + job.session + ".out";
        job.message = "Preparing...";

        if (!BUSY.compareAndSet(false, true)) {
            writeStatus(job, "error", "Another package operation is already running, wait for it to finish.", -1);
            return START_NOT_STICKY;
        }

        WORKER.execute(() -> {
            try {
                run(job);
            } finally {
                BUSY.set(false);
            }
        });
        return START_NOT_STICKY;
    }

    /* ==========================================================================================
     * Job
     * ========================================================================================== */

    private static final class Job {
        String action;
        List<String> packages = new ArrayList<>();
        String query;
        String session;
        String statusPath;
        String outPath;
        String message = "";
        int percent;
    }

    private void run(Job job) {
        try {
            if (isSearch(job.action)) {
                search(job);
            } else {
                install(job);
            }
        } catch (Throwable t) {
            Log.e(TAG, "The package operation failed", t);
            writeStatus(job, "error", describe(t), -1);
        } finally {
            stopForegroundCompat();
            stopSelf();
        }
    }

    /** The terminal sends {@code search}, an explicit intent may send the full action. */
    private static boolean isSearch(String action) {
        return "search".equals(action) || ACTION_SEARCH.equals(action);
    }

    private void install(Job job) {
        if (job.packages.isEmpty()) {
            writeStatus(job, "error", "No package name was given.", -1);
            return;
        }
        for (String name : job.packages) {
            if (RUNTIME_PACKAGES.contains(name)) {
                writeStatus(job, "error",
                    "'" + name + "' is part of the OpenCode runtime, run 'oc-install' to update it.", -1);
                return;
            }
        }
        if (OpenCodeBootstrap.isInstallRunning()) {
            writeStatus(job, "error",
                "The app is still installing its runtime. Wait for it to finish, then try again.", -1);
            return;
        }
        if (!OpenCodeBootstrap.isPrefixReady()) {
            writeStatus(job, "error",
                "The Linux runtime is not installed yet. Open the app once and let it finish.", -1);
            return;
        }

        writeStatus(job, "running", "Preparing to install " + join(job.packages) + "...", 0);
        updateNotification("Installing " + join(job.packages) + "...");

        try {
            OpenCodePackages.install(new File(OpenCodeBootstrap.PREFIX), job.packages,
                new OpenCodePackages.Progress() {
                    @Override
                    public void onStatus(String message) {
                        job.message = message;
                        writeStatus(job, "running", message, job.percent);
                        updateNotification(message);
                    }

                    @Override
                    public void onPercent(int percent, String detail) {
                        job.percent = percent;
                        writeStatus(job, "running", job.message, percent);
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "The package installation failed", e);
            writeStatus(job, "error", describe(e), -1);
            updateNotification("The installation failed");
            return;
        }

        // The profile and the shell startup file of the runtime are written by the app; make sure
        // a command installed right now is found and the shared libraries of the prefix are used.
        ensureShellIntegration();

        writeStatus(job, "done", "Installed " + join(job.packages) + ".", 100);
        updateNotification("Installed " + join(job.packages) + ".");
    }

    private void search(Job job) {
        String query = job.query == null ? "" : job.query;
        writeStatus(job, "running", "Searching the Termux repository...", 0);
        updateNotification("Searching the Termux repository...");

        List<String> results;
        try {
            results = OpenCodePackages.search(query);
        } catch (Exception e) {
            Log.e(TAG, "The package search failed", e);
            writeStatus(job, "error", describe(e), -1);
            updateNotification("The search failed");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String line : results) sb.append(line).append('\n');
        writeText(new File(job.outPath), sb.toString());

        if (results.isEmpty()) {
            writeStatus(job, "done", "No package matched \"" + query + "\".", 100);
        } else {
            writeStatus(job, "done", results.size() + " package(s) found.", 100);
        }
        updateNotification("Package search finished.");
    }

    /* ==========================================================================================
     * Status file
     * ========================================================================================== */

    private static void writeStatus(Job job, String state, String message, int percent) {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(state).append('\n');
        sb.append("message=").append(oneLine(message)).append('\n');
        if (percent >= 0) sb.append("percent=").append(percent).append('\n');
        sb.append("packages=").append(join(job.packages)).append('\n');
        sb.append("session=").append(job.session).append('\n');
        writeText(new File(job.statusPath), sb.toString());
    }

    /** Write a small text file atomically, the shell polls it while the service fills it. */
    private static void writeText(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "Could not create " + parent);
            return;
        }
        File temp = new File(file.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "Could not write " + file, e);
            return;
        }
        if (!temp.renameTo(file)) {
            if (!file.delete() || !temp.renameTo(file)) {
                // A failed rename must not leave a half written status behind.
                temp.delete();
            }
        }
    }

    /* ==========================================================================================
     * Shell integration
     * ========================================================================================== */

    /**
     * Make the packages that were just installed usable: the Termux binaries are linked against
     * libraries below {@code $PREFIX/lib}, and the git build of the repository has the old Termux
     * prefix compiled in for its helpers. The app writes both into {@code etc/opencode-env.sh},
     * which the login profile and {@code etc/bash.bashrc} source, so the next shell picks them up.
     */
    private static void ensureShellIntegration() {
        try {
            OpenCodeBootstrap.ensurePackageEnvironment();
        } catch (Exception e) {
            Log.w(TAG, "Could not refresh the shell environment", e);
        }
    }

    /* ==========================================================================================
     * Notification
     * ========================================================================================== */

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "OpenCode packages",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Installing Termux packages into the OpenCode runtime.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            new Intent(this, OpenCodeWebActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("OpenCode packages")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            && hasSpecialUsePermission()) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    private boolean hasSpecialUsePermission() {
        return checkSelfPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
            == PackageManager.PERMISSION_GRANTED;
    }

    /* ==========================================================================================
     * Helpers
     * ========================================================================================== */

    private static List<String> splitPackages(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split("[,\\s]+")) {
            String name = part.trim();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    /** Keep the session name a plain file name, it comes from the terminal. */
    private static String sanitizeSession(String value) {
        if (value == null) return "pkg";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length() && sb.length() < 64; i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "pkg" : sb.toString();
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(value);
        }
        return sb.toString();
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty())
            message = throwable.getClass().getSimpleName();
        // The error is printed by the shell, a stack trace of a wrapped exception does not help.
        int newline = message.indexOf('\n');
        if (newline >= 0) message = message.substring(0, newline);
        return message.trim();
    }
}
