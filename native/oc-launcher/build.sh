#!/usr/bin/env bash
# Build the bionic oc-launcher for one ABI. It is a small Android executable
# that runs the downloaded OpenCode binary through the glibc loader with
# liboc-pathmap.so preloaded, so updates do not depend on patching the binary.
#
# usage: build.sh [arm64-v8a|x86_64] [output]
# Requires the Android NDK (NDK or ANDROID_NDK_HOME). CC can override the compiler.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
abi=${1:-arm64-v8a}
out=${2:-$here/build/oc-launcher-$abi}
api=${API:-28}

if [ -n "${CC:-}" ]; then
  cc=$CC
else
  ndk=${NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}
  if [ -z "$ndk" ] && [ -n "${ANDROID_HOME:-}" ]; then
    # Pick the newest installed NDK.
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
"$cc" -O2 -Wall -Wextra -Werror -fPIE -pie -o "$out" "$here/oc-launcher.c"
# Strip if available to keep the asset small.
if command -v "${STRIP:-strip}" >/dev/null 2>&1; then
  "${STRIP:-strip}" "$out" 2>/dev/null || true
fi
echo "build.sh: $out"
