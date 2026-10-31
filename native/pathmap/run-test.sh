#!/usr/bin/env bash
# Build and run the liboc-pathmap tests for one ABI. For aarch64 the test binary
# and the library are executed with qemu-aarch64 and the guest sysroot.
#
# usage: run-test.sh [aarch64|x86_64]
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
arch=${1:-x86_64}
build=$here/build
mkdir -p "$build"

case "$arch" in
  aarch64)
    cc=${CC_AARCH64:-aarch64-linux-gnu-gcc}
    qemu=${QEMU_AARCH64:-qemu-aarch64}
    guest=${QEMU_SYSROOT:-/usr/aarch64-linux-gnu}
    ;;
  x86_64)
    cc=${CC_X86_64:-gcc}
    ;;
  *)
    echo "run-test.sh: unsupported ABI '$arch'" >&2
    exit 2
    ;;
esac

lib=$build/liboc-pathmap-$arch.so
testbin=$build/pathmap_test-$arch
CC="$cc" "$here/build.sh" "$arch" "$lib"
"$cc" -std=gnu11 -O2 -Wall -Wextra -Werror -o "$testbin" "$here/pathmap_test.c"

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
export OC_TEST_ROOT="$root"
# The real app prefix is longer than the Termux one, so unmapping (getcwd,
# readlink) shrinks the path. Keep that property in the test.
export OC_PREFIX="$root/private-prefix-of-the-app"
export OC_USER_HOME="$root/private-home-of-the-app"

case "$arch" in
  aarch64)
    # The host qemu is x86_64: exporting LD_PRELOAD would make the host loader
    # load the AArch64 library into qemu itself. -E passes it to the guest only.
    runner=("$qemu" -L "$guest" -E "LD_PRELOAD=$lib")
    ;;
  x86_64)
    export LD_PRELOAD="$lib"
    runner=()
    ;;
esac

echo "pathmap: testing $arch"
"${runner[@]}" "$testbin"
