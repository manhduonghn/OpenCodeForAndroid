package com.opencode.android;

import android.os.Build;
import android.system.Os;
import android.util.Log;

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Installs Termux packages into the app-private prefix without apt, the same way AndroidHarness
 * does it: the package lists are downloaded from the Termux repositories, the .deb archives are
 * downloaded and verified, and the data archives are extracted directly into the prefix. The
 * absolute paths of the Termux prefix are rewritten while extracting, so the package does not
 * need the Termux app and does not need the com.termux package name.
 */
public final class OpenCodePackages {

    private static final String TAG = "OpenCodePackages";

    /** Prefix that is baked into the Termux packages. */
    private static final String TERMUX_USR = "data/data/com.termux/files/usr";
    private static final String TERMUX_USR_ABS = "/" + TERMUX_USR + "/";
    private static final String TERMUX_SHEBANG = "#!" + TERMUX_USR_ABS;

    /** Android bridge wrappers of termux-tools, they only shadow the real /system binaries. */
    private static final Set<String> SYSTEM_WRAPPERS = new HashSet<>(Arrays.asList(
        "am", "bmgr", "bu", "cmd", "content", "device_config", "dpm", "dumpsys",
        "getprop", "ime", "input", "log", "logcat", "media", "mount", "notify",
        "pm", "settings", "setprop", "sm", "svc", "uiautomator", "umount",
        "wm", "start", "stop"
    ));

    /**
     * Dependencies that the app does not need. 'termux-tools' is a collection of wrappers around
     * the Android commands of the Termux app (am, pm, …) and pulls the Termux app itself
     * (termux-am, termux-am-socket, termux-core) plus a dozen command line packages. The bash
     * binary works without it.
     */
    private static final Set<String> SKIPPED_DEPENDENCIES = new HashSet<>(Arrays.asList(
        "termux-tools", "termux-am", "termux-am-socket", "termux-core", "termux-exec"
    ));

    private static final String[] MAIN_MIRRORS = {
        "https://packages-cf.termux.dev/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main",
        "https://grimler.se/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
    };

    private static final String[] GLIBC_MIRRORS = {
        "https://packages-cf.termux.dev/apt/termux-glibc",
        "https://packages.termux.dev/apt/termux-glibc"
    };

    private static final String INDEX_PATH = "/dists/stable/main/binary-";
    private static final String GLIBC_INDEX_PATH = "/dists/glibc/stable/binary-";

    private OpenCodePackages() {
    }

    /** Progress callbacks, they are invoked on the thread that runs the installation. */
    public interface Progress {
        void onStatus(String message);

        void onPercent(int percent, String detail);
    }

    /** A single package of the repository index. */
    private static final class Pkg {
        String name = "";
        String version = "";
        String filename = "";
        String sha256 = "";
        String repo = "";
        final List<String> depends = new ArrayList<>();
    }

    /* ==========================================================================================
     * Public API
     * ========================================================================================== */

