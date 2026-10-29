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
   platform package is downloaded, the binary is extracted and its `PT_INTERP`
   is pointed at the glibc loader of the toolchain:

   ```
   $PREFIX/glibc/lib/ld-linux-aarch64.so.1   <- PT_INTERP of the binary
   $HOME/.opencode/installed/opencode-v<ver>
   ```

   Patching the interpreter (and only the interpreter, never `--set-rpath`) is
   what makes the binary start **directly**. This matters for
   `opencode serve`: the CLI spawns its own background server through
   `/proc/self/exe`, and when the loader is passed explicitly that path is
   `ld-linux`, so the child runs `ld-linux serve` and exits with
   `serve: cannot open shared object file`. A directly started binary reports
   itself as `/proc/self/exe` and the self-spawn works.

   The Termux glibc loader has `/data/data/com.termux/files/usr/glibc/...`
   compiled into its rodata. That prefix and the sandbox root
   (`/data/data/com.opencode.android`) are both 31 characters, so the loader is
   relocated with an in-place, same-length byte replacement and two symlinks
   (`<dataDir>/glibc -> $PREFIX/glibc`, `<dataDir>/etc -> $PREFIX/etc`) make the
   relocated paths resolve. If the patch fails, the app and the launcher fall
   back to the explicit `ld-linux --library-path …` invocation, which still runs
   the TUI (only the self-spawned server is affected).

3. **Pairing the WebView.** `GET /` alone only serves the web app shell, which
   asks for a server address and password. The app therefore mints a one-time
   code with `POST /api/pair` (Basic auth), redeems it at
   `GET /auth/connect/<code>` (`Accept: application/json`) for a session token
   and loads `/?auth_token=<base64("opencode:<token>")>` — the same link format
   `opencode pair` prints. The token is also written as the
   `opencode_session_<port>` cookie, so reloads stay authenticated.

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

Because of that the prefix has **no `apt` or `dpkg`**, and the Termux app is not
needed either. The app ships its own `pkg` command (`$PREFIX/bin/pkg`) that
installs any package of the Termux repository with the same
download/verify/extract code:

```sh
pkg install git gh
pkg search editor
pkg list-installed
```

`pkg install` starts a short foreground service of the app
(`OpenCodePkgService`) through `am start-foreground-service` and follows a
status file, so the terminal shows the "Downloading… / Extracting…" progress
while the app does the work. The service is not exported; the request comes from
the same UID as the app, which is also why no root is needed. Dependencies are
resolved from the package index, the archives are extracted directly into
`$PREFIX`, and `termux-tools`/`termux-exec` are skipped (`termux-exec` is not
needed because the app targets API 28, so binaries in its data directory may be
executed).

The Termux binaries are linked against `$PREFIX/lib` with an absolute RUNPATH
that points at the com.termux prefix. `$PREFIX/etc/opencode-env.sh` (sourced by
`etc/profile` and hooked into `etc/bash.bashrc`) exports `LD_LIBRARY_PATH`, and
the OpenCode server gets the same variable in its environment, so `git`, `gh`,
`python`, `node`, … find their libraries. The git build additionally has
`libexec/git-core` and its template directory compiled in for the old prefix;
both are re-pointed with `GIT_EXEC_PATH`/`GIT_TEMPLATE_DIR`, and the unreadable
system git config is disabled with `GIT_CONFIG_NOSYSTEM`.

`pkg remove` is not implemented: packages are extracted without a dpkg
database, so there is nothing to remove from. Clearing the app data is the
supported way to start over. Reinstalling a core package (`bash`, `glibc`,
`patchelf`, `ca-certificates`) is refused as well: the app patches their
binaries (the login profile path, the relocated glibc loader), so they are
updated with `oc-install` / `opencode update`.

`bash` and `patchelf` — the two tools the app itself spawns — get their RUNPATH
rewritten once at install time (`patchelf --set-rpath $PREFIX/lib`), so they
start even from a terminal without `LD_LIBRARY_PATH`.

The Termux `bash` package has `/data/data/com.termux/files/usr/etc/profile`
compiled in and starts as `bash -l` inside the OpenCode terminal. That path
belongs to another app and is unreadable here, which produced
`bash: …/etc/profile: Permission denied`. The installer therefore rewrites the
compiled-in login paths of `bash` to the sandbox root (same-length byte
replacement), rewrites the `com.termux` paths inside `etc/bash.bashrc`, and
writes a small managed `etc/profile` to replace the one shipped by the bash
package.

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
