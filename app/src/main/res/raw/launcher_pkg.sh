#!/system/bin/sh
# @MARKER@
#
# pkg - install Termux packages into the app-private prefix.
#
# The prefix has no apt/dpkg. The app downloads the .deb archives of the Termux
# repository, verifies their SHA-256 and extracts them itself. This script only
# talks to the app: it sends a broadcast that starts OpenCodePkgService, then
# follows the status file that the service writes.

P="${PREFIX:-@PREFIX@}"
H="${HOME:-@HOME@}"
case ":$PATH:" in
    *:/system/bin:*) ;;
    *) PATH="${PATH:+$PATH:}/system/bin"; export PATH ;;
esac
APP="com.opencode.android"
USER_ID=@USER_ID@
RECEIVER="$APP/.OpenCodePkgReceiver"
REQUEST="com.opencode.android.pkg.REQUEST"
ACCEPTED=1
DIR="$H/.opencode"
SESSION="pkg-$$"
STATUS="$DIR/$SESSION.status"
RESULT="$DIR/$SESSION.out"
MARK="$P/.packages-installed"

usage() {
    cat <<'USAGE'
Usage: pkg <command> [arguments]

Commands:
  install <package...>   Install Termux packages and their dependencies
  bundle <group...>      Install a curated group: dev, python, node, java, net, all
  search <text>          Search the Termux package repository
  list-installed         List the packages of this prefix
  update, upgrade        Refresh the index (every install uses the live index)
  help                   Show this help

Examples:
  pkg install git gh
  pkg install openjdk
  pkg bundle dev
  pkg search editor
  pkg list-installed

The packages are extracted into the app-private prefix by the app itself, so apt,
dpkg and the Termux app are not needed. 'openjdk' is the same package as
'openjdk-17', 'bundle all' installs every group (it is large and not the whole
repository).
USAGE
}

fail() {
    echo "pkg: $*" >&2
    exit 1
}

pause() {
    sleep 0.2 2>/dev/null || sleep 1
}

valid_name() {
    case "$1" in
        ''|*[!a-zA-Z0-9+._-]*) return 1 ;;
    esac
    return 0
}

field() {
    # field <file> <key>
    [ -f "$1" ] || return 0
    sed -n "s/^$2=//p" "$1" | head -n 1
}

start_service() {
    # start_service <action> [extra 'am' arguments...]
    #
    # Do not use 'am start-service': the ActivityManager shell command sends
    # 'com.android.shell' as the calling package, and Android rejects it when the real
    # caller is this app ("Can't start from invalid source: <uid>"). A broadcast keeps
    # the real UID and the receiver starts the service from inside the app.
    action="$1"
    shift
    rm -f "$STATUS" "$RESULT"
    output=$(/system/bin/am broadcast --user "$USER_ID" -n "$RECEIVER" -a "$REQUEST" \
        --es action "$action" --es session "$SESSION" "$@" 2>&1)
    status=$?
    case "$output" in
        *"Broadcast completed: result=$ACCEPTED"*) ;;
        *"Broadcast completed"*)
            fail "the app did not accept the request: $output" ;;
        *Error*|*Exception*|*SecurityException*)
            fail "the app refused the request: $output" ;;
    esac
    [ "$status" -eq 0 ] || fail "the activity manager failed: $output"
}

# Termux package names that users commonly get wrong. 'openjdk' is not a package in the
# Termux repository, the JDK is built as 'openjdk-17' (or -21/-25).
alias_for() {
    case "$1" in
        openjdk|java|javac|jdk) echo openjdk-17 ;;
        node) echo nodejs ;;
        pip|pip3) echo python-pip ;;
        *) echo "$1" ;;
    esac
}

BUNDLE_DEV="clang make cmake ninja pkg-config autoconf automake libtool patchelf git gh ripgrep fd jq yq tree file less nano vim bat diffutils findutils sed gawk grep tar xz-utils zstd 7zip unzip zip curl wget openssh rsync tmux htop procps lsof ncurses-utils bash-completion sqlite"
BUNDLE_PYTHON="python python-pip python-cryptography python-numpy python-pillow"
BUNDLE_NODE="nodejs npm yarn pnpm"
BUNDLE_JAVA="openjdk-17 ca-certificates-java"
BUNDLE_NET="curl wget openssh rsync nmap dnsutils whois net-tools socat"

