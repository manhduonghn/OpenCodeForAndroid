# OpenCode for Android

**OpenCode for Android** is an Android application that runs the official
[OpenCode](https://opencode.ai) coding agent natively on Android — no root, no
proot, no chroot — and gives the agent the full Linux toolchain: shell, `apt`,
compiler toolchain, files in `$HOME`, git, python, node, …

It is the successor of the “OpenCode for Termux” technique, reworked so the app
owns its **own package id** (`com.opencode.android`) and its **own Linux prefix**
instead of borrowing the Termux ones (`com.termux`).

## How it works

```
apt-get install glibc-repo glibc-runner patchelf tar   # libraries
  → download the official npm tarball @opencode/cli-linux-<arch>
  → tar -xzf package/bin/opencode
  → patchelf --set-interpreter <prefix>/glibc/lib/ld-linux-*.so.*   # interpreter ONLY
  → verify with --version → atomic rename → <prefix>/bin/opencode launcher
```

Everything runs from **one Java class** (`OpenCodeBootstrap`) inside the app, so
the installation keeps its progress, survives the app being killed mid-download
and repairs itself afterwards. No root, no proot, no chroot.

The installer keeps apt:

1. `apt-get update` (with mirrored fallbacks via temporary `sources.list`)
2. `apt-get install glibc-repo` → `apt-get install glibc-runner patchelf tar`
3. resolve the latest OpenCode version (official API → npm registry fallback)
4. download the npm tarball, verify SHA-512, extract
5. patch `PT_INTERP` with the glibc loader, verify with `--version`
6. atomic install + symlink + markers (`.native-ready`, `.patched`)
7. launcher scripts + self-repair + progress state

## Newer glibc

The app can install a **newer glibc** through apt (`pkg`/`apt-get install
glibc …`) when the published OpenCode build requires a newer glibc than the one
bundled in the current `glibc-runner` package.

## Building from source

```
git clone <this-repo>
cd OpenCodeForAndroid
./gradlew :app:assembleDebug
```

## License

MIT — see [LICENSE](LICENSE).
