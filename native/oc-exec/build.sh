#!/usr/bin/env bash
# Build liboc-exec.so, the bionic LD_PRELOAD exec shim used with targetSdk 36.
# The library is Android-native (bionic): it must never be preloaded into the
# glibc OpenCode process, which uses liboc-pathmap.so instead.
#
# usage: build.sh [arm64-v8a|x86_64] [output]
# Requires the Android NDK (NDK / ANDROID_NDK_HOME / ANDROID_HOME). CC overrides
# the compiler; API selects the NDK API level (default 26, the app's minSdk).
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
abi=${1:-arm64-v8a}
out=${2:-$here/build/liboc-exec-$abi.so}
api=${API:-26}

if [ -n "${CC:-}" ]; then
  cc=$CC
else
  ndk=${NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}
  if [ -z "$ndk" ] && [ -n "${ANDROID_HOME:-}" ]; then
    ndk=$(ls -1d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -n1 || true)
  fi
  if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
    echo "build.sh: Android NDK not found (set NDK or ANDROID_NDK_HOME)" >&2
    exit 1
  fi
  host=$(uname -s | tr '[:upper:]' '[:lower:]')
  case "$host" in linux) host_tag=linux-x86_64 ;; darwin) host_tag=darwin-x86_64 ;; *) host_tag=linux-x86_64 ;; esac
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    x86_64) triple=x86_64-linux-android ;;
    *) echo "build.sh: unsupported ABI '$abi'" >&2; exit 2 ;;
  esac
  cc="$ndk/toolchains/llvm/prebuilt/$host_tag/bin/${triple}${api}-clang"
fi

mkdir -p "$(dirname "$out")"
"$cc" -std=gnu11 -O2 -Wall -Wextra -Werror \
  -fPIC -shared -fvisibility=hidden -Wl,-soname,liboc-exec.so \
  -o "$out" "$here/oc-exec.c" -ldl
echo "build.sh: $out"
