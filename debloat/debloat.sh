#!/usr/bin/env bash
#
# debloat.sh: reversible debloat for the Xiaomi MiTV-AXSO0 (Android TV 9, API 28).
#
# Dry run by default: it reads the TV and prints a plan, and changes nothing.
# --apply disables packages with `pm disable-user --user 0`; --restore undoes it.
# Nothing is deleted from the TV. Read README.md before the first --apply.

# Refuse sh/dash/zsh before any bash-only syntax is parsed.
if [ -z "${BASH_VERSION:-}" ]; then
    echo "Run this with bash: bash debloat.sh $*" >&2
    exit 1
fi

set -u
set -o pipefail

BARE_PKG=dev.bare.launcher
BARE_HOME=dev.bare.launcher/.MainActivity
BARE_HOME_LONG=dev.bare.launcher/dev.bare.launcher.MainActivity
EXPECT_MODEL=MiTV-AXSO0
EXPECT_SDK=28
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# Exit codes
E_USAGE=1 E_ADB=2 E_IDENTITY=3 E_GATE=4 E_PARTIAL=5 E_INT=130

# ---------------------------------------------------------------- catalogue
# "package|tier|note", exact names only, no wildcards. Gathered from community
# Mi TV debloat lists and the cleanyourtv MiTV-AXSO0 list. Not verified on this
# unit: anything the TV doesn't have is reported and skipped.
catalogue() {
    cat <<'EOF'
com.miui.tv.analytics|1|Xiaomi analytics
com.xiaomi.statistic|1|Xiaomi usage statistics
com.xm.webcontent|1|PatchWall web content and promos
com.mitv.tvhome.michannel|1|Sponsored PatchWall channels
com.mitv.tvhome.mitvplus|1|Mi TV+ sponsored live channels
com.xiaomi.android.tvsetup.partnercustomizer|1|Partner preloads and promos
android.autoinstalls.config.xioami.dangal|1|Auto-installs partner apps through Play (sic)
com.glance.tv|1|Glance news and ads on the standby screen
co.sensara.tv.mitv|1|Sensara sponsored TV guide
com.xiaomi.floatingframe|1|Promotional overlay frame
com.google.android.tvrecommendations|1|Recommendation rows feed
com.google.android.leanbacklauncher.recommendations|1|Recommendation rows feed
com.google.android.feedback|1|Google feedback reporter
com.google.android.tv.bugreportsender|1|Bug report uploader
com.mitv.dream|2|Xiaomi screensaver; Android falls back to its own
com.xiaomi.mitv.tvmanager|2|"TV Manager" cleaner
com.xiaomi.mitv.handbook|2|Xiaomi handbook
com.xiaomo.tv.milegal|2|Legal text screens (sic)
com.xiaomi.mimusic2|2|Mi Music
com.mitv.gallery|2|Mi Gallery
com.mitv.videoplayer|2|Mi video player; keep it if you play videos from USB
com.google.android.videos|3|Play Movies & TV
com.google.android.music|3|Play Music (discontinued)
com.google.android.play.games|3|Play Games
com.google.android.youtube.tvmusic|3|YouTube Music
com.google.android.partnersetup|3|Partner setup helper
com.netflix.ninja|4|Netflix; the remote's Netflix button stops working
com.amazon.amazonvideo.livingroom|4|Prime Video; the remote's Prime button stops working
com.hulu.livingroomplus|4|Hulu
com.cbs.ott|4|Paramount+
com.sling|4|Sling TV
com.pandora.android.atv|4|Pandora
com.showtime.standalone|4|Showtime
com.nousguide.android.rbtv|4|Red Bull TV
com.fancode.tv|4|FanCode
com.suntv.sunnxt.mitv|4|Sun NXT
com.eros.mitv|4|Eros Now
com.discoveryplus.mitv|4|discovery+
com.ddnational.prasarbharati|4|Prasar Bharati
net.aljazeera.english|4|Al Jazeera
com.google.android.katniss|5|Voice search; the mic button stops working
com.google.android.speech.pumpkin|5|Offline voice commands
com.google.android.apps.mediashell|5|Chromecast built-in; phones can't cast to the TV
com.mitv.milinkservice|5|Mi Cast
com.xiaomi.mitv.smartshare|5|Xiaomi screen share
com.xiaomi.wfdsinkhelperservice|5|Miracast receiver
com.mitv.tvhome.atv|6|PatchWall home screen
com.google.android.tvlauncher|6|Stock Android TV home screen
com.google.android.leanbacklauncher|6|Older Android TV home; firmware may re-enable it at boot
com.xiaomi.mitv.updateservice|U|Firmware updates; re-enable it to check for one
EOF
}

