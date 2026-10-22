#!/usr/bin/env bash
#
# rollback.sh: put the TV back to the state saved in a manual backup.
#
# Default is a check: it reads the TV, compares it with the backup and prints
# the exact commands that would undo every difference. Nothing is changed.
# --run executes those commands (after you type "yes"), then checks again.
# Apps installed since the backup are uninstalled; Home, keyboard, PatchWall flags
# and accessibility services (Bare's Home button) are put back. --save: new backup.
#
#   bash rollback.sh                       compare against the newest backup
#   bash rollback.sh --backup DIR          compare against a specific backup
#   bash rollback.sh --run                 undo the differences
#   bash rollback.sh --save                save the TV's current state as a new backup
#   bash rollback.sh --serial IP:5555      pick the adb device

if [ -z "${BASH_VERSION:-}" ]; then echo "Run this with bash." >&2; exit 1; fi
set -u -o pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BACKUP="" RUN=0 SERIAL="" SAVE=0 YES=0
# Settings put back by --run, as namespace:key. Others drift on their own and are not compared.
WATCH="global:set_patchwall_default global:patchwall_enable secure:enabled_accessibility_services secure:accessibility_enabled"
while [ $# -gt 0 ]; do
    case $1 in
        --backup) BACKUP=${2:?--backup needs a directory}; shift ;;
        --run) RUN=1 ;;
        --save) SAVE=1 ;;
        --yes) YES=1 ;;
        --serial) SERIAL=${2:?--serial needs a value}; shift ;;
        -h | --help) sed -n '3,16p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

die() { printf '\nSTOPPED: %s\n' "$*" >&2; exit 2; }

