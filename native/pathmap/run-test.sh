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
    loader_name=ld-linux-aarch64.so.1
    missing_interp=/oc-test/missing/ld-linux-aarch64.so.1
    loader_src=$guest/lib/$loader_name
    ;;
  x86_64)
    cc=${CC_X86_64:-gcc}
    loader_name=ld-linux-x86-64.so.2
    missing_interp=/oc-test/missing/ld-linux-x86-64.so.2
    loader_src=
    for candidate in /lib64/$loader_name /lib/x86_64-linux-gnu/$loader_name; do
      if [ -e "$candidate" ]; then
        loader_src=$candidate
        break
      fi
    done
    ;;
  *)
    echo "run-test.sh: unsupported ABI '$arch'" >&2
    exit 2
    ;;
esac

lib=$build/liboc-pathmap-$arch.so
testbin=$build/pathmap_test-$arch
routebin=$build/route_child-$arch
CC="$cc" "$here/build.sh" "$arch" "$lib"
"$cc" -std=gnu11 -O2 -Wall -Wextra -Werror -o "$testbin" "$here/pathmap_test.c" -ldl

# /tmp is mapped into the prefix by the library, so the test tree must live
# somewhere else. It also has to stay short: a Unix socket name is limited to
# 108 bytes, and the hashed name adds $PREFIX/tmp/.oc_sock_ plus 16 hex digits.
# $HOME is not one of the mapped roots, and the prefix below it is still longer
# than the Termux prefix, which keeps the unmap-shrinks-the-path property.
if [ -n "${OC_TEST_BASE:-}" ]; then
  root=$OC_TEST_BASE
else
  case "${HOME:-}" in
    /root|/root/*) root=/home/oc-pathmap-test-$arch ;;
    *) root=${HOME:-/home}/.oc-pathmap-test-$arch ;;
  esac
fi
rm -rf "$root"
mkdir -p "$root"
trap 'rm -rf "$root"' EXIT
export OC_TEST_ROOT="$root"
# The real app prefix is longer than the Termux one, so unmapping (getcwd,
# readlink) shrinks the path. Keep that property in the test.
export OC_PREFIX="$root/private-prefix-of-the-app"
export OC_USER_HOME="$root/private-home-of-the-app"
export OC_TEST_SELF="$testbin"

# A shared object that only exists under the temporary prefix, so the test can
# prove that dlopen() translates an absolute Termux path.
mkdir -p "$OC_PREFIX/lib"
printf 'int oc_pathmap_test_plugin(void) { return 42; }\n' > "$build/plugin.c"
"$cc" -std=gnu11 -O2 -Wall -Wextra -Werror -fPIC -shared \
  -o "$OC_PREFIX/lib/libpathmap-test-plugin.so" "$build/plugin.c"

# The exec routing fixture: a glibc program with a PT_INTERP that does not exist
# and a library next to it that only $ORIGIN/lib names.
if [ "$arch" = x86_64 ]; then
  mkdir -p "$build/lib"
  printf 'int oc_route_test(void) { return 7; }\n' > "$build/route_lib.c"
  "$cc" -std=gnu11 -O2 -Wall -Wextra -Werror -fPIC -shared \
    -o "$build/lib/liboc-route-test.so" "$build/route_lib.c"
  "$cc" -std=gnu11 -O2 -Wall -Wextra -Werror \
    -o "$routebin" "$here/route_child.c" \
    -L"$build/lib" -loc-route-test -ldl \
    -Wl,-rpath,'$ORIGIN/lib' \
    -Wl,--dynamic-linker,"$missing_interp"
  export OC_TEST_ROUTE_PROGRAM="$routebin"
  export OC_TEST_PRELOAD="$lib"
  # system()/popen() need a glibc shell that the test links into the prefix;
  # qemu cannot run the host shell, so the aarch64 run skips these cases.
  for candidate in /bin/sh /bin/bash; do
    if [ -x "$candidate" ] && grep -aq 'ld-linux' "$candidate"; then
      export OC_TEST_GLIBC_SHELL=$candidate
      break
    fi
  done
  # The shell finds cat through PATH, and the FHS map rewrites /usr/bin/cat to
  # $PREFIX/bin/cat, so the test needs a prefix link to a glibc cat as well.
  # Copy the binary out of the mapped roots first: a link back to /bin/cat
  # would be rewritten to the prefix again and end in ELOOP. Keep the name
  # "cat": the coreutils of Ubuntu 26 refuse to run under another argv[0].
  for candidate in /bin/cat /usr/bin/cat; do
    if [ -x "$candidate" ] && grep -aq 'ld-linux' "$candidate"; then
      cp -f "$candidate" "$build/cat"
      chmod 0755 "$build/cat"
      export OC_TEST_GLIBC_CAT=$build/cat
      break
    fi
  done
fi

# find_loader() looks for the exact soname in $PREFIX/glibc/lib. The symlink
# points at the loader of the host, which is the only one present in CI. The
# tests use the symlink rather than the host path: the FHS mapping would rewrite
# a resolved /usr/lib/... loader path into the temporary prefix.
if [ -n "$loader_src" ] && [ -e "$loader_src" ]; then
  loader_src=$(readlink -f "$loader_src")
  mkdir -p "$OC_PREFIX/glibc/lib"
  ln -sfn "$loader_src" "$OC_PREFIX/glibc/lib/$loader_name"
  export OC_TEST_LOADER="$OC_PREFIX/glibc/lib/$loader_name"
  # --argv0 arrived in glibc 2.33; the routing only adds it when it is present.
  if grep -aq -- '--argv0' "$loader_src"; then
    export OC_TEST_EXPECT_ARGV0=oc-route-fixture
  fi
fi

case "$arch" in
  aarch64)
    # The host qemu is x86_64: exporting LD_PRELOAD would make the host loader
    # load the AArch64 library into qemu itself. -E passes it to the guest only.
    runner=("$qemu" -L "$guest" -E "LD_PRELOAD=$lib")
    # qemu-user re-executes the host loader on execve, so /proc/self/exe is qemu,
    # not the test binary. The exec cases run on x86_64 only.
    export OC_TEST_NO_EXEC=1
    ;;
  x86_64)
    export LD_PRELOAD="$lib"
    runner=()
    ;;
esac

echo "pathmap: testing $arch"
"${runner[@]}" "$testbin"