tier_name() {
    case $1 in
        1) echo "Ads and tracking" ;;
        2) echo "Xiaomi extras" ;;
        3) echo "Google extras" ;;
        4) echo "Preloaded streaming apps" ;;
        5) echo "Voice and casting" ;;
        6) echo "Stock home screens (gated on Bare being Home)" ;;
        U) echo "Firmware updates" ;;
    esac
}

# Prints why $1 must never be touched and succeeds, or fails if it is fair game.
# Applies to everything, including names passed with --only (D6).
protected_reason() {
    local p=$1
    case $p in
        android | com.android.*) echo "core Android" ;;
        com.google.android.gms* | com.google.android.gsf*) echo "Play Services" ;;
        com.droidlogic.* | com.mediatek.* | com.amlogic.* | com.realtek.* | com.mstar.*) echo "chipset" ;;
        "$BARE_PKG") echo "Bare itself" ;;
        *tvinput* | *hdmi* | *tvsettings* | *.tv.settings*) echo "TV inputs or settings" ;;
        *inputmethod* | *.ime | *.ime.* | *keyboard*) echo "keyboard" ;;
        *remote* | *bluetooth*) echo "remote control" ;;
        *setupwraith* | *tungsten*) echo "setup wizard; can take over Home and crash-loop" ;;
        *webview* | *packageinstaller* | *permissioncontroller* | *systemui* | *provider*) echo "system component" ;;
        *)
            if [ -n "${IME_PKG:-}" ] && [ "$p" = "$IME_PKG" ]; then
                echo "the keyboard in use"
            else
                return 1
            fi
            ;;
    esac
    return 0
}

# ---------------------------------------------------------------- output
say() { printf '%s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die() {
    local code=$1
    shift
    printf '\nSTOPPED: %s\n' "$*" >&2
    exit "$code"
}
rule() { say "------------------------------------------------------------------------"; }

usage() {
    cat <<EOF
Usage: bash debloat.sh [mode] [options]

Modes (pick one; the default is a dry run):
  --dry-run          Read the TV and print what --apply would do. Changes nothing.
  --apply            Disable the planned packages (asks you to type "yes" first).
  --restore          Re-enable every package this script can touch.
  --status           Show the state of every package in the catalogue.
  --list             Print the catalogue and exit. No TV needed.

Choosing packages:
  --tiers 1,2,3      Tiers to act on (default 1,2,3). Tiers:
                       1 ads and tracking     4 preloaded streaming apps
                       2 Xiaomi extras        5 voice and casting
                       3 Google extras        6 stock home screens (gated)
  --updates          Also disable firmware updates (com.xiaomi.mitv.updateservice).
  --only a,b         Act on just these catalogue packages (ignores --tiers).
  --keep a,b         Never touch these, whatever the tiers say.

Safety and recovery:
  --set-home         With --apply: make Bare the Home app before tier 6.
  --home-to PKG      With --restore: make PKG the Home app again afterwards
                     (for example com.google.android.tvlauncher).
  --uninstall-fallback
                     If disable-user is refused, use "pm uninstall -k --user 0".
                     Still reversible; off by default.
  --restore-all      With --restore: also re-enable packages that were already
                     disabled before this script first ran.

Device:
  --serial S         Use this adb device (needed when more than one is connected).
  --force-model      Continue when the model is not $EXPECT_MODEL.
  --force-sdk        Continue when the Android SDK level is not $EXPECT_SDK.
  --offline FILE     Dry run against a saved "pm list packages" output. No adb.
  --state-dir DIR    Where logs and snapshots go (default: $SCRIPT_DIR/state).
  --yes              Don't ask for confirmation (needed when not on a terminal).
  -h, --help         This text.

Exit codes: 0 ok, 1 usage or cancelled, 2 adb or device problem, 3 wrong TV,
4 Home check refused tier 6, 5 some packages failed, 130 interrupted.
EOF
}

