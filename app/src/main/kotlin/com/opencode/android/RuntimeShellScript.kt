package com.opencode.android

/**
 * The generated `etc/opencode-env.sh`.
 *
 * Every exported variable comes from [RuntimeContract.shellExports], the same map that becomes the
 * server's `ProcessBuilder.environment()`. This object only adds the parts a process environment
 * cannot express: shell functions, mkdir side effects and fallbacks for interpreters whose
 * compiled-in paths point at the com.termux prefix.
 *
 * Keep this file free of `export KEY=...` lines for anything already in `RuntimeContract.env`:
 * a second copy of the environment is exactly what caused the layers to drift apart.
 */
object RuntimeShellScript {

    private const val HEADER =
        "# Managed by the OpenCode for Android app (package environment v5).\n" +
            "# Sourced by etc/profile and etc/bash.bashrc of the runtime.\n" +
            "# Variables are exported by RuntimeContract; only shell behaviour follows.\n"

    fun render(contract: RuntimeContract): String = buildString {
        append(HEADER)
        append(contract.shellExports())
        append(shellOnly(contract))
    }

    /** Shell-only logic. Nothing here may assign a variable that [RuntimeContract.env] owns. */
    fun shellOnly(contract: RuntimeContract): String = buildString {
        // The two roots of the sandbox, used by liboc-pathmap.so and oc-gopatch when a program
        // that was downloaded later still contains the Termux paths. Neither is a process env
        // variable: they are hints for the path translation preload. Their values come from the
        // contract, never from a second set of app constants.
        val prefix = contract.env.getValue("PREFIX")
        val home = contract.env.getValue("HOME")
        append("OC_PREFIX=\"\${OC_PREFIX:-").append(prefix).append("}\"; export OC_PREFIX\n")
        append("OC_USER_HOME=\"\${OC_USER_HOME:-").append(home).append("}\"; export OC_USER_HOME\n")
        append("[ -d \"\$TMPDIR\" ] || mkdir -p \"\$TMPDIR\" 2>/dev/null\n")
        // targetSdk 36 cannot execve() files in app data. The bionic exec shim from
        // nativeLibraryDir is the replacement for the old "unset LD_PRELOAD" rule: shells
        // carry it, glibc children drop it before ld-linux starts (the shim does that in
        // its execve route, liboc-pathmap.so is passed with --preload there).
        val shim = contract.execShim
        if (shim != null) {
            append("LD_PRELOAD='").append(RuntimeContract.shellQuote(shim)).append("'; export LD_PRELOAD\n")
        } else {
            append("unset LD_PRELOAD\n")
        }
        // `pkg` is the bionic bridge of the app. The Android linker resolves the platform
        // libraries it needs (libunwindstack pulls libz and liblzma) through LD_LIBRARY_PATH too,
        // and the copies of this prefix are not the ones Android ships: without clearing it the
        // command dies with `cannot locate symbol "Xzs_Construct"`. Only the library path is
        // cleared; LD_PRELOAD must stay so the W^X shim reaches the bridge and its children.
        append("pkg() { LD_LIBRARY_PATH= command pkg \"\$@\"; }\n")
        // The same applies to the Android commands that the extractor deliberately does not
        // shadow with the termux-tools wrappers. /system/bin/curl, for example, links against
        // the OpenSSL of the platform and dies with `cannot locate symbol "EVP_MD_CTX_create"`
        // when it picks up the OpenSSL 3 of this prefix instead.
        append("for _oc_cmd in am bmgr bu cmd content device_config dpm dumpsys getprop ime input log logcat media mount notify pm settings setprop sm svc uiautomator umount wm start stop; do\n")
        append("  eval \"\$_oc_cmd() { LD_LIBRARY_PATH= command \$_oc_cmd \\\"\\\$@\\\"; }\"\n")
        append("done\n")
        append("unset _oc_cmd\n")
        // Only wrap curl while the prefix has no curl of its own: a Termux curl needs the
        // libraries of the prefix, the Android one must not see them.
        append("if [ ! -x \"\$PREFIX/bin/curl\" ]; then\n")
        append("  curl() { LD_LIBRARY_PATH= command curl \"\$@\"; }\n")
        append("fi\n")
        append("# npm and pip keep their caches in the app HOME, the prefix may be read only for them.\n")
        append("npm_config_cache=\"\${npm_config_cache:-\${HOME:-\$PREFIX}/.npm}\"; export npm_config_cache\n")
        append("PIP_CACHE_DIR=\"\${PIP_CACHE_DIR:-\${HOME:-\$PREFIX}/.cache/pip}\"; export PIP_CACHE_DIR\n")
        // Perl was built with @INC under /data/data/com.termux/files/usr, so every module and
        // the scripts that use them (cowsay, …) fail with "Can't locate …". PERL5LIB prepends
        // the version and architecture directories of this prefix; the stale entries stay in
        // @INC and are simply not found.
        append("if [ -d \"\$PREFIX/lib/perl5\" ]; then\n")
        append("  _oc_perl=\n")
        append("  for _oc_ver in \"\$PREFIX\"/lib/perl5/site_perl/* \"\$PREFIX\"/lib/perl5/*; do\n")
        append("    [ -d \"\$_oc_ver\" ] || continue\n")
        append("    case \"\${_oc_ver##*/}\" in [0-9]*) ;; *) continue ;; esac\n")
        append("    _oc_perl=\"\${_oc_perl:+\$_oc_perl:}\$_oc_ver\"\n")
        append("    for _oc_arch in \"\$_oc_ver\"/*-android; do\n")
        append("      [ -d \"\$_oc_arch\" ] && _oc_perl=\"\${_oc_perl:+\$_oc_perl:}\$_oc_arch\"\n")
        append("    done\n")
        append("  done\n")
        append("  if [ -n \"\$_oc_perl\" ]; then PERL5LIB=\"\${PERL5LIB:+\$PERL5LIB:}\$_oc_perl\"; export PERL5LIB; fi\n")
        append("  unset _oc_perl _oc_ver _oc_arch\n")
        append("fi\n")
        // Ruby has the same problem: the compiled $LOAD_PATH starts with the com.termux prefix,
        // so `require "json"` and `gem` fail even though the files are here. RUBYLIB prepends the
        // site, vendor and core directories of this prefix, with their architecture subdirectory.
        append("if [ -d \"\$PREFIX/lib/ruby\" ]; then\n")
        append("  _oc_ruby=\n")
        append("  for _oc_base in \"\$PREFIX/lib/ruby/site_ruby\" \"\$PREFIX/lib/ruby/vendor_ruby\" \"\$PREFIX/lib/ruby\"; do\n")
        append("    [ -d \"\$_oc_base\" ] || continue\n")
        append("    for _oc_ver in \"\$_oc_base\"/*; do\n")
        append("      [ -d \"\$_oc_ver\" ] || continue\n")
        append("      case \"\${_oc_ver##*/}\" in [0-9]*) ;; *) continue ;; esac\n")
        append("      _oc_ruby=\"\${_oc_ruby:+\$_oc_ruby:}\$_oc_ver\"\n")
        append("      for _oc_arch in \"\$_oc_ver\"/*-android; do\n")
        append("        [ -d \"\$_oc_arch\" ] && _oc_ruby=\"\${_oc_ruby:+\$_oc_ruby:}\$_oc_arch\"\n")
        append("      done\n")
        append("    done\n")
        append("    _oc_ruby=\"\${_oc_ruby:+\$_oc_ruby:}\$_oc_base\"\n")
        append("  done\n")
        append("  if [ -n \"\$_oc_ruby\" ]; then RUBYLIB=\"\${RUBYLIB:+\$RUBYLIB:}\$_oc_ruby\"; export RUBYLIB; fi\n")
        append("  case \":\${RUBYOPT:-}:\" in\n")
        append("    *-rrubygems*) ;;\n")
        append("    *) RUBYOPT=\"-rrubygems\${RUBYOPT:+ \$RUBYOPT}\"; export RUBYOPT ;;\n")
        append("  esac\n")
        append("  unset _oc_ruby _oc_base _oc_ver _oc_arch\n")
        append("fi\n")
        // A sourced profile ends with the result of the last test, which may be false. The
        // colon keeps `source etc/opencode-env.sh` successful in every shell.
        append(":\n")
    }
}
