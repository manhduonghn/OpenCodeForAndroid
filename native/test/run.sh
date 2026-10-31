#!/usr/bin/env bash
# End-to-end tests for the two native pieces:
#   - liboc-pathmap.so through `oc-gopatch run` (host x86_64, the real loader),
#   - the Go static patch on an AArch64 Go program under qemu-aarch64.
#
# Needs gcc, go, qemu-aarch64 and libc6-dev-arm64-cross. The Go fixture is built
# with CGO_ENABLED=0, so the host Go toolchain is enough.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The tests are self-contained: build the generated pieces when this script is
# run on its own (CI builds them first and these steps are skipped).
if [ ! -f "$repo/native/gopatch/gosys_blob.go" ]; then
  echo "native-test: generating the oc-gosys blob"
  "$repo/native/gosys/build.sh"
fi
if [ ! -f "$repo/native/pathmap/build/liboc-pathmap-x86_64.so" ]; then
  echo "native-test: building liboc-pathmap.so for x86_64"
  "$repo/native/pathmap/build.sh" x86_64
fi

app_prefix=/data/data/com.opencode.android/files/usr
app_home=/data/data/com.opencode.android/files/home
termux_prefix=/data/data/com.termux/files/usr
termux_home=/data/data/com.termux/files/home

echo "native-test: preparing the private roots"
sudo mkdir -p "$app_prefix" "$app_home"
sudo chown -R "$(id -u):$(id -g)" /data/data/com.opencode.android
printf 'hello from the app prefix\n' > "$app_prefix/hello"
printf 'hello from the app home\n' > "$app_home/hello"

echo "native-test: building the host patcher and the AArch64 fixture"
(
  cd "$repo/native/gopatch"
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -buildvcs=false \
    -o "$work/oc-gopatch" .
)
(
  cd "$here"
  CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -buildvcs=false \
    -o "$work/fixture" .
)
qemu=(qemu-aarch64 -L /usr/aarch64-linux-gnu)

echo "native-test: an unpatched Go program does not see the private prefix"
if "${qemu[@]}" "$work/fixture" open "$termux_prefix/hello" >"$work/unpatched.out" 2>&1; then
  echo "native-test: the unpatched fixture unexpectedly opened $termux_prefix/hello" >&2
  cat "$work/unpatched.out" >&2
  exit 1
fi

echo "native-test: patching the fixture"
"$work/oc-gopatch" patch "$work/fixture" "$work/patched"
chmod 0755 "$work/patched"
[ "$("$work/oc-gopatch" classify "$work/fixture")" = "go" ]
[ "$("$work/oc-gopatch" classify "$work/patched")" = "static" ]

echo "native-test: paths, getcwd and the syscall policy"
"${qemu[@]}" "$work/patched" open "$termux_prefix/hello" | grep -q 'hello from the app prefix'
"${qemu[@]}" "$work/patched" open "$termux_home/hello" | grep -q 'hello from the app home'
"${qemu[@]}" "$work/patched" getcwd "$app_prefix" | grep -q "cwd: $termux_prefix"
"${qemu[@]}" "$work/patched" setuid | grep -q 'setuid: ok'
"${qemu[@]}" "$work/patched" clone3 | grep -q 'clone3: enosys'
"${qemu[@]}" "$work/patched" getgroups | grep -q 'one empty group'

echo "native-test: a second patch of a patched program is refused"
if "$work/oc-gopatch" patch "$work/patched" "$work/patched2" >"$work/second.out" 2>&1; then
  echo "native-test: patching an already patched program unexpectedly succeeded" >&2
  exit 1
fi

echo "native-test: the glibc preload through oc-gopatch run"
gcc -std=gnu11 -O2 -Wall -Wextra -Werror -o "$work/glibc-open" "$here/glibc_open.c"
root=$work/root
mkdir -p "$root/glibc/lib" "$root/lib" "$root/home"
ln -s /lib64/ld-linux-x86-64.so.2 "$root/glibc/lib/ld-linux-x86-64.so.2"
cp "$repo/native/pathmap/build/liboc-pathmap-x86_64.so" "$root/lib/liboc-pathmap.so"
printf 'hello from the preload\n' > "$root/hello"
OC_PREFIX="$root" OC_USER_HOME="$root/home" \
  "$work/oc-gopatch" run -- "$work/glibc-open" "$termux_prefix/hello" |
  grep -q 'hello from the preload'

echo "native-test: ok"