# ---------------------------------------------------------------- arguments
MODE=dry
TIERS=1,2,3
UPDATES=0
ONLY=""
KEEP=""
SET_HOME=0
HOME_TO=""
UNINSTALL_FALLBACK=0
RESTORE_ALL=0
SERIAL=""
FORCE_MODEL=0
FORCE_SDK=0
OFFLINE=""
STATE_DIR=$SCRIPT_DIR/state
YES=0
mode_set=0
RERUN=""
for a in "$@"; do [ "$a" = --dry-run ] || RERUN="$RERUN $a"; done

set_mode() {
    [ $mode_set = 1 ] && [ "$MODE" != "$1" ] && die $E_USAGE "Pick one mode: --dry-run, --apply, --restore, --status or --list."
    MODE=$1
    mode_set=1
}

need_arg() { [ $# -ge 2 ] && [ -n "$2" ] && [ "${2#--}" = "$2" ] || die $E_USAGE "$1 needs a value."; }

while [ $# -gt 0 ]; do
    case $1 in
        --dry-run) set_mode dry ;;
        --apply) set_mode apply ;;
        --restore) set_mode restore ;;
        --status) set_mode status ;;
        --list) set_mode list ;;
        --tiers) need_arg "$@"; TIERS=$2; shift ;;
        --updates) UPDATES=1 ;;
        --only) need_arg "$@"; ONLY=$2; shift ;;
        --keep) need_arg "$@"; KEEP=$2; shift ;;
        --set-home) SET_HOME=1 ;;
        --home-to) need_arg "$@"; HOME_TO=$2; shift ;;
        --uninstall-fallback) UNINSTALL_FALLBACK=1 ;;
        --restore-all) RESTORE_ALL=1 ;;
        --serial) need_arg "$@"; SERIAL=$2; shift ;;
        --force-model) FORCE_MODEL=1 ;;
        --force-sdk) FORCE_SDK=1 ;;
        --offline) need_arg "$@"; OFFLINE=$2; shift ;;
        --state-dir) need_arg "$@"; STATE_DIR=$2; shift ;;
        --yes) YES=1 ;;
        -h | --help) usage; exit 0 ;;
        *) usage >&2; die $E_USAGE "Unknown option: $1" ;;
    esac
    shift
done

# Validate the combinations up front, before touching anything.
case $TIERS in
    *[!1-6,]* | "" | ,* | *, | *,,*) die $E_USAGE "--tiers takes digits 1 to 6 separated by commas, e.g. --tiers 1,2,3" ;;
esac
[ -n "$OFFLINE" ] && [ "$MODE" != dry ] && die $E_USAGE "--offline only works with a dry run."
[ -n "$OFFLINE" ] && [ ! -r "$OFFLINE" ] && die $E_USAGE "Can't read $OFFLINE."
[ $SET_HOME = 1 ] && [ "$MODE" != apply ] && [ "$MODE" != dry ] && die $E_USAGE "--set-home goes with --apply."
[ -n "$HOME_TO" ] && [ "$MODE" != restore ] && die $E_USAGE "--home-to goes with --restore."
[ $RESTORE_ALL = 1 ] && [ "$MODE" != restore ] && die $E_USAGE "--restore-all goes with --restore."

valid_name() { printf '%s' "$1" | grep -Eq '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'; }
in_catalogue() { catalogue | cut -d'|' -f1 | grep -qxF "$1"; }
in_csv() { case ",$2," in *",$1,"*) return 0 ;; esac; return 1; }

for p in $(printf '%s' "$ONLY,$KEEP" | tr ',' ' '); do
    valid_name "$p" || die $E_USAGE "\"$p\" is not a valid package name."
