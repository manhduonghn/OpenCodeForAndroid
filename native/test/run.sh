#!/usr/bin/env bash
# End-to-end tests for the two native pieces:
#   - liboc-pathmap.so through `oc-gopatch run` (host x86_64, the real loader),
#   - the oc-gosys patch on an AArch64 Go program and on a static C program,
#     both under qemu-aarch64.
#
# Needs gcc, go, qemu-aarch64, gcc-aarch64-linux-gnu and libc6-dev-arm64-cross.
# The Go fixture is built with CGO_ENABLED=0, so the host Go toolchain is enough;
# the static C fixture is built by the cross compiler and skipped when it is
# missing.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
# /tmp is mapped into the private prefix by liboc-pathmap.so, so the fixtures
# (and the scripts the glibc shell opens) must live outside it.
work=$here/build/run-$$
rm -rf "$work"
mkdir -p "$work"
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
"${qemu[@]}" "$work/patched" blocked | grep -q 'blocked: enosys'

echo "native-test: a second patch of a patched program is refused"
if "$work/oc-gopatch" patch "$work/patched" "$work/patched2" >"$work/second.out" 2>&1; then
  echo "native-test: patching an already patched program unexpectedly succeeded" >&2
  exit 1
fi

# A static C program makes its own system calls, so LD_PRELOAD cannot reach it:
# the patcher rewrites its "svc #0" sites instead. The cross compiler is part of
# the CI image; a host without it skips this block.
if command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
  echo "native-test: building the static C fixture"
  aarch64-linux-gnu-gcc -static -no-pie -O2 -Wall -Wextra -Werror \
    -o "$work/static-fixture" "$here/static_fixture.c"
  [ "$("$work/oc-gopatch" classify "$work/static-fixture")" = "static" ]

  echo "native-test: an unpatched static C program does not see the private prefix"
  if "${qemu[@]}" "$work/static-fixture" open "$termux_prefix/hello" \
    >"$work/static-unpatched.out" 2>&1; then
    echo "native-test: the unpatched static fixture unexpectedly opened $termux_prefix/hello" >&2
    cat "$work/static-unpatched.out" >&2
    exit 1
  fi

  echo "native-test: patching the static C fixture"
  "$work/oc-gopatch" patch "$work/static-fixture" "$work/static-patched"
  chmod 0755 "$work/static-patched"
  [ "$("$work/oc-gopatch" classify "$work/static-patched")" = "static" ]

  echo "native-test: static C paths, getcwd and the syscall policy"
  "${qemu[@]}" "$work/static-patched" open "$termux_prefix/hello" | grep -q 'hello from the app prefix'
  "${qemu[@]}" "$work/static-patched" open "$termux_home/hello" | grep -q 'hello from the app home'
  "${qemu[@]}" "$work/static-patched" getcwd "$app_prefix" | grep -q "cwd: $termux_prefix"
  "${qemu[@]}" "$work/static-patched" setuid | grep -q 'setuid: ok'
  "${qemu[@]}" "$work/static-patched" clone3 | grep -q 'clone3: enosys'
  "${qemu[@]}" "$work/static-patched" getgroups | grep -q 'one empty group'
  "${qemu[@]}" "$work/static-patched" blocked | grep -q 'blocked: enosys'
  "${qemu[@]}" "$work/static-patched" fork | grep -q 'fork: ok'

  echo "native-test: a second patch of a patched static program is refused"
  if "$work/oc-gopatch" patch "$work/static-patched" "$work/static-patched2" \
    >"$work/static-second.out" 2>&1; then
    echo "native-test: patching an already patched static program unexpectedly succeeded" >&2
    exit 1
  fi
else
  echo "native-test: skipping the static C tests (aarch64-linux-gnu-gcc is missing)"
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

echo "native-test: OC_DEBUG reports the chosen strategy"
debug_glibc=$(OC_DEBUG=1 OC_PREFIX="$root" OC_USER_HOME="$root/home" \
  "$work/oc-gopatch" run -- "$work/glibc-open" "$termux_prefix/hello" 2>&1)
case "$debug_glibc" in
  *strategy=glibc*) ;;
  *) echo "native-test: no glibc strategy in: $debug_glibc" >&2; exit 1 ;;
esac

echo "native-test: scripts through the helper"
printf '#!/bin/sh\necho script-ok\n' > "$work/plain.sh"
printf '#!/usr/bin/env sh\necho env-script-ok\n' > "$work/env.sh"
chmod 0755 "$work/plain.sh" "$work/env.sh"
"$work/oc-gopatch" run -- "$work/plain.sh" | grep -q 'script-ok'
"$work/oc-gopatch" run -- "$work/env.sh" | grep -q 'env-script-ok'
debug_script=$(OC_DEBUG=1 "$work/oc-gopatch" run -- "$work/plain.sh" 2>&1)
case "$debug_script" in
  *strategy=script*) ;;
  *) echo "native-test: no script strategy in: $debug_script" >&2; exit 1 ;;
esac

echo "native-test: Termux spellings for the helper inputs"
mkdir -p "$app_prefix/bin"
printf '#!/bin/sh\necho termux-input-ok\n' > "$app_prefix/bin/termux-tool"
chmod 0755 "$app_prefix/bin/termux-tool"
cp "$work/fixture" "$app_prefix/bin/termux-go"
chmod 0755 "$app_prefix/bin/termux-go"
"$work/oc-gopatch" classify "$termux_prefix/bin/termux-tool" | grep -q '^script'
[ "$("$work/oc-gopatch" classify "$termux_prefix/bin/termux-go")" = "go" ]
"$work/oc-gopatch" run -- "$termux_prefix/bin/termux-tool" | grep -q 'termux-input-ok'
"$work/oc-gopatch" patch "$termux_prefix/bin/termux-go" "$work/from-termux" &&
  [ "$("$work/oc-gopatch" classify "$work/from-termux")" = "static" ]

echo "native-test: ok"