    /** @return the Debian architecture of the device, or null when OpenCode has no build for it. */
    public static String arch() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        if (abi.startsWith("arm64")) return "aarch64";
        if (abi.startsWith("x86_64")) return "x86_64";
        return null;
    }

    /**
     * Install the given packages and all of their dependencies into the prefix. Already installed
     * packages are skipped, the installation is idempotent.
     */
    public static void install(File prefix, List<String> wanted, Progress progress) throws Exception {
        String arch = arch();
        if (arch == null)
            throw new IOException("The CPU architecture \"" + firstAbi() + "\" is not supported.");

        File marker = new File(prefix, ".packages-installed");
        List<String> installed = readInstalled(marker);
        if (installed.containsAll(wanted) && new File(prefix, "bin/bash").isFile()) {
            progress.onStatus("The Linux toolchain is already installed.");
            progress.onPercent(100, "");
            ensureShellLink(prefix);
            return;
        }

        // A prefix without a shell is broken, even when the marker says the packages were
        // installed (an earlier run may have been interrupted while extracting). Left over files
        // of the apt based bootstrap are not relocatable either, start from a clean directory.
        if (!new File(prefix, "bin/bash").isFile() && prefix.exists()) {
            progress.onStatus("Removing the files of the previous installation...");
            deleteTree(prefix);
            installed.clear();
        }
        if (!prefix.isDirectory() && !prefix.mkdirs())
            throw new IOException("Could not create " + prefix);

        progress.onStatus("Fetching the package lists...");
        Map<String, Pkg> index = new LinkedHashMap<>();

        String mainMirror = null;
        for (String mirror : MAIN_MIRRORS) {
            try {
                String text = fetch(mirror + INDEX_PATH + arch + "/Packages");
                parseIndex(text, mirror, index);
                mainMirror = mirror;
                break;
            } catch (Exception e) {
                Log.w(TAG, "Could not fetch the package list from " + mirror, e);
            }
        }
        if (mainMirror == null)
            throw new IOException("Could not download the package list of the Termux repository.");

        for (String mirror : GLIBC_MIRRORS) {
            try {
                String text = fetch(mirror + GLIBC_INDEX_PATH + arch + "/Packages");
                Map<String, Pkg> glibc = new LinkedHashMap<>();
                parseIndex(text, mirror, glibc);
                for (Map.Entry<String, Pkg> entry : glibc.entrySet())
                    index.putIfAbsent(entry.getKey(), entry.getValue());
                break;
            } catch (Exception e) {
                Log.w(TAG, "Could not fetch the glibc package list from " + mirror, e);
            }
        }

        List<Pkg> closure = closure(index, wanted, installed);
        if (closure.isEmpty() && !new File(prefix, "bin/bash").isFile())
            throw new IOException("The package list does not contain the required packages.");

        File cache = new File(prefix.getParentFile(), "deb-cache");
        if (!cache.isDirectory() && !cache.mkdirs())
            throw new IOException("Could not create " + cache);

        int index0 = 0;
        for (Pkg pkg : closure) {
            index0++;
            int percent = closure.isEmpty() ? 100 : index0 * 100 / closure.size();
            progress.onStatus("Downloading " + pkg.name + " " + pkg.version + "...");
            progress.onPercent(percent, pkg.name);

            File deb = new File(cache, packageFileName(pkg));
            if (!deb.isFile() || deb.length() == 0) {
                downloadVerified(pkg.repo + "/" + pkg.filename, deb, pkg.sha256);
            }
            progress.onStatus("Extracting " + pkg.name + " " + pkg.version + "...");
            extractDeb(deb, prefix);
            deb.delete();
        }

        appendInstalled(marker, closure, installed);
        ensureShellLink(prefix);
        progress.onPercent(100, "");
    }

    /* ==========================================================================================
     * Package index
     * ========================================================================================== */

    private static void parseIndex(String text, String repo, Map<String, Pkg> out) {
        Pkg current = null;
        String lastKey = "";
        for (String rawLine : text.split("\n")) {
            String line = rawLine;
            if (line.trim().isEmpty()) {
                current = null;
                lastKey = "";
                continue;
            }
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (current != null && "Depends".equals(lastKey))
                    current.depends.addAll(parseDepends(line.trim()));
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("Package".equals(key)) {
                current = new Pkg();
                current.name = value;
                current.repo = repo;
                out.put(value, current);
                lastKey = key;
                continue;
            }
            if (current == null) continue;
            lastKey = key;
            switch (key) {
                case "Version":
                    current.version = value;
                    break;
                case "Filename":
                    current.filename = value;
                    break;
                case "SHA256":
                    current.sha256 = value;
                    break;
                case "Depends":
                    current.depends.addAll(parseDepends(value));
                    break;
                default:
                    break;
            }
        }
    }

    private static List<String> parseDepends(String value) {
        List<String> names = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return names;
        for (String part : value.split(",")) {
            String name = part.trim();
            int alternative = name.indexOf('|');
            if (alternative >= 0) name = name.substring(0, alternative).trim();
            int version = name.indexOf('(');
            if (version >= 0) name = name.substring(0, version).trim();
            int colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(0, colon).trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static List<Pkg> closure(Map<String, Pkg> index, List<String> wanted, List<String> installed) {
        LinkedHashMap<String, Pkg> out = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>(wanted);
        Set<String> seen = new HashSet<>(installed);
        Set<String> missing = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (SKIPPED_DEPENDENCIES.contains(name)) continue;
            if (!seen.add(name)) continue;
            Pkg pkg = index.get(name);
            if (pkg == null) {
                missing.add(name);
                continue;
            }
            out.put(name, pkg);
            queue.addAll(pkg.depends);
        }

        // A missing dependency is not fatal (some are optional), but a requested package has to
        // exist, otherwise the runtime is incomplete without a useful error message.
        for (String name : wanted) {
            if (missing.contains(name))
                throw new IllegalStateException("The repository does not provide the package '"
                    + name + "' for this architecture.");
        }
        if (!missing.isEmpty())
            Log.w(TAG, "The package list has no entry for: " + missing);
        return new ArrayList<>(out.values());
    }

    /* ==========================================================================================
     * Download
     * ========================================================================================== */

    private static String firstAbi() {
        return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
    }

    private static String packageFileName(Pkg pkg) {
        String name = pkg.filename;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name;
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "OpenCodeForAndroid");
        try {
            int code = connection.getResponseCode();
            if (code != 200)
                throw new IOException("HTTP " + code + " for " + url);
            InputStream in = connection.getInputStream();
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                copy(in, out);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadVerified(String url, File dest, String sha256) throws IOException {
        File temp = new File(dest.getAbsolutePath() + ".part");
        MessageDigest digest = newSha256();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("User-Agent", "OpenCodeForAndroid");
        try {
            int code = connection.getResponseCode();
            if (code != 200)
                throw new IOException("HTTP " + code + " for " + url);
            InputStream in = connection.getInputStream();
            FileOutputStream out = new FileOutputStream(temp);
            try {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
                out.flush();
            } finally {
                out.close();
                in.close();
            }
        } finally {
            connection.disconnect();
        }

        String actual = hex(digest.digest());
        if (sha256 != null && !sha256.isEmpty() && !actual.equalsIgnoreCase(sha256)) {
            temp.delete();
            throw new IOException("Checksum mismatch for " + dest.getName());
        }
        if (!temp.renameTo(dest)) {
            if (!dest.delete() || !temp.renameTo(dest)) {
                temp.delete();
                throw new IOException("Could not write " + dest);
            }
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        return sb.toString();
    }

    /* ==========================================================================================
     * Extraction
     * ========================================================================================== */

    private static void extractDeb(File deb, File prefix) throws Exception {
        try (ArArchiveInputStream ar = new ArArchiveInputStream(
            new BufferedInputStream(new FileInputStream(deb)))) {
            while (true) {
                org.apache.commons.compress.archivers.ar.ArArchiveEntry entry = ar.getNextEntry();
                if (entry == null) throw new IOException("No data archive in " + deb.getName());
                if (!entry.getName().startsWith("data.tar")) continue;
                InputStream compressed = decompressor(entry.getName(), ar);
                try (TarArchiveInputStream tar = new TarArchiveInputStream(compressed)) {
                    while (true) {
                        TarArchiveEntry tarEntry = tar.getNextEntry();
                        if (tarEntry == null) break;
                        extractEntry(tar, tarEntry, prefix);
                    }
                }
                return;
            }
        }
    }

    private static InputStream decompressor(String name, InputStream in) throws Exception {
        CompressorStreamFactory factory = new CompressorStreamFactory();
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".xz") || lower.endsWith(".lzma"))
            return factory.createCompressorInputStream(CompressorStreamFactory.XZ, in);
        if (lower.endsWith(".gz"))
            return factory.createCompressorInputStream(CompressorStreamFactory.GZIP, in);
        if (lower.endsWith(".bz2"))
            return factory.createCompressorInputStream(CompressorStreamFactory.BZIP2, in);
        if (lower.endsWith(".zst"))
            return factory.createCompressorInputStream(CompressorStreamFactory.ZSTANDARD, in);
        return in;
    }

    private static void extractEntry(TarArchiveInputStream tar, TarArchiveEntry entry, File prefix)
        throws Exception {
        String rel = entry.getName();
        if (rel.startsWith("./")) rel = rel.substring(2);
        if (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.startsWith(TERMUX_USR)) {
            rel = rel.substring(TERMUX_USR.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
        }
        if (rel.isEmpty() || rel.startsWith("data/data")) return;
        if (rel.contains("..")) throw new IOException("Refusing traversal path: " + entry.getName());

        File target = new File(prefix, rel);
        if (entry.isDirectory()) {
            target.mkdirs();
            return;
        }

        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();

        if (entry.isSymbolicLink()) {
            String rewritten = rewriteLink(entry.getLinkName(), rel);
            target.delete();
            try {
                Os.symlink(rewritten, target.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "Could not create the symlink " + target, e);
            }
            return;
        }

        if (entry.isLink()) {
            String link = entry.getLinkName();
            if (link.startsWith("./")) link = link.substring(2);
            if (link.startsWith(TERMUX_USR)) link = link.substring(TERMUX_USR.length() + 1);
            copy(new File(prefix, link), target);
            chmod(target, entry.getMode());
            return;
        }

        byte[] head = new byte[256];
        int headLength = readUpTo(tar, head, head.length);
        String firstLine = firstLine(head, headLength);
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        boolean binEntry = rel.startsWith("bin/") && !rel.contains("/applets/");

        if (binEntry && firstLine.startsWith(TERMUX_SHEBANG + "bin/sh") && SYSTEM_WRAPPERS.contains(name)) {
            // Termux bridge wrapper, drop it and keep the real Android command.
            drain(tar);
            return;
        }

        String rewritten = rewriteShebang(firstLine, prefix.getAbsolutePath());
        try (FileOutputStream out = new FileOutputStream(target)) {
            if (rewritten != null) {
                int newline = indexOfNewline(head, headLength);
                if (newline >= 0) {
                    out.write(rewritten.getBytes(StandardCharsets.UTF_8));
                    out.write(head, newline, headLength - newline);
                } else {
                    out.write(rewritten.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                out.write(head, 0, headLength);
            }
            copy(tar, out);
            out.flush();
        }
        chmod(target, entry.getMode());
    }

    private static String rewriteLink(String target, String linkPath) {
        if (target == null || !target.startsWith(TERMUX_USR_ABS)) return target;
        String inside = target.substring(TERMUX_USR_ABS.length());
        int depth = 0;
        String path = linkPath;
        while (true) {
            int slash = path.indexOf('/');
            if (slash < 0) break;
            depth++;
            path = path.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("../");
        return sb.append(inside).toString();
    }

    private static String rewriteShebang(String firstLine, String prefix) {
        if (firstLine == null || !firstLine.startsWith(TERMUX_SHEBANG)) return null;
        String rest = firstLine.substring(TERMUX_SHEBANG.length());
        return "#!" + prefix + "/" + rest;
    }

    private static String firstLine(byte[] head, int length) {
        if (length <= 0) return "";
        int end = indexOfNewline(head, length);
        String text = new String(head, 0, end >= 0 ? end : length, StandardCharsets.UTF_8);
        return text.trim();
    }

    private static int indexOfNewline(byte[] bytes, int length) {
        for (int i = 0; i < length; i++)
            if (bytes[i] == '\n') return i;
        return -1;
    }

    private static int readUpTo(InputStream in, byte[] buffer, int max) throws IOException {
        int total = 0;
        while (total < max) {
            int read = in.read(buffer, total, max - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        while (in.read(buffer) != -1) {
            // Drain the entry.
        }
    }

    private static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode & 0777);
        } catch (Exception e) {
            Log.w(TAG, "Could not chmod " + file, e);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    private static void copy(File source, File target) throws IOException {
        if (!source.isFile()) return;
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    /* ==========================================================================================
     * Markers
     * ========================================================================================== */

    private static List<String> readInstalled(File marker) {
        List<String> names = new ArrayList<>();
        if (!marker.isFile()) return names;
        try (InputStream in = new FileInputStream(marker)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            copy(in, out);
            for (String line : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                int space = line.indexOf(' ');
                String name = space >= 0 ? line.substring(0, space) : line;
                if (!name.trim().isEmpty()) names.add(name.trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read " + marker, e);
        }
        return names;
    }

    private static void appendInstalled(File marker, List<Pkg> packages, List<String> already) {
        StringBuilder sb = new StringBuilder();
        for (String name : already) sb.append(name).append('\n');
        for (Pkg pkg : packages) sb.append(pkg.name).append(' ').append(pkg.version).append('\n');
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "Could not write " + marker, e);
        }
    }

    /** Delete a directory tree without following symlinks (they may point outside the prefix). */
    private static void deleteTree(File file) throws IOException {
        java.nio.file.Path root = file.toPath();
        java.nio.file.Files.walkFileTree(root,
            new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(
                    java.nio.file.Path path, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                    java.nio.file.Files.deleteIfExists(path);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(
                    java.nio.file.Path dir, IOException failure) throws IOException {
                    java.nio.file.Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
    }

    /** Some packages ship a /bin/sh link, make sure the prefix always has one. */
    private static void ensureShellLink(File prefix) {
        File bash = new File(prefix, "bin/bash");
        File sh = new File(prefix, "bin/sh");
        if (!bash.isFile() || sh.exists()) return;
        try {
            Os.symlink("bash", sh.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "Could not create " + sh, e);
        }
    }
}