done
for p in $(printf '%s' "$ONLY" | tr ',' ' '); do
    in_catalogue "$p" || die $E_USAGE "$p is not in the catalogue (see --list). The script only touches listed packages."
    r=$(protected_reason "$p") && die $E_USAGE "$p is protected ($r)."
done
[ -n "$HOME_TO" ] && { valid_name "$HOME_TO" || die $E_USAGE "--home-to needs a package name."; }

if [ "$MODE" = list ]; then
    for t in 1 2 3 4 5 6 U; do
        say ""
        say "Tier $t: $(tier_name $t)"
        catalogue | awk -F'|' -v t=$t '$2==t { printf "  %-52s %s\n", $1, $3 }'
    done
    exit 0
fi

# ---------------------------------------------------------------- adb plumbing
ADB=(adb)
TMP=$(mktemp -d "${TMPDIR:-/tmp}/debloat.XXXXXX") || die $E_USAGE "Can't create a temp directory."
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT
interrupted() {
    printf '\n\nINTERRUPTED. Each step is a single pm command, so nothing is half-done.\n' >&2
    printf 'Run "bash debloat.sh --status" to see where things stand.\n' >&2
    exit $E_INT
}
trap interrupted INT TERM

# adb output on older versions ends lines with \r; strip it everywhere.
adbq() { "${ADB[@]}" "$@" 2>&1 | tr -d '\r'; }
dsh() { adbq shell "$@"; }

pick_device() {
    command -v adb >/dev/null 2>&1 || die $E_ADB "adb isn't installed or isn't on PATH. Install Android platform-tools first."
    local out rows n state tries=0
    while :; do
        out=$(adb devices 2>&1 | tr -d '\r') || die $E_ADB "\"adb devices\" failed:
$out"
        # Skip the daemon chatter ("* daemon started") and the header line.
        rows=$(printf '%s\n' "$out" | awk 'NF >= 2 && $1 !~ /^\*/ && $1 != "List" { print $1, $2 }')
        if [ -n "$SERIAL" ]; then
            state=$(printf '%s\n' "$rows" | awk -v s="$SERIAL" '$1 == s { print $2 }')
            [ -n "$state" ] || die $E_ADB "No device $SERIAL in \"adb devices\". If it is a network TV, run: adb connect $SERIAL"
        else
            n=$(printf '%s\n' "$rows" | grep -c .)
            [ "$n" -eq 0 ] && die $E_ADB "No TV found by adb. Turn on USB/network debugging on the TV, then: adb connect <tv-ip>:5555"
            [ "$n" -gt 1 ] && die $E_ADB "More than one device is connected; pick one with --serial:
$rows"
            SERIAL=${rows%% *}
            state=${rows#* }
        fi
        case $state in
            device) break ;;
            unauthorized) die $E_ADB "The TV hasn't allowed this computer yet. Accept the \"Allow debugging?\" prompt on the TV (tick \"Always allow\") and run again." ;;
            offline) die $E_ADB "The TV shows as offline. Run: adb disconnect; adb connect <tv-ip>:5555. If that fails, toggle debugging off and on in the TV's Developer options." ;;
            authorizing | connecting)
                tries=$((tries + 1))
                [ $tries -ge 5 ] && die $E_ADB "The TV is stuck in \"$state\". Reconnect and try again."
                sleep 2 ;;
            *) die $E_ADB "The TV is in state \"$state\", not \"device\". Reboot it normally and reconnect." ;;
        esac
    done
    ADB=(adb -s "$SERIAL")
    [ "$(dsh echo ok)" = ok ] || die $E_ADB "adb can see the TV but can't open a shell on it."
}

prop() { dsh getprop "$1" | head -n 1; }

MODEL="" SDK="" HOME_NOW="" HOME_PKG="" IME_PKG="" DEVICE_KEY=""

