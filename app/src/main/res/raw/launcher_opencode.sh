#!/system/bin/sh
# @MARKER@
P="${PREFIX:-@PREFIX@}"
H="${HOME:-@HOME@}"
BIN="$H/.opencode/bin/opencode"
GLIBCLIB="$P/glibc/lib"

case "${1:-}" in
  upgrade|update) exec "$P/bin/oc-install" ;;
esac

[ -x "$BIN" ] || { echo "opencode: not installed yet. Open the app to install it." >&2; exit 127; }
LOADER=""; for c in "$GLIBCLIB"/ld-linux*.so.*; do [ -x "$c" ] && { LOADER="$c"; break; }; done
[ -z "$LOADER" ] && { echo "opencode: the glibc runtime is missing. Open the app and run the installation again." >&2; exit 127; }

# The auto updater of OpenCode would replace the binary that the launcher knows.
export OPENCODE_DISABLE_AUTOUPDATE=1
# Never load the bionic preload of Termux into a glibc process.
unset LD_PRELOAD
# The tools OpenCode spawns (git, …) link against $P/lib. The glibc binary itself
# only needs libc.so.6/libm.so.6/libdl.so.2/libpthread.so.0, names that no bionic
# library provides, so this directory cannot shadow its own libraries.
case ":${LD_LIBRARY_PATH:-}:" in
  *":$P/lib:"*) ;;
  *) LD_LIBRARY_PATH="$P/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; export LD_LIBRARY_PATH ;;
esac
[ -n "${TMPDIR:-}" ] || { TMPDIR="$P/tmp"; export TMPDIR; }
[ -n "${SHELL:-}" ] || { SHELL="$P/bin/bash"; export SHELL; }
[ -f "$P/etc/tls/cert.pem" ] && { SSL_CERT_FILE="$P/etc/tls/cert.pem"; export SSL_CERT_FILE; }
# The terminal uses the same server password as the app, so a server that is
# started here and the one of the app cannot disagree about the credentials.
if [ -z "${OPENCODE_SERVER_PASSWORD:-}" ] && [ -f "$H/.opencode/server-password" ]; then
  IFS= read -r OPENCODE_SERVER_PASSWORD < "$H/.opencode/server-password" 2>/dev/null
  [ -n "$OPENCODE_SERVER_PASSWORD" ] && export OPENCODE_SERVER_PASSWORD
fi

# When the binary is patched, start it directly: OpenCode spawns its background
# server through /proc/self/exe, which would be the loader (and 'serve' would fail)
# if the loader was passed explicitly.
MARK=$(cat "$H/.opencode/.native-interp" 2>/dev/null)
NOW=$(/system/bin/stat -L -c '%s %Y' "$BIN" 2>/dev/null)
if [ -n "$MARK" ] && [ "$MARK" = "$NOW" ]; then
  exec "$BIN" "$@"
fi

echo "opencode: this build is not patched; using the glibc loader." >&2
exec "$LOADER" --library-path "$GLIBCLIB" "$BIN" "$@"
