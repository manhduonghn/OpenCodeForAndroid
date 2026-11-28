#!/usr/bin/env bash
# Host smoke tests for the APK entry points. The NDK builds in native.yml are the
# real compile check; these tests catch wrapper argument/environment mistakes
# without a device. No Android paths are needed: the linker call is expected to
# fail on the host and exercises the static ripgrep fallback instead.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
cc=${CC:-gcc}
work=$here/build/wrapper-test-$$
rm -rf "$work"
mkdir -p "$work"
trap 'rm -rf "$work"' EXIT

command -v "$cc" >/dev/null 2>&1 || { echo "wrapper-test: $cc missing" >&2; exit 1; }

echo "wrapper-test: host compile of the bionic shim and both wrappers"
"$cc" -std=gnu11 -O2 -Wall -Wextra -Werror -fPIC -shared \
  -o "$work/liboc-exec-host.so" "$repo/native/oc-exec/oc-exec.c" -ldl
"$cc" -std=gnu11 -O2 -Wall -Wextra -Werror -fPIE -pie \
  -o "$work/oc-run" "$here/oc-run.c"
"$cc" -std=gnu11 -O2 -Wall -Wextra -Werror -fPIE -pie \
  -o "$work/oc-rg" "$here/oc-rg.c"

echo "wrapper-test: oc-run calls the patcher with run -- and the original command"
printf '#!/bin/sh\nprintf "%%s\\n" "$@" > "$OC_WRAPPER_TEST_LOG"\n' > "$work/liboc_gopatch.so"
chmod 0755 "$work/liboc_gopatch.so"
export OC_WRAPPER_TEST_LOG="$work/patcher.args"
"$work/oc-run" jq --version
expected=$(printf 'run\n--\njq\n--version')
[ "$(cat "$OC_WRAPPER_TEST_LOG")" = "$expected" ]

echo "wrapper-test: a PATH symlink routes the command named by argv[0]"
mkdir -p "$work/wrap/bin" "$work/wrap/prefix/bin"
ln -sf "$work/oc-run" "$work/wrap/bin/git"
printf '#!/bin/sh\necho git-mock\n' > "$work/wrap/prefix/bin/git"
chmod 0755 "$work/wrap/prefix/bin/git"
OC_PREFIX="$work/wrap/prefix" "$work/wrap/bin/git" status --short
expected=$(printf 'run\n--\n%s/bin/git\nstatus\n--short' "$work/wrap/prefix")
[ "$(cat "$OC_WRAPPER_TEST_LOG")" = "$expected" ]

echo "wrapper-test: oc-run still runs a command when the patcher is absent"
mkdir -p "$work/no-patcher/bin" "$work/no-patcher/libs"
cp "$work/oc-run" "$work/no-patcher/oc-run"
printf '#!/bin/sh\necho "mock-ok $1"\n' > "$work/no-patcher/bin/mock"
chmod 0755 "$work/no-patcher/bin/mock"
[ "$(PATH="$work/no-patcher/bin:$PATH" "$work/no-patcher/oc-run" mock arg)" = "mock-ok arg" ]

echo "wrapper-test: oc-rg falls back to the prefix binary without the Android linker"
mkdir -p "$work/prefix/bin"
printf '#!/bin/sh\necho rg-ok\n' > "$work/prefix/bin/rg"
chmod 0755 "$work/prefix/bin/rg"
[ "$(PREFIX="$work/prefix" "$work/oc-rg" --version)" = "rg-ok" ]

echo "wrapper-test: ok"