if [ $SAVE = 0 ] && [ -z "$BACKUP" ]; then
    BACKUP=$(ls -d "$SCRIPT_DIR"/state/*/manual-backup-* 2>/dev/null | sort | tail -n 1)
    [ -n "$BACKUP" ] || die "No backup found under $SCRIPT_DIR/state/."
fi
[ $SAVE = 1 ] || for f in packages-installed.txt packages-disabled.txt packages-all-incl-uninstalled.txt packages-versions.txt identity.txt getprop.txt; do
    [ -r "$BACKUP/$f" ] || die "$BACKUP/$f is missing; this isn't a complete backup."
done

command -v adb >/dev/null || die "adb isn't installed or isn't on PATH."
if [ -z "$SERIAL" ]; then
    SERIAL=$(adb devices | tr -d '\r' | awk 'NR > 1 && $2 == "device" { print $1 }')
    [ -n "$SERIAL" ] || die "No authorized TV in \"adb devices\". Run: adb connect <tv-ip>:5555"
    [ "$(printf '%s\n' "$SERIAL" | wc -l)" -eq 1 ] || die "More than one device; pick one with --serial."
fi
a() { adb -s "$SERIAL" shell "$@" 2>&1 | tr -d '\r'; }
[ "$(a echo ok)" = ok ] || die "Can't open a shell on $SERIAL."

if [ $SAVE = 1 ]; then
    sn=$(a getprop ro.serialno | tr -cd 'A-Za-z0-9._-')
    [ -n "$sn" ] || die "The TV didn't report a serial number."
    D=$SCRIPT_DIR/state/$sn/manual-backup-$(date +%Y%m%d-%H%M%S)
    mkdir -p "$D" || die "Can't create $D."
    a pm list packages -f >"$D/packages-f.txt"
    a pm list packages -d >"$D/packages-disabled.txt"
    a pm list packages -e >"$D/packages-enabled.txt"
    a pm list packages -u >"$D/packages-all-incl-uninstalled.txt"
    a pm list packages >"$D/packages-installed.txt"
    a pm list packages -s >"$D/packages-system.txt"
    a pm list packages --show-versioncode >"$D/packages-versions.txt"
    a getprop >"$D/getprop.txt"
    for ns in global secure system; do a settings list $ns >"$D/settings-$ns.txt"; done
    {
        echo "home=$(a cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)"
        echo "ime=$(a settings get secure default_input_method)"
        echo "time=$(date -Iseconds)"
    } >"$D/identity.txt"
    grep -qx 'package:android' "$D/packages-installed.txt" || die "The package manager didn't answer normally; $D is incomplete."
    echo "Saved: $D"
    echo "  $(grep -c . "$D/packages-installed.txt") apps installed, $(grep -c . "$D/packages-disabled.txt") disabled"
    sed 's/^/  /' "$D/identity.txt"
    exit 0
fi

# Refuse to compare against a different TV.
want_sn=$(sed -n 's/^\[ro.serialno\]: \[\(.*\)\]$/\1/p' "$BACKUP/getprop.txt")
have_sn=$(a getprop ro.serialno)
[ "$want_sn" = "$have_sn" ] || die "This TV's serial is \"$have_sn\" but the backup is from \"$want_sn\"."

TMP=$(mktemp -d "${TMPDIR:-/tmp}/rollback.XXXXXX") || die "Can't create a temp dir."
trap 'rm -rf "$TMP"' EXIT

read_tv() {
    a pm list packages >"$TMP/installed"
    a pm list packages -d >"$TMP/disabled"
    a pm list packages -u >"$TMP/all"
    a pm list packages --show-versioncode >"$TMP/versions"
    grep -qx 'package:android' "$TMP/installed" || die "The package manager didn't answer normally."
    HOME_NOW=$(a cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)
    IME_NOW=$(a settings get secure default_input_method)
    : >"$TMP/settings"
    for w in $WATCH; do echo "$w=$(a settings get ${w%%:*} ${w#*:})" >>"$TMP/settings"; done
}

has() { grep -qxF "package:$1" "$2"; }
pkgs() { sed -n 's/^package:\([^ ]*\).*/\1/p' "$1"; }
ver() { sed -n "s/^package:$1 versionCode:\([0-9]*\).*/\1/p" "$2"; }

WANT_HOME=$(sed -n 's/^home=//p' "$BACKUP/identity.txt")
WANT_IME=$(sed -n 's/^ime=//p' "$BACKUP/identity.txt")

# Fills $TMP/cmds (commands that undo a difference) and $TMP/notes (differences
# no adb command can undo). Succeeds only when nothing needs restoring.
compare() {
    : >"$TMP/cmds"
    : >"$TMP/notes"
    local p v0 v1
    for p in $(pkgs "$BACKUP/packages-installed.txt"); do
        if ! has "$p" "$TMP/all"; then
            echo "$p is gone from the TV entirely; only a factory reset or the Play Store brings it back." >>"$TMP/notes"
        elif ! has "$p" "$TMP/installed"; then
            echo "cmd package install-existing --user 0 $p" >>"$TMP/cmds"
        elif has "$p" "$TMP/disabled" && ! has "$p" "$BACKUP/packages-disabled.txt"; then
            echo "pm enable --user 0 $p" >>"$TMP/cmds"
        fi
        v0=$(ver "$p" "$BACKUP/packages-versions.txt")
        v1=$(ver "$p" "$TMP/versions")
        if [ -n "$v0" ] && [ -n "$v1" ] && [ "$v1" -lt "$v0" ]; then
            echo "$p is at version $v1, was $v0 (its updates were removed). The Play Store reinstalls them; open it and check for updates." >>"$TMP/notes"
        fi
    done
    for p in $(pkgs "$BACKUP/packages-disabled.txt"); do
        has "$p" "$TMP/installed" && ! has "$p" "$TMP/disabled" && echo "pm disable-user --user 0 $p" >>"$TMP/cmds"
    done
    # Apps that weren't on the TV at all when the backup was taken. Before Home is restored,
    # so a new launcher is gone by the time the old one is made Home again.
    for p in $(pkgs "$TMP/all"); do
        has "$p" "$BACKUP/packages-all-incl-uninstalled.txt" || echo "pm uninstall $p" >>"$TMP/cmds"
    done
    if [ -n "$WANT_HOME" ] && [ "$HOME_NOW" != "$WANT_HOME" ]; then
        echo "cmd package set-home-activity $WANT_HOME" >>"$TMP/cmds"
    fi
    if [ -n "$WANT_IME" ] && [ "$IME_NOW" != "$WANT_IME" ]; then
        echo "ime set $WANT_IME" >>"$TMP/cmds"
    fi
    if [ -r "$BACKUP/settings-global.txt" ]; then
        local w ns k want have
        for w in $WATCH; do
            ns=${w%%:*} k=${w#*:}
            want=$(sed -n "s/^$k=//p" "$BACKUP/settings-$ns.txt" | head -n 1)
            have=$(sed -n "s/^$w=//p" "$TMP/settings")
            [ -z "$want" ] && want=null
            [ "$want" = "$have" ] && continue
            if [ "$want" = null ]; then echo "settings delete $ns $k" >>"$TMP/cmds"
            else echo "settings put $ns $k $want" >>"$TMP/cmds"; fi
        done
    fi
    [ ! -s "$TMP/cmds" ] && ! grep -q 'gone from the TV' "$TMP/notes"
}

report() {
    echo "Backup: $BACKUP"
    echo "TV:     $SERIAL (serial $have_sn)"
    echo "Home:   now ${HOME_NOW:-none}, backup $WANT_HOME"
    echo "------------------------------------------------------------------------"
    if [ -s "$TMP/cmds" ]; then
        echo "Commands that restore the backup state ($(wc -l <"$TMP/cmds")):"
        sed "s/^/  adb -s $SERIAL shell /" "$TMP/cmds"
    else
        echo "Every app, the Home app and the keyboard match the backup."
    fi
    if [ -s "$TMP/notes" ]; then
        echo ""
        echo "Notes (not fixable by adb):"
        sed 's/^/  - /' "$TMP/notes"
    fi
}

read_tv
compare
report

if [ $RUN = 0 ]; then
    echo ""
    echo "Check only: nothing on the TV was changed."
    [ -s "$TMP/cmds" ] && echo "To undo these differences: bash rollback.sh --run"
    exit 0
fi

[ -s "$TMP/cmds" ] || exit 0
if [ $YES = 0 ]; then
    [ -t 0 ] || die "--run needs a terminal so you can type yes (or --yes, once you've read the list)."
    printf '\nType yes to run the %s command(s) above on the TV: ' "$(wc -l <"$TMP/cmds")"
    read -r answer
    [ "$answer" = yes ] || { echo "Cancelled. Nothing changed."; exit 1; }
fi

echo ""
fail=0
while read -r cmd; do
    # shellcheck disable=SC2086
    out=$(a $cmd)
    printf '  %-70s %s\n' "$cmd" "$(printf '%s' "$out" | tail -n 1)"
done <"$TMP/cmds"

echo ""
echo "Checking again..."
read_tv
if compare; then
    report
    echo ""
    echo "Done: the TV matches the backup. Reboot it (adb reboot) and run this check once more."
else
    report
    echo ""
    echo "Some differences remain (listed above). Run --run again, or re-enable them in Settings > Apps."
    fail=1
fi
exit $fail