identify() {
    MODEL=$(prop ro.product.model)
    SDK=$(prop ro.build.version.sdk)
    rule
    say "TV identity (read-only)"
    say "  adb serial    $SERIAL"
    say "  model         $MODEL"
    say "  device        $(prop ro.product.device)"
    say "  Android       $(prop ro.build.version.release) (SDK $SDK)"
    say "  ABI           $(prop ro.product.cpu.abi)"
    say "  build         $(prop ro.build.display.id)"
    say "  fingerprint   $(prop ro.build.fingerprint)"
    say "  screen        $(dsh wm size | tail -n 1)"
    say "  density       $(dsh wm density | tail -n 1)"

    if [ "$MODEL" != "$EXPECT_MODEL" ]; then
        if [ $FORCE_MODEL = 1 ]; then
            warn "Model is \"$MODEL\", not $EXPECT_MODEL. Continuing because of --force-model."
        else
            die $E_IDENTITY "Model is \"$MODEL\", not $EXPECT_MODEL. This TV had its mainboard swapped, so a
different model string is possible. If you're sure this is the right TV, add --force-model."
        fi
    fi
    if [ "$SDK" != "$EXPECT_SDK" ]; then
        if [ $FORCE_SDK = 1 ]; then
            warn "SDK level is \"$SDK\", not $EXPECT_SDK. Continuing because of --force-sdk."
        else
            die $E_IDENTITY "SDK level is \"$SDK\", not $EXPECT_SDK. The package lists were gathered for Android 9; add --force-sdk to go ahead anyway."
        fi
    fi

    # Snapshots are keyed by the TV's own serial, so a new IP address still finds them.
    DEVICE_KEY=$(prop ro.serialno | tr -cd 'A-Za-z0-9._-')
    [ -n "$DEVICE_KEY" ] || DEVICE_KEY=$(printf '%s' "$SERIAL" | tr -cd 'A-Za-z0-9._-')

    IME_PKG=$(dsh settings get secure default_input_method | head -n 1)
    IME_PKG=${IME_PKG%%/*}
    [ "$IME_PKG" = null ] && IME_PKG=""
    HOME_NOW=$(resolve_home)
    HOME_PKG=${HOME_NOW%%/*}
    say "  Home app      ${HOME_NOW:-none found}"
    say "  keyboard      ${IME_PKG:-none set}"
}

# The activity HOME resolves to, or the chooser when nothing is preferred.
resolve_home() {
    dsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME |
        grep '/' | tail -n 1
}

load_packages() {
    if [ -n "$OFFLINE" ]; then
        tr -d '\r' <"$OFFLINE" | sed -n 's/^\(package:[^ ]*\).*/\1/p' >"$TMP/installed"
        : >"$TMP/disabled"
        cp "$TMP/installed" "$TMP/all"
        grep -q . "$TMP/installed" || die $E_USAGE "$OFFLINE has no \"package:\" lines. Save it with: adb shell pm list packages > packages.txt"
        return
    fi
    dsh pm list packages >"$TMP/installed"
    dsh pm list packages -d >"$TMP/disabled"
    dsh pm list packages -u >"$TMP/all"
    grep -qx 'package:android' "$TMP/installed" ||
        die $E_ADB "The package manager didn't answer normally. First lines of its output:
$(head -n 5 "$TMP/installed")"
}

state_of() {
    if grep -qxF "package:$1" "$TMP/disabled"; then
        echo disabled
    elif grep -qxF "package:$1" "$TMP/installed"; then
        echo enabled
    elif grep -qxF "package:$1" "$TMP/all"; then
        echo uninstalled
    else
        echo absent
    fi
}

healthy() {
    [ "$(adbq get-state)" = device ] && dsh pm path android | grep -q '^package:'
}

# ---------------------------------------------------------------- selection
# Lines of "package|tier|note" in the scope of this run.
selected() {
    catalogue | while IFS='|' read -r p t note; do
        if [ -n "$ONLY" ]; then
            in_csv "$p" "$ONLY" || continue
        elif [ "$MODE" != restore ] && [ "$MODE" != status ]; then
            if [ "$t" = U ]; then
                [ $UPDATES = 1 ] || continue
            else
                in_csv "$t" "$TIERS" || continue
            fi
        fi
        in_csv "$p" "$KEEP" && continue
        printf '%s|%s|%s\n' "$p" "$t" "$note"
    done
}

BASE=""
baseline_has() { [ -n "$BASE" ] && [ -f "$BASE/$2" ] && grep -qxF "package:$1" "$BASE/$2"; }

# Writes "action|package|tier|note" to $TMP/plan and prints the table.
make_plan() {
    : >"$TMP/plan"
    local p t note st r action
    while IFS='|' read -r p t note; do
        st=$(state_of "$p")
        if r=$(protected_reason "$p"); then
            action="protected ($r)"
        elif [ "$MODE" = restore ]; then
            case $st in
                disabled | uninstalled)
                    if [ $RESTORE_ALL = 0 ] && { baseline_has "$p" disabled || baseline_has "$p" uninstalled; }; then
                        action="leave: was $st before this script first ran"
                    else
                        action=restore
                    fi ;;
                enabled) action="ok: already enabled" ;;
                absent) action="skip: not on this TV" ;;
            esac
        else
            case $st in
                enabled)
                    if [ "$t" != 6 ] && [ "$p" = "$HOME_PKG" ]; then
                        action="protected (current Home app)"
                    else
                        action=disable
                    fi ;;
                disabled) action="ok: already disabled" ;;
                uninstalled) action="ok: already removed for user 0" ;;
                absent) action="skip: not on this TV" ;;
            esac
        fi
        printf '%s|%s|%s|%s\n' "$action" "$p" "$t" "$note" >>"$TMP/plan"
    done < <(selected)

    rule
    if [ "$MODE" = restore ]; then say "Restore plan"; else say "Plan"; fi
    printf '  %-5s %-50s %s\n' TIER PACKAGE ACTION
    sort -t'|' -k3,3 "$TMP/plan" | while IFS='|' read -r action p t note; do
        printf '  %-5s %-50s %s\n' "$t" "$p" "$action"
        case $action in disable | restore) printf '  %-5s   %s\n' "" "$note" ;; esac
    done
    TODO=$(grep -cE '^(disable|restore)\|' "$TMP/plan")
    say ""
    say "$TODO package(s) to $([ "$MODE" = restore ] && echo restore || echo disable)."
}

