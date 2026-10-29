#!/system/bin/sh
# @MARKER@
#
# pkg - install Termux packages into the app-private prefix.
#
# The prefix has no apt/dpkg. The app downloads the .deb archives of the Termux
# repository, verifies their SHA-256 and extracts them itself. This script only
# talks to the app: it starts OpenCodePkgService through 'am' and follows the
# status file that the service writes.

P="${PREFIX:-@PREFIX@}"
H="${HOME:-@HOME@}"
case ":$PATH:" in
    *:/system/bin:*) ;;
    *) PATH="${PATH:+$PATH:}/system/bin"; export PATH ;;
esac
APP="com.opencode.android"
USER_ID=@USER_ID@
SVC="$APP/.OpenCodePkgService"
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
  search <text>          Search the Termux package repository
  list-installed         List the packages of this prefix
  update, upgrade        Refresh the index (every install uses the live index)
  help                   Show this help

Examples:
  pkg install git gh
  pkg search editor
  pkg list-installed

The packages are extracted into the app-private prefix by the app itself, so apt,
dpkg and the Termux app are not needed.
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
    action="$1"
    shift
    rm -f "$STATUS" "$RESULT"
    output=$(/system/bin/am start-foreground-service --user "$USER_ID" -n "$SVC" --es action "$action" --es session "$SESSION" "$@" 2>&1)
    status=$?
    case "$output" in
        *Error*|*Exception*|*SecurityException*)
            fail "the app refused the request: $output" ;;
    esac
    [ "$status" -eq 0 ] || fail "the activity manager failed: $output"
}

wait_for_service() {
    waited=0
    while [ ! -f "$STATUS" ]; do
        pause
        waited=$((waited + 1))
        [ "$waited" -lt 50 ] || fail "the app did not answer. Bring OpenCode to the foreground and try again."
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

pkg_install() {
    packages=""
    for argument in "$@"; do
        case "$argument" in
            -y|--yes|-f|--fix-broken) continue ;;
            -*) fail "install: unsupported option '$argument'" ;;
        esac
        valid_name "$argument" || fail "install: '$argument' is not a valid package name"
        packages="$packages $argument"
    done
    [ -n "$packages" ] || fail "install: no package name given. Try: pkg install git gh"
    [ -x "$P/bin/bash" ] || fail "the Linux runtime is not installed yet. Open the app first."

    echo "Installing$packages ..."
    start_service install --es packages "$packages"
    wait_for_service || return 1
    echo
    echo "Installed in $P."
    echo "For git in this shell run: . \"$P/etc/opencode-env.sh\""
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
