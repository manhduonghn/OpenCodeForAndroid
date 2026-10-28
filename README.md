# OpenCode for Android

**OpenCode for Android** runs the official [OpenCode](https://opencode.ai) coding agent
natively on Android — no root, no proot, no chroot — inside its **own package id**
(`com.opencode.android`) and its **own Linux prefix**, without the `com.termux` app.

## How it works

The app combines the two techniques that make this possible:

1. **Installing Termux packages without Termux** (the AndroidHarness technique).
   The Termux repository indexes are downloaded, the `.deb` archives are verified
   (SHA-256) and extracted **in Java** (`commons-compress` + `xz`). While
   extracting, the absolute `/data/data/com.termux/files/usr` paths that are
   baked into the packages are rewritten:

   * `data/data/com.termux/files/usr/...` → `$PREFIX/...`
   * absolute symlinks → relative symlinks inside the prefix
   * `#!/data/data/com.termux/files/usr/bin/sh` shebangs → `$PREFIX/bin/sh`

   No `apt`/`dpkg` is involved, they would write into the real Termux prefix.

2. **Running OpenCode** (the opencode-termux technique). The official npm
   platform package is downloaded, the binary is extracted and its ELF
   interpreter is changed to the glibc loader of the toolchain:

   ```
   patchelf --set-interpreter $PREFIX/glibc/lib/ld-linux-*.so.* \
            --set-rpath       $PREFIX/glibc/lib  bin/opencode
   ```

   The server is then started as the loader with
   `--library-path $PREFIX/glibc/lib …/opencode serve --port 49374`, and the app
   shows `http://127.0.0.1:49374/` in a WebView.

### What gets installed

Only the minimum the runtime needs (≈16 MB of packages):

| package | why |
| --- | --- |
| `bash` (+ `readline`, `ncurses`, `libiconv`, `libandroid-support`) | shell for OpenCode and scripts |
| `glibc` (+ `linux-api-headers-glibc`) | glibc loader and libraries for the OpenCode binary |
| `patchelf` (+ `libc++`) | rewrites the interpreter/RUNPATH of the binary |
| `ca-certificates` | HTTPS trust store (`SSL_CERT_FILE`) |

`termux-tools` is deliberately skipped: it is a set of wrappers around the
Termux app (`am`, `pm`, …) and would pull the Termux app and a dozen extra
packages.

The packages are built for the Termux prefix, so their `RUNPATH` is rewritten
once at install time (`patchelf --set-rpath $PREFIX/lib`), which makes `bash`
and the OpenCode binary start even from a terminal without `LD_LIBRARY_PATH`.

`targetSdk` is **28** on purpose: Android 10+ denies `execve()` of files inside
the app data directory for apps that target API 29 or newer. Termux itself does
the same.

### Installation progress

The first start downloads ≈20 MB of toolchain and ≈60 MB of OpenCode, so the
app shows a progress screen instead of a blank WebView. The WebView only loads
`http://127.0.0.1:49374/` once the server really accepts connections, which
avoids `net::ERR_CACHE_MISS` / `ERR_CONNECTION_REFUSED`.

### Terminal usage

```sh
# any Android shell, no Termux needed
$PREFIX/bin/opencode --version
$PREFIX/bin/opencode            # TUI
$PREFIX/bin/opencode upgrade    # asks the app to re-download the runtime
```

with `PREFIX=/data/data/com.opencode.android/files/usr` and
`HOME=/data/data/com.opencode.android/files/home`.

## Diagnostics

Every crash and every server process that exits with an error is written to

* `Android/data/com.opencode.android/files/OpenCodeForAndroid/`
* `Download/opencode-*.log` (MediaStore on Android 10+, plain file below)

## Building

```sh
git clone https://github.com/manhduonghn/OpenCodeForAndroid
cd OpenCodeForAndroid
./gradlew :app:assembleDebug
```

The nightly workflow builds a debug APK on every push, the release workflow
builds a signed APK for every `v*` tag.

## License

MIT — see [LICENSE](LICENSE).
