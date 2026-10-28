package com.opencode.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes crash reports and runtime logs of the OpenCode runtime into the device storage, so that
 * they can be shared or pulled for analysis. A copy goes to the app-private external directory
 * (always writable) and another one to the public Download folder (MediaStore on Android 10+,
 * plain file on older versions).
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
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        StringBuilder sb = new StringBuilder();
        sb.append("OpenCode for Android - crash log\n");
        sb.append("time=").append(timestamp()).append("\n");
        sb.append("thread=").append(thread.getName()).append("\n");
        sb.append(sw);
        persist(context, "opencode-crash-" + System.currentTimeMillis() + ".log", sb.toString());
    }

    /**
     * Write a free form diagnostic report (eg. the output of a server process that exited) into
     * the same locations as a crash report.
     */
    public static void writeReport(Context context, String kind, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("OpenCode for Android - ").append(kind).append('\n');
        sb.append("time=").append(timestamp()).append("\n");
        sb.append("app=").append(context.getPackageName()).append('\n');
        sb.append("sdk=").append(Build.VERSION.SDK_INT).append("\n\n");
        sb.append(content);
        persist(context, "opencode-" + kind + "-" + System.currentTimeMillis() + ".log", sb.toString());
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static void persist(Context context, String name, String content) {
        byte[] bytes;
        try {
            bytes = content.getBytes("UTF-8");
        } catch (Exception e) {
            return;
        }

        // 1. App-private external directory, it never needs a permission.
        try {
            File baseDir = context.getExternalFilesDir(null);
            if (baseDir != null) {
                File dir = new File(baseDir, CRASH_DIR);
                if (dir.isDirectory() || dir.mkdirs()) {
                    FileOutputStream out = new FileOutputStream(new File(dir, name));
                    try {
                        out.write(bytes);
                        out.flush();
                    } finally {
                        out.close();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write the app-private report", e);
        }

        // 2. Public Download folder, so the user can send the file without a computer.
        writeToDownload(context, name, bytes);
    }

    private static void writeToDownload(Context context, String name, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                ContentResolver resolver = context.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return;
                OutputStream out = resolver.openOutputStream(uri);
                if (out == null) return;
                try {
                    out.write(bytes);
                    out.flush();
                } finally {
                    out.close();
                }
                return;
            }

            File downloadDir = new File(Environment.getExternalStorageDirectory(), "Download");
            if (!downloadDir.isDirectory()) return;
            FileOutputStream out = new FileOutputStream(new File(downloadDir, name));
            try {
                out.write(bytes);
                out.flush();
            } finally {
                out.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write the report into the Download folder", e);
        }
    }
}