# ---------------------------------------------------------------- tier 6 gate
# Stock home screens may only go once Bare is installed, is Home, and starts (D5).
gate_check_readonly() {
    if ! grep -qxF "package:$BARE_PKG" "$TMP/installed"; then
        echo "Bare is not installed"
    elif grep -qxF "package:$BARE_PKG" "$TMP/disabled"; then
        echo "Bare is installed but disabled"
    elif [ "$HOME_NOW" != "$BARE_HOME" ] && [ "$HOME_NOW" != "$BARE_HOME_LONG" ]; then
        echo "Home resolves to ${HOME_NOW:-nothing}, not Bare"
    fi
}

gate_apply() {
    local why out
    why=$(gate_check_readonly)
    if [ -n "$why" ] && [ "$why" != "${why#Home resolves}" ] && [ $SET_HOME = 1 ]; then
        say "Making Bare the Home app (--set-home)..."
        out=$(dsh cmd package set-home-activity "$BARE_HOME")
        say "  $out"
        HOME_NOW=$(resolve_home)
        HOME_PKG=${HOME_NOW%%/*}
        why=$(gate_check_readonly)
    fi
    if [ -n "$why" ]; then
        GATE_WHY=$why
        return 1
    fi
    say "Starting Home to check that Bare opens..."
    out=$(dsh am start -W -a android.intent.action.MAIN -c android.intent.category.HOME)
    if ! printf '%s' "$out" | grep -q 'Status: ok'; then
        GATE_WHY="Home didn't start cleanly: $(printf '%s' "$out" | tail -n 2 | tr '\n' ' ')"
        return 1
    fi
    sleep 4
    if [ -z "$(dsh pidof "$BARE_PKG")" ]; then
        GATE_WHY="Bare isn't running 4 seconds after starting; it may be crashing"
        return 1
    fi
    if ! dsh dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity' | grep -q "$BARE_PKG"; then
        GATE_WHY="Bare started but isn't the screen in front"
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------- actions
LOGDIR="" ACTLOG=""

open_state() {
    LOGDIR=$STATE_DIR/$DEVICE_KEY
    mkdir -p "$LOGDIR" || die $E_USAGE "Can't create $LOGDIR."
    ACTLOG=$LOGDIR/actions.log
    local stamp
    stamp=$(date +%Y%m%d-%H%M%S)
    # Everything printed from here on is also kept in a run log.
    exec > >(tee -a "$LOGDIR/run-$stamp.log") 2>&1
    # The first snapshot ever taken is the baseline --restore compares against.
    if [ ! -d "$LOGDIR/baseline" ]; then
        mkdir -p "$LOGDIR/baseline"
        snapshot "$LOGDIR/baseline"
    fi
    snapshot "$LOGDIR/before-$stamp"
}

snapshot() {
    cp "$TMP/installed" "$1/installed"
    cp "$TMP/disabled" "$1/disabled"
    grep -vxFf "$TMP/installed" "$TMP/all" >"$1/uninstalled" || true
    {
        echo "model=$MODEL"
        echo "sdk=$SDK"
        echo "fingerprint=$(prop ro.build.fingerprint)"
        echo "home=$HOME_NOW"
        echo "ime=$IME_PKG"
        echo "time=$(date)"
    } >"$1/identity"
}

record() { printf '%s %s %s %s\n' "$(date +%Y-%m-%dT%H:%M:%S)" "$SERIAL" "$1" "$2" >>"$ACTLOG"; }

disable_one() {
    local p=$1 out
    out=$(dsh pm disable-user --user 0 "$p")
    if printf '%s' "$out" | grep -q 'new state: disabled-user'; then
        record disabled "$p"
        say "  disabled     $p"
        return 0
    fi
    if [ $UNINSTALL_FALLBACK = 1 ]; then
        out=$(dsh pm uninstall -k --user 0 "$p")
        if printf '%s' "$out" | grep -q '^Success'; then
            record uninstalled "$p"
            say "  removed      $p (for user 0; the APK stays, --restore brings it back)"
            return 0
        fi
    fi
    say "  FAILED       $p: $(printf '%s' "$out" | tail -n 1)"
    return 1
}

restore_one() {
    local p=$1 st=$2 out
    if [ "$st" = uninstalled ]; then
        out=$(dsh cmd package install-existing --user 0 "$p")
        if printf '%s' "$out" | grep -qi 'installed for user'; then
            record reinstalled "$p"
            say "  reinstalled  $p"
            return 0
        fi
    else
        out=$(dsh pm enable --user 0 "$p")
        if printf '%s' "$out" | grep -q 'new state: enabled'; then
            record enabled "$p"
            say "  enabled      $p"
            return 0
        fi
    fi
    say "  FAILED       $p: $(printf '%s' "$out" | tail -n 1)"
    return 1
}

confirm() {
    [ $YES = 1 ] && return 0
    [ -t 0 ] || die $E_USAGE "Not running on a terminal. Re-run with --yes to confirm."
    local answer
    printf '\nType yes to %s: ' "$1"
    read -r answer || die $E_USAGE "Cancelled. Nothing changed."
    [ "$answer" = yes ] || die $E_USAGE "Cancelled. Nothing changed."
}

# ---------------------------------------------------------------- modes
if [ -n "$OFFLINE" ]; then
    say "Offline dry run against $OFFLINE. Disabled state and Home are unknown offline."
    load_packages
else
    pick_device
    identify
    load_packages
fi

if [ "$MODE" != dry ] || [ -z "$OFFLINE" ]; then
    [ -n "$DEVICE_KEY" ] && [ -d "$STATE_DIR/$DEVICE_KEY/baseline" ] && BASE=$STATE_DIR/$DEVICE_KEY/baseline
fi

if [ "$MODE" = status ]; then
    rule
    say "Status of every catalogue package"
    printf '  %-5s %-50s %s\n' TIER PACKAGE STATE
    catalogue | while IFS='|' read -r p t note; do
        st=$(state_of "$p")
        r=$(protected_reason "$p") && st="$st, protected ($r)"
        baseline_has "$p" disabled && st="$st, disabled at baseline"
        printf '  %-5s %-50s %s\n' "$t" "$p" "$st"
    done
    [ -n "$BASE" ] || say "No baseline yet: this script hasn't changed anything on this TV."
    exit 0
fi

make_plan

if [ "$MODE" = dry ]; then
    if grep -qE '^disable\|[^|]*\|6\|' "$TMP/plan"; then
        rule
        if [ -n "$OFFLINE" ]; then
            say "Tier 6 needs a live check that Bare is Home; it can't be judged offline."
        else
            why=$(gate_check_readonly)
            if [ -z "$why" ]; then
                say "Tier 6: Bare is installed and is Home. --apply will also test that it starts."
            elif [ $SET_HOME = 1 ] && [ "$why" != "${why#Home resolves}" ]; then
                say "Tier 6: $why. --apply --set-home would make Bare Home first, then test it."
            else
                say "Tier 6 would be REFUSED: $why. The other tiers would still run."
            fi
        fi
    fi
    if [ "$HOME_PKG" = com.mitv.tvhome.atv ]; then
        say ""
        say "Note: PatchWall is Home right now. If it misbehaves after tier 1, run --restore."
    fi
    rule
    say "Dry run: nothing on the TV was changed."
    [ "$TODO" -gt 0 ] && say "To do it: bash debloat.sh --apply$RERUN"
    exit 0
fi

[ "$TODO" -eq 0 ] && { say "Nothing to do."; exit 0; }

FAILED=0
GATE_REFUSED=0
if [ "$MODE" = apply ]; then
    confirm "disable $TODO package(s)"
    open_state
    rule
    # Lowest tier first, stock home screens last and only through the gate.
    for t in 1 2 3 4 5 U 6; do
        pkgs=$(awk -F'|' -v t=$t '$1 == "disable" && $3 == t { print $2 }' "$TMP/plan")
        [ -n "$pkgs" ] || continue
        if [ $t = 6 ]; then
            GATE_WHY=""
            if ! gate_apply; then
                say "Tier 6 REFUSED: $GATE_WHY. Stock home screens were left alone."
                GATE_REFUSED=1
                break
            fi
        fi
        say "Tier $t: $(tier_name $t)"
        for p in $pkgs; do
            healthy || die $E_ADB "Lost the TV mid-run. Everything done so far is in $ACTLOG; reconnect and run --status."
            disable_one "$p" || FAILED=$((FAILED + 1))
        done
        sleep 2
        healthy || die $E_ADB "The TV stopped answering after tier $t. Reconnect, check the screen, and see README.md (\"If something goes wrong\")."
    done
else
    confirm "restore $TODO package(s)"
    open_state
    rule
    # Home screens first, so the TV has a Home again as early as possible.
    for t in 6 1 2 3 4 5 U; do
        while IFS='|' read -r action p tier note; do
            [ "$action" = restore ] && [ "$tier" = "$t" ] || continue
            healthy || die $E_ADB "Lost the TV mid-run. Reconnect and run --restore again; it picks up where it stopped."
            restore_one "$p" "$(state_of "$p")" || FAILED=$((FAILED + 1))
        done <"$TMP/plan"
    done
    if [ -n "$HOME_TO" ]; then
        comp=$(dsh cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME |
            grep "^$HOME_TO/" | head -n 1)
        if [ -n "$comp" ]; then
            say "Making $comp the Home app..."
            say "  $(dsh cmd package set-home-activity "$comp")"
        else
            say "Couldn't find a Home activity in $HOME_TO; Home left as it is."
            FAILED=$((FAILED + 1))
        fi
    fi
fi

load_packages
rule
say "Done. $FAILED failure(s). Log: $ACTLOG"
say "Reboot the TV (adb reboot), reconnect, and run --status: some firmware re-enables packages at boot."
[ $FAILED -eq 0 ] || exit $E_PARTIAL
[ $GATE_REFUSED -eq 0 ] || exit $E_GATE
exit 0