bundle_list() {
    case "$1" in
        dev) echo "$BUNDLE_DEV" ;;
        python) echo "$BUNDLE_PYTHON" ;;
        node) echo "$BUNDLE_NODE" ;;
        java) echo "$BUNDLE_JAVA" ;;
        net) echo "$BUNDLE_NET" ;;
        all) echo "$BUNDLE_DEV $BUNDLE_PYTHON $BUNDLE_NODE $BUNDLE_JAVA $BUNDLE_NET" ;;
        *) return 1 ;;
    esac
}


wait_for_service() {
    waited=0
    while [ ! -f "$STATUS" ]; do
        pause
        waited=$((waited + 1))
        [ "$waited" -lt 150 ] || fail "the app did not answer. Bring OpenCode to the foreground and try again."
    done

    last=""
    idle=0
    while :; do
        state=$(field "$STATUS" state)
        message=$(field "$STATUS" message)
        percent=$(field "$STATUS" percent)
        if [ -n "$message" ] && [ "$message" != "$last" ]; then
            case "$percent" in
                ''|0) echo "$message" ;;
                *) echo "[$percent%] $message" ;;
            esac
            last="$message"
            idle=0
        else
            idle=$((idle + 1))
        fi
        case "$state" in
            done) return 0 ;;
            error) echo "pkg: $message" >&2; return 1 ;;
        esac
        [ "$idle" -lt 7200 ] || fail "no progress for 30 minutes, giving up. Check the notification of the app."
        pause
    done
}

add_package() {
    case " $packages " in
        *" $1 "*) ;;
        *) packages="$packages $1" ;;
    esac
}

pkg_install() {
    packages=""
    for argument in "$@"; do
        case "$argument" in
            -y|--yes|-f|--fix-broken) continue ;;
            -*) fail "install: unsupported option '$argument'" ;;
        esac
        package=$(alias_for "$argument")
        valid_name "$package" || fail "install: '$argument' is not a valid package name"
        add_package "$package"
        # The JDK ships a trust store that is empty for real CAs; ca-certificates-java adds
        # lib/security/jssecacerts, which Java prefers. It is only a Recommends of openjdk-17,
        # so add it here to make HTTPS from Java/Gradle work out of the box.
        case "$package" in
            openjdk-*) add_package ca-certificates-java ;;
        esac
    done
    [ -n "$packages" ] || fail "install: no package name given. Try: pkg install git gh"
    [ -x "$P/bin/bash" ] || fail "the Linux runtime is not installed yet. Open the app first."

    echo "Installing$packages ..."
    start_service install --es packages "$packages"
    wait_for_service || return 1
    echo
    echo "Installed in $P."
    echo "For the new commands in this shell run: . \"$P/etc/opencode-env.sh\""
}

pkg_bundle() {
    [ "$#" -ge 1 ] || fail "bundle: no group given. Try: pkg bundle dev"
    packages=""
    for group in "$@"; do
        case "$group" in
            dev|python|node|java|net|all) ;;
            *) fail "bundle: unknown group '$group'. Try: dev, python, node, java, net, all" ;;
        esac
        packages="$packages $(bundle_list "$group")"
    done
    set -- $packages
    pkg_install "$@"
}

pkg_search() {
    [ "$#" -ge 1 ] || fail "search: no search text given. Try: pkg search git"
    query="$*"
    echo "Searching for '$query' ..."
    start_service search --es query "$query"
    wait_for_service || return 1
    echo
    if [ -s "$RESULT" ]; then
        cat "$RESULT"
    else
        echo "No package matched '$query'."
    fi
}

pkg_list() {
    if [ ! -s "$MARK" ]; then
        echo "No package is installed yet."
        return 0
    fi
    sort -u "$MARK"
}

pkg_update() {
    echo "Every installation downloads the live package index, there is nothing to refresh."
    echo "Run 'pkg install <package>' to install or upgrade a package."
}

umask 077
mkdir -p "$DIR" 2>/dev/null
trap 'rm -f "$STATUS" "$RESULT"' EXIT

command="${1:-help}"
[ "$#" -gt 0 ] && shift

case "$command" in
    install|add) pkg_install "$@" ;;
    bundle|toolbox) pkg_bundle "$@" ;;
    search|find) pkg_search "$@" ;;
    list-installed|list|installed) pkg_list ;;
    update|upgrade) pkg_update ;;
    remove|uninstall|delete)
        fail "'$command' is not supported: the app installs packages without dpkg, so there is no package database to remove from. Clear the app data to start over." ;;
    files|show)
        fail "'$command' is not supported: the packages are extracted directly into $P, without a dpkg database." ;;
    help|-h|--help|'') usage ;;
    *) echo "pkg: unknown command '$command'" >&2; usage >&2; exit 2 ;;
esac
