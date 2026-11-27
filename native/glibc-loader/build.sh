#!/usr/bin/env bash
# Extract the glibc dynamic loader of the Termux glibc repository into an APK
# native library. Files under nativeLibraryDir are labelled apk_data_file, so
# Android still allows execve() on them with targetSdk >= 29 (W^X). The loader
# extracted into the app prefix is app_data_file and can no longer be executed.
#
# This is the only glibc binary that has to live in the APK: it receives the
# real OpenCode binary as an argument and keeps using $PREFIX/glibc/lib through
# --library-path, so no glibc runtime is copied into the APK.
#
# usage: build.sh [arm64-v8a|x86_64] [output]
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
abi=${1:-arm64-v8a}
out=${2:-$here/build/$abi/liboc_ld.so}
repo=${GLIBC_REPO_URL:-https://packages.termux.dev/apt/termux-glibc}

case "$abi" in
  arm64-v8a) arch=aarch64 ;;
  x86_64) arch=x86_64 ;;
  *) echo "build.sh: unsupported ABI '$abi'" >&2; exit 2 ;;
esac
index="$repo/dists/glibc/stable/binary-$arch/Packages.gz"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --retry 3 "$index" | gzip -dc > "$tmp/Packages"
filename=$(awk '/^Package: glibc$/{found=1} found && /^Filename:/{print $2; exit}' "$tmp/Packages")
sha256=$(awk '/^Package: glibc$/{found=1} found && /^SHA256:/{print $2; exit}' "$tmp/Packages")
if [ -z "$filename" ] || [ -z "$sha256" ]; then
  echo "build.sh: glibc package not found in $index" >&2
  exit 1
fi

curl -fsSL --retry 3 "$repo/$filename" -o "$tmp/glibc.deb"
echo "$sha256  $tmp/glibc.deb" | sha256sum -c -
(
  cd "$tmp"
  ar x glibc.deb
  tar xf data.tar.* 2>/dev/null || tar xf data.tar
)
loader=$(find "$tmp" -type f -name 'ld-linux*.so.1' | head -n1)
if [ -z "$loader" ]; then
  echo "build.sh: no ld-linux in $filename" >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"
cp "$loader" "$out"
# The loader in the APK is immutable, so the one-to-one relocation has to happen
# here instead of at runtime (OpenCodeBootstrap.relocateGlibc patches the prefix
# copy). Both paths are 31 bytes, so no ELF offset changes.
python3 - "$out" <<'PY'
import sys

path = sys.argv[1]
old = b"/data/data/com.termux/files/usr"
new = b"/data/data/com.opencode.android"
assert len(old) == len(new) == 31
with open(path, "rb") as f:
    data = bytearray(f.read())
count = data.count(old)
if count == 0:
    raise SystemExit("build.sh: no Termux prefix string in the glibc loader")
with open(path, "wb") as f:
    f.write(data.replace(old, new))
print(f"build.sh: relocated {count} prefix string(s) in {path}")
PY
chmod 755 "$out"
echo "build.sh: $out"
