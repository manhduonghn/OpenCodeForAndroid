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
   platform package is downloaded, the binary is extracted and started through
   the glibc loader of the toolchain:

   ```
   $PREFIX/glibc/lib/ld-linux-aarch64.so.1 \
       --library-path $PREFIX/glibc/lib $HOME/.opencode/bin/opencode serve \
       --hostname 127.0.0.1 --port 49374
   ```

   The 240 MB binary is not rewritten with `patchelf` (that is slow and can run
   out of memory on a phone); the glibc loader is passed explicitly instead.

3. **Pairing the WebView.** `GET /` alone only serves the web app shell, which
   asks for a server address and password. The app therefore mints a one-time
   code with `POST /api/pair` (Basic auth) and loads
   `/auth/connect/<code>`: the server stores a `opencode_session_<port>` cookie
   valid for 30 days and redirects to the app, which is then logged in.

### What gets installed

Only the minimum the runtime needs (≈14 MB of packages):

| package | why |
| --- | --- |
| `bash` (+ `readline`, `ncurses`, `libiconv`, `libandroid-support`) | shell for OpenCode and scripts |
| `glibc` (+ `linux-api-headers-glibc`) | glibc loader and libraries for the OpenCode binary |
| `patchelf` (+ `libc++`) | rewrites the RUNPATH of `bash`/`patchelf` themselves |
| `ca-certificates` | HTTPS trust store (`SSL_CERT_FILE`) |

`termux-tools` is deliberately skipped: it is a set of wrappers around the
Termux app (`am`, `pm`, …) and would pull the Termux app and a dozen extra
packages.

The Termux binaries point at the `com.termux` prefix with their `RUNPATH`, so it
is rewritten once at install time (`patchelf --set-rpath $PREFIX/lib`), which
makes `bash` start even from a terminal without `LD_LIBRARY_PATH`.

`targetSdk` is **28** on purpose: Android 10+ denies `execve()` of files inside
the app data directory for apps that target API 29 or newer. Termux itself does
the same.

### Server authentication

The OpenCode 2.x server requires HTTP Basic auth for its API. A random password
is generated once and stored in the shared preferences of the app, passed to the
server as `OPENCODE_SERVER_PASSWORD` and used by the app to mint pairing codes.
The web UI itself never sees the password: it gets a session cookie through the
pairing link. The launcher exports the same password from
`$HOME/.opencode/server-password`, so the terminal and the app can talk to the
same server.

### Installation progress

The first start downloads ≈14 MB of toolchain and ≈114 MB of OpenCode, so the
app shows a progress screen instead of a blank WebView. The WebView only loads
the web UI once the server really accepts connections, which avoids
`net::ERR_CACHE_MISS` / `ERR_CONNECTION_REFUSED`.

### Terminal usage

```sh
# any Android shell, no Termux needed
$PREFIX/bin/opencode --version
$PREFIX/bin/opencode            # TUI
$PREFIX/bin/opencode upgrade    # asks the app to re-download the runtime
```

with `PREFIX=/data/data/com.opencode.android/files/usr` and
`HOME=/data/data/com.opencode.android/files/home`.

### Server lifecycle

The server is a child of the app process, and Android does not always kill it
when the app process dies. On start the service therefore looks the server up
(pid file `$HOME/.opencode/server.pid` plus a `/proc` scan) and reuses it when
it runs the current binary, instead of starting a second `serve` that would
fail with `EADDRINUSE`. A server of an older binary is stopped first, and the
service waits until the port is free before spawning.

## Diagnostics

Crash reports and server errors stay inside the app sandbox:

* `Android/data/com.opencode.android/files/OpenCodeForAndroid/`

Nothing is written into the public `Download` folder, the app does not request
storage permissions. `opencode-install-*.log`, `opencode-server-*.log` and
`opencode-port-*.log` are the files to look at when something fails.

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
