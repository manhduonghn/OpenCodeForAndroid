#!/usr/bin/env bash
# Build liboc-pathmap.so for one ABI. The library is glibc, not bionic: it is
# preloaded only into the glibc programs that the app starts through oc-run.
#
# usage: build.sh [aarch64|x86_64] [output]
# The cross compiler can be selected with CC (default aarch64-linux-gnu-gcc for
# aarch64 and gcc for x86_64).
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
arch=${1:-aarch64}
out=${2:-$here/build/liboc-pathmap-$arch.so}

if [ -n "${CC:-}" ]; then
  cc=$CC
else
  case "$arch" in
    aarch64) cc=aarch64-linux-gnu-gcc ;;
    x86_64) cc=gcc ;;
    *) echo "build.sh: unsupported ABI '$arch' (aarch64 or x86_64)" >&2; exit 2 ;;
  esac
fi

mkdir -p "$(dirname "$out")"
"$cc" \
  -std=gnu11 -O2 -Wall -Wextra -Werror \
  -fPIC -shared -fvisibility=hidden \
  -Wl,-soname,liboc-pathmap.so \
  -o "$out" "$here/pathmap.c" -ldl
echo "build.sh: $out"
