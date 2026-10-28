package com.opencode.android;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes uncaught crash reports of the OpenCode runtime into the external storage of the device,
 * so that they end up in the download folder and can be shared or pulled for analysis.
 */
public final class OpenCodeCrashLog {

    public static final String TAG = "OpenCodeCrashLog";
    private static final String CRASH_DIR = "OpenCodeForAndroid";

    private static volatile Context appContext;
    private static volatile Thread.UncaughtExceptionHandler previousHandler;
    private static volatile boolean handlerInstalled;

    private OpenCodeCrashLog() {
    }

    /** Install the process wide crash handler that writes the crash report into the storage. */
    public static void install(Context context) {
        appContext = context.getApplicationContext();
        if (handlerInstalled) return;
        handlerInstalled = true;
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(appContext, thread, throwable);
            } catch (Exception ignored) {
                // Never mask the original crash.
            }
            Thread.UncaughtExceptionHandler handler = previousHandler;
            if (handler != null) handler.uncaughtException(thread, throwable);
        });
    }

    /** Write the crash report of the crashing thread into the external storage. */
    public static void write(Context context, Thread thread, Throwable throwable) {
        try {
            File baseDir = context.getExternalFilesDir(null);
            if (baseDir == null) return;
            File dir = new File(baseDir, CRASH_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;

            String name = "opencode-crash-" + System.currentTimeMillis() + ".log";
            File file = new File(dir, name);
            String content = buildReport(thread, throwable);
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(content.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }

            // Mirror the report into the public download folder of the shared storage.
            File downloadDir = new File(Environment.getExternalStorageDirectory(), "Download");
            if (downloadDir != null && downloadDir.isDirectory()) {
                File mirror = new File(downloadDir, name);
                FileOutputStream mirrorOut = new FileOutputStream(mirror);
                try {
                    mirrorOut.write(content.getBytes("UTF-8"));
                    mirrorOut.flush();
                } finally {
                    mirrorOut.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write the crash log", e);
        }
    }

    private static String buildReport(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        StringBuilder sb = new StringBuilder();
        sb.append("OpenCode for Android - crash log\n");
        sb.append("time=").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(new Date())).append("\n");
        sb.append("thread=").append(thread.getName()).append("\n");
        sb.append(sw.toString());
        return sb.toString();
    }
}
