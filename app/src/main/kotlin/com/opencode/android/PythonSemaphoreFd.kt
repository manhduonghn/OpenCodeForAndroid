package com.opencode.android

import java.io.File

/**
 * Keeps the descriptor used by the relocated semaphore library alive across Python process
 * boundaries.
 *
 * `TermuxSemaphorePath` points `libandroid-posix-semaphore.so` at `/proc/self/fd/9`. The native
 * launchers open that descriptor before `execve`, but CPython's multiprocessing starts children
 * with `close_fds=True` and only passes an explicit allow-list of descriptors. `forkserver` and
 * `spawn` therefore lost FD 9: `sem_open` created the semaphore under the right name, yet the
 * worker could not resolve the alias and failed with `FileNotFoundError`.
 *
 * This object writes a small, version-independent startup module into every `site-packages` of the
 * prefix. At interpreter startup the module reopens FD 9 from `OC_SEMAPHORE_DIR` when needed (a
 * child may have been started with the descriptor already closed) and wraps
 * `multiprocessing.util.spawnv_passfds` so the descriptor is passed to every further child. It is
 * installed both after extracting a package and during the ordinary repair pass, so installing
 * Python from the terminal fixes the existing prefix without a restart.
 */
internal object PythonSemaphoreFd {

    const val MODULE = "oc_semaphore_fd.py"
    const val PTH = "oc-semaphore.pth"

    // No Python triple-quoted strings here: this whole block is a Kotlin raw string.
    private val MODULE_SOURCE = """
# Keep the semaphore alias descriptor across Python multiprocessing spawns.
# Installed by OpenCode for Android; do not edit by hand.

import os
import multiprocessing.util as _mp_util

_SEM_FD = 9
_DIRECTORY = os.environ.get("OC_SEMAPHORE_DIR")


def _is_semaphore_directory(fd):
    if not _DIRECTORY:
        return False
    try:
        opened = os.fstat(fd)
        expected = os.stat(_DIRECTORY)
        return (opened.st_dev, opened.st_ino) == (expected.st_dev, expected.st_ino)
    except OSError:
        return False


# A child started by spawn/forkserver may be launched with close_fds=True. Recover the
# descriptor from the exported directory so /proc/self/fd/9 keeps resolving.
if _DIRECTORY and not _is_semaphore_directory(_SEM_FD):
    try:
        opened = os.open(_DIRECTORY, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        if opened != _SEM_FD:
            os.dup2(opened, _SEM_FD)
            os.close(opened)
    except OSError:
        pass

# CPython's spawn helpers pass only an explicit allow-list of descriptors. Add ours.
if _is_semaphore_directory(_SEM_FD):
    _original = _mp_util.spawnv_passfds
    if not getattr(_original, "_oc_semaphore_fd", False):
        def _spawnv_passfds(path, args, passfds):
            fds = set(int(fd) for fd in passfds)
            fds.add(_SEM_FD)
            return _original(path, args, tuple(sorted(fds)))

        _spawnv_passfds._oc_semaphore_fd = True
        _mp_util.spawnv_passfds = _spawnv_passfds
""".trimIndent()

    private const val PTH_SOURCE = "import oc_semaphore_fd\n"

    /**
     * Install the hook into every Python installation below [prefix].
     *
     * @return true when at least one file was created or changed.
     */
    fun install(prefix: File): Boolean {
        val libraries = File(prefix, "lib").takeIf { it.isDirectory } ?: return false
        var changed = false
        val versions = libraries.listFiles()?.filter { it.isDirectory && it.name.startsWith("python") }
            ?: return false
        for (version in versions) {
            val site = File(version, "site-packages")
            if (!site.isDirectory && !site.mkdirs()) continue
            changed = writeIfChanged(File(site, MODULE), MODULE_SOURCE) || changed
            changed = writeIfChanged(File(site, PTH), PTH_SOURCE) || changed
        }
        return changed
    }

    private fun writeIfChanged(file: File, content: String): Boolean {
        return try {
            if (file.isFile && file.readText() == content) return false
            file.writeText(content)
            true
        } catch (e: Exception) {
            // A read-only or broken prefix is repaired again on the next pass.
            false
        }
    }
}
